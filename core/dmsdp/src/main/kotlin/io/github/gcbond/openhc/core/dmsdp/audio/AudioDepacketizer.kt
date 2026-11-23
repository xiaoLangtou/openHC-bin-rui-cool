package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.common.codec.ReceiveBuffer
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.dmsdp.AesCtr

/**
 * 音频数据连接的拆包：TCP 字节流 → 帧 → RTP → 载荷。
 *
 * 这条连接上的分帧还没有确定的说法，所以先**识别**：按 [FrameKind] 里的几种帧头、每种再分「帧内直接是 RTP」
 * 和「帧内是 `IV16 ‖ AES-128-CTR 密文`、解开才是 RTP」，逐个试。RTP 版本位只有 2 位，随机数据一帧命中的概率是
 * 四分之一，所以要**同一路（同一个 SSRC）连续 [LOCK_PACKETS] 个 RTP 包**都满足：帧长首尾相接、版本是 2、
 * 载荷类型不变、序号逐个加一，才算认出来。第一个 RTP 包之前允许有至多 [MAX_LEADING] 个帧头合法但内容不是 RTP 的帧，
 * 中间夹着的 RTCP 包不打断判断。
 *
 * **一条连接上可能有几路**：手机分流时音乐和导航是两个 SSRC（路号见 [AudioBus]），包交错着到。所以识别和之后的
 * 序号检查都按 SSRC 分开记：识别期间最多容许 [MAX_LANES] 路，再多就当这种分帧不对；认出来之后新出现的路记一条诊断、
 * 照常交出，各路各算各的序号跳变。
 *
 * 认出来之后，用这几包的载荷再定：手机**实际**发的是什么编码（以 ADTS 头开头就是 AAC，否则当 PCM，
 * 不看车机要的是什么）、载荷本身是不是又一层 `IV16 ‖ 密文`，以及 PCM 载荷像不像声音（见 [PcmProbe]）；
 * 之后按这个结论逐包拆、逐包交出。PCM 载荷前面带 12 字节头（见 [PcmPacketHeader]）的包，去掉头再交出。
 *
 * **这里有意不按「格式不对就抛异常」处理**：声音是附带的功能，它的数据对不上不能连累投屏。
 * 识别不出来（候选全部对不上、识别窗口超过 [DETECT_LIMIT]、连接结束时还没认出来）或认出来之后又失步，
 * 都转成原始模式：出一条诊断，之后只靠上层的原始字节回调交出数据，不再拆包。单个坏帧只计数、跳过。
 *
 * 不是线程安全的；只在协议线程上用。
 */
