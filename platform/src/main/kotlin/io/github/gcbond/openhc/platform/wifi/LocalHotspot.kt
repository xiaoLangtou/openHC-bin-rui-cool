package io.github.gcbond.openhc.platform.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** 本地热点开好以后交出的实际信息，原样写进给手机的连接串。toString 不含网络名和密码。 */
class LocalHotspot(
    /** 车机在热点网段上的 IPv4（热点网关），已经能 bind。 */
    val localIp: String,
    val networkName: String,
    /** 小写、冒号分隔。 */
    val bssid: String,
    val passphrase: String,
    /** 报给手机的频率（MHz），多数时候是推测值，见 [frequencySource]。 */
    val frequencyMhz: Int,
    /** 热点网卡名，日志用。 */
    val interfaceName: String,
    val bssidSource: HotspotMacSource,
    val frequencySource: HotspotFrequencySource,
    val security: HotspotSecurity,
) {
    override fun toString() =
        "LocalHotspot($localIp，$frequencyMhz MHz，网卡 $interfaceName，BSSID ${MacText.mask(bssid)}，" +
            "加密 ${security.label}，网络名与密码不显示)"
}

/** 开热点的结果，在热点自己的工作线程上回调，不要阻塞。[LocalHotspotHandle.stop] 之后不再回调。 */
interface LocalHotspotEvents {
    /** 热点开好了，网卡上也有了地址。 */
    fun onReady(hotspot: LocalHotspot)

    /** 没开成（说明给用户和日志看，不含网络名和密码）。 */
    fun onFailed(message: String)

    /** 开好以后热点没了（被系统收回）。 */
    fun onLost(message: String)
}

/** 一次开热点。[start] 开始申请，[stop] 关掉（不等手机离开）；两者都不阻塞，可以在任意线程调用。 */
interface LocalHotspotHandle {
    fun start()

    fun stop()
}

/**
 * 车机开本地热点（无线连接时给手机准备的 WiFi 网络）：系统公开的「只供本地通信」热点（安卓 8.0 起），名称和密码由系统生成、
 * 开好后读回来，手机按普通 WiFi 加入。进程里只有一个（[get]），每次连接用 [create] 要一次，用完 [LocalHotspotHandle.stop]。
 *
 * - 申请的先后、间隔、主人中途走了怎么办，都由 [HotspotRequestMachine] 决定（规则见它的说明）：两次申请之间至少隔 3.5 秒，
 *   热点被关时不等手机离开。
 * - **先开好留着**：无线开着时调 [keepWarm]，不等连接要就申请，连接之间不关，无线停了调 [coolDown]。
 * - 申请热点的系统调用前后各记一行（含用时）；调用卡住时另一条线程定时记下它卡在哪里。
 * - 开好之后：读系统给的网络名、密码、加密方式和频段，再在网卡上找热点地址（开热点前后各拍一次网卡快照，
 *   见 [LocalHotspotRules.pickInterface]），找到才交出 [LocalHotspot]。
 * - **频率**：普通应用读不到本地热点实际开在哪个信道（只有系统接口给得出），交出的多半是推测值
 *   （[LocalHotspotRules.estimateFrequency]，按系统配置里的频段，默认 2.4 GHz 第 6 信道）。
 * - **BSSID**：系统配置里一般没有；热点网卡的硬件地址在安卓 10 及以前读得到，安卓 11 起对高 targetSdk 的应用多半给不出，
 *   这时用占位值。
 * - 单射频的车机开热点时自己连着的 WiFi 会断开（系统行为）。每次申请前记下车机 WiFi 开没开（[stationWasOnBeforeHotspot]），
 *   给「退出后恢复车机 WiFi」用。
 *
 * 系统的要求：安卓 8～12 要精确位置权限、系统定位开关打开；安卓 13 起要「附近的 WiFi 设备」权限；应用要在前台。
 * 定位权限是「使用期间允许」时，只有界面在前台才算数，光有前台服务不算（2026-11-22 安卓 10 车机实测：界面在后台时
 * 系统拒绝「does not have Coarse/Fine Location permission」）。车机已经开着个人热点、或在共享网络时开不了。
 * 权限由调用方在开始前确认。
 */
