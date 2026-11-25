package io.github.gcbond.openhc.core.dmsdp.audio

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 音频测试用的合成数据：密钥 00..0f、SSRC 0x11223344 之类一眼能看出是假的值。
 * 加密用 JCE 的 AES/CTR 另外实现一遍，不经过被测的 AesCtr。
 */
internal object AudioSynthetic {
    val KEY = ByteArray(16) { it.toByte() }
    const val SSRC = 0x11223344L
    const val PT = 96

    fun stream(codec: AudioCodec = AudioCodec.PCM, channels: Int = 2, id: String = "77") = AudioStreamInfo(
        dataSessionId = id,
        sampleRate = 48_000,
        channels = channels,
        bitsPerSample = 16,
        requestedCodec = 0,
        codec = codec,
        transport = AudioTransport.TCP,
        parameters = "SampleRate=48000;Format=16;ChannelNum=$channels;Codec=0",
    )

    /** 双声道 440Hz / 660Hz 正弦，[frames] 帧、从第 [startFrame] 帧起，16 位小端（或大端）。 */
    fun pcm(startFrame: Int, frames: Int = 1024, bigEndian: Boolean = false, amplitude: Double = 8000.0): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val t = (startFrame + i) / 48_000.0
            val left = (amplitude * sin(2 * PI * 440 * t)).roundToInt()
            val right = (amplitude * sin(2 * PI * 660 * t)).roundToInt()
            put16(out, i * 4, left, bigEndian)
            put16(out, i * 4 + 2, right, bigEndian)
        }
        return out
    }

    private fun put16(out: ByteArray, at: Int, value: Int, bigEndian: Boolean) {
        if (bigEndian) {
            out[at] = (value shr 8).toByte()
            out[at + 1] = value.toByte()
        } else {
            out[at] = value.toByte()
            out[at + 1] = (value shr 8).toByte()
        }
    }

    /**
     * 一个 ADTS 帧：AAC-LC、48kHz、双声道、无 CRC，帧长 = 7 + [body]；[trailing] 是帧后面多出来的字节数（不算进帧长）。
     */
    fun adts(body: ByteArray, trailing: Int = 0): ByteArray {
        val length = body.size + 7
        val header = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x4C, (0x80 or (length shr 11)).toByte(),
            (length shr 3).toByte(), ((length and 7) shl 5 or 0x1F).toByte(), 0xFC.toByte(),
        )
        return header + body + ByteArray(trailing) { 0x5A }
    }

    /** PCM 载荷前的 12 字节头：`00 01 ‖ BE64 时间戳 ‖ BE16 数据长度`（数据长度可以故意写错）。 */
    fun pcmHeader(timestamp: Long, dataLength: Int): ByteArray {
        val out = ByteArray(12)
        out[1] = 1
        for (i in 0 until 8) out[2 + i] = (timestamp ushr (56 - 8 * i)).toByte()
        out[10] = (dataLength ushr 8).toByte()
        out[11] = dataLength.toByte()
        return out
    }

    /** 一个 RTP 包：V=2、无 CSRC、无扩展、无填充。 */
    fun rtp(sequence: Int, payload: ByteArray, ssrc: Long = SSRC, pt: Int = PT, marker: Boolean = false): ByteArray {
        val timestamp = sequence * 1024L
        val header = byteArrayOf(
            0x80.toByte(), ((if (marker) 0x80 else 0) or pt).toByte(),
            (sequence shr 8).toByte(), sequence.toByte(),
            (timestamp shr 24).toByte(), (timestamp shr 16).toByte(), (timestamp shr 8).toByte(), timestamp.toByte(),
            (ssrc shr 24).toByte(), (ssrc shr 16).toByte(), (ssrc shr 8).toByte(), ssrc.toByte(),
        )
        return header + payload
    }

    /** RTCP 发送者报告的形状（28 字节，长度字段 6）。 */
    fun rtcpSenderReport(): ByteArray = ByteArray(28).also {
        it[0] = 0x80.toByte()
        it[1] = 200.toByte()
        it[3] = 6
    }

    /** `IV16 ‖ AES-128-CTR 密文`，IV 由 [ivSeed] 决定。 */
    fun seal(plain: ByteArray, ivSeed: Int, key: ByteArray = KEY): ByteArray {
        val iv = ByteArray(16) { (ivSeed * 31 + it * 7).toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(plain)
    }

    /** 按候选的帧头格式包一帧。控制帧形状的标志：整帧加密用 0xAB，否则 0x00。 */
    fun frame(kind: FrameKind, body: ByteArray, sealed: Boolean = false): ByteArray {
        val n = body.size
        val head = when (kind) {
            FrameKind.LENGTH32 -> byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
            FrameKind.LENGTH16 -> byteArrayOf((n ushr 8).toByte(), n.toByte())
            FrameKind.CONTROL -> byteArrayOf(if (sealed) 0xAB.toByte() else 0, (n ushr 8).toByte(), n.toByte())
            FrameKind.INTERLEAVED -> byteArrayOf('$'.code.toByte(), 0, (n ushr 8).toByte(), n.toByte())
        }
        return head + body
    }

    /** [count] 个音频包按 [kind] 分帧拼成的字节流；返回（字节流，各包的 PCM 载荷）。 */
    fun pcmStream(
        kind: FrameKind,
        sealed: Boolean,
        count: Int,
        firstSequence: Int = 100,
        bigEndian: Boolean = false,
    ): Pair<ByteArray, List<ByteArray>> {
        val payloads = (0 until count).map { pcm(it * 1024, bigEndian = bigEndian) }
        var stream = ByteArray(0)
        payloads.forEachIndexed { i, payload ->
            val packet = rtp((firstSequence + i) and 0xFFFF, payload)
            stream += frame(kind, if (sealed) seal(packet, i + 1) else packet, sealed)
        }
        return stream to payloads
    }
}

/** 记下拆包器的全部输出。 */
internal class Recorder : AudioDepacketizer.Output {
    var framing: AudioFraming? = null
    var unknownReason: String? = null
    var unknownHead: ByteArray? = null
    val payloads = ArrayList<ByteArray>()
    val sequences = ArrayList<Int>()
    val buses = ArrayList<Int>()
    val lanes = ArrayList<Long>()
    val usages = ArrayList<Int>()
    val orders = ArrayList<PcmByteOrder>()
    val codecs = ArrayList<AudioCodec>()
    val playable = ArrayList<Boolean>()
    val notes = ArrayList<String>()

    override fun onFraming(framing: AudioFraming) {
        this.framing = framing
    }

    override fun onUnknown(reason: String, head: ByteArray) {
        unknownReason = reason
        unknownHead = head
    }

    override fun onPacket(packet: AudioPacket) {
        payloads += packet.copyPayload()
        sequences += packet.sequence
        buses += packet.bus
        lanes += packet.lane
        usages += packet.usage
        orders += packet.byteOrder
        codecs += packet.codec
        playable += packet.playable
    }

    override fun onNote(message: String) {
        notes += message
    }
}
