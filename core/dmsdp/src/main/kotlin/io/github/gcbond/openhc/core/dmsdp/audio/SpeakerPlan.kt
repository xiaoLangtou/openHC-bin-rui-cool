package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.common.toHex
import java.security.MessageDigest

/**
 * 车机本地用来认出「是哪台手机」的编号：手机设备信息（命令 101）里 deviceId 的 SHA-256 前 8 字节，16 位小写十六进制。
 * 只在车机本地记结果用，不上线；日志里只露前 6 位。
 */
class PhoneRef(val key: String, val name: String?, val version: String? = null) {
    /** 日志里用的短编号。 */
    val shortKey: String get() = key.take(SHORT_KEY)

    /**
     * 按手机记传输方式时用的名单条目：编号加 HiCar 版本（`编号@版本`），版本不知道时只有编号。手机升级 HiCar 后条目对不上，
     * 会重新试一次 TCP。
     */
    val listEntry: String get() = version?.let { "$key$VERSION_SEPARATOR$it" } ?: key

    /** 在名单里：有这台手机这个版本的条目，或者只记了编号、没记版本的条目（旧条目，任何版本都算）。 */
    fun listedIn(entries: Set<String>): Boolean = listEntry in entries || key in entries

    override fun toString() = "手机 编号 $shortKey"

    companion object {
        private const val KEY_BYTES = 8
        private const val SHORT_KEY = 6

        /** 名单条目里编号和版本之间的分隔符。 */
        const val VERSION_SEPARATOR = "@"

        /** 名单条目 [entry] 是不是这台手机（编号 [key]）的，不管哪个版本。 */
        fun entryOf(entry: String, key: String): Boolean = entry == key || entry.startsWith("$key$VERSION_SEPARATOR")

        /** 由手机的 deviceId 算编号。 */
        fun keyOf(deviceId: String): String =
            MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8)).copyOf(KEY_BYTES).toHex()
    }
}

/**
 * 这次连接向手机声明的扬声器能力（属性 2102 的内容由它决定），连接里第一次用到时定下、之后不变。
 *
 * @property fallback 本来要声明 PCM，这台手机记着在 PCM 下不出声，所以改声明 AAC。
 */
class SpeakerPlan(
    val codec: AudioCodec,
    val multiBus: Boolean,
    val phone: PhoneRef?,
    val fallback: Boolean,
) {
    override fun toString(): String {
        val who = phone?.toString() ?: "还没收到手机的设备信息，认不出是哪台手机"
        val why = if (fallback) "；这台手机之前在 PCM 下不出声，改声明 AAC" else ""
        return "向手机声明扬声器：${codec.label}，多声道分流${if (multiBus) "开" else "关"}；$who$why"
    }
}

/** 声明 PCM 时，这台手机的表现。应用按手机记下来，决定下次连接要不要退回 AAC。 */
enum class PcmVerdict(val label: String, val failure: Boolean) {
    /** 收到够多的、能放的 PCM 包。 */
    WORKS("PCM 能正常出声", false),

    /** 手机要过长期焦点（想放歌），但整个连接里一次数据会话都没开。 */
    NO_DATA_SESSION("手机想出声，却始终没有开数据会话", true),

    /** 手机发来的 PCM 载荷不像声音（多半两边对编码的理解不一致），被静音不放。 */
    UNPLAYABLE("手机发来的 PCM 不像声音", true),

    /**
     * 手机在 ParaAudio 里明确要 AAC（Codec=1），却不应答 PCM 的 SETUP、把会话拆掉
     * （华为 HiCar 15.2 有线实测）。手机自己都不要 PCM，一次就记退回。
     */
    NO_ANSWER("手机不应答 PCM 的 SETUP（它要的是 AAC）", true),
}

/**
 * 声明 PCM 时判断这台手机能不能出声（纯逻辑）。声明 AAC 时什么都不判。每种结论每个连接最多出一次。
 *
 * - [PcmVerdict.WORKS]：收到 [WORKS_PACKETS] 个能放的 PCM 包（约 0.27 秒的声音）。
 * - [PcmVerdict.UNPLAYABLE]：识别时判定 PCM 载荷不像声音。
 * - [PcmVerdict.NO_DATA_SESSION]：连接结束时才判——手机要过长期焦点（放歌），整个连接里却没触发过一次数据会话，
 *   且从第一次长期焦点请求到结束超过 [WINDOW_MILLIS]（太短的连接不下结论）。只看「始终没有」，不在中途下结论。
 *   短暂焦点不算：手机连上时会要一下短暂焦点、马上放掉，不一定开数据会话；把它算进去，连上后没放歌、
 *   超过窗口才断开的连接都会被误判成 PCM 不出声。
 */
internal class PcmCheck(private val declaredPcm: Boolean) {
    private var firstRequestAt = -1L
    private var triggers = 0
    private var playablePcm = 0L
    private var worksSent = false
    private var unplayableSent = false

    /** 手机要焦点；[focusType] 见 [PhoneAudioFocus.focusType]，只有长期焦点（1）算「想出声」。 */
    fun onFocusRequest(focusType: Int?, nowMillis: Long) {
        if (declaredPcm && focusType == LONG_TERM_FOCUS && firstRequestAt < 0) firstRequestAt = nowMillis
    }

    fun onTrigger() {
        triggers++
    }

    /** 识别出分帧：PCM 载荷被判为不像声音时返回失败。 */
    fun onFraming(payloadCodec: AudioCodec, playable: Boolean): PcmVerdict? {
        if (!declaredPcm || unplayableSent || payloadCodec != AudioCodec.PCM || playable) return null
        unplayableSent = true
        return PcmVerdict.UNPLAYABLE
    }

    fun onPacket(codec: AudioCodec, playable: Boolean): PcmVerdict? {
        if (!declaredPcm || worksSent || codec != AudioCodec.PCM || !playable) return null
        playablePcm++
        if (playablePcm < WORKS_PACKETS) return null
        worksSent = true
        return PcmVerdict.WORKS
    }

    fun onClose(nowMillis: Long): PcmVerdict? {
        if (!declaredPcm || firstRequestAt < 0 || triggers > 0) return null
        return if (nowMillis - firstRequestAt >= WINDOW_MILLIS) PcmVerdict.NO_DATA_SESSION else null
    }

    companion object {
        const val WORKS_PACKETS = 50L
        const val WINDOW_MILLIS = 10_000L
        private const val LONG_TERM_FOCUS = 1
    }
}