internal class AudioDepacketizer(
    private val cipher: AesCtr,
    private val stream: AudioStreamInfo,
    private val out: Output,
) {
    interface Output {
        fun onFraming(framing: AudioFraming)
        fun onUnknown(reason: String, head: ByteArray)
        fun onPacket(packet: AudioPacket)
        fun onNote(message: String)
    }

    private enum class Mode { DETECTING, STREAMING, RAW }

    private val buffer = ReceiveBuffer(INITIAL_BUFFER)
    private var mode = Mode.DETECTING
    private val trials = ArrayList<Trial>()
    private var plan: Trial? = null
    private var payloadEncrypted = false
    private var playable = true
    private var payloadCodec = stream.codec
    private var leadingSkipped = 0
    private var oddPcmNoted = false

    /** 去掉过 12 字节头的音频包数（PCM / AAC 分开记）、带不上头（原样交出）的包数。 */
    var pcmHeadersStripped = 0L
    var aacHeadersStripped = 0L
        private set
    private var pcmTrailerNoted = false
        private set
    var pcmWithoutHeader = 0L
        private set
    var aacWithoutHeader = 0L
        private set

    private val head = ByteArray(HEAD_BYTES)
    private var headLength = 0
    private val rtp = RtpView()
    private val iv = ByteArray(AesCtr.IV_BYTES)
    private var frameScratch = ByteArray(INITIAL_SCRATCH)
    private var payloadScratch = ByteArray(INITIAL_SCRATCH)
    private val packet = AudioPacket(stream)
    private val byteOrder = ByteOrderDetector(stream.channels)

    /** 认出来之后各路的上一个序号和包数（按 SSRC，最多记 [MAX_TRACKED_LANES] 路）。 */
    private val lanes = LinkedHashMap<Long, LaneStats>()
    private var untrackedPackets = 0L
    private var notes = 0

    /** 收到的字节数（含识别失败后原样交出的）。 */
    var bytes = 0L
        private set
    var packets = 0L
        private set
    var rtcpPackets = 0L
        private set
    var sequenceGaps = 0L
        private set
    var badFrames = 0L
        private set

    /** 分帧结论，一句话（给汇总用）。 */
    var conclusion: String = "没有收到数据"
        private set

    /** 各路收到的包数，一句话（给汇总用），例如「第 8 路 1200 包、第 0 路 56 包」。 */
    val streams: String
        get() = buildString {
            for ((ssrc, lane) in lanes) {
                if (isNotEmpty()) append("、")
                append(AudioBus.label(AudioBus.of(ssrc)))
                if (AudioBus.of(ssrc) == AudioBus.UNKNOWN) append("（SSRC ").append(ssrc).append("）")
                append(' ').append(lane.packets).append(" 包")
            }
            if (untrackedPackets > 0) append("、其余 $untrackedPackets 包没分路记")
        }

    val pcmByteOrder: PcmByteOrder get() = byteOrder.order

    init {
        // 「每帧尝试解密」排在前面；同一种帧头下两种解释不会同时连续命中。
        for (frame in FrameKind.entries) {
            trials += Trial(frame, sealed = true)
            trials += Trial(frame, sealed = false)
        }
    }

    fun feed(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        if (length == 0) return
        bytes += length
        keepHead(data, offset, length)
        when (mode) {
            Mode.RAW -> Unit
            Mode.DETECTING -> {
                buffer.append(data, offset, length)
                conclusion = "还没识别出分帧"
                detect(final = false)
            }
            Mode.STREAMING -> {
                buffer.append(data, offset, length)
                drain()
            }
        }
    }

    /** 连接结束（被关或出错）：还在识别就按已有数据下结论，残留的半帧只记一笔。 */
    fun finish() {
        if (mode == Mode.DETECTING && bytes > 0) detect(final = true)
        if (mode == Mode.STREAMING && buffer.size > 0) note("连接结束时留下 ${buffer.size} 字节不完整的帧")
        buffer.clear()
    }

    // ---------------- 识别 ----------------

    private fun detect(final: Boolean) {
        val overLimit = buffer.size >= DETECT_LIMIT
        // 连续几包都对得上已经是很强的证据：哪个候选先认出来就用哪个，不等排在前面、还卡在某个大长度上的候选。
        for (trial in trials) {
            trial.advance()
            if (trial.locked) {
                lock(trial)
                return
            }
        }
        if (final) {
            // 连接已经结束：同一路连续两包对得上也接受。
            trials.firstOrNull { it.rejection == null && it.lockingLane().frames.size >= 2 }?.let {
                lock(it)
                return
            }
        }
        when {
            trials.all { it.rejection != null } ->
                giveUp("每种分帧都对不上（" + trials.joinToString("；") { "${it.label}：${it.rejection}" } + "）")
            overLimit -> giveUp("前 ${buffer.size} 字节里没能识别出分帧")
            final -> giveUp("连接结束时还没识别出分帧（共 ${buffer.size} 字节）")
        }
    }

    private fun giveUp(reason: String) {
        mode = Mode.RAW
        conclusion = "分帧识别不出来"
        buffer.clear()
        out.onUnknown(reason, head.copyOf(headLength))
    }

    private fun lock(trial: Trial) {
        plan = trial
        mode = Mode.STREAMING
        var header = ""
        val lane = trial.lockingLane()
        val payloads = lane.frames.mapNotNull { (bodyAt, bodyLength) ->
            if (parseFrame(buffer.array, buffer.start + bodyAt, bodyLength, trial.sealed)) {
                if (header.isEmpty()) header = rtp.describeHeader()
                rtp.data.copyOfRange(rtp.payloadStart, rtp.payloadEnd)
            } else {
                null
            }
        }
        decidePayload(payloads)
        val shown = payloads.map { if (payloadEncrypted) decryptPayloadCopy(it) ?: it else it }
        val framing = AudioFraming(
            frame = trial.frame.label,
            frameEncrypted = trial.sealed,
            payloadEncrypted = payloadEncrypted,
            payloadCodec = payloadCodec,
            leadingFrames = trial.leading,
            payloadType = lane.payloadType,
            ssrc = lane.ssrc,
            rtpHeader = header,
            payloadLengths = shown.map { it.size },
            playable = playable,
            head = head.copyOf(headLength),
            payloadHead = shown.firstOrNull()?.let { it.copyOf(minOf(it.size, HEAD_BYTES)) } ?: ByteArray(0),
        )
        conclusion = "分帧=${trial.label}，载荷 ${payloadCodec.label}" + (if (payloadEncrypted) "，载荷再解一层" else "") +
            (if (playable) "" else "，载荷像噪声（静音）")
        out.onFraming(framing)
        note("手机实际发的是 ${payloadCodec.label}（按载荷判断；车机要的是 ${stream.codec.label}）")
        drain()
    }

    /**
     * 定载荷这一层（按识别用的几包多数决定，个别包例外不影响），依次看：原样是 ADTS → 去掉前 16 字节当 IV 解开后
     * 是 ADTS（都算 AAC）→ 原样像 PCM → 解开后像 PCM（都算 PCM）。像不像 PCM 至少要有一包够长才判断。
     * 都不中时按车机要的编码走：载荷都太短、判断不了就照常放；判断过、两头都不像时，要的是 AAC 就原样送解码器，
     * 要的是 PCM 就判为不能放（多半还有一层没解开的封装，免得放出噪声）。
     */
    private fun decidePayload(payloads: List<ByteArray>) {
        payloadEncrypted = false
        playable = true
        payloadCodec = stream.codec
        if (payloads.isEmpty()) return
        val decrypted = payloads.map { decryptPayloadCopy(it) }
        val allDecrypted = if (decrypted.all { it != null }) decrypted.filterNotNull() else null
        fun majority(list: List<ByteArray>, test: (ByteArray) -> Boolean) = list.count(test) * 2 > list.size
        // 是 ADTS，或 12 字节头后跟着 ADTS（有的手机在 ADTS 帧前加和 PCM 一样的头）都算 AAC。
        val adts = { p: ByteArray -> Adts.looksLike(p, 0, p.size) || AudioPacketHeader.dataLength(p, 0, p.size, adts = true) >= 0 }
        val pcm = { p: ByteArray -> PcmProbe.looksLikePcm(p, 0, p.size, stream.channels) }
        val judgeable = { list: List<ByteArray> -> list.any { PcmProbe.canJudge(it.size, stream.channels) } }
        when {
            majority(payloads, adts) -> payloadCodec = AudioCodec.AAC
            allDecrypted != null && majority(allDecrypted, adts) -> {
                payloadCodec = AudioCodec.AAC
                payloadEncrypted = true
            }
            judgeable(payloads) && majority(payloads, pcm) -> payloadCodec = AudioCodec.PCM
            allDecrypted != null && judgeable(allDecrypted) && majority(allDecrypted, pcm) -> {
                payloadCodec = AudioCodec.PCM
                payloadEncrypted = true
            }
            !judgeable(payloads) -> note("载荷太短、判断不了是不是 PCM，按车机要的 ${stream.codec.label} 处理")
            else -> {
                note("载荷既不像 ADTS 也不像 PCM，按车机要的 ${stream.codec.label} 处理")
                playable = stream.codec != AudioCodec.PCM
            }
        }
    }

    private fun decryptPayloadCopy(payload: ByteArray): ByteArray? {
        if (payload.size <= AesCtr.IV_BYTES) return null
        System.arraycopy(payload, 0, iv, 0, AesCtr.IV_BYTES)
        val plain = ByteArray(payload.size - AesCtr.IV_BYTES)
        cipher.transform(iv, payload, AesCtr.IV_BYTES, plain.size, plain, 0)
        return plain
    }

    // ---------------- 认出来之后逐帧拆 ----------------

    private fun drain() {
        val trial = plan ?: return
        while (mode == Mode.STREAMING) {
            val body = trial.frame.bodyLength(buffer.array, buffer.start, buffer.end)
            if (body == NEED_MORE) return
            if (body == INVALID) {
                mode = Mode.RAW
                conclusion += "，之后失步"
                buffer.clear()
                note("帧头对不上了（失步），这条连接之后不再拆包，只交原始字节")
                return
            }
            val total = trial.frame.headerBytes + body
            if (buffer.size < total) return
            onFrame(trial, buffer.array, buffer.start + trial.frame.headerBytes, body)
            buffer.consume(total)
        }
    }

    private fun onFrame(trial: Trial, data: ByteArray, bodyAt: Int, bodyLength: Int) {
        if (!parseFrame(data, bodyAt, bodyLength, trial.sealed)) {
            if (packets == 0L && rtcpPackets == 0L && leadingSkipped < trial.leading) {
                leadingSkipped++
            } else {
                badFrames++
                noteLimited("一帧解不出 RTP，跳过（帧体 $bodyLength 字节）")
            }
            return
        }
        if (rtp.isRtcp) {
            rtcpPackets++
            return
        }
        trackLane(rtp.ssrc, rtp.sequence)

        var payload = rtp.data
        var from = rtp.payloadStart
        var length = rtp.payloadEnd - rtp.payloadStart
        if (payloadEncrypted) {
            if (length <= AesCtr.IV_BYTES) {
                badFrames++
                noteLimited("载荷只有 $length 字节，装不下 IV，跳过")
                return
            }
            System.arraycopy(payload, from, iv, 0, AesCtr.IV_BYTES)
            length -= AesCtr.IV_BYTES
            if (payloadScratch.size < length) payloadScratch = ByteArray(grow(length))
            cipher.transform(iv, payload, from + AesCtr.IV_BYTES, length, payloadScratch, 0)
            payload = payloadScratch
            from = 0
        }
        if (payloadCodec == AudioCodec.PCM) {
            val declared = AudioPacketHeader.dataLength(payload, from, length, adts = false)
            if (declared >= 0) {
                onPcmHeader(payload, from)
                val trailer = length - AudioPacketHeader.SIZE - declared
                if (trailer > 0 && !pcmTrailerNoted) {
                    pcmTrailerNoted = true
                    note("PCM 包尾比头里声明的多 $trailer 字节（序号 ${rtp.sequence}，载荷 $length 字节），只放声明的 $declared 字节")
                }
                from += AudioPacketHeader.SIZE
                length = declared
            } else {
                pcmWithoutHeader++
                if (pcmHeadersStripped > 0 && pcmWithoutHeader == 1L) {
                    note("有 PCM 包对不上 12 字节头的规则，这样的包原样放（序号 ${rtp.sequence}，$length 字节）")
                }
            }
            if (byteOrder.offer(payload, from, length)) note("PCM 字节序判断为${byteOrder.order.label}")
            val frameBytes = 2 * stream.channels.coerceAtLeast(1)
            if (length % frameBytes != 0 && !oddPcmNoted) {
                oddPcmNoted = true
                note("PCM 载荷 $length 字节不是整帧（$frameBytes 字节）的倍数：播放时每包的尾巴会被丢掉，可能错位成噪声")
            }
        } else if (payloadCodec == AudioCodec.AAC) {
            // 手机在 ADTS 前也垫了同一个 12 字节头（第 10～11 字节声明 ADTS 整帧长）：不剥掉的话解码器收到
            // 「头 + ADTS」的混合数据解不出 PCM，音乐和导航就都没声（实测 HarmonyOS NEXT 15.2.0.255）。
            val declared = AudioPacketHeader.dataLength(payload, from, length, adts = true)
            if (declared >= 0) {
                onAacHeader(payload, from)
                from += AudioPacketHeader.SIZE
                length = declared
            } else {
                aacWithoutHeader++
                if (aacHeadersStripped > 0 && aacWithoutHeader == 1L) {
                    note("有 AAC 包对不上 12 字节头的规则，这样的包原样放（序号 ${rtp.sequence}，$length 字节）")
                }
            }
        }
        packet.sequence = rtp.sequence
        packet.timestamp = rtp.timestamp
        packet.ssrc = rtp.ssrc
        packet.payloadType = rtp.payloadType
        packet.bus = AudioBus.of(rtp.ssrc)
        packet.usage = AudioBus.usageOf(rtp.ssrc)
        packet.marker = rtp.marker
        packet.data = payload
        packet.offset = from
        packet.length = length
        packet.byteOrder = byteOrder.order
        packet.playable = playable
        packet.codec = payloadCodec
        packets++
        out.onPacket(packet)
    }

    /** 按 SSRC 记序号：每一路各自判断跳变；新出现的一路记一条诊断（不受诊断条数上限的限制，路数本身有上限）。 */
    private fun trackLane(ssrc: Long, sequence: Int) {
        val lane = lanes[ssrc]
        if (lane == null) {
            if (lanes.size >= MAX_TRACKED_LANES) {
                untrackedPackets++
                return
            }
            lanes[ssrc] = LaneStats(sequence)
            if (lanes.size > 1) {
                note("这条连接上出现了新的一路：${AudioBus.label(AudioBus.of(ssrc))}（SSRC $ssrc），各路分开计序号")
            }
            return
        }
        if (!isNextSequence(lane.lastSequence, sequence)) {
            sequenceGaps++
            noteLimited("${AudioBus.label(AudioBus.of(ssrc))} RTP 序号跳变：${lane.lastSequence} → $sequence")
        }
        lane.lastSequence = sequence
        lane.packets++
    }

    /** 去头之前：规则只记一次；头部原样记下前几包和之后每 [HEADER_SAMPLE_EVERY] 包抽一包，用来核对各字段怎么变。 */
    private fun onPcmHeader(payload: ByteArray, from: Int) {
        pcmHeadersStripped++
        if (pcmHeadersStripped == 1L) {
            conclusion += "，PCM 载荷去掉 12 字节头"
            note("PCM 载荷前有 12 字节头（第 10～11 字节按大端是其后的数据长度，包尾可能多几个字节），去掉头再放、只放声明的长度；对不上的包原样放")
        }
        val index = packets + 1
        if (pcmHeadersStripped <= HEADER_SAMPLES || index % HEADER_SAMPLE_EVERY == 0L) {
            note("PCM 头（第 $index 包，序号 ${rtp.sequence}）：${payload.copyOfRange(from, from + AudioPacketHeader.SIZE).toHex()}")
        }
    }

    /** AAC 的 12 字节头：同样只记一次规则说明，头部照 PCM 的方式抽样。 */
    private fun onAacHeader(payload: ByteArray, from: Int) {
        aacHeadersStripped++
        if (aacHeadersStripped == 1L) {
            conclusion += "，AAC 载荷去掉 12 字节头"
            note("AAC 载荷前有 12 字节头（第 10～11 字节按大端是其后的 ADTS 整帧长度，包尾可能多几个字节），去掉头再送解码器、只放声明的长度；对不上的包原样放")
        }
        val index = packets + 1
        if (aacHeadersStripped <= HEADER_SAMPLES || index % HEADER_SAMPLE_EVERY == 0L) {
            note("AAC 头（第 $index 包，序号 ${rtp.sequence}）：${payload.copyOfRange(from, from + AudioPacketHeader.SIZE).toHex()}")
        }
    }

    /**
     * 解释一帧的帧体：明文时直接按 RTP 解析；整帧加密时先把 `IV16` 之后的部分解到 [frameScratch] 再解析。
     * 结果在 [rtp] 里（[RtpView.data] 指向原数组或 [frameScratch]）。
     */
    private fun parseFrame(data: ByteArray, bodyAt: Int, bodyLength: Int, sealed: Boolean): Boolean {
        if (!sealed) return rtp.parse(data, bodyAt, bodyAt + bodyLength)
        if (bodyLength < AesCtr.IV_BYTES + RtpView.MIN_RTCP) return false
        val plainLength = bodyLength - AesCtr.IV_BYTES
        if (frameScratch.size < plainLength) frameScratch = ByteArray(grow(plainLength))
        System.arraycopy(data, bodyAt, iv, 0, AesCtr.IV_BYTES)
        cipher.transform(iv, data, bodyAt + AesCtr.IV_BYTES, plainLength, frameScratch, 0)
        return rtp.parse(frameScratch, 0, plainLength)
    }

    // ---------------- 工具 ----------------

    private fun keepHead(data: ByteArray, offset: Int, length: Int) {
        if (headLength == HEAD_BYTES) return
        val n = minOf(HEAD_BYTES - headLength, length)
        System.arraycopy(data, offset, head, headLength, n)
        headLength += n
    }

    private fun note(message: String) = out.onNote(message)

    /** 同类信息太多时只记前面一些，免得刷屏。 */
    private fun noteLimited(message: String) {
        notes++
        when {
            notes <= MAX_NOTES -> note(message)
            notes == MAX_NOTES + 1 -> note("拆包的诊断信息太多，后面的不再逐条记录（见结束时的汇总）")
        }
    }

    private fun grow(needed: Int): Int {
        var size = INITIAL_SCRATCH
        while (size < needed) size *= 2
        return size
    }

    /** 认出来之后一路的统计。 */
    private class LaneStats(var lastSequence: Int) {
        var packets = 1L
    }

    /** 识别期间一路（一个 SSRC）的进度。 */
    private class Lane(val ssrc: Long, val payloadType: Int, var lastSequence: Int) {
        /** 这一路已认出的 RTP 帧：帧体相对缓冲起点的位置和长度。 */
        val frames = ArrayList<Pair<Int, Int>>(LOCK_PACKETS)
    }

    /** 一种候选分帧的识别进度。增量推进：每次只看新到的完整帧，不从头重算。 */
    private inner class Trial(val frame: FrameKind, val sealed: Boolean) {
        /** 下一帧帧头相对缓冲起点的位置（识别期间缓冲不取走数据）。 */
        var position = 0
        var leading = 0
        var rtcp = 0

        /** 已认出的 RTP 帧（各路合在一起，按到达顺序）：帧体相对缓冲起点的位置和长度。 */
        val rtpFrames = ArrayList<Pair<Int, Int>>(LOCK_PACKETS)
        private val lanes = ArrayList<Lane>(2)
        var rejection: String? = null

        val locked: Boolean get() = lanes.any { it.frames.size >= LOCK_PACKETS }
        val label: String get() = frame.label + if (sealed) "，整帧解密" else "，帧内明文"

        /** 包数最多的那一路（认出来时就是凑够 [LOCK_PACKETS] 包的那一路）。 */
        fun lockingLane(): Lane = lanes.maxByOrNull { it.frames.size } ?: Lane(-1, -1, -1)

        fun advance() {
            while (rejection == null && !locked) {
                val at = buffer.start + position
                val frameNumber = leading + rtcp + rtpFrames.size + 1
                val body = frame.bodyLength(buffer.array, at, buffer.end)
                if (body == NEED_MORE) return
                if (body == INVALID) {
                    rejection = "第 $frameNumber 帧的帧头不对"
                    return
                }
                val total = frame.headerBytes + body
                if (buffer.end - at < total) return
                val bodyAt = position + frame.headerBytes
                if (!parseFrame(buffer.array, buffer.start + bodyAt, body, sealed)) {
                    if (rtpFrames.isEmpty() && leading < MAX_LEADING) {
                        leading++
                        position += total
                        continue
                    }
                    rejection = "第 $frameNumber 帧不是 RTP"
                    return
                }
                if (rtp.isRtcp) {
                    rtcp++
                    position += total
                    continue
                }
                val lane = lanes.firstOrNull { it.ssrc == rtp.ssrc }
                when {
                    lane == null && lanes.size >= MAX_LANES -> {
                        rejection = "第 $frameNumber 帧又是新的一路（超过 $MAX_LANES 路）"
                        return
                    }
                    lane == null -> lanes += Lane(rtp.ssrc, rtp.payloadType, rtp.sequence).also { it.frames += bodyAt to body }
                    rtp.payloadType != lane.payloadType || !isNextSequence(lane.lastSequence, rtp.sequence) -> {
                        rejection = "第 $frameNumber 帧的 RTP 头和同一路的前一包对不上"
                        return
                    }
                    else -> {
                        lane.lastSequence = rtp.sequence
                        lane.frames += bodyAt to body
                    }
                }
                rtpFrames += bodyAt to body
                position += total
            }
        }
    }

    companion object {
        /** 连续这么多个 RTP 包对得上才算认出分帧。 */
        const val LOCK_PACKETS = 3

        /** 第一个 RTP 包之前最多跳过几个内容不是 RTP 的帧。 */
        const val MAX_LEADING = 2

        /** 识别期间最多容许几路（几个 SSRC）交错：手机分流时是两路，留点余量；随机数据每帧都像新的一路，很快超限。 */
        const val MAX_LANES = 4

        /** 认出来之后最多分开记几路的序号（本机限制）。 */
        const val MAX_TRACKED_LANES = 16

        /** 识别窗口（本机限制）：攒到这么多字节还认不出就放弃。 */
        const val DETECT_LIMIT = 256 * 1024

        const val HEAD_BYTES = 32
        private const val MAX_NOTES = 20

        /** 头部原样记前几包，之后每这么多包抽一包。 */
        private const val HEADER_SAMPLES = 3
        private const val HEADER_SAMPLE_EVERY = 500L
        private const val INITIAL_BUFFER = 16 * 1024
        private const val INITIAL_SCRATCH = 8 * 1024

        /** 序号逐个加一；65535 之后回到 0，也接受 64999 之后回到 0（投屏视频流就是这样回绕的）。 */
        fun isNextSequence(previous: Int, next: Int): Boolean =
            next == ((previous + 1) and 0xFFFF) || (next == 0 && previous >= 64_999)
    }
}

