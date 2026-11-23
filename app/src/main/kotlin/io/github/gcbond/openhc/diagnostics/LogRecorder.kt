package io.github.gcbond.openhc.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.gcbond.openhc.platform.log.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 把本应用自己的日志一直记进私有目录（「导出诊断日志」用）。车机的日志缓冲常常只有 256 KB、全车共用，
 * 事后再读早就被冲掉了，所以要边跑边记。
 *
 * - OpenHC 自己的日志（走 [Log]）在打的时候就交过来，由写文件线程直接写进 [CURRENT]，不经过 `logcat`：
 *   有的车机系统不让应用读到自己的日志，有的读一阵就断。打日志的线程只把行放进队列，不碰文件；
 *   队列满了（写文件跟不上）就丢，丢了多少行会记一笔。
 * - 另起一个 `logcat` 子进程，补记系统在本进程里打的日志（蓝牙套接字、崩溃堆栈、权限拒绝等）：
 *   普通应用本来就只读得到自己的日志，安卓 7.0 起再按本进程号过滤一遍；OpenHC 自己的标签已经直接记了，跳过
 *   （[DiagnosticsReport.writtenDirectly]）。`logcat` 子进程意外退出时隔几秒重启，最多 [MAX_RESTARTS] 次。
 * - 文件满 [MAX_FILE_BYTES] 就换成 [PREVIOUS]（旧的删掉），最多两份。
 * - 启动时把系统崩溃缓冲里本应用的记录（上次崩溃的堆栈）存进 [CRASH]。
 *
 * 线程：两个后台线程（写文件、读 `logcat`）；[flush] 和读文件的方法可在任意线程调。
 * 锁的顺序：先 [fileLock] 再 [queueLock]；打日志的线程只拿 [queueLock]。所有排队的行（含诊断记录自己的标记行）
 * 都在持有 [fileLock] 时整批取出写掉，所以按排队的先后写进文件；`logcat` 读到的系统日志另外直接写，会比实际晚一点。
 */
internal object LogRecorder {
    private const val TAG = "OpenHC-Diag"
    const val DIR = "diagnostics"
    const val CURRENT = "log.txt"
    const val PREVIOUS = "log.1.txt"
    const val CRASH = "crash.txt"
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_CRASH_BYTES = 256 * 1024
    private const val MAX_RESTARTS = 20
    private const val RESTART_DELAY_MS = 5_000L

    /** 还没写进文件的自己的日志最多攒这么多行。 */
    private const val MAX_QUEUED_LINES = 2_000

    private val fileLock = Any()
    private val queueLock = ReentrantLock()
    private val queueNotEmpty = queueLock.newCondition()

    @Volatile
    private var dir: File? = null

    // 以下由 fileLock 保护。
    private var writer: BufferedWriter? = null
    private var written = 0L

    // 以下由 queueLock 保护。
    private var queued = ArrayList<String>()
    private var dropped = 0

    private val pid = Process.myPid()

    /** 开始记（应用启动时调一次；再调不做什么）。 */
    fun start(context: Context) {
        synchronized(fileLock) {
            if (dir != null) return
            dir = File(context.filesDir, DIR).apply { mkdirs() }
            openWriter()
        }
        // 先接上自己的日志，之后打的每一行都不再依赖 logcat。
        Log.sink = Log.Sink { priority, tag, message, error -> enqueue(priority, tag, message, error) }
        mark("进程 $pid 开始记录")
        Thread({ drainLoop() }, "OpenHC-LogWriter").apply { isDaemon = true }.start()
        Thread({ logcatLoop() }, "OpenHC-LogRecorder").apply { isDaemon = true }.start()
    }

    /** 现有的日志文件，旧的在前。 */
    fun logFiles(): List<File> {
        val d = dir ?: return emptyList()
        return listOf(File(d, PREVIOUS), File(d, CURRENT)).filter { it.isFile }
    }

    /** 上次启动时读到的崩溃记录文件（没有就 null）。 */
    fun crashFile(): File? = dir?.let { File(it, CRASH) }?.takeIf { it.isFile && it.length() > 0 }

    /** 把队列里和缓冲里还没写进文件的行写进去（导出前调）。 */
    fun flush() {
        synchronized(fileLock) {
            writeQueued()
            flushQuietly()
        }
    }

    /** 在打日志的线程上调：只排队，不碰文件。 */
    private fun enqueue(priority: Int, tag: String, message: String, error: Throwable?) {
        val stack = error?.stackTraceToString()
        val lines = DiagnosticsReport.ownLines(now(), pid, Process.myTid(), priority, tag, message, stack)
        enqueueLines(lines)
    }

    private fun enqueueLines(lines: List<String>) {
        queueLock.withLock {
            if (queued.size + lines.size > MAX_QUEUED_LINES) {
                dropped += lines.size
            } else {
                queued.addAll(lines)
            }
            queueNotEmpty.signal()
        }
    }

