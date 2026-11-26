package io.github.gcbond.openhc.wifi

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/** 「连接时暂停车机 WiFi 扫描」的开关和待恢复记号（单独一个 SharedPreferences 文件）。 */
class ScanPausePreferences(context: Context) : PendingJournal {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 设置开关，默认关。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 同步写盘：暂停前必须先落盘，进程随时可能没了。 */
    override var pending: Boolean
        get() = prefs.getBoolean(KEY_PENDING, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_PENDING, value) }

    private companion object {
        const val FILE = "openhc-scan-pause"
        const val KEY_ENABLED = "enabled"
        const val KEY_PENDING = "pending"
    }
}

internal fun createScanPause(context: Context): ScanPause {
    val prefs = ScanPausePreferences(context)
    return ScanPause(
        runner = LocalAdbRunner(context, ScanSwitchCommand.helper, "ScanPause"),
        journal = prefs,
        enabled = { prefs.enabled },
        worker = ExecutorWorker("openhc-scan-pause"),
        log = { Log.i("ScanPause", it) },
    )
}
