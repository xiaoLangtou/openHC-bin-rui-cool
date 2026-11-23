package io.github.gcbond.openhc.connection

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.AppIntents
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.audio.AudioOutput
import io.github.gcbond.openhc.audio.MicInput
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.nearby.NearbySession
import io.github.gcbond.openhc.core.session.ReceiverConfig
import io.github.gcbond.openhc.core.session.ReceiverSession
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.SocketRunner
import io.github.gcbond.openhc.core.session.VideoParameters
import io.github.gcbond.openhc.input.MediaKeys
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.platform.usb.PhoneUsbMode
import io.github.gcbond.openhc.platform.usb.UsbDescriber
import io.github.gcbond.openhc.platform.usb.UsbStage
import io.github.gcbond.openhc.platform.usb.WiredSession
import io.github.gcbond.openhc.platform.usb.WiredEndReason
import io.github.gcbond.openhc.platform.usb.WiredSessionConfig
import io.github.gcbond.openhc.platform.usb.WiredState
import io.github.gcbond.openhc.platform.usb.WiredTimeouts
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.VideoPreferences
import io.github.gcbond.openhc.trust.FileTrustStore
import io.github.gcbond.openhc.trust.TrustStoreCorruptException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 有线投屏的总调度：一次连接从开始到收尾、拔线重插的闸门、界面状态。进程内唯一（应用对象持有），
 * 有连接要求期间由前台服务 [ConnectionService] 保活，切到后台也不断。
 *
 * 一次连接里各零件的接线顺序：
 * 1. 读身份资产、查 VPN 授权、打开信任库、定蓝牙地址和画布、选控制端口，建 [ReceiverConfig]（声明接受触摸）；
 * 2. 起前台服务；用 [SocketRunner] 跑 [ReceiverSession]：控制监听绑通配地址，只接受连到车机 IP 的连接；
 * 3. 收到 ControlListening(端口) 后，用 nearby 按这个端口和配置里同一个蓝牙地址生成 carInfo，开 [WiredSession] 等手机
 *    （监听事先绑在通配地址上，网卡就绪时不用再绑）；
 * 4. 收到 VideoNegotiated 时在协议线程上建 [VideoDecoder] 接到会话的视频回调，之后每帧同步送进解码器；
 * 5. 会话事件、有线状态映射成 [ConnectionSignal]，归并成 [state] 给界面。
 *
 * 收尾（不管哪头先结束，都要带走另一头）：执行器 close（趁链路还在发 TEARDOWN、关全部连接）→ 解码器 close →
 * 稍等让最后几个包经 USB 发出 → 有线会话 stop → 等到它报 Ended 才算结束，之后才能开下一次
 * （同一进程同时只能有一个有线会话）。界面显示最先结束的那一头的原因。
 *
 * 发过 carInfo 的连接结束后不立刻重开：手机约 1.5 秒后才退回普通状态，这时发 carInfo 它不理。
 * 要用户拔线等 5 秒再插，手机离开够久后自动开始下一次（[ReplugGate]）。
 *
 * 线程：除 [sendInput]（主线程，直接投到协议线程）外，调度都在自己的 [HandlerThread] 上串行执行；
 * 公开方法可以在任意线程调用。
 */
class ConnectionController(context: Context) {

    /** 前台服务监听：要不要留在前台、通知内容要不要更新。在主线程上回调。 */
    fun interface HostListener {
        fun onControllerChanged()
    }

