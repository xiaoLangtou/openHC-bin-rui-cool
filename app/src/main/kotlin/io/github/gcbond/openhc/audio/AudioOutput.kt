package io.github.gcbond.openhc.audio

import android.content.Context
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket
import io.github.gcbond.openhc.core.dmsdp.audio.AudioRoute
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.PcmByteOrder
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneCallPhase
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.media.AudioFocusController
import io.github.gcbond.openhc.platform.media.AudioFocusPolicy
import io.github.gcbond.openhc.platform.media.AudioPlayer
import io.github.gcbond.openhc.platform.media.AudioTuning
import io.github.gcbond.openhc.platform.media.AudioUsage
import io.github.gcbond.openhc.settings.AudioPreferences
import java.io.Closeable
import java.util.EnumMap

/**
 * 一次连接的声音出口：接会话的音频回调（都在协议线程上），按数据会话里的每一路（SSRC）和它该放的通道各建一个
 * [AudioPlayer]：媒体、导航、提示与语音、通话各用各的音轨用途，导航放完不影响音乐。调试构建顺带把原始字节和识别结果
 * 写进抓包文件（[AudioCapture]）。日志标签 `OpenHC-Audio`。
 *
 * - 播放器在这一路第一包音频到来时才建（采样参数取自那次数据会话，编码按载荷判断的实际编码），数据会话结束或数据连接断开就关。
 *   识别时判为「不像声音」的载荷不放，免得放出噪声。缓冲参数在连接开始时读一次设置。
 * - 音频焦点（[AudioFocusController]）：手机的焦点消息、各路开始和停止出声、手机的通话状态（来电 / 通话中 / 挂断）和
 *   语音助手状态交给它，它决定申请和放弃；车机别的应用抢走焦点时它让我们压低或静音，并经 [tellPhone] 告诉手机暂停或恢复；
 *   来电和通话中媒体在本地静音，挂断后恢复。
 * - 声明 PCM 时的判定（[AudioEvent.CodecVerdict]）按手机记进设置（[AudioPreferences.recordPcmVerdict]），
 *   「不出声」的手机下次连接改声明 AAC。
 * - 每一路每 [STATS_EVERY] 包记一行统计，带我们这边的延迟拆分。
 */
