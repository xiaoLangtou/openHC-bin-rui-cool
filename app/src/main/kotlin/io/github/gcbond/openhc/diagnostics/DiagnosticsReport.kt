package io.github.gcbond.openhc.diagnostics

import java.util.Locale

/** 诊断日志文件的组成（纯逻辑，JVM 单元测试覆盖）：文件名、开头的说明和摘要、各段的标题。 */
internal object DiagnosticsReport {
    /** 存到「下载」里的子文件夹名。 */
    const val FOLDER = "OpenHC"

    /** [timestamp] 形如 `20261122-201530`。 */
    fun fileName(timestamp: String): String = "openhc-diagnostics-$timestamp.txt"

    /** 文件开头：一段说明，再一行一项的摘要（项目：值）。 */
    fun header(facts: List<Pair<String, String>>): String = buildString {
        append("OpenHC 诊断日志\n")
        append("只有 OpenHC 自己的运行记录：不含连接密钥和 WiFi 密码，手机、车机的蓝牙和网卡地址只留首尾几位。\n")
        append("报告问题时把整个文件附上即可。\n\n")
        for ((name, value) in facts) append(name).append("：").append(value.ifBlank { "（空）" }).append('\n')
    }

    fun section(title: String): String = "\n===== $title =====\n"

    /**
     * 导出前逐行处理：完整的蓝牙 / 网卡地址只留首尾几位（和 OpenHC 自己打日志时的写法一样）。OpenHC 自己本来就不打完整地址，
     * 这里兜住系统在本进程里打的日志（各家车机系统打什么不一定）。占位地址（全 0、02:00:00:00:00:00、全 f）不认人，原样留着。
     */
    fun redact(line: String): String = MAC.replace(line) { m ->
        val mac = m.value
        if (mac.lowercase() in KEEP) mac else mac.substring(0, 2) + ":**:**:**:" + mac.substring(12)
    }

    /**
     * 这一行（`logcat -v threadtime` 格式）是不是 [pid] 这个进程打的。不是日志行的（例如「--------- beginning of main」）算是。
     * 安卓 7.0 以下 logcat 不能按进程号过滤，靠这个保证只记本进程的日志（不依赖系统只给应用看自己日志的限制）。
     */
    fun fromProcess(line: String, pid: String): Boolean {
        val m = THREADTIME_PID.find(line) ?: return true
        return m.groupValues[1] == pid
    }

    /**
     * OpenHC 自己的一条日志写成 `logcat -v threadtime` 的格式（和读 logcat 记下的行一样），多行的消息和堆栈每行都带前缀。
     * [time] 形如 `01-02 03:04:05.678`；[priority] 是安卓日志级别（2 VERBOSE ～ 7 ASSERT）。
     */
    fun ownLines(time: String, pid: Int, tid: Int, priority: Int, tag: String, message: String, stack: String?): List<String> {
        val prefix = String.format(Locale.US, "%s %5d %5d %c %s: ", time, pid, tid, levelChar(priority), tag)
        val text = if (stack.isNullOrBlank()) message else message + "\n" + stack.trimEnd()
        return text.split('\n').map { prefix + it.removeSuffix("\r") }
    }

    /**
     * `logcat` 读到的这一行是不是 OpenHC 自己的日志（已经直接记过，读 logcat 时跳过，免得记两遍）：标签是 `OpenHC` 或
     * `OpenHC-…`。内核的权限拒绝记录（`type=1400 audit…`）会拿线程名当标签，本应用的线程名也是 `OpenHC-…`，那些不是
     * 自己打的，照记。
     */
    fun writtenDirectly(line: String): Boolean {
        val m = THREADTIME_TAG.find(line) ?: return false
        val tag = m.groupValues[1]
        if (tag != OWN_TAG && !tag.startsWith("$OWN_TAG-")) return false
        return !m.groupValues[2].startsWith("type=1400")
    }

    private fun levelChar(priority: Int): Char = when (priority) {
        2 -> 'V'
        3 -> 'D'
        4 -> 'I'
        5 -> 'W'
        6 -> 'E'
        7 -> 'A'
        else -> '?'
    }

    private const val OWN_TAG = "OpenHC"

    private val THREADTIME_PID = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+(\\d+)\\s+\\d+\\s")

    /** threadtime 行里的标签和消息；短标签 logcat 会在冒号前补空格。 */
    private val THREADTIME_TAG = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+\\d+\\s+\\d+ [VDIWEFAS] (.+?)\\s*: ?(.*)$")

    /** 冒号分隔的 6 段地址；前后紧挨着别的十六进制段的不算（例如更长的 IPv6 地址）。 */
    private val MAC = Regex("(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]:)[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?!:?[0-9A-Fa-f])")

    private val KEEP = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")
}