    private val app: Context = context.applicationContext ?: context
    private val settings = AppSettings(app)
    private val worker = HandlerThread("OpenHC-Connection").also { it.start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as? UsbManager
    /** 信任库目录：不参与备份的应用私有目录。第一次取会建目录，所以留到调度线程上用到时再取。 */
    private val trustDir by lazy { File(app.noBackupFilesDir, TRUST_DIR) }

    private val mutableState = MutableStateFlow(ConnectionUiState())

    /** 界面看的连接状态。 */
    val state: StateFlow<ConnectionUiState> = mutableState.asStateFlow()

    private val mutableDecoder = MutableStateFlow<VideoDecoder?>(null)

    /** 当前连接的解码器（协商出投屏参数后才有），投屏页把画面接到它上面。 */
    val decoder: StateFlow<VideoDecoder?> = mutableDecoder.asStateFlow()

    private val mutableTouchResets = MutableStateFlow(0)

    /** 每次加一，投屏视图就清一次本地触摸状态（发送端报告状态对不上、或回控刚就绪时）。 */
    val touchResets: StateFlow<Int> = mutableTouchResets.asStateFlow()

    /** 触摸被丢弃的原因只警告一次，之后同样的丢弃不再刷日志。 */
    private val inputDropNoted = AtomicBoolean(false)

    /** 前台服务应该在前台：用户要求连接期间为 true。 */
    @Volatile
    var wantsForeground: Boolean = false
        private set

    /** 前台服务真的进了前台（服务自己报）。开机静默连接据此判断后台等待建立起来没有。 */
    @Volatile
    var serviceInForeground: Boolean = false
        internal set

    private val hostListeners = CopyOnWriteArrayList<HostListener>()

    // ---------------------------------------------------------------- 以下只在调度线程上访问

    /** 用户要求连接（从开始到停止，含等拔线重插）。 */
    private var connectionWanted = false
    private var attempt: Attempt? = null
    private var attemptCounter = 0
    private val gate = ReplugGate(REPLUG_ABSENT_MS)
    private var gatePolling = false

    /** 发触摸用，主线程读。 */
    @Volatile
    private var inputTarget: Attempt? = null

    // ---------------------------------------------------------------- 对外操作（任意线程）

    /** 首页「开始」。 */
    fun start() = post { onStartRequest(StartTrigger.BUTTON) }

    /** 应用被「设备接入」拉起（插线）。手机重新枚举也会走到这里，按 [StartPolicy] 忽略。 */
    fun onUsbAttached() = post { onStartRequest(StartTrigger.USB_ATTACH) }

    /** 等拔线重插时，用户坚持马上再试（例如手机其实已经换过）。 */
    fun retryNow() = post {
        if (attempt != null) return@post
        gate.disarm()
        connectionWanted = true
        beginAttempt()
    }

    /** 首页「停止」：本次连接按顺序收尾，不再自动重连。 */
    fun stop() = post {
        connectionWanted = false
        val current = attempt
        apply(ConnectionSignal.StopRequested)
        if (current == null) {
            releaseForeground()
            return@post
        }
        Log.i(TAG, "用户停止第 ${current.id} 次连接")
        current.causes.onUserStop()
        teardown(current)
    }

    /** 界面那边就开始不了（例如用户没同意 VPN 授权）。 */
    fun reportBlocked(notice: Notice) = post {
        if (attempt == null) block(notice)
    }

    /** 用户确认清除损坏的配对记录：把文件改名留存，然后重新开始。 */
    fun resetTrustStoreAndStart() = post {
        if (attempt != null) return@post
        try {
            FileTrustStore.setAside(trustDir, System.currentTimeMillis())?.let {
                Log.w(TAG, "损坏的配对记录已改名留存为 ${it.name}，之后手机要重新配对")
            }
        } catch (e: IOException) {
            block(Notice.TrustStoreUnavailable(e.message ?: e.javaClass.simpleName))
            return@post
        }
        gate.disarm()
        connectionWanted = true
        beginAttempt()
    }

    /**
     * 投屏视图换算好的触摸（主线程）。回控就绪前不发；发送在协议线程上做，异常就地接住：
     * 任务里抛出的异常会让整个执行器退出。
     */
    fun sendInput(input: HiSightInput) {
        val current = inputTarget ?: run {
            if (inputDropNoted.compareAndSet(false, true)) {
                Log.w(TAG, "收到触摸但还没有可回控的连接，丢弃（协商结果见 VideoNegotiated）")
            }
            return
        }
        if (!current.inputReady) {
            if (inputDropNoted.compareAndSet(false, true)) {
                Log.w(TAG, "收到触摸但这条连接的回控还没就绪（inputReady=false，多半是手机没选触摸事件），丢弃")
            }
            return
        }
        current.runner.submit {
            try {
                current.session.sendInput(input)
            } catch (e: IllegalStateException) {
                // 两边的按下状态对不上（例如回控就绪前的按下没发出去）：清本地状态，下一次按下重新开始。
                Log.d(TAG, "触摸状态对不上，清掉重来：${e.message}")
                mutableTouchResets.update { it + 1 }
                emptyList()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "触摸不合法，丢弃：${e.message}")
                emptyList()
            }
        }
    }