internal const val NEED_MORE = -1
internal const val INVALID = -2

/** 候选的帧头格式。帧体长度都不含帧头本身。 */
internal enum class FrameKind(val label: String, val headerBytes: Int) {
    /** `BE32 长度 ‖ 帧体`（投屏视频连接就是这样）。 */
    LENGTH32("4 字节大端长度", 4) {
        override fun bodyLength(data: ByteArray, at: Int, end: Int): Int {
            if (end - at < headerBytes) return NEED_MORE
            val length = be32(data, at)
            return if (length < MIN_BODY || length > MAX_BODY) INVALID else length.toInt()
        }
    },

    /** `BE16 长度 ‖ 帧体`（RFC 4571 的做法）。 */
    LENGTH16("2 字节大端长度", 2) {
        override fun bodyLength(data: ByteArray, at: Int, end: Int): Int {
            if (end - at < headerBytes) return NEED_MORE
            val length = be16(data, at)
            return if (length < MIN_BODY) INVALID else length
        }
    },

    /** 与设备控制连接相同的外层：`标志(00 明文 / AB 加密) ‖ BE16 长度 ‖ 帧体`。 */
    CONTROL("标志 00/AB + 2 字节大端长度", 3) {
        override fun bodyLength(data: ByteArray, at: Int, end: Int): Int {
            if (end - at < headerBytes) return NEED_MORE
            val flag = data[at].toInt() and 0xFF
            if (flag != 0x00 && flag != 0xAB) return INVALID
            val length = be16(data, at + 1)
            return if (length < MIN_BODY) INVALID else length
        }
    },

