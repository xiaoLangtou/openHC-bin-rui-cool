package io.github.gcbond.openhc.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.UiVisibility
import io.github.gcbond.openhc.platform.bluetooth.BluetoothStatus
import io.github.gcbond.openhc.platform.bluetooth.LePairingPeripheral
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.wifi.MacText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * 设置「补全手机蓝牙配对」的一次会话：从对话框打开到关上。规则见 LePairing.kt，GATT 和广播见 [LePairingPeripheral]。
 *
 * 顺序：检查蓝牙、权限 → 原名称落盘（上次没改回去的沿用记着的原名称） → 改成临时名称、等它生效（最多 [NAME_WAIT_MS]）
 * → 开 GATT 服务和广播 → 听系统的配对状态和蓝牙开关广播 → [close] 时关掉入口、改回名称。改不回去（蓝牙关了等）时
 * 记录留着，蓝牙重新打开或下次启动时再改（[LePairingNames]）。
 *
 * 本应用的界面离开前台超过 [AWAY_LIMIT_MS] 就自动关掉（[LePairingStage.EXPIRED]），免得车主忘了关、名称一直是临时的。
 * 不在离开的那一刻就关：有的车机把系统的配对确认做成全屏页面，弹出来时本应用也算离开了前台。
 *
 * 全部方法在主线程上调用；平台回调先投回主线程。日志标签 `OpenHC-LePair`，地址打码。
 */
internal class LePairingSession(context: Context) {

    private val app: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow(LePairingStatus())

    /** 对话框看的状态。 */
    val status: StateFlow<LePairingStatus> = mutableStatus.asStateFlow()

    private var adapter: BluetoothAdapter? = null
    private var peripheral: LePairingPeripheral? = null
    private var receiverRegistered = false
    private var entranceOpen = false

    /** 结束时要改回去的原名称；没改过名为 null。 */
    private var original: String? = null

    /** 本会话自己调过改名（不是沿用上次留下的临时名称）。 */
    private var renamed = false
    private var started = false
    private var closed = false
    private var nameWait: Runnable? = null
    private val expire = Runnable { onAwayTooLong() }
    private val visibility: UiVisibility? = (app as? OpenHcApp)?.uiVisibility
    private val visibilityListener = UiVisibility.Listener { visible -> onUiVisibilityChanged(visible) }

    /** 开入口。只做一次；结果看 [status]。 */
    fun start() {
        if (started || closed) return
        started = true
        // 上次没改回去的不在这里先改回：改名是异步的，刚发出改回、马上又读名称会读到旧值。直接按记录处理
        // （名称还是临时名称就沿用记着的原名称，见 LePairingRules.planName），结束时一并改回。
        LePairingNames.active?.close("又开了一次")
        LePairingNames.active = this
        visibility?.addListener(visibilityListener)
        if (visibility != null && !visibility.visible) onUiVisibilityChanged(false)

        val adapter = BluetoothStatus.adapter(app) ?: return fail(LePairingFailure.NO_BLUETOOTH)
        if (BluetoothStatus.state(adapter) != BluetoothStatus.State.ON) return fail(LePairingFailure.BLUETOOTH_OFF)
        val missing = LePairingRules.requiredPermissions(Build.VERSION.SDK_INT).filterNot { granted(it) }
        if (missing.isNotEmpty()) return fail(LePairingFailure.PERMISSIONS, missing.joinToString { it.substringAfterLast('.') })
        this.adapter = adapter
        registerReceiver()

        val current = LePairingNames.readName(adapter)
        when (val plan = LePairingRules.planName(current, LePairingNames.saved(app))) {
            LePairingRules.NamePlan.Unknown -> return fail(LePairingFailure.NAME_UNKNOWN)
            is LePairingRules.NamePlan.Reuse -> {
                Log.i(TAG, "车机蓝牙名称还是上次的临时名称，沿用记着的原名称")
                original = plan.original
                openEntrance()
            }
            is LePairingRules.NamePlan.Rename -> {
                if (!LePairingNames.save(app, plan.original)) return fail(LePairingFailure.NAME_NOT_SAVED)
                original = plan.original
                if (!LePairingNames.setName(adapter, LePairingRules.PAIRING_NAME)) {
                    LePairingNames.forget(app)
                    original = null
                    return fail(LePairingFailure.RENAME_REFUSED)
                }
                renamed = true
                Log.i(TAG, "车机蓝牙名称临时改成 ${LePairingRules.PAIRING_NAME}，等它生效")
                waitForName(adapter, SystemClock.uptimeMillis() + NAME_WAIT_MS)
            }
        }
    }

