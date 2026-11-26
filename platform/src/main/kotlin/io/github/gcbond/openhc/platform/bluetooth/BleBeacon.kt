package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * 车机的一次 BLE 广播：[start] 之后到 [stop] 之间发同一份数据；要换数据就停掉这个、再开一个新的。
 *
 * 广播参数：传统广播、可连接（因而可扫描）、30 ms 间隔、1 dBm 发射功率、不超时；
 * 广播数据只有挂在 [serviceDataUuid] 下的一段 Service Data，不带设备名和功率字段，扫描响应为空。
 * 可连接广播最前面的 Flags 由系统蓝牙栈自己加。
 *
 * 用的是安卓 8.0 起的广播集接口 `startAdvertisingSet`，**不能**用安卓 5.0 的老接口 `startAdvertising`：
 * 本车机的 GOC 定制蓝牙栈（吉利/亿咖通 IHU536G 实测）把老接口的请求在框架转换层静默吞掉——不回调成功也不回调失败，
 * 车机的蓝牙中间件（gocsdk）根本收不到命令；而同一台车机上，官方 CarLink 应用（uid system）走新接口
 * 广播正常（`onAdvertisingSetStarted status 0`、固件层 `advertise enable success`）。
 * 新接口的参数照抄 CarLink 的用法：反射把间隔降到 48×0.625 ms（系统 setter 的下限是 160），失败就退回下限值。
 *
 * **发送地址由系统蓝牙栈决定**（开了 LE 隐私就是随机地址），应用既改不了也读不到；要在真机上用别的设备扫描核对。
 *
 * 回调在主线程上（系统接口如此），里面不要阻塞。[stop] 可以在任意时刻、任意线程调用，可重复调用：
 * 还在启动中时先记下，等系统回了「已开始」再真正停掉（启动没完成就停，部分系统版本会漏停）。
 *
 * 权限：安卓 12 起要「附近的设备」里的广播权限（BLUETOOTH_ADVERTISE），更早是清单里的普通蓝牙权限；没有时报 onFailed。
 *
 * **本类整体只用安卓 8.0（API 26）起才存在的广播集接口**：构造前必须先过 [support]——它查了 SDK_INT >= 26，
 * 调用链上（beaconPlan → ReconnectBeacon.startAdvertising）没有别的构造点，所以这里对 lint 整类压制 NewApi。
 */
