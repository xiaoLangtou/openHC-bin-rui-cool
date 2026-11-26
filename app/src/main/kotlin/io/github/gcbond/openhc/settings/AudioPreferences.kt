package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.dmsdp.audio.MicProfile
import io.github.gcbond.openhc.core.dmsdp.audio.PcmVerdict
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneRef
import io.github.gcbond.openhc.core.session.AudioSettings
import io.github.gcbond.openhc.core.session.MicSettings
import io.github.gcbond.openhc.platform.media.AudioStreamChoice
import io.github.gcbond.openhc.platform.media.AudioTuning

/**
 * 声音相关的设置（单独一个 SharedPreferences 文件）。改动在下一次连接时生效。任意线程可读写。
 *
 * 编码默认 PCM（延迟比 AAC 低），配合按手机的兜底：某台手机在 PCM 下不出声，就记下来、下次连接对它改声明 AAC
 * （见 [PcmFallbackBook]），设置页能看到并恢复。用户自己选了 AAC 就一律 AAC。传输方式、多声道分流、低延迟输出
 * 是给真机实验用的调试开关，平时保持默认。
 */
class AudioPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 把手机的声音放到车机上（向手机声明扬声器）。默认开。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 向手机要的编码。默认 PCM；只有明确选过 AAC 才是 AAC。 */
    var codec: AudioCodec
        get() = if (prefs.getString(KEY_CODEC, null) == VALUE_AAC) AudioCodec.AAC else AudioCodec.PCM
        set(value) = prefs.edit { putString(KEY_CODEC, if (value == AudioCodec.AAC) VALUE_AAC else VALUE_PCM) }

    /** 调试：SETUP 里提议的传输方式。默认 TCP。 */
    var transport: AudioTransport
        get() = if (prefs.getString(KEY_TRANSPORT, null) == VALUE_UDP) AudioTransport.UDP else AudioTransport.TCP
        set(value) = prefs.edit { putString(KEY_TRANSPORT, if (value == AudioTransport.UDP) VALUE_UDP else VALUE_TCP) }

    /** 调试：声明多音频总线（导航语音单独一路、走导航声道）。默认开；关掉就回到所有声音混在媒体一路。 */
    var multiBus: Boolean
        get() = prefs.getBoolean(KEY_MULTI_BUS, true)
        set(value) = prefs.edit { putBoolean(KEY_MULTI_BUS, value) }

    /**
     * 声音延后（毫秒）：在最低缓冲之上多攒这么多，声音整体往后挪。默认 0（最低）。
     * 声音比画面早时往上调；只认 [DELAY_CHOICES] 里的值，别的按 0。
     */
    var delayMillis: Int
        get() = prefs.getInt(KEY_DELAY, 0).takeIf { it in DELAY_CHOICES } ?: 0
        set(value) = prefs.edit { putInt(KEY_DELAY, if (value in DELAY_CHOICES) value else 0) }

    /** 调试：安卓 8.0 起向系统要低延迟的输出路径。默认开；关掉用来对比延迟。 */
    var lowLatency: Boolean
        get() = prefs.getBoolean(KEY_LOW_LATENCY, true)
        set(value) = prefs.edit { putBoolean(KEY_LOW_LATENCY, value) }

    /** 音乐（媒体那一路）放到车机的哪个声道：自动（默认）或 1～20 号，见 [AudioStreamChoice]。 */
    var mediaStream: Int
        get() = AudioStreamChoice.normalize(prefs.getInt(KEY_MEDIA_STREAM, AudioStreamChoice.AUTO))
        set(value) = prefs.edit { putInt(KEY_MEDIA_STREAM, AudioStreamChoice.normalize(value)) }

    /** 导航播报那一路放到车机的哪个声道：自动（默认）或 1～20 号，见 [AudioStreamChoice]。 */
    var navigationStream: Int
        get() = AudioStreamChoice.normalize(prefs.getInt(KEY_NAVIGATION_STREAM, AudioStreamChoice.AUTO))
        set(value) = prefs.edit { putInt(KEY_NAVIGATION_STREAM, AudioStreamChoice.normalize(value)) }

    /** 车机麦克风交给手机（打电话、语音助手）。默认开；没有录音权限时照样不声明。 */
    var micEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIC_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_MIC_ENABLED, value) }

    /**
     * 调试：向手机声明麦克风能力（16kHz 单声道 PCM）。默认开，手机会要 PCM；关掉就不声明能力，
     * 手机按自己的默认参数要（可能要 AAC），用来对比。
     */
    var micDeclareProfile: Boolean
        get() = prefs.getBoolean(KEY_MIC_PROFILE, true)
        set(value) = prefs.edit { putBoolean(KEY_MIC_PROFILE, value) }

    /** 开始连接前是否已经自动申请过一次录音权限（之后只在设置页里由用户主动申请）。 */
    var micPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_MIC_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_MIC_ASKED, value) }

    /** 下一次连接的麦克风设置：关掉或没有录音权限时为 null（不声明麦克风，手机用自己的）。 */
    fun micSettings(permissionGranted: Boolean): MicSettings? =
        if (micEnabled && permissionGranted) MicSettings(if (micDeclareProfile) MicProfile.PCM_16K_MONO else null) else null

    /** 按手机记下的 PCM 结果（读的是当前快照）。 */
    fun pcmFallbacks(): PcmFallbackBook = synchronized(LOCK) { PcmFallbackBook.decode(prefs.getString(KEY_PCM_BOOK, null)) }

    /** 记一次 PCM 判定（协议线程上调用），返回这台手机更新后的记录。 */
    fun recordPcmVerdict(key: String, name: String?, verdict: PcmVerdict, nowMillis: Long): PcmPhoneRecord = synchronized(LOCK) {
        val book = PcmFallbackBook.decode(prefs.getString(KEY_PCM_BOOK, null))
        val record = book.record(key, name, verdict, nowMillis)
        prefs.edit { putString(KEY_PCM_BOOK, book.encode()) }
        record
    }

    /** 设置页「恢复 PCM」：忘掉这台手机的记录。 */
    fun forgetPcmFallback(key: String) = synchronized(LOCK) {
        val book = PcmFallbackBook.decode(prefs.getString(KEY_PCM_BOOK, null))
        book.forget(key)
        prefs.edit { putString(KEY_PCM_BOOK, book.encode()) }
    }

    /** 要用 UDP 传声音的手机（它们不走 TCP 方式的声音连接，见 [AudioSettings]）：条目是「编号@HiCar 版本」，旧条目只有编号。 */
    fun udpPhones(): Set<String> = synchronized(LOCK) { prefs.getStringSet(KEY_UDP_PHONES, null)?.toSet() ?: emptySet() }

    /**
     * 记下一台要用 UDP 的手机（协议线程上调用）：同一台手机以前的条目（别的 HiCar 版本）换成这一条；
     * 最多记 [MAX_UDP_PHONES] 台，多了清掉重记。
     */
    fun addUdpPhone(phone: PhoneRef) = synchronized(LOCK) {
        val now = prefs.getStringSet(KEY_UDP_PHONES, null)?.toMutableSet() ?: HashSet()
        now.removeAll { PhoneRef.entryOf(it, phone.key) }
        if (now.size >= MAX_UDP_PHONES) now.clear()
        now += phone.listEntry
        prefs.edit { putStringSet(KEY_UDP_PHONES, now) }
    }

    /** 忘掉一台手机的「改用 UDP」记录（恢复走 TCP），移除该手机所有版本的条目。 */
    fun forgetUdpPhone(key: String) = synchronized(LOCK) {
        val now = prefs.getStringSet(KEY_UDP_PHONES, null)?.toMutableSet() ?: return
        if (now.removeAll { PhoneRef.entryOf(it, key) }) {
            prefs.edit { putStringSet(KEY_UDP_PHONES, now) }
        }
    }

    /** 清空全部「改用 UDP」记录。 */
    fun clearUdpPhones() = synchronized(LOCK) {
        prefs.edit { remove(KEY_UDP_PHONES) }
    }

    /** 下一次连接的音频设置；关掉声音时为 null（不声明扬声器，声音留在手机上）。 */
    fun toSettings(): AudioSettings? = if (enabled) {
        AudioSettings(transport, codec, multiBus, if (codec == AudioCodec.PCM) pcmFallbacks().aacPhones() else emptySet(), udpPhones())
    } else {
        null
    }

    /** 下一次连接的播放参数：缓冲默认最低，加上声音延后；音乐、导航各放到哪个声道。 */
    fun playbackTuning(): AudioTuning = AudioTuning(
        extraDelayMillis = delayMillis,
        lowLatency = lowLatency,
        mediaStream = mediaStream,
        navigationStream = navigationStream,
    )

    companion object {
        /** 设置页给的「声音延后」档位（毫秒），0 是最低。 */
        val DELAY_CHOICES = listOf(0, 50, 100, 150, 200, 300)

        /** 记录的读改写在协议线程和界面线程上都会发生。 */
        private val LOCK = Any()

        private const val FILE = "openhc-audio"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CODEC = "debug_codec"
        private const val KEY_TRANSPORT = "debug_transport"
        private const val KEY_UDP_PHONES = "udp_phones"
        private const val MAX_UDP_PHONES = 32
        private const val KEY_MULTI_BUS = "debug_multi_bus"
        private const val KEY_DELAY = "delay_millis"
        private const val KEY_LOW_LATENCY = "debug_low_latency"
        private const val KEY_MEDIA_STREAM = "media_stream"
        private const val KEY_NAVIGATION_STREAM = "navigation_stream"
        private const val KEY_MIC_ENABLED = "mic_enabled"
        private const val KEY_MIC_PROFILE = "debug_mic_profile"
        private const val KEY_MIC_ASKED = "mic_permission_asked"
        private const val KEY_PCM_BOOK = "pcm_fallback_v1"
        private const val VALUE_PCM = "pcm"
        private const val VALUE_AAC = "aac"
        private const val VALUE_TCP = "tcp"
        private const val VALUE_UDP = "udp"
    }
}