    fun addHostListener(listener: HostListener) {
        hostListeners += listener
    }

    fun removeHostListener(listener: HostListener) {
        hostListeners -= listener
    }

    /** 前台服务起不来（例如安卓 14 起前台服务的前置条件不满足）。 */
    fun onServiceStartFailed(message: String) = post {
        wantsForeground = false
        connectionWanted = false
        val current = attempt
        if (current == null) {
            block(Notice.StartFailed(message))
        } else {
            current.causes.onFailure(message)
            apply(ConnectionSignal.StopRequested)
            teardown(current)
        }
    }

    /** 前台服务不是自己要退出、却被销毁了：没有保活，停掉连接。 */
    fun onServiceDestroyed() = post {
        if (!wantsForeground) return@post
        Log.w(TAG, "前台服务被系统结束，停止连接")
        wantsForeground = false
        connectionWanted = false
        val current = attempt
        apply(ConnectionSignal.StopRequested)
        if (current != null) {
            current.causes.onUserStop()
            teardown(current)
        }
    }

    // ---------------------------------------------------------------- 开始

    private fun onStartRequest(trigger: StartTrigger) {
        val decision = StartPolicy.decide(trigger, connectionWanted || attempt != null, gateOpen())
        Log.i(TAG, "开始请求（$trigger）：$decision")
        when (decision) {
            StartDecision.IGNORE -> Unit
            StartDecision.START -> {
                gate.disarm()
                connectionWanted = true
                beginAttempt()
            }
            StartDecision.WAIT_FOR_REPLUG -> {
                connectionWanted = true
                if (!requestForeground()) {
                    block(Notice.StartFailed(FOREGROUND_FAILED))
                    return
                }
                apply(ConnectionSignal.ReplugWaiting(unplugged = gate.phoneAway))
                ensureGatePolling()
            }
        }
    }

    private fun beginAttempt() {
        if (attempt != null) return
        val identity = when (val loaded = IdentityLoader.load(app)) {
            is IdentityLoader.Result.Loaded -> loaded.assets
            IdentityLoader.Result.Missing -> return block(Notice.IdentityMissing)
            is IdentityLoader.Result.Invalid -> return block(Notice.IdentityInvalid(loaded.reason))
        }
        // 每次开始前都重查：别的 VPN 应用被启用后，本应用的授权会被取代。
        when (val consent = VpnConsent.check(app)) {
            VpnConsent.Status.Granted -> Unit
            is VpnConsent.Status.NeedsUserConsent -> return block(Notice.VpnConsentRequired)
            is VpnConsent.Status.Unavailable -> return block(Notice.VpnUnavailable(consent.reason))
        }
        val trustStore = try {
            FileTrustStore.open(trustDir)
        } catch (e: TrustStoreCorruptException) {
            return block(Notice.TrustStoreCorrupt(e.message ?: ""))
        } catch (e: IOException) {
            return block(Notice.TrustStoreUnavailable(e.message ?: e.javaClass.simpleName))
        }
        val bluetooth = BluetoothAddressPolicy.choose(DeviceInfo.systemBluetoothAddress(), settings.manualBluetoothAddress)
        val fullScreen = settings.fullScreen
        val display = try {
            DisplayPlan.resolve(DisplayArea.screenFor(app, fullScreen), ResolutionChoice.Auto, settings.fps, settings.uiScalePercent)
        } catch (e: IllegalArgumentException) {
            return block(Notice.StartFailed("屏幕参数不对：${e.message}"))
        }
        DisplayArea.activeFullScreen = fullScreen
        val clarity = ClarityPreferences(app).clarity
        val port = try {
            pickControlPort()
        } catch (e: IOException) {
            return block(Notice.StartFailed("找不到能用的端口：${e.message}"))
        }
        val audio = AudioPreferences(app).toSettings()
        val micGranted = MicPermission.granted(app)
        val mic = AudioPreferences(app).micSettings(micGranted)
        val config = try {
            ReceiverConfig(
                identity = identity,
                bluetoothAddress = bluetooth.address,
                localIp = CAR_IP,
                controlPort = port,
                display = display.withClarity(clarity),
                authEnvelopeVersion = identity.authEnvelopeVersion,
                trustStore = trustStore,
                touchInput = true,
                mediaKeyInput = MediaKeyPreferences(app).enabled,
                audio = audio,
                mic = mic,
            )
        } catch (e: IllegalArgumentException) {
            return block(Notice.StartFailed("配置不合法：${e.message}"))
        }
        if (!requestForeground()) return block(Notice.StartFailed(FOREGROUND_FAILED))

        val grace = UsbAutoConnectPolicy.permissionGraceMs(UsbAutoConnect.isEnabled(app), settings.usbAttachAccepted)
        val current = Attempt(++attemptCounter, identity, config, grace)
        attempt = current
        inputTarget = current
        Log.i(
            TAG,
            "第 ${current.id} 次连接：蓝牙地址来源 ${bluetooth.source}，deviceId ${config.deviceId.take(4)}…，" +
                "画布 ${display.width}x${display.height}@${display.fps} dpi ${display.dpi}（界面大小 ${settings.uiScalePercent}%）GOP ${display.gop}" +
                "（全屏${if (DisplayArea.activeFullScreen) "开" else "关，让出底部导航栏 ${DisplayArea.navigationBarHeight(app)} 像素"}），控制端口 $port，" +
                "已配对手机 ${trustStore.peerCount} 台，USB 授权宽限 ${grace}ms",
        )
        Log.i(TAG, "第 ${current.id} 次连接的清晰度：${clarity.key}，声明码率 ${VideoClarity.describe(config.display.bitrate)}")
        Log.i(TAG, "第 ${current.id} 次连接的声音设置：${audio ?: "关（不声明扬声器）"}")
        Log.i(
            TAG,
            "第 ${current.id} 次连接的车机麦克风：" +
                (mic?.toString() ?: if (micGranted) "关（设置里关了，不声明麦克风）" else "不声明（没有录音权限）"),
        )
        apply(ConnectionSignal.AttemptStarted(current.id))
        current.runner.start()
        watchTermination(current)
    }