@SuppressLint("NewApi")
class BleBeacon(
    private val adapter: BluetoothAdapter,
    private val serviceDataUuid: UUID,
    payload: ByteArray,
    private val events: Events,
) {
    interface Events {
        /** 系统确认开始广播。 */
        fun onStarted()

        /** 没发出去。[permanent] 为 true 表示这台车机这样发不了（不支持、数据太长），重试没用。 */
        fun onFailed(message: String, permanent: Boolean)
    }

    /** 车机能不能发 BLE 广播。 */
    sealed class Support {
        object Available : Support() {
            override fun toString() = "支持"
        }

        class Unavailable(val reason: String) : Support() {
            override fun toString() = "不支持（$reason）"
        }
    }

    private enum class State { NEW, STARTING, STOP_PENDING, STARTED, STOPPED }

    private val data = payload.copyOf()
    private val lock = Any()
    private var state = State.NEW
    private var advertiser: BluetoothLeAdvertiser? = null

    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            val previous = synchronized(lock) {
                val s = state
                if (s == State.STARTING) state = State.STARTED
                if (s == State.STOP_PENDING) state = State.STOPPED
                s
            }
            when (previous) {
                State.STARTING ->
                    if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) events.onStarted()
                    else events.onFailed(describe(status), permanent(status))
                // 启动中被要求停：现在才真正停掉。
                State.STOP_PENDING -> stopAdvertising()
                else -> Unit
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            synchronized(lock) { if (state != State.STOP_PENDING) state = State.STOPPED }
        }
    }

    /** 开始广播。只能调用一次；结果经 [Events] 回来（开不了的情况可能在本方法里同步回调）。 */
    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，下面按失败上报。
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            check(state == State.NEW) { "start 只能调用一次" }
            state = State.STARTING
        }
        val le = try {
            adapter.bluetoothLeAdvertiser
        } catch (e: RuntimeException) {
            null
        }
        if (le == null) return fail("系统没有提供广播接口（蓝牙没开，或车机不支持 BLE 广播）", permanent = false)
        synchronized(lock) { advertiser = le }
        // 参数照抄官方 CarLink 应用（这台车机上实测广播成功的那份）：传统可连接广播、1 dBm；
        // 间隔先按系统下限建，再用反射压到 48（30 ms）——系统 setter 校验下限 160，反射是 CarLink 的原做法。
        val builder = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .setTxPowerLevel(1)
        try {
            val field = AdvertisingSetParameters.Builder::class.java.getDeclaredField("mInterval")
            field.isAccessible = true
            field.setInt(builder, 48)
        } catch (e: ReflectiveOperationException) {
            // 降回默认间隔（160×0.625 ms = 100 ms）：慢一点但照样能用。
        }
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(serviceDataUuid), data)
            .build()
        val dataSize = data.size
        try {
            val parameters = builder.build()
            android.util.Log.i(
                "BleBeacon",
                "调 startAdvertisingSet：interval=${parameters.interval}, txPower=${parameters.txPowerLevel}, 数据 $dataSize 字节"
            )
            le.startAdvertisingSet(parameters, advertiseData, null, null, null, 0, 0, callback)
            android.util.Log.i("BleBeacon", "startAdvertisingSet 调用返回（没抛异常）")
        } catch (e: SecurityException) {
            fail("没有广播权限（安卓 12 起要「附近的设备」权限）：${describe(e)}", permanent = false)
        } catch (e: RuntimeException) {
            fail("开不了广播：${describe(e)}", permanent = false)
        }
    }

    /** 停止广播。可重复调用；之后不再回调。 */
    fun stop() {
        val started = synchronized(lock) {
            when (state) {
                // 还在启动中：等系统回了结果再停（见类注释）。
                State.STARTING -> {
                    state = State.STOP_PENDING
                    false
                }
                State.STARTED -> {
                    state = State.STOPPED
                    true
                }
                State.NEW -> {
                    state = State.STOPPED
                    false
                }
                State.STOP_PENDING, State.STOPPED -> false
            }
        }
        if (started) stopAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val le = synchronized(lock) { advertiser } ?: return
        try {
            le.stopAdvertisingSet(callback)
        } catch (e: RuntimeException) {
            // 蓝牙已经关了、权限被收回等：广播本来也停了。
        }
    }

    private fun fail(message: String, permanent: Boolean) {
        val report = synchronized(lock) { (state == State.STARTING).also { state = State.STOPPED } }
        if (report) events.onFailed(message, permanent)
    }

    companion object {
        /**
         * 车机能不能发 BLE 广播：没有蓝牙、蓝牙没开算不能。
         * `isMultipleAdvertisementSupported` 在不少平台（尤其 MTK）上不准——明明能广播也报 false，
         * 这里不再拿它当硬门槛：蓝牙开着就认为可以，真发不了时 [start] 的运行时失败会兜底降级。
         */
        fun support(adapter: BluetoothAdapter?): Support {
            // 广播集接口（AdvertisingSetCallback / startAdvertisingSet）安卓 8.0 才有；更早的系统直接判不支持。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return Support.Unavailable("要安卓 8.0（API 26）起的广播集接口，本系统 ${Build.VERSION.SDK_INT}")
            }
            if (adapter == null) return Support.Unavailable("没有蓝牙")
            val on = try {
                adapter.isEnabled
            } catch (e: RuntimeException) {
                false
            }
            if (!on) return Support.Unavailable("蓝牙没开")
            val multiple = try {
                adapter.isMultipleAdvertisementSupported
            } catch (e: RuntimeException) {
                false
            }
            // 系统报不支持时不再直接判死：部分平台此 API 误报，实际广播可用。失败由运行时 onFailed 兜底。
            return Support.Available.also {
                if (!multiple) android.util.Log.i("BleBeacon", "系统报告不支持多广播（MTK 平台常见误报），仍尝试广播")
            }
        }

        /** 这些失败重试也没用。 */
        private fun permanent(status: Int): Boolean =
            status == AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ||
                status == AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE

        private fun describe(status: Int): String = when (status) {
            AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据太长"
            AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "系统的广播实例用完了（别的应用也在广播）"
            AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已经在广播"
            AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "系统蓝牙内部错误"
            AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "车机不支持 BLE 广播"
            else -> "错误码 $status"
        }

        private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
