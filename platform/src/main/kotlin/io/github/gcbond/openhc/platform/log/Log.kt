package io.github.gcbond.openhc.platform.log

/**
 * 代替 `android.util.Log`：照常写进系统日志，同时交给 [sink]（应用的诊断记录器），由它直接写进诊断日志文件。
 *
 * 诊断日志原来全靠应用自己读 `logcat`，但有的车机系统不让应用读到自己的日志（导出来只有进程开始的标记），
 * 有的读一阵就断（中间缺几分钟）。OpenHC 自己的日志走这里就不受影响。
 *
 * 用法和 `android.util.Log` 一样，只换 import。应用和 platform 里不要再直接用 `android.util.Log`，
 * 否则那一行只进系统日志：诊断记录器读 `logcat` 时会跳过 OpenHC 的标签，免得同一行记两遍。
 */
object Log {
    /** 收日志的一方。在打日志的线程上调用，要很快返回（不做文件读写）。 */
    fun interface Sink {
        fun record(priority: Int, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    fun v(tag: String, message: String): Int = emit(android.util.Log.VERBOSE, tag, message, null)

    fun d(tag: String, message: String): Int = emit(android.util.Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String): Int = emit(android.util.Log.INFO, tag, message, null)

    fun w(tag: String, message: String): Int = emit(android.util.Log.WARN, tag, message, null)

    fun w(tag: String, message: String, error: Throwable?): Int = emit(android.util.Log.WARN, tag, message, error)

    fun e(tag: String, message: String): Int = emit(android.util.Log.ERROR, tag, message, null)

    fun e(tag: String, message: String, error: Throwable?): Int = emit(android.util.Log.ERROR, tag, message, error)

    private fun emit(priority: Int, tag: String, message: String, error: Throwable?): Int {
        val written = if (error == null) {
            android.util.Log.println(priority, tag, message)
        } else {
            android.util.Log.println(priority, tag, message + '\n' + android.util.Log.getStackTraceString(error))
        }
        try {
            sink?.record(priority, tag, message, error)
        } catch (e: RuntimeException) {
            // 诊断记录出问题不能影响正常功能。
        }
        return written
    }
}