    /** 开始不了：回到空闲，显示原因。 */
    private fun block(notice: Notice) {
        Log.w(TAG, "不能开始：$notice")
        connectionWanted = false
        apply(ConnectionSignal.Blocked(notice))
        releaseForeground()
    }

    // ---------------------------------------------------------------- 一次连接

    /** 一次连接持有的全部资源。 */
    private inner class Attempt(
        val id: Int,
        val identity: IdentityAssets,
        val config: ReceiverConfig,
        val permissionGraceMs: Long,
    ) {
        val sink = ForwardingSink()

        /** 车机媒体键的去处（[MediaKeys]）：按键在主线程上转进来，投到协议线程上发给手机。 */
        val keys = MediaKeys.Target("有线 #$id") { key ->
            if (!tearingDown) runner.submit { MediaKeys.sendNow("有线 #$id", key, session::sendInput) }
        }

        /**
         * 声音出口：音频回调在协议线程上，收尾时在执行器停下之后关。车机这边的焦点变化（别的应用抢走焦点等）
         * 投到协议线程上发给手机；媒体焦点拿着与否报给 [MediaKeys]（媒体按钮会话跟着它建和关）。
         */
        val audio: AudioOutput = AudioOutput(app, id, mediaFocusChanged = { held -> MediaKeys.mediaFocus(keys, held) }) { change ->
            runner.submit { session.sendAudioFocus(change) }
        }

        /** 车机麦克风入口：麦克风回调在协议线程上，采到的声音经执行器投回协议线程送出；收尾时同样在执行器停下之后关。 */
        val mic: MicInput = MicInput(app, id)
        val session: ReceiverSession = ReceiverSession(config, sink, audio, mic)
        val runner: SocketRunner = SocketRunner(
            session,
            { event -> onProtocolEvent(this, event) },
            SocketRunner.Options(threadName = "OpenHC-Session-$id", log = { Log.i(TAG_SESSION, "[$id] $it") }),
        )

        init {
            mic.attach { bytes, done ->
                runner.submit { now ->
                    done()
                    session.sendMicAudio(bytes, 0, bytes.size, now)
                }
            }
        }
        val causes = EndCauseTracker()
        var wired: WiredSession? = null
        var wiredEnded = false
        var replugRequired = false
        var touchNegotiated = false
        var keyNegotiated = false
        @Volatile
        var tearingDown = false
        var finished = false

        /** 有线会话在等手机切成 USB 网卡（已发 carInfo）。 */
        var waitingForSwitch = false
        var switchWatchRunning = false
        val switchAbsence = AbsenceTimer()

        /** 等切换时手机已经连续拔下够久：拔线重插的要求已经满足，下一次不用再等。 */
        var replugSatisfied = false

        /** 协议线程建、调度线程收尾时关。 */
        @Volatile
        var videoDecoder: VideoDecoder? = null

        /** 解码器选项（上屏节拍开关），开始连接时在调度线程上读一次设置。 */
        val videoOptions: VideoDecoder.Options = VideoPreferences(app).decoderOptions()

        @Volatile
        var inputReady = false
    }

