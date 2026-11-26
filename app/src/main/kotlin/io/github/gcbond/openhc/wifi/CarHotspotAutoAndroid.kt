package io.github.gcbond.openhc.wifi

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/** 「自动打开车机热点」的开关和待关闭记号（单独一个 SharedPreferences 文件）。 */
class CarHotspotAutoPreferences(context: Context) : PendingJournal {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 设置开关，默认关。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 同步写盘：打开前必须先落盘，进程随时可能没了。 */
    override var pending: Boolean
        get() = prefs.getBoolean(KEY_PENDING, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_PENDING, value) }

    private companion object {
        const val FILE = "openhc-car-hotspot-auto"
        const val KEY_ENABLED = "enabled"
        const val KEY_PENDING = "pending"
    }
}

internal fun createCarHotspotAuto(context: Context): CarHotspotAuto = CarHotspotAuto(
    runner = LocalAdbRunner(context, HotspotSwitchCommand.helper, "CarHotspotAuto"),
    journal = CarHotspotAutoPreferences(context),
    worker = ExecutorWorker("openhc-car-hotspot-auto"),
    log = { Log.i("CarHotspotAuto", it) },
)