    /** RTSP 的交织格式：`'$' ‖ 通道号 ‖ BE16 长度 ‖ 帧体`。 */
    INTERLEAVED("'$' + 通道号 + 2 字节大端长度", 4) {
        override fun bodyLength(data: ByteArray, at: Int, end: Int): Int {
            if (end - at < 1) return NEED_MORE
            if (data[at].toInt() != '$'.code) return INVALID
            if (end - at < headerBytes) return NEED_MORE
            val length = be16(data, at + 2)
            return if (length < MIN_BODY) INVALID else length
        }
    },
    ;

    /** 从 `data[at]` 起的帧头算帧体长度：帧头还不全返回 [NEED_MORE]，帧头不合法返回 [INVALID]。 */
    abstract fun bodyLength(data: ByteArray, at: Int, end: Int): Int

    companion object {
        /** 帧体至少能放下最短的 RTCP 包。 */
        const val MIN_BODY = RtpView.MIN_RTCP

        /** 本机限制：一帧音频最多 64KiB（48kHz 双声道 16 位 PCM 也有 340ms）。 */
        const val MAX_BODY = 64L * 1024
    }
}

/**
 * 音频载荷（PCM / AAC 都实测有）前面的 12 字节头：形如 `00 0N ‖ 8 字节 ‖ BE16 长度`（N 为 1～3），
 * 最后两个字节按大端是其后音频帧的字节数——PCM 是裸采样数据，AAC 是含 ADTS 头的整帧。
 * 手机分流（多音频总线）时包尾还会多出几个字节（实测 4 字节），所以只放头里声明的那段，头和尾都不放。
 * 其余字段的含义没有确认，不解释。
 */