    /** 视频回调的转接：解码器要等协商出参数才建，建好前到的帧丢掉（第一帧总在 PLAY 之后）。 */
    private class ForwardingSink : AccessUnitSink {
        @Volatile
        var target: AccessUnitSink? = null

        override fun onAccessUnit(unit: AccessUnit) {
            target?.onAccessUnit(unit)
        }
    }

    /** 会话事件，在协议线程上。 */
    private fun onProtocolEvent(current: Attempt, event: SessionEvent) {
        if (event is SessionEvent.Diagnostic) Log.d(TAG_SESSION, "[${current.id}] $event") else Log.i(TAG_SESSION, "[${current.id}] $event")
        when (event) {
            // 在协议线程上同步建好解码器：之后的帧也在这个线程上送，保证第一帧到时解码器已经接上。
            is SessionEvent.VideoNegotiated -> prepareDecoder(current, event.video)
            // 结束原因按发生的先后记，不等调度线程排到。
            is SessionEvent.Ended -> current.causes.onSessionEnded(event.reason)
            else -> Unit
        }
        post { onSessionEvent(current, event) }
    }

    private fun prepareDecoder(current: Attempt, video: VideoParameters) {
        val decoder = current.videoDecoder ?: VideoDecoder(DecoderListener(current.id), current.videoOptions).also {
            current.videoDecoder = it
            current.sink.target = it
            mutableDecoder.value = it
        }
        decoder.prepare(video.width, video.height, video.fps)
    }

    private inner class DecoderListener(private val attemptId: Int) : VideoDecoder.Listener {
        override fun onVideoSizeChanged(width: Int, height: Int) {
            post { if (attempt?.id == attemptId) apply(ConnectionSignal.VideoSize(width, height)) }
        }

        override fun onDecoderUnavailable(detail: String) {
            post { if (attempt?.id == attemptId) apply(ConnectionSignal.DecoderProblem(detail)) }
        }
    }

    private fun onSessionEvent(current: Attempt, event: SessionEvent) {
        if (current !== attempt || current.finished) return
        apply(ConnectionSignal.Session(event))
        when (event) {
            is SessionEvent.ControlListening -> startWired(current, event.port)
            is SessionEvent.VideoNegotiated -> {
                current.touchNegotiated = event.video.touchInput
                current.keyNegotiated = event.video.keyInput
                MediaKeys.negotiated(current.keys, current.config.mediaKeyInput, event.video.keyInput)
            }
            // PLAY 只在回控连上后才发：手机接受了 PLAY，回控就绪。
            SessionEvent.PlayAccepted -> {
                current.inputReady = current.touchNegotiated && !current.tearingDown
                if (current.keyNegotiated && !current.tearingDown) MediaKeys.ready(app, current.keys)
            }
            is SessionEvent.DeviceSessionReady -> CarHome.onDeviceSessionReady()
            SessionEvent.ProjectionPaused -> CarHome.onProjectionPaused("有线 #${current.id}")
            is SessionEvent.Ended -> if (current.tearingDown) maybeFinish(current) else teardown(current)
            else -> Unit
        }
    }

