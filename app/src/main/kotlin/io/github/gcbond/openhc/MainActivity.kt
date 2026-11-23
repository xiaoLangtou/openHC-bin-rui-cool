package io.github.gcbond.openhc

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.ui.HomeActions
import io.github.gcbond.openhc.ui.HomeScreen
import io.github.gcbond.openhc.ui.OpenHcTheme
import io.github.gcbond.openhc.ui.Palette
import io.github.gcbond.openhc.ui.ProjectionScreen
import io.github.gcbond.openhc.ui.SettingsScreen
import io.github.gcbond.openhc.ui.WirelessActions
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
 * 首页左栏是无线连接、右栏是有线连接（[HomeScreen]）；无线开始前的运行时权限由 [WirelessStartFlow] 申请。
 * 两种连接同时只能有一种：无线在进行时不开始有线（包括插线拉起）；只是在等已配对的手机回连、手机还没连上时，
 * 有线优先，无线让出。界面回到前台时看看要不要进入等回连（[WirelessController.onAppForeground]）。
 */
class MainActivity : ComponentActivity() {

    private enum class StartKind { START, USB_ATTACHED, BOOT }

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
        // 记下底部导航栏的实际高度（不管此刻显不显示），「全屏显示」关时按它让出画面、算声明尺寸。
        window.decorView.post {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView) ?: return@post
            val bar = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            if (bar > 0) io.github.gcbond.openhc.connection.DisplayArea.measuredNavigationBarHeight = bar
        }
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
        wireless.onAppForeground()
    }

    override fun onDestroy() {
        wireless.removeHostListener(onControllerChanged)
        controller.removeHostListener(wiredListener)
        super.onDestroy()
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
                Log.i(TAG, "开机自动打开：先等已配对的手机无线回连，插线时由系统拉起本应用再开始有线")
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
        requestStart(StartKind.USB_ATTACHED)
    }

    private fun requestStart(kind: StartKind) {
        if (wireless.blocksWired) {
            // 两种连接同时只能有一种：插线拉起时只记一笔，用户点开始时给出原因。
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
        // 无线在等已配对的手机回连的话先让出（有线没真的开始时，它过一会儿自己回来）。
        wireless.yieldToWired()
        when (kind) {
            StartKind.START, StartKind.BOOT -> controller.start()
            StartKind.USB_ATTACHED -> controller.onUsbAttached()
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
    // 设置可能改了：还没连上手机的等待按新设置重开。
    val closeSettings = {
        wireless.onSettingsClosed()
        screen = Screen.HOME
    }
    // 返回键：设置页、投屏页都回首页；投屏时连接保持，可以再回到投屏画面。
    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.SETTINGS) closeSettings() else screen = Screen.HOME
    }

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
                onOpenSettings = { screen = Screen.SETTINGS },
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
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> {
            val context = LocalContext.current
            val screenSpec = remember { io.github.gcbond.openhc.connection.DisplayArea.screenFor(context, settings.fullScreen) }
            val systemBluetooth = remember { DeviceInfo.systemBluetoothAddress() }
            SettingsScreen(settings, screenSpec, systemBluetooth, onBack = closeSettings)
        }
        Screen.PROJECTION -> if (projectionWireless) {
            ProjectionScreen(wState.projection, wDecoder, wTouchResets, onInput = wireless::sendInput)
        } else {
            ProjectionScreen(state, decoder, touchResets, onInput = controller::sendInput)
        }
    }
}
