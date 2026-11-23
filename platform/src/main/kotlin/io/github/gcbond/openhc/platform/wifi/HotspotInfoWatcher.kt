package io.github.gcbond.openhc.platform.wifi

import android.net.wifi.WifiManager
import android.os.Build
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * 读本地热点的真实频率和 BSSID：挂系统的「本地热点信息」回调（`WifiManager.registerLocalOnlyHotspotSoftApCallback`），
 * 回调交来的 `SoftApInfo` 带热点实际的频率和 BSSID。这个方法安卓 13 才有，而且是系统接口（`@SystemApi`，
 * 要「附近的 WiFi 设备」权限），普通应用在不少系统上会被拦；方法和回调接口都用反射找，找不到、被系统拒绝、
 * 或者回调一直没来，[frequencyMhz]、[bssid] 就保持空，调用方照旧推测。只在安卓 13 起尝试。
 *
 * 来由：有车主在安卓 13 车机上实测，本地热点开在 5 GHz 36 信道、BSSID 每次随机，推测的 2437 MHz 和占位 BSSID
 * 都让手机找不到网（手机按频率和 BSSID 精确找网）；据他说这个回调在他的车机上能用。
 *
 * 线程：[register]、[unregister]、[reset] 在调用方线程上调；系统的回调经 [executor] 交付，结果写进 @Volatile 字段。
 */
internal class HotspotInfoWatcher(
    private val wifi: WifiManager,
    private val executor: Executor,
    private val log: (String) -> Unit,
) {
    /** 系统回报的实际频率（MHz），还没回报为 0。 */
    @Volatile
    var frequencyMhz: Int = 0
        private set

    /** 系统回报的 BSSID（原样文本，用前按 [MacText.normalize] 过滤），还没回报为 null。 */
    @Volatile
    var bssid: String? = null
        private set

    /** 回调挂上了。挂不上时不必等它的回报。 */
    @Volatile
    var registered: Boolean = false
        private set

    private var callback: Any? = null
    private var callbackClass: Class<*>? = null

    /** 试过一次挂不上就不再试（同一台车机上结果不会变）。 */
    private var unavailable = false

    /** 每次申请热点前清掉上一次的回报：热点的 BSSID 每次随机，频率也可能变。 */
    fun reset() {
        frequencyMhz = 0
        bssid = null
    }

    /** 挂上回调；已经挂上、安卓 13 以前、或者之前挂不上的，不再尝试。 */
    fun register() {
        if (registered || unavailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val type = Class.forName(CALLBACK_CLASS)
            val method = WifiManager::class.java.getMethod(REGISTER, Executor::class.java, type)
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type), Callback())
            method.invoke(wifi, executor, proxy)
            callback = proxy
            callbackClass = type
            registered = true
            log("热点信息回调已挂上：系统会回报热点实际的频率和 BSSID")
        } catch (e: ReflectiveOperationException) {
            giveUp(e)
        } catch (e: RuntimeException) {
            giveUp(e)
        } catch (e: LinkageError) {
            giveUp(e)
        }
    }

    /** 摘掉回调（热点收回时）。下次申请热点前再 [register]。 */
    fun unregister() {
        val held = callback ?: return
        val type = callbackClass ?: return
        callback = null
        registered = false
        try {
            WifiManager::class.java.getMethod(UNREGISTER, type).invoke(wifi, held)
        } catch (e: ReflectiveOperationException) {
            // 摘不掉也无妨：进程退出时系统会清掉。
        } catch (e: RuntimeException) {
            // 同上。
        }
    }

    private fun giveUp(e: Throwable) {
        unavailable = true
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        log("挂不上热点信息回调（${cause.javaClass.simpleName}：${cause.message}），热点的频率和 BSSID 只能推测")
    }

    /** 系统的回调：只关心热点信息变化，其余（状态、终端列表等）都不处理。 */
    private inner class Callback : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "onInfoChanged" -> {
                onInfo(args?.firstOrNull())
                null
            }
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "OpenHC-HotspotInfoWatcher"
            else -> null
        }
    }

    /** 新系统一次交来一组热点信息（双频热点有两个），老系统交一个；取第一个带频率的。 */
    private fun onInfo(arg: Any?) {
        val info = if (arg is List<*>) arg.firstOrNull { frequencyOf(it) > 0 } else arg
        if (info == null) return
        val frequency = frequencyOf(info)
        val reported = bssidOf(info)
        if (frequency > 0) frequencyMhz = frequency
        if (reported != null) bssid = reported
        log(
            "系统回报热点信息：频率 ${if (frequency > 0) "$frequency MHz" else "没给"}，" +
                "BSSID ${if (reported != null) MacText.mask(MacText.normalize(reported) ?: reported) else "没给"}",
        )
    }

    private fun frequencyOf(info: Any?): Int = try {
        (info?.javaClass?.getMethod("getFrequency")?.invoke(info) as? Int) ?: 0
    } catch (e: ReflectiveOperationException) {
        0
    } catch (e: RuntimeException) {
        0
    }

    private fun bssidOf(info: Any?): String? = try {
        info?.javaClass?.getMethod("getBssid")?.invoke(info)?.toString()
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val CALLBACK_CLASS = "android.net.wifi.WifiManager\$SoftApCallback"
        const val REGISTER = "registerLocalOnlyHotspotSoftApCallback"
        const val UNREGISTER = "unregisterLocalOnlyHotspotSoftApCallback"
    }
}