    private fun startWired(current: Attempt, port: Int) {
        if (current.tearingDown || current.wired != null) return
        val carInfo = try {
            // 端口取监听实际开在的端口；蓝牙地址必须和会话配置里的是同一个，几处的 deviceId 才同源。
            NearbySession.carInfo(current.identity.host, current.config.bluetoothAddress, CAR_IP, port)
        } catch (e: IllegalArgumentException) {
            current.causes.onFailure("生成 carInfo 失败：${e.message}")
            teardown(current)
            return
        }
        val wiredConfig = WiredSessionConfig(
            carInfo = carInfo,
            carAddress = CAR_ADDRESS,
            timeouts = WiredTimeouts(permissionGraceMs = current.permissionGraceMs),
            vpnSessionName = VPN_SESSION_NAME,
            vpnConfigureIntent = AppIntents.openApp(app),
        )
        val wired = WiredSession(app, wiredConfig, object : WiredSession.Callback {
            override fun onStateChanged(state: WiredState) {
                post { onWiredState(current, state) }
            }
            // 控制监听事先绑在通配地址上，网卡就绪时不用再绑。
        })
        current.wired = wired
        Log.i(TAG, "控制监听已就绪（端口 $port），carInfo ${carInfo.size} 字节已生成，开始等手机")
        wired.start()
    }

    private fun onWiredState(current: Attempt, state: WiredState) {
        if (current !== attempt || current.finished) return
        state.toProgress()?.let { apply(ConnectionSignal.Wired(it)) }
        current.waitingForSwitch = state is WiredState.WaitingForPhoneSwitch
        if (current.waitingForSwitch) watchUnplugWhileSwitching(current)
        if (state is WiredState.Ended) {
            current.wiredEnded = true
            current.replugRequired = state.replugRequired
            current.causes.onWiredEnded(state.reason, state.detail)
            if (current.tearingDown) maybeFinish(current) else teardown(current)
        }
    }

    /**
     * 等手机切成 USB 网卡时，平台层对拔线只记一笔、对重插回来的普通状态手机也不理，要等满切换上限（90 秒）才结束。
     * 界面这时让用户「拔线等 5 秒再插」，所以这里自己看：手机连续不在 [REPLUG_ABSENT_MS] 就结束这次连接，
     * 这段离开算作已经满足拔线重插，马上开始下一次等插线。正常切换只断开一两秒，等用户在手机上点「连接」时手机一直在。
     */
    private fun watchUnplugWhileSwitching(current: Attempt) {
        if (current.switchWatchRunning) return
        current.switchWatchRunning = true
        current.switchAbsence.reset()
        handler.post(object : Runnable {
            override fun run() {
                if (current.finished || current.tearingDown || !current.waitingForSwitch) {
                    current.switchWatchRunning = false
                    return
                }
                val now = SystemClock.elapsedRealtime()
                current.switchAbsence.update(phonePresent(), now)
                if (current.switchAbsence.awayFor(now) >= REPLUG_ABSENT_MS) {
                    current.switchWatchRunning = false
                    Log.i(TAG, "等手机切换时手机已拔下 ${REPLUG_ABSENT_MS / 1000} 秒以上：结束这次连接，重新等插线")
                    current.replugSatisfied = true
                    current.causes.onWiredEnded(WiredEndReason.PHONE_UNPLUGGED, null)
                    apply(ConnectionSignal.StopRequested)
                    teardown(current)
                    return
                }
                handler.postDelayed(this, GATE_POLL_MS)
            }
        })
    }

    /** 执行器意外退出（不会有 Ended 事件）时也要能收尾：另开一个线程等它结束。 */
    private fun watchTermination(current: Attempt) {
        thread(name = "OpenHC-SessionWatch-${current.id}", isDaemon = true) {
            try {
                while (!current.runner.awaitTermination(WATCH_SLICE_MS)) {
                    // 一直等到执行器结束。
                }
            } catch (e: InterruptedException) {
                return@thread
            }
            post { onRunnerTerminated(current) }
        }
    }

    private fun onRunnerTerminated(current: Attempt) {
        if (current.finished) return
        current.runner.failure?.let { failure ->
            Log.e(TAG, "第 ${current.id} 次连接的执行器意外结束", failure)
            current.causes.onFailure("执行器意外结束：${failure.javaClass.simpleName}：${failure.message}")
        }
        if (current.tearingDown) {
            maybeFinish(current)
        } else {
            apply(ConnectionSignal.StopRequested)
            teardown(current)
        }
    }

