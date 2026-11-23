package io.github.gcbond.openhc.platform.wifi

// 车机开本地热点（无线连接用的 WiFi 网络）用到的纯逻辑：申请的状态机、从网卡变化里认出热点网卡、
// 读不到信道时的频率推测、BSSID 和加密方式的取舍。本文件不引用安卓类，由 JVM 单元测试覆盖；
// 接到系统接口上见 LocalHotspotOwner。

/** 2.4 GHz 或 5 GHz。 */
enum class WifiBand(val label: String) {
    GHZ_2("2.4 GHz"),
    GHZ_5("5 GHz"),
}

/** 一次申请为什么没拿到热点。 */
enum class HotspotFailure(val label: String) {
    /** 系统回了失败，或者申请的调用本身被拒（具体原因由调用方另外记下）。 */
    SYSTEM("系统没开成热点"),

    /** 系统一直没有回话。 */
    NO_ANSWER("系统一直没有回应"),

    /** 另一次连接正在用热点。 */
    BUSY("另一次连接正在用热点"),
}

/** [HotspotRequestMachine] 要调用方做的事，按列表顺序做。 */
sealed class HotspotEffect {
    /** 到 [atMillis]（单调时钟）调一次 onTick；新的替换旧的。 */
    data class WakeAt(val atMillis: Long) : HotspotEffect()

    /** 向系统申请热点。 */
    object Request : HotspotEffect() {
        override fun toString() = "Request"
    }

    /** 关掉手里的热点。 */
    object Release : HotspotEffect() {
        override fun toString() = "Release"
    }

    /** 热点开好了，归 [owner] 用。 */
    data class Started(val owner: Int) : HotspotEffect()

    data class Failed(val owner: Int, val failure: HotspotFailure) : HotspotEffect()

    /** [owner] 用着的热点被系统收回了。 */
    data class Lost(val owner: Int) : HotspotEffect()

    /** 上次关热点不久，[delayMillis] 后才申请（日志用）。 */
    data class Deferred(val owner: Int, val delayMillis: Long) : HotspotEffect()
}

/**
 * 申请本地热点的状态机，进程里只有一个（系统按进程记申请：同一进程的申请还没了结时再申请，系统直接拒绝）。
 * 每次连接用一个整数 id 当热点的「主人」；同一时刻只有一个主人。
 *
 * 几条规则（「为什么」写在各处）：
 * - **两次申请之间至少隔 [minGapMillis]**：刚关掉热点就重新申请，实测系统会一直不回话、半分钟后才报失败；
 *   隔约 3 秒再申请正常。热点被系统收回、申请失败之后同样按这个间隔。
 * - **主人走了，申请还没回话**：不能撤回（没有公开接口），留着；回话说开好了就立刻关掉，期间来了新主人就直接交给它。
 * - **热点被关不等手机离开**：关热点、热点被系统收回都只看系统回调，不依赖「手机已离开热点」之类的通知，
 *   关完马上回到空闲，下一次连接照常申请。
 * - 申请发出后 [answerTimeoutMillis] 还没回话，告诉主人失败（申请本身还留着，规则同上）；
 *   留了超过 [staleRequestMillis] 的申请当作已经作废，新主人来了重新申请。
 * - **先开好留着**（[hold]）：无线开着时不等连接要，先把热点开好；没有主人时也不关，下一次连接来了直接接手，
 *   几次连接之间不再关了又开（刚关就开系统会卡住），直到 [unhold]。车机在手机认证之后才开热点时，
 *   正赶上手机关掉自己的热点、车机 WiFi 刚断，实测系统一直不回话；先开好就避开了这个时刻。
 *
 * 不做 I/O，时间由调用方传入（单调时钟毫秒）；只在一个线程上调用。
 */