// 权限由调用方在开始前确认（见类注释）；没有时系统拒绝申请或回失败，都按失败报。
@SuppressLint("MissingPermission")
class LocalHotspotOwner private constructor(context: Context) {
    /** 安卓 7.0 以前用界面 Context 取 WifiManager 会泄漏，统一从应用 Context 取；本对象不另外持有 Context。 */
    private val wifi: WifiManager? = (context.applicationContext ?: context).getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val thread = HandlerThread("OpenHC-Hotspot").also { it.start() }
    private val handler = Handler(thread.looper)

    /** 看申请热点的系统调用有没有卡住：卡住时工作线程动不了，只能在另一条线程上看。 */
    private val watchThread = HandlerThread("OpenHC-HotspotWatch").also { it.start() }
    private val watchHandler = Handler(watchThread.looper)

    /** 安卓 13 起尝试读系统回报的热点实际频率和 BSSID（读不到就推测）；回报在工作线程上交付。 */
    private val infoWatcher: HotspotInfoWatcher? = wifi
        ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
        ?.let { manager -> HotspotInfoWatcher(manager, { handler.post(it) }) { note(it) } }

    // ---------------------------------------------------------------- 以下只在工作线程上访问

    private val machine = HotspotRequestMachine()
    private val runs = HashMap<Int, Run>()

    /** 已经停了、还在等收尾（让会话的最后几个包先发出去）的连接：id → 收尾任务。 */
    private val draining = HashMap<Int, Runnable>()

    /** 系统给的热点（安卓 8.0 起才有这个类型，老系统上一直是 null）。 */
    private var reservation: Any? = null

    /** 最近一次申请前的网卡快照：网卡名 → IPv4。 */
    private var before: Map<String, Set<String>> = emptyMap()

    /** 最近一次失败的具体原因（系统回话或调用被拒）。 */
    private var lastFailure: String? = null

    /** 先开好留着时给的日志：没有连接用着热点时，申请和系统回话写到这里。 */
    private var standbyLog: ((String) -> Unit)? = null
    private val wake = Runnable { drive { machine.onTick(now()) } }

    // ---------------------------------------------------------------- 任意线程可读

    @Volatile
    private var stationNote = false

    @Volatile
    private var busyNow = false

    private val idLock = Any()
    private var nextId = 1

    /** 从上次 [clearStationNote] 起，有过一次申请热点时车机 WiFi 是开着的（之后被热点顶掉的不算关）。 */
    val stationWasOnBeforeHotspot: Boolean get() = stationNote

    fun clearStationNote() {
        stationNote = false
    }

    /** 有连接在用（或在等）热点，或者有申请还没回话。 */
    val busy: Boolean get() = busyNow

    /** 准备开一次热点：[LocalHotspotHandle.start] 才开始申请。[log] 在工作线程上调用。 */
    fun create(events: LocalHotspotEvents, log: (String) -> Unit = {}): LocalHotspotHandle {
        val id = synchronized(idLock) { nextId++ }
        return Run(id, events, log)
    }

    /**
     * 先把热点开好留着（无线开着时调用）：现在就申请，连接结束也不关，下一次连接直接接手，直到 [coolDown]。
     * 系统要求申请时应用在前台；在后台申请会失败，回到前台时再调一次即可（已经开着或在申请时什么都不做）。
     * [log] 在工作线程上调用。
     */
    fun keepWarm(log: (String) -> Unit) {
        handler.post {
            standbyLog = log
            if (!machine.held) log("无线开着：先把热点开好留着，不等手机认证（连接之间不关）")
            drive { machine.hold(now()) }
        }
    }

    /** 不用再留着热点了（无线停了）：没有连接在用就关掉。 */
    fun coolDown() {
        handler.post {
            if (!machine.held) return@post
            note("无线停了：不再留着热点")
            drive { machine.unhold(now()) }
        }
    }

    private enum class Stage { NEW, WAITING_SYSTEM, WAITING_ADDRESS, READY, ENDED }

