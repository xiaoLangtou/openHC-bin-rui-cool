package io.github.gcbond.openhc.wifi

/**
 * 经车机本机的网络 ADB、以 shell 身份跑本应用安装包里的小程序（[ScanSwitchMain]、[HotspotSwitchMain]）的公共部分：
 * 结果、命令行和输出格式、调度接口。只有两个可选功能用到（暂停车机 WiFi 扫描、自动打开车机热点），都默认关。
 */
enum class AdbSwitchResult {
    /** 改好了。 */
    DONE,

    /** 检查：ADB 授权了、系统有这个接口，整条路走得通。 */
    READY,

    /** 要打开的东西本来就开着，没动它（车机热点）。 */
    ALREADY_ON,

    /** 车机本机的网络 ADB 没开（端口连不上）。 */
    ADB_OFF,

    /** 车机没授权这个应用调试（没点允许）。 */
    NOT_APPROVED,

    /** 车机只接受加密的「无线调试」，不支持。 */
    TLS_ONLY,

    /** 车机系统里没有这个接口。 */
    UNSUPPORTED,

    /** 系统拒绝了 shell 身份调用。 */
    DENIED,

    /** 其他错误：超时、输出不对。 */
    FAILED,
}

/**
 * 一个小程序的命令行和输出：`CLASSPATH=<安装包> app_process /system/bin <入口类> <命令>`，输出里找 `头|结果|说明` 那一行；
 * 没有（小程序超时退出、起不来）或结果不认识时算 [AdbSwitchResult.FAILED]。
 */
class AdbHelper(val mainClass: String, private val header: String, private val commands: Set<String>) {
    fun line(apkPath: String, command: String): String {
        require(command in commands)
        return "CLASSPATH=${quote(apkPath)} app_process /system/bin $mainClass $command"
    }

    fun parse(output: String): AdbSwitchResult {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$header|") }
            ?: return AdbSwitchResult.FAILED
        return when (line.split('|').getOrNull(1)) {
            "DONE" -> AdbSwitchResult.DONE
            "READY" -> AdbSwitchResult.READY
            "ALREADY_ON" -> AdbSwitchResult.ALREADY_ON
            "UNSUPPORTED" -> AdbSwitchResult.UNSUPPORTED
            "DENIED" -> AdbSwitchResult.DENIED
            else -> AdbSwitchResult.FAILED
        }
    }

    /** 单引号括起来，路径里的单引号按 shell 规矩转义。 */
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}

/** 跑一个小程序（阻塞，几秒到十几秒）。[ask] 为真时车机不认这个应用的钥匙就弹「允许调试吗」等车主点。 */
fun interface AdbHelperRunner {
    fun run(command: String, ask: Boolean): AdbSwitchResult
}

/** 「我们改了车机、还没改回去」的记号，存在磁盘上：进程中途没了，下次启动按它改回去。 */
interface PendingJournal {
    var pending: Boolean
}

/** 调度：都在同一个后台线程上按顺序跑。 */
interface AdbWorker {
    fun execute(task: () -> Unit)

    /** [delayMillis] 后跑；返回取消用的函数。 */
    fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit
}