    /** 写文件线程：有新行就写，写完一批 flush 一次。 */
    private fun drainLoop() {
        while (true) {
            try {
                queueLock.withLock {
                    while (queued.isEmpty() && dropped == 0) queueNotEmpty.await()
                }
            } catch (e: InterruptedException) {
                return
            }
            synchronized(fileLock) {
                writeQueued()
                flushQuietly()
            }
        }
    }

    /** 调用时持有 [fileLock]：把队列里的行整批取出来写掉，保证先排队的先写。 */
    private fun writeQueued() {
        val batch: List<String>
        val lost: Int
        queueLock.withLock {
            batch = queued
            lost = dropped
            queued = ArrayList()
            dropped = 0
        }
        if (lost > 0) writeLine(markLine("写文件跟不上，丢了 $lost 行日志"))
        for (line in batch) writeLine(line)
    }

    private fun logcatLoop() {
        saveCrashBuffer()
        var restarts = 0
        while (true) {
            val why = pump(tailOnly = restarts > 0)
            if (restarts >= MAX_RESTARTS) {
                mark("读系统日志的 logcat 退出了（$why），已经重启 $MAX_RESTARTS 次，不再读（OpenHC 自己的日志照记）")
                return
            }
            restarts++
            mark("读系统日志的 logcat 退出了（$why），${RESTART_DELAY_MS / 1000} 秒后重启（OpenHC 自己的日志照记）")
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** 跑一个 `logcat` 直到它退出，返回退出原因。[tailOnly]：重启时只接着记新日志，不把缓冲里的旧行再记一遍。 */
    private fun pump(tailOnly: Boolean): String {
        val command = mutableListOf("logcat", "-v", "threadtime")
        if (tailOnly) command += listOf("-T", "1")
        val ownPid = pid.toString()
        // 安卓 7.0 起 logcat 自己按进程号过滤；更早的系统逐行看进程号（[DiagnosticsReport.fromProcess]）。
        val filterLines = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        if (!filterLines) command += "--pid=$ownPid"
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.w(TAG, "起不来 logcat，系统日志不记：${e.message}")
            return "起不来：${e.message}"
        }
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (filterLines && !DiagnosticsReport.fromProcess(line, ownPid)) continue
                    if (DiagnosticsReport.writtenDirectly(line)) continue
                    synchronized(fileLock) {
                        writeLine(line)
                        flushQuietly()
                    }
                }
            }
        } catch (e: IOException) {
            return "读失败：${e.message}"
        } finally {
            process.destroy()
        }
        return "输出结束"
    }

    /** 诊断记录自己的标记行，和自己的日志一起排队。 */
    private fun mark(text: String) = enqueueLines(listOf(markLine(text)))

    private fun markLine(text: String): String = "===== ${now()} OpenHC 诊断记录：$text ====="

    private fun now(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** 调用时持有 [fileLock]。 */
    private fun flushQuietly() {
        try {
            writer?.flush()
        } catch (e: IOException) {
            // 下次再试。
        }
    }

    /** 调用时持有 [fileLock]。不 flush：由调用方按批 flush（写文件线程每批一次，读 logcat 每行一次，导出前一次）。 */
    private fun writeLine(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            written += line.toByteArray(Charsets.UTF_8).size + 1L
            if (written >= MAX_FILE_BYTES) rotate()
        } catch (e: IOException) {
            // 写满或者文件出问题：丢这一行，下一行再试。
        }
    }

    /** 调用时持有 [fileLock]。 */
    private fun openWriter() {
        val d = dir ?: return
        val file = File(d, CURRENT)
        written = if (file.isFile) file.length() else 0L
        if (written >= MAX_FILE_BYTES) return rotate()
        writer = try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "打不开诊断日志文件：${e.message}")
            null
        }
    }

    /** 调用时持有 [fileLock]。 */
    private fun rotate() {
        val d = dir ?: return
        try {
            writer?.close()
        } catch (e: IOException) {
            // 关不掉也接着换。
        }
        writer = null
        File(d, PREVIOUS).delete()
        File(d, CURRENT).renameTo(File(d, PREVIOUS))
        written = 0L
        writer = try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(File(d, CURRENT), false), Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
    }

    /** 系统崩溃缓冲里本应用的记录（普通应用只读得到自己的）存进 [CRASH]，有上限。 */
    private fun saveCrashBuffer() {
        val d = dir ?: return
        val out = StringBuilder()
        try {
            val process = ProcessBuilder("logcat", "-d", "-b", "crash", "-v", "threadtime").redirectErrorStream(true).start()
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (out.length < MAX_CRASH_BYTES) {
                        val line = reader.readLine() ?: break
                        out.append(line).append('\n')
                    }
                }
            } finally {
                process.destroy()
            }
        } catch (e: IOException) {
            return
        }
        val text = out.toString().trim()
        val file = File(d, CRASH)
        if (text.isEmpty() || text.startsWith("logcat:")) {
            file.delete()
            return
        }
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            file.writeText("（$time 应用启动时读到的系统崩溃记录）\n$text\n", Charsets.UTF_8)
        } catch (e: IOException) {
            // 存不下就算了。
        }
    }
}
