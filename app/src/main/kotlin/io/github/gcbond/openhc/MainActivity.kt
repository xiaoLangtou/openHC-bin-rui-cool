package io.github.gcbond.openhc

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.connection.CarHome
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.platform.usb.PhoneUsbMode
import io.github.gcbond.openhc.platform.usb.UsbDescriber
import io.github.gcbond.openhc.projection.SystemBars
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.PendingSetting
import io.github.gcbond.openhc.settings.ReconnectReminder
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.ui.HomeActions
import io.github.gcbond.openhc.ui.HomeScreen
import io.github.gcbond.openhc.ui.HiCarSplashOverlay
import io.github.gcbond.openhc.ui.LaunchSplashPolicy
import io.github.gcbond.openhc.ui.OpenHcTheme
import io.github.gcbond.openhc.ui.Palette
import io.github.gcbond.openhc.ui.ProjectionScreen
import io.github.gcbond.openhc.ui.ReconnectReminderDialog
import io.github.gcbond.openhc.ui.SettingsScreen
import io.github.gcbond.openhc.ui.WirelessActions
import io.github.gcbond.openhc.ui.pendingSettingsSnapshot
import io.github.gcbond.openhc.wireless.WirelessController
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStartFlow

/**
 * 唯一的界面：首页、设置、全屏投屏页。连接本身在 [ConnectionController] 和前台服务里，界面关掉不影响。
 *
 * 插线拉起：设置「插线时不再弹窗」打开后（见 UsbAutoConnect），清单里给华为、荣耀手机声明的 USB 设备过滤器才生效，
 * 插线时系统会问用不用本应用打开，勾了「默认」以后就直接拉起来（经 [onNewIntent] 或 [onCreate]）。
 * 会话中途手机重新枚举也会再拉一次，由调度按状态忽略。
 *
 * 开机自动打开：设置打开后，开机广播接收器打开本界面（见 [BootStart]），这里当作点了「开始」，进入等插线；
 * 要做无线自动回连、插线又能自己把有线带起来时不进入，让无线先等（见 BootStartPolicy.waitForCable）。
 * 「开机静默连接」开着时开机不打开本界面，等待在后台开始；手机连上时用 SHOW_PROJECTION 把本界面带到前台，
 * 这时不开始任何连接，投屏页、连接码由下面按连接状态自己切过去。
 *
 * 开始之前要的授权按顺序拿：通知权限（安卓 13 起，只问一次，拒绝了也照常连接）→ 录音权限（开着车机麦克风时，
 * 只自动问一次，拒绝了照常连接、不声明麦克风）→ VPN 授权（有线连接必需）。
 *
 * 首页只有一张「连接手机」卡片（[HomeScreen]）：点「开始连接」走无线扫码，插线走有线；无线开始前的运行时权限由
 * [WirelessStartFlow] 申请。两种连接同时只能有一种。插线优先：真插上了华为 / 荣耀手机（界面开着时的设备接入、或回到前台时
 * 手机插着，都要闸门开着——手机切换模式的重新枚举不算）就开始有线，无线还在等手机（扫码、等回连、车机在拨）时让出；
 * 手机已经连上无线时不管插线（多半是在充电）。用户在手机插着时点了「停止」，这次插入不再自动开始。
 * 界面回到前台时还看看要不要自动连接无线（[WirelessController.onAppForeground]）。
 */
class MainActivity : ComponentActivity() {

    /**
     * 开始有线的来由。START：点了按钮；BOOT：开机等插线；USB_ATTACHED：系统按「设备接入」拉起（闸门关着的重新枚举也会来）；
     * PLUGGED：确认真插上了手机（界面开着时的设备接入、或回到前台时手机插着），插线优先。
     */
    private enum class StartKind { START, USB_ATTACHED, BOOT, PLUGGED }

    private lateinit var settings: AppSettings
    private var pendingStart: StartKind? = null

    /** 上次控制器回调时有没有投屏在进行（无线或有线任一）。 */
    private var wasProjecting = false

    /** 投屏状态监听：控制器状态每次变化都会在主线程回调（见两个控制器的 [HostListener]）。 */
    private val onControllerChanged = WirelessController.HostListener { backToLauncherWhenEnded() }
    private val wiredListener = ConnectionController.HostListener { backToLauncherWhenEnded() }