    /** 按顺序收尾，见类注释。可重复调用。 */
    private fun teardown(current: Attempt) {
        if (current.tearingDown) return
        current.tearingDown = true
        current.inputReady = false
        MediaKeys.gone(current.keys)
        // 1. 协议层：链路还在时 TEARDOWN 才发得出去；会话已经结束时立即返回，否则最多阻塞几秒。
        current.runner.close()
        // 声音：执行器已经停了，不会再有音频回调。
        current.audio.close()
        current.mic.close()
        // 2. 解码器：执行器已经停了，不会再有帧送进来。
        current.sink.target = null
        current.videoDecoder?.close()
        current.videoDecoder = null
        mutableDecoder.value = null
        // 3. 有线链路：稍等一下，让 TEARDOWN 和关连接的包经 TUN、USB 发到手机，再停。
        val wired = current.wired
        if (wired != null && !current.wiredEnded) {
            handler.postDelayed({ if (!current.wiredEnded) wired.stop() }, LINK_DRAIN_MS)
            handler.postDelayed({
                if (!current.finished && !current.wiredEnded) {
                    Log.w(TAG, "有线会话 ${WIRED_STOP_TIMEOUT_MS / 1000} 秒内没有结束，不再等（下次开始可能报「已有有线会话」）")
                    current.wiredEnded = true
                    maybeFinish(current)
                }
            }, WIRED_STOP_TIMEOUT_MS)
        }
        maybeFinish(current)
    }

    private fun maybeFinish(current: Attempt) {
        if (current.finished || !current.tearingDown || !current.runner.isTerminated) return
        if (current.wired != null && !current.wiredEnded) return
        current.finished = true
        if (attempt === current) {
            attempt = null
            inputTarget = null
        }
        val cause = current.causes.result()
        val keepConnecting = connectionWanted && current.replugRequired && cause !is EndCause.UserStop
        // 等切换时已经拔下够久：不用再等拔线重插。
        val replugPending = current.replugRequired && !current.replugSatisfied
        Log.i(
            TAG,
            "第 ${current.id} 次连接已收尾：$cause；" + when {
                !current.replugRequired -> "没有发过 carInfo"
                replugPending -> "这次插入已经用过，要拔线等 5 秒再插"
                else -> "手机已经拔下够久，可以直接再插"
            },
        )
        if (replugPending) gate.arm(SystemClock.elapsedRealtime(), phonePresent())
        if (!keepConnecting) connectionWanted = false
        apply(ConnectionSignal.AttemptFinished(cause, replugPending, keepConnecting))
        if (!keepConnecting) {
            releaseForeground()
        } else if (!replugPending) {
            beginAttempt()
        }
        ensureGatePolling()
    }

    // ---------------------------------------------------------------- 拔线重插

    private fun gateOpen(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (gate.isArmed) gate.onPresence(phonePresent(), now)
        return gate.isOpen(now)
    }

    /** 闸门关着时每半秒查一次手机在不在：用户没在连接时也查，好判断下一次插线是不是拔够了时间。 */
    private fun ensureGatePolling() {
        if (gatePolling || !gate.isArmed) return
        gatePolling = true
        handler.post(gatePoll)
    }

    private val gatePoll = object : Runnable {
        override fun run() {
            gatePolling = false
            if (!gate.isArmed) return
            val now = SystemClock.elapsedRealtime()
            val present = phonePresent()
            gate.onPresence(present, now)
            if (gate.isOpen(now)) {
                gate.disarm()
                Log.i(TAG, "手机已拔下 ${REPLUG_ABSENT_MS / 1000} 秒以上，可以重新连接")
                if (connectionWanted && attempt == null) beginAttempt()
                return
            }
            if (connectionWanted && attempt == null) apply(ConnectionSignal.ReplugWaiting(unplugged = !present))
            gatePolling = true
            handler.postDelayed(this, GATE_POLL_MS)
        }
    }

    /** 车机上插着处于可用状态（普通、USB 调试、USB 网卡）的华为 / 荣耀手机。 */
    private fun phonePresent(): Boolean {
        val usb = usbManager ?: return false
        return try {
            usb.deviceList.values.any { UsbDescriber.phoneMode(it) in PRESENT_MODES }
        } catch (e: RuntimeException) {
            false
        }
    }