    /** 关掉入口、改回名称。可重复调用。 */
    fun close(why: String) {
        if (closed) return
        closed = true
        Log.i(TAG, "补配对结束（$why），状态 ${status.value.stage}")
        shutdown()
        visibility?.removeListener(visibilityListener)
        main.removeCallbacks(expire)
        if (LePairingNames.active === this) LePairingNames.active = null
    }

    // ---------------------------------------------------------------- 开入口

    /** 改名是异步的：名称真的变了再开广播，广播里带的才是临时名称。超时也照开（日志里记一笔）。 */
    private fun waitForName(adapter: BluetoothAdapter, deadline: Long) {
        val check = object : Runnable {
            override fun run() {
                nameWait = null
                if (closed || status.value.finished) return
                val now = LePairingNames.readName(adapter)
                when {
                    now == LePairingRules.PAIRING_NAME -> openEntrance()
                    SystemClock.uptimeMillis() >= deadline -> {
                        Log.w(TAG, "改名 ${NAME_WAIT_MS}ms 后还没生效，照常开入口（手机列表里可能显示原来的名称）")
                        openEntrance()
                    }
                    else -> {
                        nameWait = this
                        main.postDelayed(this, NAME_POLL_MS)
                    }
                }
            }
        }
        nameWait = check
        main.post(check)
    }

    private fun openEntrance() {
        val adapter = adapter ?: return
        if (closed || entranceOpen) return
        entranceOpen = true
        val p = LePairingPeripheral(app, adapter, peripheralEvents) { Log.i(TAG, it) }
        peripheral = p
        p.open()
    }

    private val peripheralEvents = object : LePairingPeripheral.Events {
        override fun onAdvertising() = post { signal(LePairingSignal.Advertising) }

        override fun onFailed(failure: LePairingPeripheral.Failure) = post {
            val reason = when (failure.reason) {
                LePairingPeripheral.Reason.UNSUPPORTED ->
                    if (failure.stage == LePairingPeripheral.Stage.ADVERTISING) LePairingFailure.ADVERTISING_UNSUPPORTED else LePairingFailure.GATT
                LePairingPeripheral.Reason.TOO_MANY_ADVERTISERS -> LePairingFailure.ADVERTISING_BUSY
                LePairingPeripheral.Reason.PERMISSION -> LePairingFailure.PERMISSIONS
                LePairingPeripheral.Reason.DATA_TOO_LARGE, LePairingPeripheral.Reason.OTHER ->
                    if (failure.stage == LePairingPeripheral.Stage.GATT) LePairingFailure.GATT else LePairingFailure.ADVERTISING
            }
            signal(LePairingSignal.Failed(reason, failure.detail))
        }

        override fun onClientConnection(address: String, connected: Boolean) = post {
            Log.i(TAG, "${MacText.mask(address)} 经 LE ${if (connected) "连上了" else "断开了"}")
        }

        override fun onEncryptedRead(address: String) = post {
            Log.i(TAG, "${MacText.mask(address)} 在加密链路上读了特征：LE 加密配对已完成")
            signal(LePairingSignal.EncryptedRead)
        }
    }

