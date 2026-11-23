package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.session.DisplaySettings

/**
 * 设置里的清晰度：决定向手机声明的目标码率（bit/s）。纯逻辑。
 *
 * - 标准（默认）：不声明码率，手机按自己的默认码率编码（实测一台手机默认目标约 9 Mbps、上限 15 Mbps）；
 * - 高：声明 12 Mbps；
 * - 最高：声明 15 Mbps。
 *
 * 实测手机按车机声明的目标码率编码（声明 12 Mbps，手机就用 12 Mbps），但上限被手机自己封在 15 Mbps，所以不设更高的档；
 * 只声明目标、目标又低于手机默认值时，手机不一定照做（实测声明 5 Mbps 手机仍用约 9 Mbps），所以也不设省流档。
 * 码率越高，手机越耗电、越容易发热（发热后手机会自己降帧），无线连接也越容易拥塞。
 *
 * 声明走 [DisplaySettings.bitrate]：投屏能力里的 `bitrate`，以及设备信息里的码率属性。只声明目标，不声明上下限。
 * 协议里目标码率的取值范围是 [MIN_BITRATE]～[MAX_BITRATE]，各档都在范围内。
 */
enum class VideoClarity(
    /** 存进设置里的文本，与界面文字无关，不能改。 */
    val key: String,
    /** 声明给手机的目标码率；null 表示不声明，由手机决定。 */
    val bitrate: Int?,
) {
    STANDARD("standard", null),
    HIGH("high", 12_000_000),
    HIGHEST("highest", 15_000_000),
    ;

    companion object {
        /** 默认「高」：车机是常供电设备，码率高一点不心疼；12 Mbps 在无线热点的 TCP 承载内也稳。
         *  「标准」（不声明，手机约 9 Mbps）作为兜底档保留。 */
        val DEFAULT = HIGH

        /** 协议允许声明的目标码率下限、上限（bit/s）。 */
        const val MIN_BITRATE = 500_000
        const val MAX_BITRATE = 20_000_000

        /** 解析设置里存的文本；没存过或认不出来按默认。 */
        fun parse(text: String?): VideoClarity = entries.firstOrNull { it.key == text } ?: DEFAULT

        /** 日志用：声明了多少码率。 */
        fun describe(bitrate: Int?): String =
            if (bitrate == null) "不声明（手机按自己的默认码率）" else "$bitrate bit/s"
    }
}

/**
 * 按清晰度定声明的码率，其余参数不变。「标准」不改动：原来声明了什么就是什么（现在的画布计算不声明码率）。
 */
fun DisplaySettings.withClarity(clarity: VideoClarity): DisplaySettings = DisplaySettings(
    width = width,
    height = height,
    fps = fps,
    dpi = dpi,
    density = density,
    gop = gop,
    bitrate = clarity.bitrate ?: bitrate,
)