internal object AudioPacketHeader {
    const val SIZE = 12

    /** 头后实际字节数比声明多出的上限：多出更多就不当成带头的包。 */
    const val MAX_TRAILER = 16

    /**
     * 带头时返回头里声明的音频字节数，不带头返回 -1。判据：第 1 字节为 0、第 2 字节为 1～3，
     * 第 10～11 字节（大端）是正数，不超过头后剩下的长度，且剩下的比它多出不到 [MAX_TRAILER] 字节。
     * PCM 还要求声明的长度是偶数（16 位采样的整帧）；AAC 额外要求头后紧跟 ADTS，
     * 且声明的长度正好是那个 ADTS 帧长（否则裸 AAC 数据碰巧长这样会被误剥）。
     */
    fun dataLength(data: ByteArray, offset: Int, length: Int, adts: Boolean): Int {
        if (length <= SIZE) return -1
        if (data[offset].toInt() != 0 || (data[offset + 1].toInt() and 0xFF) !in 1..3) return -1
        val declared = be16(data, offset + SIZE - 2)
        val rest = length - SIZE
        if (declared <= 0 || declared > rest || rest - declared > MAX_TRAILER) return -1
        return if (adts) {
            Adts.frameLength(data, offset + SIZE, rest).takeIf { it == declared } ?: -1
        } else if (declared % 2 == 0) declared else -1
    }
}