    private inner class Run(val id: Int, private val events: LocalHotspotEvents, val log: (String) -> Unit) : LocalHotspotHandle {
        @Volatile
        private var stopped = false

        // 以下只在工作线程上访问。
        private var stage = Stage.NEW
        private var info: ReservationInfo? = null
        private var addressDeadline = 0L
        private var infoDeadline = 0L
        private var wlanFallbackAt = 0L
        private val poll = Runnable { pollAddress() }

        override fun start() {
            handler.post {
                if (stopped || stage != Stage.NEW) return@post
                // 上一次连接还在收尾：先把它的热点关掉，不然这次会被当成「另一次连接正在用热点」。
                flushDraining()
                stage = Stage.WAITING_SYSTEM
                runs[id] = this
                drive { machine.start(id, now()) }
            }
        }

        /** 立即不再回调；稍等一下再关热点，让会话收尾时最后几个包（例如投屏的 TEARDOWN）先发出去。 */
        override fun stop() {
            stopped = true
            handler.post {
                if (stage == Stage.ENDED && !runs.containsKey(id)) return@post
                stage = Stage.ENDED
                handler.removeCallbacks(poll)
                runs.remove(id)
                val finish = Runnable {
                    draining.remove(id)
                    drive { machine.stop(id, now()) }
                }
                draining[id] = finish
                handler.postDelayed(finish, STOP_DRAIN_MS)
            }
        }

        /** 系统开好了热点：读配置，再等网卡上出现地址。 */
        fun onSystemStarted(reservation: Any) {
            if (stopped || stage != Stage.WAITING_SYSTEM) return
            val read = readReservation(reservation)
            info = read
            log("系统开好了热点：加密 ${read.security.label}，${read.hint ?: "读不到频段"}")
            when {
                LocalHotspotRules.networkName(read.ssid) == null -> return fail("读不到热点的网络名")
                read.security == HotspotSecurity.NO_PASSPHRASE || read.passphrase.isNullOrEmpty() ->
                    return fail("系统开的热点没有口令（开放热点），这种热点手机按这套连接方式连不上")
                read.security == HotspotSecurity.WPA3_ONLY -> log("热点只开了 WPA3：手机按 WPA2 口令可能连不上，连不上时先查这个")
            }
            stage = Stage.WAITING_ADDRESS
            addressDeadline = now() + ADDRESS_WAIT_MS
            infoDeadline = now() + INFO_WAIT_MS
            wlanFallbackAt = now() + WLAN_FALLBACK_DELAY_MS
            pollAddress()
        }

        private fun pollAddress() {
            if (stopped || stage != Stage.WAITING_ADDRESS) return
            // 车机自己的 WiFi 还开着（能一边连 WiFi 一边开热点）时，「只有一个 wlan 地址就用它」的兜底推迟一会儿再用，
            // 免得热点网卡的地址还没出现就选了车机 WiFi 的地址；开热点时 WiFi 会断开的车机不用等。
            val wlanFallback = !stationEnabled() || now() >= wlanFallbackAt
            val picked = LocalHotspotRules.pickInterface(before, snapshot(), stationIpv4(), wlanFallback)
            // 挂上了热点信息回调、回报还没到：再等一会儿（最多 INFO_WAIT_MS），拿到实际频率和 BSSID 再交出去。
            val waitInfo = infoWatcher?.let { it.registered && it.frequencyMhz == 0 } == true && now() < infoDeadline
            if (picked != null && !waitInfo) return ready(picked.first, picked.second)
            if (picked != null) {
                handler.postDelayed(poll, ADDRESS_POLL_MS)
                return
            }
            if (now() < addressDeadline) {
                handler.postDelayed(poll, ADDRESS_POLL_MS)
                return
            }
            fail("热点开好了，但 ${ADDRESS_WAIT_MS / 1000} 秒内没在网卡上找到它的地址")
        }

        private fun ready(interfaceName: String, ip: String) {
            val read = info ?: return fail("热点信息丢了")
            val name = LocalHotspotRules.networkName(read.ssid) ?: return fail("读不到热点的网络名")
            val passphrase = read.passphrase ?: return fail("读不到热点的口令")
            val hardware = hardwareAddress(interfaceName)
            val linkLocal = InterfaceAddresses.linkLocalMac(interfaceName)
            val (bssid, macSource) = LocalHotspotRules.pickBssid(read.bssid, hardware, infoWatcher?.bssid, linkLocal)
            InterfaceAddresses.crossCheck(MacText.of(hardware), linkLocal)?.let { log("热点网卡：$it") }
            val (frequency, frequencySource) = LocalHotspotRules.estimateFrequency(read.hint, infoWatcher?.frequencyMhz ?: 0)
            val hotspot = LocalHotspot(ip, name, bssid, passphrase, frequency, interfaceName, macSource, frequencySource, read.security)
            stage = Stage.READY
            log(
                "热点就绪：$hotspot；频率：${frequencySource.label}；BSSID：${macSource.label}；" +
                    "车机 WiFi 现在${if (stationEnabled()) "开着" else "关着"}（当前连接 ${stationFrequency()} MHz）",
            )
            if (!stopped) events.onReady(hotspot)
        }

        fun fail(message: String) {
            if (stage == Stage.ENDED) return
            stage = Stage.ENDED
            handler.removeCallbacks(poll)
            log("热点没开成：$message")
            if (!stopped) events.onFailed(message)
        }

        fun lose(message: String) {
            if (stage != Stage.READY) return fail(message)
            stage = Stage.ENDED
            log(message)
            if (!stopped) events.onLost(message)
        }
    }