    // ---------------------------------------------------------------- 前台服务

    private fun requestForeground(): Boolean {
        if (wantsForeground) return true
        // 先置位：服务起来时据此决定留在前台还是退出。
        wantsForeground = true
        return try {
            ContextCompat.startForegroundService(app, Intent(app, ConnectionService::class.java))
            true
        } catch (e: RuntimeException) {
            // 例如安卓 12 起应用在后台时不能起前台服务。
            Log.e(TAG, "起不了前台服务", e)
            wantsForeground = false
            false
        }
    }

    private fun releaseForeground() {
        if (!wantsForeground) return
        wantsForeground = false
        notifyHost()
    }

    private fun notifyHost() {
        mainHandler.post { for (listener in hostListeners) listener.onControllerChanged() }
    }

    // ---------------------------------------------------------------- 工具

    private fun apply(signal: ConnectionSignal) {
        val before = mutableState.value
        val after = ConnectionReducer.reduce(before, signal)
        if (after == before) return
        mutableState.value = after
        // 回控刚就绪：清一次本地触摸状态，就绪前按下的手指不会接着发 MOVE。
        if (after.inputReady && !before.inputReady) mutableTouchResets.update { it + 1 }
        if (after.phase != before.phase || after.step != before.step) {
            Log.i(TAG, "状态 ${before.phase}/${before.step} → ${after.phase}/${after.step}")
            notifyHost()
        }
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    private fun WiredState.toProgress(): WiredProgress? = when (this) {
        WiredState.Idle -> null
        WiredState.WaitingForPhone -> WiredProgress.WaitingForPhone
        WiredState.UsbDebuggingOn -> WiredProgress.UsbDebuggingOn
        is WiredState.WaitingForUsbPermission -> WiredProgress.WaitingForUsbPermission(forNetwork = stage == UsbStage.USB_NETWORK)
        WiredState.SendingCarInfo -> WiredProgress.SendingCarInfo
        is WiredState.WaitingForPhoneSwitch -> WiredProgress.WaitingForPhoneSwitch(slow)
        WiredState.OpeningNetwork -> WiredProgress.OpeningNetwork
        is WiredState.Connected -> WiredProgress.NetworkReady
        is WiredState.Ended -> WiredProgress.Ended(reason, replugRequired, detail)
    }

    /** 选一个空闲端口作控制监听端口（配置要求具体端口）。之后执行器在同一端口上开监听，中间被占的概率可以忽略。 */
    @Throws(IOException::class)
    private fun pickControlPort(): Int = ServerSocket().use { probe ->
        probe.reuseAddress = true
        probe.bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(4)), 0))
        probe.localPort
    }

    companion object {
        private const val TAG = "OpenHC-Connection"
        private const val TAG_SESSION = "OpenHC-Session"

        /** 车机在 USB 网卡上的地址：carInfo 里这个字段定长 14 个字符，取 192.168.66.x 里正好 14 个字符的一个。 */
        const val CAR_IP = "192.168.66.150"
        private val CAR_ADDRESS =
            InetAddress.getByAddress(CAR_IP.split('.').map { it.toInt().toByte() }.toByteArray()) as Inet4Address

        private const val VPN_SESSION_NAME = "OpenHC"
        private const val TRUST_DIR = "trust"

        /** 执行器收尾后再等这么久才停有线链路，让 TEARDOWN 等最后几个包经 USB 发出去。 */
        private const val LINK_DRAIN_MS = 200L

        /** 停有线会话后等它报结束的上限。 */
        private const val WIRED_STOP_TIMEOUT_MS = 15_000L

        /** 手机要连续拔下这么久，才自动开始下一次连接。 */
        private const val REPLUG_ABSENT_MS = 5_000L
        private const val GATE_POLL_MS = 500L
        private const val WATCH_SLICE_MS = 60_000L

        private const val FOREGROUND_FAILED = "前台服务启动失败"

        private val PRESENT_MODES = setOf(PhoneUsbMode.NORMAL, PhoneUsbMode.USB_DEBUGGING, PhoneUsbMode.NCM)
    }
}