class HotspotRequestMachine(
    private val minGapMillis: Long = MIN_GAP_MILLIS,
    private val answerTimeoutMillis: Long = ANSWER_TIMEOUT_MILLIS,
    private val staleRequestMillis: Long = STALE_REQUEST_MILLIS,
) {
    enum class Phase {
        /** 没有申请，也没有热点。 */
        IDLE,

        /** 有主人在等间隔过去。 */
        GAP,

        /** 申请已发出，等系统回话（可能没有主人：主人中途走了）。 */
        REQUESTED,

        /** 热点开着，归主人用。 */
        ACTIVE,
    }

    init {
        require(minGapMillis >= 0 && answerTimeoutMillis > 0 && staleRequestMillis >= answerTimeoutMillis) { "时长不合理" }
    }

    var phase: Phase = Phase.IDLE
        private set

    /** 现在的主人；没有为 null。 */
    var owner: Int? = null
        private set

    private var gapEndsAt = 0L
    private var requestedAt = 0L
    private var answerDeadline = 0L
    private var answerOverdue = false
    private var lastReleasedAt: Long? = null

    /** 正留着热点（[hold] 之后、[unhold] 之前）。 */
    var held: Boolean = false
        private set

    /** 有连接在用（或在等）热点、有申请还没回话（回话时热点会短暂打开），或者正留着热点。 */
    val busy: Boolean get() = owner != null || phase == Phase.REQUESTED || held

    /** 下次要调 [onTick] 的时间；没有为 null。 */
    val nextWakeAt: Long?
        get() = when {
            phase == Phase.GAP -> gapEndsAt
            phase == Phase.REQUESTED && owner != null && !answerOverdue -> answerDeadline
            else -> null
        }

    /** 连接 [id] 要热点。 */
    fun start(id: Int, now: Long): List<HotspotEffect> {
        val current = owner
        if (current == id) return emptyList()
        if (current != null) return listOf(HotspotEffect.Failed(id, HotspotFailure.BUSY))
        return when (phase) {
            Phase.IDLE -> begin(id, now)
            // 不会出现（没有主人的间隔会直接回到空闲），按接手处理。
            Phase.GAP -> {
                owner = id
                listOf(HotspotEffect.Deferred(id, gapEndsAt - now), HotspotEffect.WakeAt(gapEndsAt))
            }
            Phase.REQUESTED -> if (now - requestedAt >= staleRequestMillis) {
                // 留得太久的申请当作作废了：重新申请（系统要是还记着它，会拒绝这次申请，按失败报）。
                begin(id, now)
            } else {
                // 上一个主人走了、申请还在：接手，回话时直接交给新主人。
                owner = id
                answerDeadline = now + answerTimeoutMillis
                answerOverdue = false
                listOf(HotspotEffect.WakeAt(answerDeadline))
            }
            // 不会出现（主人走时热点就关了），按接手处理。
            Phase.ACTIVE -> {
                owner = id
                listOf(HotspotEffect.Started(id))
            }
        }
    }

    /** 连接 [id] 不要热点了（会话结束）。不是现在的主人就什么都不做；正留着热点时不关。 */
    fun stop(id: Int, now: Long): List<HotspotEffect> {
        if (owner != id) return emptyList()
        owner = null
        return when (phase) {
            Phase.GAP -> {
                if (!held) phase = Phase.IDLE
                emptyList()
            }
            // 撤不回：等系统回话，开好了就关掉（留着时不关），失败就算了。
            Phase.REQUESTED -> emptyList()
            Phase.ACTIVE -> if (held) emptyList() else release(now)
            Phase.IDLE -> emptyList()
        }
    }

    /** 先把热点开好留着（无线开着、还没有连接要用）。已经在申请、开着、在等间隔或者有主人时只记下要留着。 */
    fun hold(now: Long): List<HotspotEffect> {
        held = true
        if (owner != null || phase != Phase.IDLE) return emptyList()
        val last = lastReleasedAt
        if (last != null && now - last < minGapMillis) {
            phase = Phase.GAP
            gapEndsAt = last + minGapMillis
            return listOf(HotspotEffect.WakeAt(gapEndsAt))
        }
        return request(now)
    }

    /** 不用再留着了：没有连接在用就关掉；还在等回话的申请，回话说开好了时关。 */
    fun unhold(now: Long): List<HotspotEffect> {
        if (!held) return emptyList()
        held = false
        if (owner != null) return emptyList()
        return when (phase) {
            Phase.ACTIVE -> release(now)
            Phase.GAP -> {
                phase = Phase.IDLE
                emptyList()
            }
            Phase.REQUESTED, Phase.IDLE -> emptyList()
        }
    }

    /** 到了 [nextWakeAt]。早调、晚调都行。 */
    fun onTick(now: Long): List<HotspotEffect> = when (phase) {
        Phase.GAP -> when {
            now < gapEndsAt -> emptyList()
            owner != null || held -> request(now)
            else -> {
                phase = Phase.IDLE
                emptyList()
            }
        }
        Phase.REQUESTED -> {
            val current = owner
            if (current != null && !answerOverdue && now >= answerDeadline) {
                answerOverdue = true
                owner = null
                listOf(HotspotEffect.Failed(current, HotspotFailure.NO_ANSWER))
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }

    /** 系统回话：热点开好了。 */
    fun onStarted(now: Long): List<HotspotEffect> {
        if (phase == Phase.ACTIVE) return emptyList()
        val current = owner
        if (current == null) {
            // 没有主人（走了、或这是一次早就作废的申请才回话）：留着热点时开着等下一次连接，否则关掉。
            if (!held) return release(now)
            phase = Phase.ACTIVE
            return emptyList()
        }
        // 在等间隔时，早先一次申请回话了：热点已经开好，直接给等着的主人。
        phase = Phase.ACTIVE
        return listOf(HotspotEffect.Started(current))
    }

    /** 系统回话：没开成。 */
    fun onFailed(now: Long): List<HotspotEffect> {
        if (phase != Phase.REQUESTED) return emptyList()
        return giveUp(now)
    }

    /** 申请的调用本身被系统拒绝（同步抛出的异常）。 */
    fun onRequestRejected(now: Long): List<HotspotEffect> = onFailed(now)

    /** 系统收回了热点（或者在开好之前就停了）。 */
    fun onStopped(now: Long): List<HotspotEffect> = when (phase) {
        Phase.ACTIVE -> {
            phase = Phase.IDLE
            lastReleasedAt = now
            val current = owner
            owner = null
            if (current != null) listOf(HotspotEffect.Lost(current)) else emptyList()
        }
        Phase.REQUESTED -> giveUp(now)
        else -> emptyList()
    }

    private fun begin(id: Int, now: Long): List<HotspotEffect> {
        owner = id
        val last = lastReleasedAt
        if (last != null && now - last < minGapMillis) {
            phase = Phase.GAP
            gapEndsAt = last + minGapMillis
            return listOf(HotspotEffect.Deferred(id, gapEndsAt - now), HotspotEffect.WakeAt(gapEndsAt))
        }
        return request(now)
    }

    private fun request(now: Long): List<HotspotEffect> {
        phase = Phase.REQUESTED
        requestedAt = now
        answerDeadline = now + answerTimeoutMillis
        answerOverdue = false
        return listOf(HotspotEffect.Request, HotspotEffect.WakeAt(answerDeadline))
    }

    private fun giveUp(now: Long): List<HotspotEffect> {
        phase = Phase.IDLE
        // 失败之后系统可能还在收拾，下次申请同样隔开。
        lastReleasedAt = now
        val current = owner
        owner = null
        return if (current != null) listOf(HotspotEffect.Failed(current, HotspotFailure.SYSTEM)) else emptyList()
    }

    private fun release(now: Long): List<HotspotEffect> {
        phase = Phase.IDLE
        lastReleasedAt = now
        return listOf(HotspotEffect.Release)
    }

    override fun toString() = "HotspotRequestMachine($phase, 主人=${owner ?: "无"})"

    companion object {
        /** 两次申请之间至少隔这么久：实测刚关就申请系统会卡住，隔约 3 秒正常，这里多留半秒。 */
        const val MIN_GAP_MILLIS = 3_500L

        /** 申请发出后等系统回话的上限（正常一两秒）。 */
        const val ANSWER_TIMEOUT_MILLIS = 10_000L

        /** 申请留了这么久还没回话，当作已经作废。 */
        const val STALE_REQUEST_MILLIS = 60_000L
    }
}

/** 热点的 BSSID（写进连接串）取自哪里。 */
enum class HotspotMacSource(val label: String) {
    CONFIG("系统给的热点配置"),
    REPORTED("系统回报的热点信息"),
    INTERFACE("热点网卡的硬件地址"),
    PLACEHOLDER("读不到真实地址，用占位值"),
}

/** 热点的频率（协商和连接串里报给手机）取自哪里。只有 [CONFIGURED] 不是推测。 */
enum class HotspotFrequencySource(val label: String, val verified: Boolean) {
    REPORTED("系统回报的热点实际频率", true),
    CONFIGURED("系统的热点配置里写明了信道", true),
    ASSUMED_BAND("推测：系统只说了频段、没说信道，按这个频段的常用信道", false),
    ASSUMED_DEFAULT("推测：读不到频段和信道，按 2.4 GHz 第 6 信道（本地热点默认开在 2.4 GHz）", false),
}

/** 系统热点配置里能读到的频段和信道（信道 0 表示没指定、由系统自己挑）。 */
class HotspotBandHint(val band: WifiBand?, val channel: Int) {
    override fun toString() = "HotspotBandHint(${band?.label ?: "频段未知"}, 信道 ${if (channel > 0) channel else "未指定"})"
}

/** 热点的加密方式对这套协议能不能用。连接串只带一个 WPA2 口令。 */
enum class HotspotSecurity(val label: String) {
    /** WPA2，或 WPA2/WPA3 混合（只会 WPA2 的手机照样能连）。 */
    OK("WPA2"),

    /** 只开了 WPA3：手机要按 WPA3 连，按 WPA2 口令可能连不上。 */
    WPA3_ONLY("只开了 WPA3"),

    /** 开放热点、或没有口令的加密方式：连接串里给不出口令。 */
    NO_PASSPHRASE("没有口令"),

    /** 系统没说（老系统读不到）。 */
    UNKNOWN("未知"),
}

/** 本地热点的纯逻辑。 */
object LocalHotspotRules {
    /** 推测用的 2.4 GHz 频率：第 6 信道。 */
    const val DEFAULT_2G_MHZ = 2437

    /** 推测用的 5 GHz 频率：第 149 信道（协商时手机端最常见的共同信道）。 */
    const val DEFAULT_5G_MHZ = 5745

    /** 不会是热点的网卡（回环、隧道、蜂窝、USB 共享、WiFi 直连、有线等），按名字前缀排除。 */
    private val EXCLUDED_PREFIXES = listOf(
        "lo", "tun", "ppp", "rmnet", "r_rmnet", "rev_rmnet", "ccmni", "rndis", "usb", "ncm", "p2p", "dummy",
        "ip6tnl", "ip_vti", "sit", "ifb", "bt-pan", "clat", "v4-", "veth", "eth", "bond", "radio", "epdg", "seth", "mhi",
    )

    /** 热点常用的网卡名前缀（最像热点的排前面）。 */
    private val AP_PREFIXES = listOf("ap", "softap", "swlan", "uap")
    private val WLAN_PREFIXES = listOf("wlan", "wifi", "wl")

    /**
     * 从开热点前后两次网卡快照（网卡名 → 上面的 IPv4）里认出热点网卡和车机在上面的地址。
     *
     * 1. 优先找开热点后**新出现**的地址：单射频车机开热点时自己的 WiFi 会断，热点地址常常出现在同一块网卡上，
     *    只看网卡名分不出来；
     * 2. 新地址有好几个时按网卡名排：ap / softap / swlan / uap 开头的最像，其次 wlan / wifi，其余最后；
     * 3. 没有新地址（热点被本机别的应用先开着，或者地址比回调先出现、两次快照之间没变化）：
     *    在名字最像热点的网卡里找；只有 wlan 一类网卡时，只在恰好一个地址的情况下用它。
     * 车机自己作为终端连着 WiFi 时的地址（[stationIp]）一律不算：能一边连 WiFi 一边开热点的车机上，
     * 这个地址可能恰好在开热点时重新拿到、被当成新地址（有车主在安卓 13 车机上遇到，手机连不上车机的端口）。
     * [wlanFallback] 为 false 时第 3 步里「只有一个 wlan 地址就用它」先不用（车机 WiFi 还开着、热点网卡的地址可能还没出现）。
     *
     * 返回 网卡名 to 地址；认不出返回 null。
     */
    fun pickInterface(
        before: Map<String, Set<String>>,
        after: Map<String, Set<String>>,
        stationIp: String? = null,
        wlanFallback: Boolean = true,
    ): Pair<String, String>? {
        val fresh = ArrayList<Triple<Int, String, String>>()
        for ((name, addresses) in after) {
            val rank = rankOf(name) ?: continue
            val old = before[name].orEmpty()
            for (ip in addresses) if (ip !in old && ip != stationIp && usableIpv4(ip)) fresh += Triple(rank, name, ip)
        }
        best(fresh)?.let { return it }
        val existing = ArrayList<Triple<Int, String, String>>()
        for ((name, addresses) in after) {
            val rank = rankOf(name) ?: continue
            for (ip in addresses) if (ip != stationIp && usableIpv4(ip)) existing += Triple(rank, name, ip)
        }
        best(existing.filter { it.first == RANK_AP })?.let { return it }
        if (!wlanFallback) return null
        val wlan = existing.filter { it.first == RANK_WLAN }
        return if (wlan.size == 1) wlan[0].second to wlan[0].third else null
    }

    private fun best(list: List<Triple<Int, String, String>>): Pair<String, String>? =
        list.sortedWith(compareBy<Triple<Int, String, String>>({ it.first }, { it.second }, { it.third }))
            .firstOrNull()?.let { it.second to it.third }

    /** 网卡名的排序（小的更像热点）；不可能是热点的返回 null。 */
    internal fun rankOf(name: String): Int? {
        val n = name.lowercase()
        if (n.isEmpty() || EXCLUDED_PREFIXES.any { n.startsWith(it) }) return null
        return when {
            AP_PREFIXES.any { n.startsWith(it) } -> RANK_AP
            WLAN_PREFIXES.any { n.startsWith(it) } -> RANK_WLAN
            else -> RANK_OTHER
        }
    }

    /** 点分十进制、不是全 0、回环、链路本地、组播或广播的 IPv4。 */
    fun usableIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { p -> if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return false else p.toInt() }
        if (bytes.any { it > 255 }) return false
        val a = bytes[0]
        val b = bytes[1]
        return a != 0 && a != 127 && !(a == 169 && b == 254) && a < 224
    }

    /**
     * 报给手机的频率。普通应用读不到本地热点实际开在哪个信道（只有系统接口给得出），所以多数时候是推测：
     * - 系统配置里写明了信道（少见，一般是 0 = 由系统自己挑）：用它；
     * - 只知道频段：2.4 GHz 报第 6 信道、5 GHz 报第 149 信道；
     * - 什么都不知道：按 2.4 GHz 第 6 信道（本地热点默认开在 2.4 GHz）。
     * 系统回报了实际频率（安卓 13 起的热点信息回调，[reportedMhz]，2.4 / 5 GHz 才算）就用它，不再推测。
     *
     * 推测的频段对、信道不对时手机能不能照样连上，没有实测对照；频段报错了手机多半找不到网。
     */
    fun estimateFrequency(hint: HotspotBandHint?, reportedMhz: Int = 0): Pair<Int, HotspotFrequencySource> {
        if (isWifiChannelFrequency(reportedMhz)) return reportedMhz to HotspotFrequencySource.REPORTED
        val band = hint?.band
        if (hint != null && band != null && hint.channel > 0) {
            val mhz = frequencyOf(hint.channel, band)
            if (mhz != null) return mhz to HotspotFrequencySource.CONFIGURED
        }
        return when (band) {
            WifiBand.GHZ_5 -> DEFAULT_5G_MHZ to HotspotFrequencySource.ASSUMED_BAND
            WifiBand.GHZ_2 -> DEFAULT_2G_MHZ to HotspotFrequencySource.ASSUMED_BAND
            null -> DEFAULT_2G_MHZ to HotspotFrequencySource.ASSUMED_DEFAULT
        }
    }

    /** 2.4 GHz 或 5 GHz 的信道中心频率（连接串只认这两个频段）。 */
    fun isWifiChannelFrequency(mhz: Int): Boolean = mhz == 2484 || (mhz in 2412..2472 && (mhz - 2407) % 5 == 0) ||
        (mhz in 5160..5885 && (mhz - 5000) % 5 == 0)

    /** 信道号在给定频段里的中心频率；不是该频段的信道返回 null。 */
    fun frequencyOf(channel: Int, band: WifiBand): Int? = when (band) {
        WifiBand.GHZ_2 -> when (channel) {
            in 1..13 -> 2407 + 5 * channel
            14 -> 2484
            else -> null
        }
        WifiBand.GHZ_5 -> if (channel in 32..177) 5000 + 5 * channel else null
    }

    /**
     * 老式热点配置（安卓 8～10 的本地热点配置用它）的频段、信道字段：频段 0 = 2.4 GHz、1 = 5 GHz、-1 = 任选；
     * 信道 0 = 由系统挑。读不到字段传 null。
     */
    fun bandHintFromLegacy(apBand: Int?, apChannel: Int?): HotspotBandHint? {
        if (apBand == null && apChannel == null) return null
        val band = when (apBand) {
            LEGACY_BAND_2G -> WifiBand.GHZ_2
            LEGACY_BAND_5G -> WifiBand.GHZ_5
            else -> null
        }
        return HotspotBandHint(band, (apChannel ?: 0).coerceAtLeast(0))
    }

    /**
     * 新式热点配置（安卓 11 起）的文字描述里读频段和信道：`Channels = {频段=信道}`（安卓 12 起），
     * 或 `Band = 频段` 与 `Channel = 信道`（安卓 11）。频段是按位的：1 = 2.4 GHz、2 = 5 GHz、4 = 6 GHz；
     * 只认单独的 2.4 GHz 或 5 GHz，几个频段任选、6 GHz 都当作不知道。读不出返回 null。
     */
    fun bandHintFromDescription(text: String?): HotspotBandHint? {
        if (text.isNullOrEmpty()) return null
        val pair = CHANNELS_ENTRY.find(text)
        val bandBits: Int
        val channel: Int
        if (pair != null) {
            bandBits = pair.groupValues[1].toIntOrNull() ?: return null
            channel = pair.groupValues[2].toIntOrNull() ?: 0
        } else {
            bandBits = BAND_FIELD.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            channel = CHANNEL_FIELD.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        val band = when (bandBits) {
            BAND_BIT_2G -> WifiBand.GHZ_2
            BAND_BIT_5G -> WifiBand.GHZ_5
            else -> null
        }
        return HotspotBandHint(band, channel.coerceAtLeast(0))
    }

    /** 新式热点配置的加密方式编号（0 开放、1 WPA2、2 WPA2/WPA3 混合、3 只 WPA3、4/5 增强型开放）对这套协议能不能用。 */
    fun securityOf(type: Int?): HotspotSecurity = when (type) {
        null -> HotspotSecurity.UNKNOWN
        1, 2 -> HotspotSecurity.OK
        3 -> HotspotSecurity.WPA3_ONLY
        0, 4, 5 -> HotspotSecurity.NO_PASSPHRASE
        else -> HotspotSecurity.UNKNOWN
    }

    /**
     * 写进连接串的 BSSID：系统热点配置里给了就用（多数系统不给），其次系统回报的热点信息（安卓 13 起，[reportedBssid]），
     * 再次热点网卡的硬件地址（新系统对普通应用常常给不出）；都没有用占位值 [MacText.PLACEHOLDER]。
     * 有车主在安卓 13 车机上实测：手机按 BSSID 精确找网，占位值找不到。
     */
    fun pickBssid(configBssid: String?, interfaceMac: ByteArray?, reportedBssid: String? = null): Pair<String, HotspotMacSource> {
        MacText.normalize(configBssid)?.let { return it to HotspotMacSource.CONFIG }
        MacText.normalize(reportedBssid)?.let { return it to HotspotMacSource.REPORTED }
        MacText.of(interfaceMac)?.let { return it to HotspotMacSource.INTERFACE }
        return MacText.PLACEHOLDER to HotspotMacSource.PLACEHOLDER
    }

    /** 热点名：去掉首尾引号；空的、系统给的「未知」占位返回 null。 */
    fun networkName(raw: String?): String? {
        val name = raw?.let { stripQuotes(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return if (name == UNKNOWN_SSID) null else name
    }

    private fun stripQuotes(text: String): String =
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1) else text

    private const val RANK_AP = 0
    private const val RANK_WLAN = 1
    private const val RANK_OTHER = 2

    private const val LEGACY_BAND_2G = 0
    private const val LEGACY_BAND_5G = 1
    private const val BAND_BIT_2G = 1
    private const val BAND_BIT_5G = 2

    /** 系统读不出网络名时给的占位文字。 */
    private const val UNKNOWN_SSID = "<unknown ssid>"

    private val CHANNELS_ENTRY = Regex("""Channels\s*=\s*\{\s*(\d+)\s*=\s*(\d+)""")
    private val BAND_FIELD = Regex("""\bBand\s*=\s*(\d+)""")
    private val CHANNEL_FIELD = Regex("""\bChannel\s*=\s*(\d+)""")
}