    // ---------------------------------------------------------------- 状态机的输出

    /**
     * 调状态机，按顺序做它要做的事；结束后按它的下一次时间重排定时。只在工作线程上调用（可以嵌套）。
     *
     * 别改名叫 apply：在内部类 [Run] 里写 `apply { … }` 会被编译成标准库的作用域函数，
     * 状态机要做的事（申请热点、把热点交给连接）被悄悄丢掉，什么日志都没有。
     */
    private fun drive(step: () -> List<HotspotEffect>) {
        for (effect in step()) {
            when (effect) {
                HotspotEffect.Request -> requestNow()
                HotspotEffect.Release -> releaseNow()
                is HotspotEffect.Started -> {
                    val held = reservation
                    if (held == null) {
                        lastFailure = "系统回话里没有热点"
                        drive { machine.onFailed(now()) }
                    } else {
                        runs[effect.owner]?.onSystemStarted(held)
                    }
                }
                is HotspotEffect.Failed -> runs[effect.owner]?.fail(failureText(effect.failure))
                is HotspotEffect.Lost -> runs[effect.owner]?.lose("热点被系统收回了（例如车机 WiFi 被关、开了个人热点）")
                is HotspotEffect.Deferred ->
                    runs[effect.owner]?.log("刚关掉热点 ${MIN_GAP_TEXT}内，${effect.delayMillis} 毫秒后再申请（太快重新申请系统会卡住不回话）")
                is HotspotEffect.WakeAt -> Unit
            }
        }
        busyNow = machine.busy
        handler.removeCallbacks(wake)
        machine.nextWakeAt?.let { at -> handler.postDelayed(wake, (at - now()).coerceAtLeast(0)) }
    }

    private fun failureText(failure: HotspotFailure): String = when (failure) {
        HotspotFailure.SYSTEM -> "${failure.label}：${lastFailure ?: "原因不明"}"
        HotspotFailure.NO_ANSWER -> "申请开热点后系统 ${HotspotRequestMachine.ANSWER_TIMEOUT_MILLIS / 1000} 秒没有回应"
        HotspotFailure.BUSY -> failure.label
    }

