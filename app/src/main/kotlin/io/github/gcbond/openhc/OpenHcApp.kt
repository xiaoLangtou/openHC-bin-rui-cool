package io.github.gcbond.openhc

import android.app.Application
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.diagnostics.LogRecorder
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.ProjectionSurfacer
import io.github.gcbond.openhc.wireless.LePairingNames
import io.github.gcbond.openhc.wireless.WirelessController

class OpenHcApp : Application() {

    /** 有线投屏的总调度，进程内唯一。 */
    val connection: ConnectionController by lazy { ConnectionController(this) }

    private val wirelessLazy = lazy {
        WirelessController(
            this,
            wiredActive = { connection.state.value.active },
            // 有线在准备或等插线、车机上又没插着手机：只是在等插线，认识的手机蓝牙连上时让给无线。
            wiredWaitingForPhone = {
                connection.state.value.phase in WIRED_WAITING_PHASES && !BootStart.phonePlugged(this)
            },
            stopWiredWaiting = { connection.stop() },
            resumeWiredWaiting = { connection.start() },
            phoneOnCable = { BootStart.phonePlugged(this) },
        ).also { controller ->
            controller.addHostListener { surfacer.onStateChanged() }
        }
    }

    /** 无线投屏的总调度，进程内唯一；和有线互斥（有线在进行时不开始）。 */
    val wireless: WirelessController by wirelessLazy

    /** 无线调度已经建了才返回它：只想看一眼状态时不为此去建。 */
    internal val wirelessIfCreated: WirelessController? get() = if (wirelessLazy.isInitialized()) wireless else null

    /** 本应用有没有界面在前台。 */
    internal val uiVisibility = UiVisibility()

    /** 开机静默连接：手机连上时把界面带到前台（静默关着时什么都不做）。 */
    private val surfacer by lazy { ProjectionSurfacer(this) }

    override fun onCreate() {
        super.onCreate()
        // 一启动就开始记自己的日志（「设置 → 诊断 → 导出诊断日志」用），越早越能把启动过程记全。
        LogRecorder.start(this)
        registerActivityLifecycleCallbacks(uiVisibility)
        // 有线开始时无线让出等回连，有线结束后无线再回到等待。
        connection.addHostListener {
            wireless.onWiredChanged()
            surfacer.onStateChanged()
        }
        // 「补全手机蓝牙配对」上次没改回去的车机蓝牙名称（进程中途没了）：现在改回去，蓝牙关着就等它打开。
        LePairingNames.restoreIfNeeded(this, "应用启动")
    }

    private companion object {
        /** 有线还没接上手机的阶段：准备中、等插线。 */
        val WIRED_WAITING_PHASES = setOf(Phase.PREPARING, Phase.WAITING_FOR_PHONE)
    }
}