    private val controller: ConnectionController get() = (application as OpenHcApp).connection
    private val wireless: WirelessController get() = (application as OpenHcApp).wireless

    /** 无线连接开始前申请权限；结果回调要在 onCreate 之前注册，所以在构造时创建。 */
    private val wirelessStart = WirelessStartFlow(this) { wireless }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askNextPermission() }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.i(TAG, "用户没有给录音权限：这次不声明车机麦克风")
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
            }
            askNextPermission()
        }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val kind = pendingStart
        pendingStart = null
        if (kind == null) return@registerForActivityResult
        if (result.resultCode == RESULT_OK && VpnConsent.isGranted(this)) {
            dispatch(kind)
        } else {
            Log.i(TAG, "用户没有同意 VPN 授权")
            controller.reportBlocked(Notice.VpnConsentRequired)
        }
    }

    override fun onResume() {
        super.onResume()
        CarHome.attach(this)
        // 记下底部导航栏的实际高度（不管此刻显不显示），「全屏显示」关时按它让出画面、算声明尺寸。
        window.decorView.post {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView) ?: return@post
            val bar = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            if (bar > 0) io.github.gcbond.openhc.connection.DisplayArea.measuredNavigationBarHeight = bar
        }
    }

    override fun onPause() {
        CarHome.detach(this)
        super.onPause()
    }

    /** 上次发出的日夜模式（null = 还没发过）：onConfigurationChanged 旋转/尺寸变更也会触发，只在天/夜真变时补发 501。 */
    private var lastNightMode: Boolean? = null

    /**
     * 车机 uiMode 昼夜切换（大灯/暗色模式）：给当前活跃的有线/无线会话补发 501 `{"dayNightMode":…}`，
     * 手机高德/百度地图和 HiCar 桌面随车机换日夜间皮肤（原厂 HiCarService2 onConfigurationChanged 同款，需求文档第 2 项）。
     * 天/夜没变（旋转、尺寸变更）时不发；night 直接取自 newConfig（app.resources 可能滞后）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (lastNightMode == night) return
        lastNightMode = night
        Log.i(TAG, "uiMode 切换：${if (night) "夜间" else "日间"}，补发日夜模式给手机")
        controller.onUiModeChanged(night)
        wireless.onUiModeChanged(night)
    }

    /**
     * 界面没处理的方向盘、媒体键交给 [io.github.gcbond.openhc.input.MediaKeys]：有能发键的连接时转给手机并吃掉，
     * 系统就不再把它派给别的媒体应用；没有连接时照常交给系统。
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        io.github.gcbond.openhc.input.MediaKeys.onWindowKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        io.github.gcbond.openhc.input.MediaKeys.onWindowKey(event) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        Palette.dark = settings.darkTheme
        // 投屏结束回车机：参照官方 CarLink 的 backToHome——不拉起某个具体桌面，只把自己退到后台，
        // 露出底下原来的任务（从哪个桌面进来的就回哪个）。监听在 onCreate 注册，随界面生死，投屏时界面在前台。
        wireless.addHostListener(onControllerChanged)
        controller.addHostListener(wiredListener)
        val identity = IdentityLoader.load(this)
        val actions = HomeActions(
            onStart = { requestStart(StartKind.START) },
            onStop = { controller.stop() },
            onRetryNow = { controller.retryNow() },
            onGrantVpn = { requestStart(StartKind.START) },
            onResetTrustStore = { controller.resetTrustStoreAndStart() },
            phonePlugged = { controller.phonePlugged() },
        )
        setContent {
            OpenHcTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.Background) {
                    AppRoot(controller, settings, identity, actions, wireless, wirelessStart)
                }
            }
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, usbAttached, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // 回到前台时手机插着：当作刚插上。还没同意过 VPN 授权时不自动弹授权框（拒绝后回到前台会一直弹），等用户点首页的按钮。
        controller.startIfPlugged { if (VpnConsent.isGranted(this)) requestStart(StartKind.PLUGGED) }
        wireless.onAppForeground()
    }

    override fun onStop() {
        unregisterReceiver(usbAttached)
        super.onStop()
    }

    override fun onDestroy() {
        wireless.removeHostListener(onControllerChanged)
        controller.removeHostListener(wiredListener)
        super.onDestroy()
    }

    /**
     * 界面开着时插上华为 / 荣耀手机：确认是真插线（不是手机切换模式的重新枚举）就开始有线（「插线时不再弹窗」关着时
     * 系统不拉起本应用，靠这里）。
     */
    private val usbAttached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (UsbDescriber.phoneMode(device) == PhoneUsbMode.NOT_A_PHONE) return
            controller.checkPlug { real ->
                if (real) {
                    Log.i(TAG, "界面开着时插上了手机（${UsbDescriber.summary(device)}）")
                    requestStart(StartKind.PLUGGED)
                } else {
                    Log.i(TAG, "设备接入（${UsbDescriber.summary(device)}）：在连接中或手机没拔够时间，多半是切换模式，不算插线")
                }
            }
        }
    }

    /**
     * 投屏结束（手机退出 PEER_TEARDOWN 或本机停止）回车机桌面：同官方 CarLink 的 backToHome()，
     * 只把 OpenHC 的任务退到后台，不指定回哪个应用。投屏中界面在前台，从桌面进来的就回到桌面。
     */
    private fun backToLauncherWhenEnded() {
        val projecting = wireless.state.value.phase == WirelessPhase.PROJECTING ||
            controller.state.value.phase == Phase.PROJECTING
        if (wasProjecting && !projecting) {
            Log.i(TAG, "投屏结束：回车机（moveTaskToBack）")
            moveTaskToBack(true)
        }
        wasProjecting = projecting
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        if (BootStart.isBootLaunch(intent)) {
            if (BootStart.waitForCable(this)) {
                Log.i(TAG, "开机自动打开：开始等插线")
                requestStart(StartKind.BOOT)
            } else {
                Log.i(TAG, "开机自动打开：不等插线（插线时由系统拉起本应用再开始有线），无线自动连手机")
            }
        } else if (intent?.action == BootStartPolicy.ACTION_SHOW_PROJECTION) {
            Log.i(TAG, "开机静默连接：手机连上了，界面带到前台")
        } else {
            handleUsbAttach(intent)
        }
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // 任务最初是插线拉起的话，从「最近任务」重开时系统会把那次的 Intent 再给一遍，那不是插线。
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // 被「设备接入」拉起，说明用户在系统框里同意过用本应用打开手机：之后有线会话先等系统自动授权。
        settings.usbAttachAccepted = true
        Log.i(TAG, "插线拉起")
        // 真插线（闸门开着）按插线优先办；重新枚举照旧交给调度忽略。
        controller.checkPlug { real -> requestStart(if (real) StartKind.PLUGGED else StartKind.USB_ATTACHED) }
    }

    private fun requestStart(kind: StartKind) {
        if (kind == StartKind.PLUGGED) {
            // 插线优先，但手机已经连着无线（正在连、投屏中）时不管插线：多半是在充电。
            if (wireless.phoneOnWireless) {
                Log.i(TAG, "手机已经连着无线，插线不开始有线")
                return
            }
        } else if (wireless.blocksWired) {
            // 两种连接同时只能有一种：用户点开始时给出原因。
            Log.i(TAG, "无线连接正在进行，不开始有线连接（$kind）")
            if (kind == StartKind.START) controller.reportBlocked(Notice.StartFailed(getString(R.string.wireless_notice_wireless_active)))
            return
        }
        pendingStart = kind
        askNextPermission()
    }

    /** 开始前的运行时权限按顺序问，每样只自动问一次；问完（同意与否）接着 [continueStart]。 */
    private fun askNextPermission() {
        if (pendingStart == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val audio = AudioPreferences(this)
        if (audio.micEnabled && !audio.micPermissionAsked && !MicPermission.granted(this)) {
            audio.micPermissionAsked = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        continueStart()
    }

    private fun continueStart() {
        val kind = pendingStart ?: return
        when (val consent = VpnConsent.check(this)) {
            VpnConsent.Status.Granted -> {
                pendingStart = null
                dispatch(kind)
            }
            is VpnConsent.Status.NeedsUserConsent -> try {
                vpnConsent.launch(consent.intent)
            } catch (e: ActivityNotFoundException) {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(getString(R.string.vpn_dialog_missing)))
            }
            is VpnConsent.Status.Unavailable -> {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(consent.reason))
            }
        }
    }

    private fun dispatch(kind: StartKind) {
        // 无线还在等手机的话先让出：真插线时扫码等待、车机在拨也让；点开始、开机等插线时只让自动进入的等回连。
        wireless.yieldToWired(plugged = kind == StartKind.PLUGGED)
        when (kind) {
            StartKind.START, StartKind.BOOT -> controller.start()
            StartKind.USB_ATTACHED, StartKind.PLUGGED -> controller.onUsbAttached()
        }
    }

    private companion object {
        const val TAG = "OpenHC-App"
    }
}