/**
 * ADTS 头的粗判（ISO/IEC 13818-7）：同步字 0xFFF、layer 00、采样率索引在标准表里（0～12）、
 * 头长（7 或带 CRC 的 9）≤ 帧长 ≤ 数据长度。帧长不要求正好等于数据长度：尾部多几个字节也算。
 */
internal object Adts {
    fun looksLike(data: ByteArray, offset: Int, length: Int): Boolean = frameLength(data, offset, length) >= 0

    /** 是 ADTS 就返回头里声明的整帧长（含头），不是返回 -1。 */
    fun frameLength(data: ByteArray, offset: Int, length: Int): Int {
        if (length < HEADER) return -1
        fun u8(i: Int) = data[offset + i].toInt() and 0xFF
        if (u8(0) != 0xFF || u8(1) and 0xF6 != 0xF0) return -1
        val header = if (u8(1) and 0x01 != 0) HEADER else HEADER_WITH_CRC
        if ((u8(2) ushr 2) and 0x0F > MAX_SAMPLE_RATE_INDEX) return -1
        val frameLength = ((u8(3) and 0x03) shl 11) or (u8(4) shl 3) or (u8(5) ushr 5)
        return if (frameLength in header..length) frameLength else -1
    }

    private const val HEADER = 7
    private const val HEADER_WITH_CRC = 9
    private const val MAX_SAMPLE_RATE_INDEX = 12
}