    // ---------------------------------------------------------------- 系统广播

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondChanged(intent)
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        signal(LePairingSignal.Failed(LePairingFailure.BLUETOOTH_OFF))
                    }
                }
            }
        }
    }

    private fun onBondChanged(intent: Intent) {
        val current = BondState.of(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) ?: return
        val previous = BondState.of(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR))
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        Log.i(TAG, "配对状态：${device?.let { describe(it) } ?: "未知设备"} $previous → $current")
        signal(LePairingSignal.Bond(previous, current))
    }

    /** 打码的地址和设备类型（经典 / LE / 双模），只记日志用。 */
    @SuppressLint("MissingPermission")
    private fun describe(device: BluetoothDevice): String {
        val address = try {
            MacText.mask(device.address)
        } catch (e: RuntimeException) {
            "?"
        }
        val type = try {
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典"
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
                else -> "未知类型"
            }
        } catch (e: RuntimeException) {
            "读不到类型"
        }
        return "$address（$type）"
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            // 只有系统发这两个广播，导出也无妨。
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "注册不了配对状态广播：${e.message}（配对成没成看手机来读特征）")
        }
    }

    // ---------------------------------------------------------------- 状态与收尾

    private fun signal(signal: LePairingSignal) {
        if (closed) return
        val before = mutableStatus.value
        val after = LePairingReducer.reduce(before, signal)
        if (after == before) return
        mutableStatus.value = after
        Log.i(TAG, "补配对 ${before.stage} → ${after.stage}${after.failure?.let { "（$it${after.detail?.let { d -> "：$d" } ?: ""}）" } ?: ""}")
        // 出错或自动关掉之后入口没用了：马上关、改回名称；配对完成的等车主点「完成」再收。
        if (after.stage == LePairingStage.FAILED || after.stage == LePairingStage.EXPIRED) shutdown()
    }

    private fun fail(failure: LePairingFailure, detail: String? = null) {
        signal(LePairingSignal.Failed(failure, detail))
    }

    /** 关掉入口、注销广播接收、改回名称。可重复调用。 */
    private fun shutdown() {
        nameWait?.let { main.removeCallbacks(it) }
        nameWait = null
        peripheral?.close()
        peripheral = null
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // 已经注销过。
            }
        }
        val name = original ?: return
        original = null
        if (LePairingNames.active === this) LePairingNames.active = null
        // 本会话自己改的名：不看现在的名称，直接改回去。改名是异步的，刚改就取消时读到的还是原名称，
        // 按现在的名称判断会以为不用改，随后临时名称才生效，就留在车机上了；再发一次改回，系统按先后顺序处理。
        val adapter = adapter
        if (renamed && adapter != null && LePairingNames.setName(adapter, name)) {
            renamed = false
            LePairingNames.forget(app)
            Log.i(TAG, "补配对结束：车机蓝牙名称已改回原来的")
            return
        }
        // 沿用上次留下的、或这次没改回去（蓝牙关了等）：按记录和规则改，改不了就等蓝牙打开或下次启动。
        // 记录万一被删了就补上，免得改不回去。
        if (LePairingNames.saved(app) == null) LePairingNames.save(app, name)
        LePairingNames.restoreIfNeeded(app, "补配对结束")
    }

    private fun onUiVisibilityChanged(visible: Boolean) {
        if (closed) return
        main.removeCallbacks(expire)
        if (!visible) main.postDelayed(expire, AWAY_LIMIT_MS)
    }

    private fun onAwayTooLong() {
        if (closed || status.value.finished) return
        Log.i(TAG, "离开本应用超过 ${AWAY_LIMIT_MS / 1000} 秒，关掉配对入口、改回名称")
        signal(LePairingSignal.Expired)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun post(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    companion object {
        private const val TAG = "OpenHC-LePair"

        /** 改名后最多等多久看它生效。 */
        const val NAME_WAIT_MS = 2_000L
        private const val NAME_POLL_MS = 100L

        /** 界面离开前台超过这么久，自动关掉入口。 */
        const val AWAY_LIMIT_MS = 60_000L
    }
}

/**
 * 车机蓝牙原名称的记录与恢复（单独一个 SharedPreferences 文件）。改名前同步落盘；改回去以后删掉。
 * 进程中途没了，下次启动（[OpenHcApp]）、或本进程里蓝牙重新打开时，按 [LePairingRules.restorePlan] 改回去。
 */
internal object LePairingNames {
    private const val TAG = "OpenHC-LePair"
    private const val FILE = "openhc-le-pairing"
    private const val KEY_ORIGINAL = "original_name"

    @Volatile
    private var activeRef: WeakReference<LePairingSession>? = null

    /** 正在进行的补配对（主线程上读写）：它在时不去改回名称。 */
    var active: LePairingSession?
        get() = activeRef?.get()
        set(value) {
            activeRef = value?.let { WeakReference(it) }
        }

    private var waitingForBluetooth = false

    fun saved(context: Context): String? = prefs(context).getString(KEY_ORIGINAL, null)?.takeIf { it.isNotEmpty() }

    /** 同步落盘：返回 false 时不能改名。要拿 commit 的结果，KTX 的 edit 不给，所以不用它。 */
    @SuppressLint("UseKtx")
    fun save(context: Context, name: String): Boolean = prefs(context).edit().putString(KEY_ORIGINAL, name).commit()

    fun forget(context: Context) = prefs(context).edit { remove(KEY_ORIGINAL) }

    /** 车机现在的蓝牙名称；读不到（没有权限等）为 null。 */
    @SuppressLint("MissingPermission")
    fun readName(adapter: BluetoothAdapter): String? = try {
        adapter.name
    } catch (e: RuntimeException) {
        null
    }

    /** 改车机蓝牙名称（异步生效）；系统拒绝、没有权限返回 false。 */
    @SuppressLint("MissingPermission")
    fun setName(adapter: BluetoothAdapter, name: String): Boolean = try {
        adapter.setName(name)
    } catch (e: RuntimeException) {
        Log.w(TAG, "改不了车机蓝牙名称：${e.javaClass.simpleName}：${e.message}")
        false
    }

    /**
     * 有记着的原名称就按规则处理：改回去、删记录，或者以后再说（这时登记一个蓝牙打开时再试的广播接收）。
     * 有补配对在进行时不动。任意线程可调用。返回 true 表示没有要改回去的了。
     */
    fun restoreIfNeeded(context: Context, why: String): Boolean {
        if (active != null) return false
        val saved = saved(context) ?: return true
        val adapter = BluetoothStatus.adapter(context)
        val on = BluetoothStatus.state(adapter) == BluetoothStatus.State.ON
        val current = adapter?.let { readName(it) }
        return when (val plan = LePairingRules.restorePlan(saved, current, on)) {
            LePairingRules.RestorePlan.Nothing -> true
            LePairingRules.RestorePlan.Forget -> {
                Log.i(TAG, "$why：车机蓝牙名称已不是临时名称，不用改回，删掉记录")
                forget(context)
                true
            }
            is LePairingRules.RestorePlan.Rename -> if (adapter != null && setName(adapter, plan.name)) {
                Log.i(TAG, "$why：车机蓝牙名称已改回原来的")
                forget(context)
                true
            } else {
                Log.w(TAG, "$why：车机蓝牙名称没能改回去，蓝牙重新打开或下次启动时再试")
                restoreWhenBluetoothOn(context)
                false
            }
            LePairingRules.RestorePlan.Later -> {
                Log.i(TAG, "$why：现在改不回车机蓝牙名称（蓝牙关着或读不到名称），之后再试")
                restoreWhenBluetoothOn(context)
                false
            }
        }
    }

    /** 本进程里蓝牙打开时再改一次（一次性，改成了就注销）。 */
    @Synchronized
    private fun restoreWhenBluetoothOn(context: Context) {
        if (waitingForBluetooth) return
        val app = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state != BluetoothAdapter.STATE_ON) return
                if (restoreIfNeeded(app, "蓝牙打开了") || saved(app) == null) {
                    synchronized(this@LePairingNames) { waitingForBluetooth = false }
                    try {
                        app.unregisterReceiver(this)
                    } catch (e: IllegalArgumentException) {
                        // 已经注销过。
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
            waitingForBluetooth = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "注册不了蓝牙开关的广播：${e.message}")
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