    /** 申请热点。申请前拍网卡快照（认热点网卡用）、记下车机 WiFi 开没开（恢复 WiFi 用）。 */
    private fun requestNow() {
        lastFailure = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return rejected("安卓 8.0 以下没有本地热点")
        val manager = wifi ?: return rejected("拿不到系统的 WiFi 服务")
        before = snapshot()
        infoWatcher?.reset()
        infoWatcher?.register()
        val stationOn = stationEnabled()
        if (stationOn) stationNote = true
        note("向系统申请本地热点（车机 WiFi ${if (stationOn) "开着，当前连接 ${stationFrequency()} MHz" else "关着"}）")
        forcedChannel?.let { channel ->
            val failure = forceSystemApBand(manager, channel)
            note(
                if (failure == null) "强制热点频段：5 GHz 信道 $channel 已写进系统热点配置（系统开本地热点时继承；个人热点配置也被改到这一频段）"
                else "强制热点频段没成（$failure），交给系统定"
            )
        }
        // 看门狗在另一条线程上跑：写到哪个日志在这里先取好，调用返回后它不再记、也不再排下一次。
        val log = currentLog()
        val returned = AtomicBoolean(false)
        val calledAt = now()
        val caller = Thread.currentThread()
        val watchdog = object : Runnable {
            override fun run() {
                if (returned.get()) return
                log("申请热点的系统调用 ${now() - calledAt} 毫秒还没返回（线程状态 ${caller.state}），卡在：${stackText(caller)}")
                if (!returned.get()) watchHandler.postDelayed(this, CALL_WATCH_REPEAT_MS)
            }
        }
        watchHandler.postDelayed(watchdog, CALL_WATCH_FIRST_MS)
        try {
            // 系统的回话都在工作线程上（给了它这个 Handler）。
            manager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) = onSystemStarted(reservation)

                    override fun onStopped() = onSystemStopped()

                    override fun onFailed(reason: Int) = onSystemFailed(reason)
                },
                handler,
            )
            note("系统收下了申请（调用用时 ${now() - calledAt} 毫秒），等系统回话")
        } catch (e: SecurityException) {
            rejected("系统不让开热点（${e.message}）：安卓 8～12 要精确位置权限、系统定位开关要打开，安卓 13 起要「附近的 WiFi 设备」权限")
        } catch (e: IllegalStateException) {
            // 系统按进程记申请：上一次申请还没了结时再申请会被拒。
            rejected("系统说本应用上一次开热点的申请还没了结（${e.message}），稍后再试")
        } catch (e: RuntimeException) {
            rejected("开热点出错：${e.javaClass.simpleName}：${e.message}")
        } finally {
            returned.set(true)
            watchHandler.removeCallbacks(watchdog)
        }
    }

    /** 线程现在的调用栈（只取最上面几层），诊断用。 */
    private fun stackText(thread: Thread): String = try {
        thread.stackTrace.take(STACK_FRAMES).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    } catch (e: SecurityException) {
        "读不到调用栈"
    }

    /** 申请和系统回话写到谁的日志里：有连接在用热点写它的，没有写先开好留着时给的。只在工作线程上调用。 */
    private fun note(message: String) = currentLog()(message)

    /** 见 [note]。只在工作线程上调用。 */
    private fun currentLog(): (String) -> Unit = machine.owner?.let { runs[it]?.log } ?: standbyLog ?: {}

    private fun rejected(message: String) {
        lastFailure = message
        drive { machine.onRequestRejected(now()) }
    }

    /**
     * 把系统热点配置的频段改写成 5 GHz、信道 [channel]（反射，特权接口）。写成返回 null，否则给说明。
     * 只改频段和信道，网络名、密码等其余字段原样保留（本地热点反正由系统随机生成，个人热点的名字密码不动）。
     */
    @Suppress("DEPRECATION")
    private fun forceSystemApBand(manager: WifiManager, channel: Int): String? = try {
        val config = WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(manager)
            as? android.net.wifi.WifiConfiguration ?: return "读不到系统热点配置"
        config.javaClass.getDeclaredField("apBand").apply { isAccessible = true }.set(config, 1)
        config.javaClass.getDeclaredField("apChannel").apply { isAccessible = true }.set(config, channel)
        val ok = WifiManager::class.java
            .getMethod("setWifiApConfiguration", android.net.wifi.WifiConfiguration::class.java)
            .invoke(manager, config) as? Boolean ?: false
        if (ok) null else "系统拒绝写入热点配置"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}：${e.message ?: "原因不明"}"
    }

    /** 系统回话：热点开好了（[held] 是系统给的热点）。 */
    private fun onSystemStarted(held: Any?) {
        note(if (machine.owner == null && machine.held) "系统回话：热点开好了，留着等手机" else "系统回话：热点开好了")
        if (held == null) {
            lastFailure = "系统回话里没有热点"
            drive { machine.onFailed(now()) }
            return
        }
        // 系统按进程只给一个热点；万一手里还有旧的，先关掉。
        val old = reservation
        if (old != null && old !== held) closeQuietly(old)
        reservation = held
        drive { machine.onStarted(now()) }
    }

    /** 系统回话：热点被收回了。系统已经收回，手里的这个不用再关。 */
    private fun onSystemStopped() {
        note("系统回话：热点被收回了")
        reservation = null
        drive { machine.onStopped(now()) }
    }

    private fun onSystemFailed(reason: Int) {
        lastFailure = reasonText(reason)
        note("系统回话：没开成（$lastFailure）")
        drive { machine.onFailed(now()) }
    }

    private fun releaseNow() {
        infoWatcher?.unregister()
        val held = reservation ?: return
        reservation = null
        closeQuietly(held)
    }

    private fun closeQuietly(held: Any) {
        // 只有安卓 8.0 起才会拿到系统给的热点。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            (held as? WifiManager.LocalOnlyHotspotReservation)?.close()
        } catch (e: RuntimeException) {
            // 关不掉也无妨：系统在本进程退出时会收回。
        }
    }

    /** 还在收尾的连接马上收完（新的连接要开热点了）。 */
    private fun flushDraining() {
        if (draining.isEmpty()) return
        for ((_, task) in draining.toList()) {
            handler.removeCallbacks(task)
            task.run()
        }
    }

    // ---------------------------------------------------------------- 读系统给的热点

    private class ReservationInfo(
        val ssid: String?,
        val passphrase: String?,
        val bssid: String?,
        val security: HotspotSecurity,
        val hint: HotspotBandHint?,
    )

    private fun readReservation(held: Any): ReservationInfo {
        val unknown = ReservationInfo(null, null, null, HotspotSecurity.UNKNOWN, null)
        // 只有安卓 8.0 起才会拿到系统给的热点。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return unknown
        val reservation = held as? WifiManager.LocalOnlyHotspotReservation ?: return unknown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            @Suppress("DEPRECATION")
            val ssid = config.ssid
            return ReservationInfo(
                ssid = ssid,
                passphrase = config.passphrase,
                bssid = config.bssid?.toString(),
                security = LocalHotspotRules.securityOf(config.securityType),
                // 频段、信道没有公开的取值接口，从系统给的文字描述里读（读不出就按推测）。
                hint = LocalHotspotRules.bandHintFromDescription(config.toString()),
            )
        }
        @Suppress("DEPRECATION")
        val config = reservation.wifiConfiguration
            ?: return ReservationInfo(null, null, null, HotspotSecurity.UNKNOWN, null)
        val passphrase = config.preSharedKey
        return ReservationInfo(
            ssid = config.SSID,
            passphrase = passphrase,
            bssid = config.BSSID,
            security = if (passphrase.isNullOrEmpty()) HotspotSecurity.NO_PASSPHRASE else HotspotSecurity.OK,
            // 老系统的热点配置有频段、信道两个字段（不在公开接口里，读不到就按推测）。
            hint = LocalHotspotRules.bandHintFromLegacy(intField(config, "apBand"), intField(config, "apChannel")),
        )
    }

    private fun intField(target: Any, name: String): Int? = try {
        target.javaClass.getField(name).getInt(target)
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun reasonText(reason: Int): String = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "原因 $reason" else when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "没有可用的信道"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "系统内部错误（刚关掉热点又马上申请、或安卓 8～12 系统定位开关没开时常见）"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "当前状态开不了：车机可能开着个人热点或在共享网络，或者本应用不在前台"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "系统策略不允许开热点"
        else -> "原因 $reason"
    }

    // ---------------------------------------------------------------- 工具

    private fun stationEnabled(): Boolean = try {
        wifi?.isWifiEnabled == true
    } catch (e: RuntimeException) {
        false
    }

    /** 车机作为终端正连着的 WiFi 的频率（日志用），没连为 0。 */
    @Suppress("DEPRECATION")
    private fun stationFrequency(): Int = try {
        val info = wifi?.connectionInfo
        if (info == null || info.networkId == -1) 0 else info.frequency.coerceAtLeast(0)
    } catch (e: RuntimeException) {
        0
    }

    /**
     * 车机作为终端正连着的 WiFi 上的 IPv4，没连为 null。选热点网卡时排除它：能一边连 WiFi 一边开热点的车机上，
     * 它可能被误当成热点地址。
     */
    @Suppress("DEPRECATION")
    private fun stationIpv4(): String? = try {
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else "${ip and 0xFF}.${ip ushr 8 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 24 and 0xFF}"
    } catch (e: RuntimeException) {
        null
    }

    /** 开着的网卡和上面的 IPv4（不含回环、链路本地）。 */
    private fun snapshot(): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in Collections.list(all)) {
                val up = try {
                    nif.isUp && !nif.isLoopback
                } catch (e: SocketException) {
                    false
                }
                if (!up) continue
                val addresses = Collections.list(nif.inetAddresses)
                    .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .toSet()
                if (addresses.isNotEmpty()) out[nif.name] = addresses
            }
        } catch (e: SocketException) {
            // 读不到就当没有，找不到地址时会报出来。
        } catch (e: RuntimeException) {
            // 同上。
        }
        return out
    }

    /** 安卓 11 起以高 targetSdk 运行时一般返回 null（系统不给普通应用网卡 MAC），由 [LocalHotspotRules.pickBssid] 退到占位值。 */
    private fun hardwareAddress(name: String): ByteArray? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
    } catch (e: SocketException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        /** 热点开好后等网卡上出现地址的上限。本车机实测：ap0 的地址有时要 3 秒以上才出现（刚关过一次热点
         *  或预热被间隔挡住时更慢），3 秒会把系统只是慢、并非失败的情况误判成失败，放宽到 10 秒。 */
        const val ADDRESS_WAIT_MS = 10_000L
        const val ADDRESS_POLL_MS = 100L

        /** 挂上了热点信息回调时，系统开好热点后等它回报实际频率和 BSSID 的上限（到点按推测）。 */
        const val INFO_WAIT_MS = 1_500L

        /** 车机 WiFi 还开着时，「只有一个 wlan 地址就用它」的兜底在系统开好热点这么久以后才用（车主实测过 1.5 秒够）。 */
        const val WLAN_FALLBACK_DELAY_MS = 1_500L

        /** 停掉后过这么久才关热点：让会话收尾时最后几个包先发出去。 */
        const val STOP_DRAIN_MS = 200L

        private const val MIN_GAP_TEXT = "3.5 秒"

        /** 申请热点的系统调用多久没返回就记下它卡在哪里，之后每隔多久再记一次。 */
        private const val CALL_WATCH_FIRST_MS = 3_000L
        private const val CALL_WATCH_REPEAT_MS = 5_000L
        private const val STACK_FRAMES = 12

        @Volatile
        private var instance: LocalHotspotOwner? = null

        /** 进程里唯一的一个（系统按进程记热点申请）。 */
        fun get(context: Context): LocalHotspotOwner =
            instance ?: synchronized(this) { instance ?: LocalHotspotOwner(context).also { instance = it } }

        /** 本进程用过热点才有；没用过返回 null（不为此建工作线程）。 */
        fun existing(): LocalHotspotOwner? = instance

        /**
         * 强制热点频段：申请热点前把系统热点配置的信道改写成 [value]（5 GHz 信道号，如 36），
         * 系统开本地热点时继承频段/信道；null = 不强制，交给系统定（这台车机落 2.4 GHz 信道 6）。
         * 对照吉利 CarLink：同样反射写系统热点配置（apBand=1 + apChannel=36）。由调用方（app 层）按设置给值。
         */
        @Volatile
        var forcedChannel: Int? = null
    }
}