/** 按 RFC 3550 解析的 RTP 头；也认 RTCP（第二字节 200～204）。 */
internal class RtpView {
    /** 解析时用的数组，[payloadStart]、[payloadEnd] 是它里面的下标。 */
    var data: ByteArray = ByteArray(0)
        private set
    var isRtcp = false
        private set
    var payloadType = 0
        private set
    var marker = false
        private set
    var sequence = 0
        private set
    var timestamp = 0L
        private set
    var ssrc = 0L
        private set
    var payloadStart = 0
        private set
    var payloadEnd = 0
        private set

    // 头部各标志位的原值，只用于诊断。
    private var csrcCount = 0
    private var extension = false
    private var extensionLength = 0
    private var padding = false

    /** 头部标志的原值（CSRC 数、扩展头和它的长度字段、填充），给识别结论用。 */
    fun describeHeader(): String = "CC=$csrcCount，X=${if (extension) 1 else 0}" +
        (if (extension) "（扩展长度字段=$extensionLength，按 32 位字计）" else "") + "，P=${if (padding) 1 else 0}"

    /** 解析 `data[start, end)`。版本不是 2、各段长度装不下、或媒体包没有载荷时返回 false。 */
    fun parse(data: ByteArray, start: Int, end: Int): Boolean {
        val n = end - start
        if (n < MIN_RTCP) return false
        val b0 = data[start].toInt() and 0xFF
        if (b0 ushr 6 != 2) return false
        val b1 = data[start + 1].toInt() and 0xFF
        this.data = data
        if (b1 in RTCP_FIRST..RTCP_LAST) {
            // RTCP 的长度字段是「32 位字数减一」。
            if ((be16(data, start + 2) + 1) * 4 > n) return false
            isRtcp = true
            payloadType = b1
            return true
        }
        if (n < FIXED_HEADER) return false
        isRtcp = false
        marker = b1 and 0x80 != 0
        payloadType = b1 and 0x7F
        sequence = be16(data, start + 2)
        timestamp = be32(data, start + 4)
        ssrc = be32(data, start + 8)
        csrcCount = b0 and 0x0F
        extension = b0 and 0x10 != 0
        padding = b0 and 0x20 != 0
        extensionLength = 0
        var header = FIXED_HEADER + 4 * csrcCount
        if (header > n) return false
        if (extension) {
            if (header + 4 > n) return false
            extensionLength = be16(data, start + header + 2)
            header += 4 + 4 * extensionLength
            if (header > n) return false
        }
        var payloadLength = n - header
        if (padding) {
            val padding = data[end - 1].toInt() and 0xFF
            if (padding == 0 || padding > payloadLength) return false
            payloadLength -= padding
        }
        if (payloadLength <= 0) return false
        payloadStart = start + header
        payloadEnd = payloadStart + payloadLength
        return true
    }