internal class AudioOutput(
    context: Context,
    private val attemptId: Int,
    /** 无线连接：音乐和导航多垫 [WIRELESS_CUSHION_MILLIS]（[AudioTuning.withCushion]），电话、提示与语音、铃声不垫。 */
    private val wireless: Boolean = false,
    /** 我们是不是在替手机拿着媒体焦点，变化时在焦点线程上回调（媒体按钮会话跟着它建和关）。 */
    private val mediaFocusChanged: (Boolean) -> Unit = {},
    /** 把车机的焦点变化告诉手机（实现方投到协议线程上发）。 */
    private val tellPhone: (CarAudioFocus) -> Unit,
) : AudioStreamListener, Closeable {
    private val app: Context = context.applicationContext ?: context
    private val lock = Any()
    private val prefs = AudioPreferences(app)
    private val tuning: AudioTuning = prefs.playbackTuning()

    /** 抓包到第一次有音频回调时才建（没声明扬声器、手机没要声音的连接不用开线程和文件）。 */
    private var capture: AudioCapture? = null

    /** 一路一个播放器：键是（数据会话、SSRC、通道）。 */
    private val streams = LinkedHashMap<StreamKey, Stream>()
    private val unsupportedSessions = HashSet<String>()

    /** 焦点决定的各用途音量（新建的播放器也照这个设）。 */
    private val volumes = EnumMap<AudioUsage, Float>(AudioUsage::class.java)
    private var focus: AudioFocusController? = null
    private var closed = false
    private var muted = 0L

    private data class StreamKey(val dataSessionId: String, val ssrc: Long, val route: AudioRoute)

    /** 一路声音。[sounding] 跟着播放器的「开始出声 / 放空」走，焦点按它算这个用途还在不在响。 */
    private inner class Stream(val key: StreamKey, val usage: AudioUsage, val bus: Int) : AudioPlayer.Listener {
        lateinit var player: AudioPlayer
        var packets = 0L
        private var sounding = false
        private var finished = false

        override fun onAudioPlayerEvent(event: AudioPlayer.Event) {
            Log.i(TAG, "[$attemptId] ${label()}播放器：$event")
            when (event) {
                is AudioPlayer.Event.Started, is AudioPlayer.Event.Resumed -> sound(true)
                is AudioPlayer.Event.Starved -> sound(false)
                is AudioPlayer.Event.Failed -> if (event.fatal) sound(false)
                else -> Unit
            }
        }

        /**
         * 播放器线程和关闭它的线程都会调：在这一路的锁里翻状态并交给焦点控制（它只是投递，不阻塞），保证同一路的
         * 开始、停止按顺序到。锁的先后固定是「这一路 → [lock]」，持有 [lock] 时不碰这一路的锁。关掉之后不再算出声。
         */
        @Synchronized
        fun sound(on: Boolean) {
            if (on == sounding || (on && finished)) return
            sounding = on
            val controller = focusController() ?: return
            if (on) controller.soundStarted(usage) else controller.soundStopped(usage)
        }

        /** 关掉这一路。不要在持有 [lock] 时调用。 */
        fun finish() {
            synchronized(this) { finished = true }
            sound(false)
            player.close()
        }

        fun label(): String = "${key.route.label}（${if (bus >= 0) "第 $bus 路" else "SSRC ${key.ssrc}"}）"
    }

    override fun onAudioEvent(event: AudioEvent) {
        Log.i(TAG, "[$attemptId] $event")
        capture()?.onEvent(event)
        when (event) {
            is AudioEvent.PhoneFocus -> onPhoneFocus(event.message)
            is AudioEvent.PhoneCallState -> {
                val phase = when (event.phase) {
                    PhoneCallPhase.IDLE -> AudioFocusPolicy.CallPhase.IDLE
                    PhoneCallPhase.RINGING -> AudioFocusPolicy.CallPhase.RINGING
                    PhoneCallPhase.ACTIVE -> AudioFocusPolicy.CallPhase.ACTIVE
                    null -> null
                }
                if (phase != null) focusController()?.callStateChanged(phase)
            }
            is AudioEvent.PhoneVoiceState -> focusController()?.assistantStateChanged(event.active)
            is AudioEvent.StreamRouted ->
                if (event.unknownUsage) Log.w(TAG, "[$attemptId] SSRC ${event.ssrc} 带的用途 ${event.usage} 不认识，按媒体放")
            is AudioEvent.CodecVerdict -> onVerdict(event)
            is AudioEvent.Ended -> {
                stopSession(event.stream.dataSessionId, "数据会话结束（${event.reason.label}）")
                // 手机拆掉数据会话就是不放了：焦点一并放掉（通话中的通话焦点除外），它重新出声时会再要；被新的替换时不放。
                if (event.reason == AudioEndReason.PEER_TEARDOWN || event.reason == AudioEndReason.LISTEN_FAILED) {
                    synchronized(lock) { focus }?.dataSessionEnded()
                }
            }
            is AudioEvent.PeerClosed -> stopSession(event.stream.dataSessionId, "数据连接断开")
            else -> Unit
        }
    }

    override fun onAudioRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) {
        capture()?.onRawData(stream, data, offset, length)
    }

    override fun onAudioPacket(packet: AudioPacket) {
        val stale = ArrayList<Stream>(0)
        try {
            deliver(packet, stale)
        } finally {
            for (stream in stale) stream.finish()
        }
    }

    /** 在 [lock] 里找到（或建好）这一路、送进播放器；要关掉的旧播放器放进 [stale]，由调用方出锁之后关。 */
    private fun deliver(packet: AudioPacket, stale: MutableList<Stream>) {
        synchronized(lock) {
            if (closed) return
            if (!packet.playable) {
                muted++
                if (muted == 1L) Log.w(TAG, "[$attemptId] 载荷不像声音（多半还有一层没解开），静音不放；看分帧识别的日志和抓包")
                return
            }
            val stream = streamFor(packet, stale) ?: return
            val target = stream.player
            when (packet.codec) {
                AudioCodec.PCM -> target.queuePcm(
                    packet.data, packet.offset, packet.length, packet.byteOrder == PcmByteOrder.BIG_ENDIAN,
                )
                AudioCodec.AAC -> target.queueAac(packet.data, packet.offset, packet.length)
            }
            stream.packets++
            if (stream.packets % STATS_EVERY == 0L) {
                val underruns = target.trackUnderruns?.let { "，音轨欠载 $it 次" } ?: ""
                Log.i(
                    TAG,
                    "[$attemptId] ${stream.label()}已送进播放器 ${stream.packets} 包（静音 $muted）；${target.latency ?: "延迟还没测到"}；" +
                        "起播压回丢 ${target.startTrimmedMillis}ms，削峰丢 ${target.trimmedMillis}ms（${target.trimmedChunks} 块），" +
                        "突发超限丢 ${target.droppedMillis}ms，放空 ${target.starvations} 次$underruns",
                )
            }
        }
    }

    /** 连接结束：停掉播放、放掉焦点、写完抓包。只在调度线程上调用（执行器已经停了，不会再有回调）。 */
    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            streams.values.toList().also { streams.clear() }
        }
        for (stream in all) stream.finish()
        // 各路的「停止出声」已经投给焦点控制；它在这些之后放掉全部焦点、停线程。
        synchronized(lock) { focus.also { focus = null } }?.close()
        synchronized(lock) { capture }?.close()
    }

    // ---------------- 内部 ----------------

    private fun capture(): AudioCapture? = synchronized(lock) {
        if (closed) return null
        capture ?: AudioCaptureFactory.create(app, attemptId).also { capture = it }
    }

    /** 焦点控制在第一次用到时才建（没有声音的连接不开线程）。连接关掉之后为 null。 */
    private fun focusController(): AudioFocusController? = synchronized(lock) {
        focus ?: if (closed) null else AudioFocusController(app, FocusListener()).also { focus = it }
    }

    private inner class FocusListener : AudioFocusController.Listener {
        override fun onVolume(usage: AudioUsage, volume: Float) {
            synchronized(lock) {
                volumes[usage] = volume
                for (stream in streams.values) if (stream.usage == usage) stream.player.setVolume(volume)
            }
        }

        override fun onPhoneChange(change: AudioFocusPolicy.PhoneChange) {
            val message = when (change) {
                AudioFocusPolicy.PhoneChange.GAINED -> CarAudioFocus.GAINED
                AudioFocusPolicy.PhoneChange.LOST -> CarAudioFocus.LOST
                AudioFocusPolicy.PhoneChange.LOST_TRANSIENT -> CarAudioFocus.LOST_TRANSIENT
                AudioFocusPolicy.PhoneChange.DUCK -> CarAudioFocus.DUCK
            }
            if (synchronized(lock) { closed }) return
            tellPhone(message)
        }

        override fun onLog(message: String) {
            Log.i(TAG, "[$attemptId] $message")
        }

        override fun onMediaFocus(held: Boolean) {
            mediaFocusChanged(held)
        }
    }

    /**
     * 手机的焦点消息交给焦点控制，带着手机要的焦点类型（不分流的手机播报导航时要的是「短暂、压低别人」，
     * 不能因此长期拿走车机收音机的焦点）。分流消息按它的用途。老式消息（type 1～5）：通话、铃声按各自的用途
     * （它们本来就是单独一路）；别的用途手机不分流时混在媒体里，一律当媒体——只有说的确实是媒体（或用途不明）的放弃
     * 才放媒体焦点，免得别的用途的放弃把音乐的焦点放掉。
     */
    private fun onPhoneFocus(message: PhoneAudioFocus) {
        val route = message.route
        val usage = when {
            message.split -> route?.let { usageOf(it) } ?: return
            route == AudioRoute.CALL || route == AudioRoute.RINGTONE -> usageOf(route)
            else -> AudioUsage.MEDIA
        }
        val controller = focusController() ?: return
        when (message.action) {
            PhoneAudioFocus.Action.REQUEST ->
                controller.phoneRequested(usage, AudioFocusPolicy.Gain.ofPhoneFocusType(message.focusType))
            PhoneAudioFocus.Action.ABANDON ->
                if (message.split || usage != AudioUsage.MEDIA || route == null || route == AudioRoute.MEDIA) {
                    controller.phoneAbandoned(usage)
                }
            PhoneAudioFocus.Action.ABANDON_ALL -> controller.phoneAbandonedAll()
            PhoneAudioFocus.Action.POLICY, PhoneAudioFocus.Action.OTHER -> Unit
        }
    }

    private fun onVerdict(event: AudioEvent.CodecVerdict) {
        val phone = event.phone ?: return
        val record = prefs.recordPcmVerdict(phone.key, phone.name, event.verdict, System.currentTimeMillis())
        val outcome = when {
            record.useAac -> "这台手机下次连接改声明 AAC（设置页「声音」里能看到、能恢复）"
            event.verdict.failure -> "连续不出声 ${record.failures} 次，出过声的手机要连续 2 次才改用 AAC"
            else -> "记下：这台手机能用 PCM"
        }
        Log.i(TAG, "[$attemptId] $phone：${event.verdict.label}；$outcome")
    }

    /**
     * 这一路的播放器：没有就建（格式取自数据会话，编码按载荷判断）。放不了的数据会话返回 null。
     * 编码变了的旧播放器从表里拿掉、放进 [stale]（持有 [lock] 时不关它）。
     */
    private fun streamFor(packet: AudioPacket, stale: MutableList<Stream>): Stream? {
        val info = packet.stream
        val key = StreamKey(info.dataSessionId, packet.ssrc, packet.route)
        streams[key]?.let { existing ->
            if (existing.player.format.encoding == encodingOf(packet.codec)) return existing
            streams.remove(key)
            stale += existing
        }
        if (info.dataSessionId in unsupportedSessions) return null
        val encoding = encodingOf(packet.codec)
        if (encoding == AudioPlayer.Encoding.PCM_16 && info.bitsPerSample != 16) {
            return unsupported(info, "只支持 16 位 PCM，手机给的是 ${info.bitsPerSample} 位")
        }
        val format = try {
            AudioPlayer.Format(info.sampleRate, info.channels, encoding)
        } catch (e: IllegalArgumentException) {
            return unsupported(info, e.message ?: "格式不支持")
        }
        val usage = usageOf(packet.route)
        val stream = Stream(key, usage, packet.bus)
        val cushioned = wireless && (usage == AudioUsage.MEDIA || usage == AudioUsage.NAVIGATION)
        stream.player = AudioPlayer(format, stream, if (cushioned) tuning.withCushion(WIRELESS_CUSHION_MILLIS) else tuning, usage)
        volumes[usage]?.let { stream.player.setVolume(it) }
        streams[key] = stream
        Log.i(
            TAG,
            "[$attemptId] 建播放器：${stream.label()}，$format，音轨用途 ${usage.label}" +
                "（按载荷判断是 ${packet.codec.label}，声明的是 ${info.codec.label}）；${stream.player.tuning}",
        )
        return stream
    }

    private fun unsupported(stream: AudioStreamInfo, why: String): Stream? {
        unsupportedSessions += stream.dataSessionId
        Log.w(TAG, "[$attemptId] 放不了这次的声音：$why")
        return null
    }

    private fun stopSession(dataSessionId: String, why: String) {
        val stopped = synchronized(lock) {
            val gone = streams.values.filter { it.key.dataSessionId == dataSessionId }
            for (stream in gone) streams.remove(stream.key)
            gone
        }
        for (stream in stopped) {
            stream.finish()
            Log.i(TAG, "[$attemptId] 关播放器：${stream.label()}，$why")
        }
    }

    private companion object {
        const val TAG = "OpenHC-Audio"

        /** 每一路每这么多包记一行统计（媒体 AAC 约 10 秒）。 */
        const val STATS_EVERY = 500L

        /** 无线时音乐和导航多攒的毫秒数：车机扫描 WiFi 时投屏信道一次停 0.1～0.3 秒，留点余量。 */
        const val WIRELESS_CUSHION_MILLIS = 300

        fun encodingOf(codec: AudioCodec): AudioPlayer.Encoding =
            if (codec == AudioCodec.PCM) AudioPlayer.Encoding.PCM_16 else AudioPlayer.Encoding.AAC

        fun usageOf(route: AudioRoute): AudioUsage = when (route) {
            AudioRoute.MEDIA -> AudioUsage.MEDIA
            AudioRoute.NAVIGATION -> AudioUsage.NAVIGATION
            AudioRoute.VOICE -> AudioUsage.ASSISTANT
            AudioRoute.CALL -> AudioUsage.CALL
            AudioRoute.RINGTONE -> AudioUsage.RINGTONE
        }
    }
}