private enum class Screen { HOME, SETTINGS, PROJECTION }

@Composable
private fun AppRoot(
    controller: ConnectionController,
    settings: AppSettings,
    identity: IdentityLoader.Result,
    actions: HomeActions,
    wireless: WirelessController,
    wirelessStart: WirelessStartFlow,
) {
    val state by controller.state.collectAsState()
    val decoder by controller.decoder.collectAsState()
    val touchResets by controller.touchResets.collectAsState()
    val wState by wireless.state.collectAsState()
    val wDecoder by wireless.decoder.collectAsState()
    val wTouchResets by wireless.touchResets.collectAsState()
    val wirelessProjecting = wState.phase == WirelessPhase.PROJECTING
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // 投屏页显示的是哪种连接的画面。
    var projectionWireless by rememberSaveable { mutableStateOf(false) }
    // 已经自动切到投屏页的那次连接：用户按返回回到首页后，同一次连接不再强拉回去。
    var autoShownAttempt by remember { mutableIntStateOf(0) }
    var autoShownWireless by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    // 进设置页时「下次连接才生效」各项的值；回首页时对比，正在进行的连接用不上的改动弹框提醒。
    var settingsOnEntry by remember { mutableStateOf<Map<PendingSetting, String>?>(null) }
    var reconnectReminder by remember { mutableStateOf<List<PendingSetting>>(emptyList()) }
    var reminderWhileProjecting by remember { mutableStateOf(false) }
    // 「HiCar 启动动画」开关（设置里即时改，回首页时同步）；收起动画时的连接次数，同一次连接不再弹。
    var launchAnimationEnabled by remember { mutableStateOf(settings.launchAnimation) }
    var dismissedWirelessAttempt by remember { mutableIntStateOf(0) }
    var dismissedWiredAttempt by remember { mutableIntStateOf(0) }
    val openSettings = {
        settingsOnEntry = pendingSettingsSnapshot(context, settings)
        screen = Screen.SETTINGS
    }

    LaunchedEffect(state.phase, state.attempt, wState.phase, wState.attempt) {
        when {
            wirelessProjecting && autoShownWireless != wState.attempt -> {
                autoShownWireless = wState.attempt
                projectionWireless = true
                screen = Screen.PROJECTION
            }
            state.phase == Phase.PROJECTING && autoShownAttempt != state.attempt -> {
                autoShownAttempt = state.attempt
                projectionWireless = false
                screen = Screen.PROJECTION
            }
            // 投屏结束回首页（首页两栏各自写着结束原因）。
            screen == Screen.PROJECTION && projectionWireless && !wirelessProjecting -> screen = Screen.HOME
            screen == Screen.PROJECTION && !projectionWireless && state.phase != Phase.PROJECTING -> screen = Screen.HOME
            // 要输连接码时回首页，免得用户停在设置页看不到。
            state.phase == Phase.PAIRING && screen == Screen.SETTINGS -> screen = Screen.HOME
        }
    }
    // 设置可能改了：空闲时照回到前台一样看要不要自动连接；正在进行的连接（含还在等手机的）不重开，改动要重新连接才生效，提醒一下。
    val closeSettings = {
        val before = settingsOnEntry
        settingsOnEntry = null
        if (before != null) {
            reconnectReminder = ReconnectReminder.changed(
                before,
                pendingSettingsSnapshot(context, settings),
                wiredActive = ReconnectReminder.wiredUsesOldSettings(state.phase),
                wirelessActive = ReconnectReminder.wirelessUsesOldSettings(wState.phase),
            )
            reminderWhileProjecting = state.phase == Phase.PROJECTING || wirelessProjecting
        }
        launchAnimationEnabled = settings.launchAnimation
        wireless.onSettingsClosed()
        screen = Screen.HOME
    }
    // HiCar 启动动画显隐（纯决策，单测覆盖各分支）与逃生出口：双击/返回键收起后，同一次连接不再弹。
    val showSplash = LaunchSplashPolicy.shouldShow(
        enabled = launchAnimationEnabled,
        inSettings = screen == Screen.SETTINGS,
        inProjection = screen == Screen.PROJECTION,
        projectionWireless = projectionWireless,
        wired = state,
        wireless = wState,
        dismissedWirelessAttempt = dismissedWirelessAttempt,
        dismissedWiredAttempt = dismissedWiredAttempt,
    )
    val dismissSplash = {
        if (screen == Screen.PROJECTION && !projectionWireless) {
            dismissedWiredAttempt = state.attempt
        } else {
            dismissedWirelessAttempt = wState.attempt
        }
    }
    // 返回键：动画显示时先收起动画；否则设置页、投屏页都回首页（投屏时连接保持，可以再回到投屏画面）。
    BackHandler(enabled = showSplash) { dismissSplash() }
    BackHandler(enabled = !showSplash && screen != Screen.HOME) {
        if (screen == Screen.SETTINGS) closeSettings() else screen = Screen.HOME
    }
    // 沉浸衔接：动画在首页/设置页盖着时先藏系统栏（投屏页的 SystemBars 由 ProjectionSurface 自己管），
    // 动画收起后还停在首页（没切进投屏页）时恢复，防止 HOME → PROJECTION 切换瞬间状态栏闪烁跳动。
    // 不能用 DisposableEffect(showSplash, screen) 的 onDispose 判断——onDispose 闭包捕获的是
    // 效果创建那一刻的旧值，「动画刚收起」的恢复分支永远走不到；这里用状态标记跟效果解耦。
    val view = LocalView.current
    val window = remember { (context as? Activity)?.window }
    var hidBarsForSplash by remember { mutableStateOf(false) }
    LaunchedEffect(showSplash, screen) {
        when {
            showSplash && screen != Screen.PROJECTION -> {
                window?.let { SystemBars.hide(it, view, settings.fullScreen) }
                hidBarsForSplash = true
            }
            hidBarsForSplash -> {
                // 切进投屏页时不恢复：栏的显隐交给 ProjectionView 继续保持隐藏，别抢着 show 打架。
                if (screen != Screen.PROJECTION) {
                    window?.let { SystemBars.show(it, view) }
                }
                hidBarsForSplash = false
            }
        }
    }
    // 界面整体离场（退出 AppRoot）的兜底：动画还盖着时把系统栏恢复回来。
    DisposableEffect(Unit) {
        onDispose {
            if (hidBarsForSplash) {
                window?.let { SystemBars.show(it, view) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                wired = state,
                wireless = wState,
                identity = identity,
                actions = actions,
                wirelessActions = WirelessActions(
                    onStart = wirelessStart::start,
                    onStop = wireless::stop,
                    onShowProjection = {
                        if (wirelessProjecting) {
                            projectionWireless = true
                            screen = Screen.PROJECTION
                        }
                    },
                    onOpenSettings = openSettings,
                    onOpenAppSettings = wirelessStart::openAppSettings,
                    onEnableBluetooth = wirelessStart::requestEnableBluetooth,
                    onResetTrustStore = wireless::resetTrustStoreAndStart,
                ),
                onShowProjection = {
                    if (state.phase == Phase.PROJECTING) {
                        projectionWireless = false
                        screen = Screen.PROJECTION
                    }
                },
                onOpenSettings = openSettings,
            )
            Screen.SETTINGS -> {
                val screenSpec = remember { io.github.gcbond.openhc.connection.DisplayArea.screenFor(context, settings.fullScreen) }
                val systemBluetooth = remember { DeviceInfo.systemBluetoothAddress() }
                SettingsScreen(settings, screenSpec, systemBluetooth, onBack = closeSettings)
            }
            Screen.PROJECTION -> if (projectionWireless) {
                ProjectionScreen(wState.projection, wDecoder, wTouchResets, onInput = wireless::sendInput, showWaitingText = !showSplash)
            } else {
                ProjectionScreen(state, decoder, touchResets, onInput = controller::sendInput, showWaitingText = !showSplash)
            }
        }
        // 中层：HiCar 启动动画（淡出结束自动退出组合树，触摸交还底层）。
        HiCarSplashOverlay(visible = showSplash, onDismiss = dismissSplash)
    }
    if (reconnectReminder.isNotEmpty() && screen == Screen.HOME) {
        ReconnectReminderDialog(reconnectReminder, reminderWhileProjecting) { reconnectReminder = emptyList() }
    }
}
