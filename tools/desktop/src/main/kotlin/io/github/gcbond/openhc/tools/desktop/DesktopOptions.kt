package io.github.gcbond.openhc.tools.desktop

import java.nio.file.Path
import java.nio.file.Paths

/** 参数不对；[message] 直接给用户看。 */
internal class UsageException(message: String) : Exception(message)

/** 用户要看用法。 */
internal class HelpRequested : Exception()

/** 桌面程序的命令行参数，写法见 [USAGE]。 */
internal class DesktopOptions(
    val identity: Path,
    val trustDir: Path,
    val ip: String,
    val port: Int,
    val bindIp: String,
    val localFilter: Boolean,
    val bluetooth: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val dpi: Int,
    val density: Double,
    val gop: Int?,
    val bitrate: Int?,
    val videoOut: Path,
    val carInfoOut: Path?,
    val durationSeconds: Long?,
    /** 声明手机请求的实验服务（1/3/5/9，空属性），观察数据通道（媒体卡片 515 等）是否建立。 */
    val extraServices: Boolean,
) {
    companion object {
        val USAGE = """
            |用法：openhc-desktop [参数]
            |
            |必填：
            |  --identity <文件>        连接参数 JSON（构建安装包用的同一份 openhc-identity.json，格式见 docs/identity.example.json）
            |  --trust-dir <目录>       信任库目录；不存在会以 0700 新建，首次配对后写入，下次免码回连
            |  --ip <IPv4>              车机在 USB 网卡上的地址，正好 14 个字符（例如 192.168.66.150），写进 carInfo
            |  --port <端口>            控制监听端口，写进 carInfo
            |  --bt <AA:BB:CC:DD:EE:FF> 车机蓝牙地址，可以是合成值；换了它手机会当成另一台车
            |  --video-out <文件>       H.264（Annex-B）输出文件，不能已存在
            |
            |可选：
            |  --carinfo-out <文件>     把 91 字节 carInfo 写到这里（0600，原子覆盖），给 USB 拉起脚本用
            |  --width <像素>           画布宽，默认 1280
            |  --height <像素>          画布高，默认 720
            |  --fps <帧率>             默认 30；车机屏刷新率不是 60Hz 时按实际填
            |  --dpi <DPI>              默认 160
            |  --density <比例>         缩放比例，默认 1.0
            |  --gop <帧数>             声明关键帧间隔，默认不声明
            |  --bitrate <bit/s>        声明目标码率，默认不声明
            |  --bind-ip <IPv4>         控制监听绑定的地址，默认 0.0.0.0（只接受连到 --ip 的连接）
            |  --local-filter on|off    通配监听时是否只接受连到 --ip 的连接，默认 on；筛选误判时可以关掉
            |  --duration <秒>          运行这么久后自动结束，默认一直运行到会话结束或 Ctrl-C
            |  --extra-services on|off  声明手机请求的实验服务（1/3/5/9），观察媒体卡片数据通道是否建立，默认 off
            |  -h, --help               显示本说明
            """.trimMargin()

        private val FLAGS = setOf(
            "identity", "trust-dir", "ip", "port", "bt", "video-out", "carinfo-out",
            "width", "height", "fps", "dpi", "density", "gop", "bitrate", "bind-ip", "local-filter", "duration",
            "extra-services",
        )

        /** 支持 `--名字 值` 和 `--名字=值` 两种写法。 */
        @Throws(UsageException::class, HelpRequested::class)
        fun parse(args: Array<String>): DesktopOptions {
            val values = LinkedHashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (arg == "-h" || arg == "--help") throw HelpRequested()
                if (!arg.startsWith("--")) throw UsageException("看不懂的参数：$arg")
                val eq = arg.indexOf('=')
                val name = if (eq >= 0) arg.substring(2, eq) else arg.substring(2)
                if (name !in FLAGS) throw UsageException("没有这个参数：--$name")
                val value = if (eq >= 0) {
                    arg.substring(eq + 1)
                } else {
                    i++
                    if (i >= args.size) throw UsageException("--$name 后面缺少取值")
                    args[i]
                }
                if (values.put(name, value) != null) throw UsageException("--$name 写了不止一次")
                i++
            }
            val missing = listOf("identity", "trust-dir", "ip", "port", "bt", "video-out").filter { it !in values }
            if (missing.isNotEmpty()) throw UsageException("缺少必填参数：" + missing.joinToString("、") { "--$it" })

            fun text(name: String) = values.getValue(name).also {
                if (it.isEmpty()) throw UsageException("--$name 不能为空")
            }
            fun int(name: String, default: Int?, range: IntRange): Int? {
                val raw = values[name] ?: return default
                val value = raw.toIntOrNull() ?: throw UsageException("--$name 必须是整数：$raw")
                if (value !in range) throw UsageException("--$name 必须在 ${range.first}～${range.last} 之间：$raw")
                return value
            }
            fun path(name: String): Path? = values[name]?.let {
                if (it.isEmpty()) throw UsageException("--$name 不能为空")
                Paths.get(it)
            }

            val density = values["density"]?.let { raw ->
                raw.toDoubleOrNull()?.takeIf { it > 0 && !it.isInfinite() } ?: throw UsageException("--density 必须是正数：$raw")
            } ?: 1.0
            val localFilter = when (val raw = values["local-filter"] ?: "on") {
                "on" -> true
                "off" -> false
                else -> throw UsageException("--local-filter 只能是 on 或 off：$raw")
            }
            val duration = values["duration"]?.let { raw ->
                raw.toLongOrNull()?.takeIf { it > 0 } ?: throw UsageException("--duration 必须是正整数秒：$raw")
            }
            return DesktopOptions(
                identity = path("identity")!!,
                trustDir = path("trust-dir")!!,
                ip = text("ip"),
                port = int("port", null, 1..65535)!!,
                bindIp = values["bind-ip"] ?: "0.0.0.0",
                localFilter = localFilter,
                bluetooth = text("bt"),
                width = int("width", 1280, 1..8192)!!,
                height = int("height", 720, 1..8192)!!,
                fps = int("fps", 30, 1..240)!!,
                dpi = int("dpi", 160, 1..2000)!!,
                density = density,
                gop = int("gop", null, 1..10_000),
                bitrate = int("bitrate", null, 1..Int.MAX_VALUE),
                videoOut = path("video-out")!!,
                carInfoOut = path("carinfo-out"),
                durationSeconds = duration,
                extraServices = when (val raw = values["extra-services"] ?: "off") {
                    "on" -> true
                    "off" -> false
                    else -> throw UsageException("--extra-services 只能是 on 或 off：$raw")
                },
            )
        }
    }
}