    companion object {
        const val FIXED_HEADER = 12

        /** 最短的 RTCP 包（不带报告块的 RR）。 */
        const val MIN_RTCP = 8
        private const val RTCP_FIRST = 200
        private const val RTCP_LAST = 204
    }
}

/**
 * 16 位 PCM 的粗判断。依据：同一声道相邻采样的差，真实声音（包括很响的音乐）远小于随机数据；
 * 均匀随机的 16 位数据，差的平均绝对值约为值域的三分之一（21845）。按错了字节序时，低字节跑到高位，差也会大很多。
 */
internal object PcmProbe {
    /** 差值太少不判断。 */
    const val MIN_DELTAS = 64

    /** 平均绝对差高于它就当噪声：取随机数据的六成左右，两边都留了余量。 */
    const val NOISE_LEVEL = 13_000.0

    /** 同一声道相邻采样差的平均绝对值；样本太少返回 NaN。 */
    fun meanAbsDelta(data: ByteArray, offset: Int, length: Int, channels: Int, bigEndian: Boolean): Double {
        val stride = channels.coerceIn(1, 8)
        val samples = length / 2
        if (samples - stride < MIN_DELTAS) return Double.NaN
        var sum = 0L
        for (i in stride until samples) {
            sum += kotlin.math.abs(sample(data, offset, i, bigEndian) - sample(data, offset, i - stride, bigEndian))
        }
        return sum.toDouble() / (samples - stride)
    }

    /** [length] 字节的 16 位 PCM 够不够做判断。 */
    fun canJudge(length: Int, channels: Int): Boolean = length / 2 - channels.coerceIn(1, 8) >= MIN_DELTAS

    /** 两种字节序里有一种像声音就算像；数据太短时不判断，当作像。 */
    fun looksLikePcm(data: ByteArray, offset: Int, length: Int, channels: Int): Boolean {
        val little = meanAbsDelta(data, offset, length, channels, bigEndian = false)
        if (little.isNaN()) return true
        val big = meanAbsDelta(data, offset, length, channels, bigEndian = true)
        return minOf(little, big) < NOISE_LEVEL
    }

    fun sample(data: ByteArray, offset: Int, index: Int, bigEndian: Boolean): Int {
        val a = data[offset + 2 * index].toInt()
        val b = data[offset + 2 * index + 1].toInt()
        return if (bigEndian) (a shl 8) or (b and 0xFF) else (b shl 8) or (a and 0xFF)
    }
}

/** 逐包累计两种字节序下的平均绝对差，攒够了、差距明显时下结论；全是静音时一直不下结论（按小端放）。 */
internal class ByteOrderDetector(private val channels: Int) {
    var order = PcmByteOrder.UNDECIDED
        private set
    private var little = 0.0
    private var big = 0.0
    private var deltas = 0L

    /** 返回 true 表示这一次刚下结论。 */
    fun offer(data: ByteArray, offset: Int, length: Int): Boolean {
        if (order != PcmByteOrder.UNDECIDED) return false
        val le = PcmProbe.meanAbsDelta(data, offset, length, channels, bigEndian = false)
        if (le.isNaN()) return false
        val be = PcmProbe.meanAbsDelta(data, offset, length, channels, bigEndian = true)
        val count = length / 2 - channels.coerceIn(1, 8)
        little += le * count
        big += be * count
        deltas += count
        if (deltas < MIN_DELTAS_TO_DECIDE || maxOf(little, big) == 0.0) return false
        order = when {
            little < big * RATIO -> PcmByteOrder.LITTLE_ENDIAN
            big < little * RATIO -> PcmByteOrder.BIG_ENDIAN
            else -> return false
        }
        return true
    }

    private companion object {
        const val MIN_DELTAS_TO_DECIDE = 4096L

        /** 小的一边不到大的一半才下结论。 */
        const val RATIO = 0.5
    }
}

internal fun be16(data: ByteArray, at: Int): Int = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

internal fun be32(data: ByteArray, at: Int): Long =
    ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
        ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)
