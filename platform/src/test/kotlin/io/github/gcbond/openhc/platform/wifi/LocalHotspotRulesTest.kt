package io.github.gcbond.openhc.platform.wifi

import io.github.gcbond.openhc.platform.wifi.HotspotEffect.Deferred
import io.github.gcbond.openhc.platform.wifi.HotspotEffect.Failed
import io.github.gcbond.openhc.platform.wifi.HotspotEffect.Lost
import io.github.gcbond.openhc.platform.wifi.HotspotEffect.Release
import io.github.gcbond.openhc.platform.wifi.HotspotEffect.Request
import io.github.gcbond.openhc.platform.wifi.HotspotEffect.Started
import io.github.gcbond.openhc.platform.wifi.HotspotEffect.WakeAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 本地热点的纯逻辑：申请的间隔与状态机、认热点网卡、频率推测、BSSID 与加密方式。测试数据都是合成值。 */
class LocalHotspotRulesTest {

    private val gap = HotspotRequestMachine.MIN_GAP_MILLIS
    private val answer = HotspotRequestMachine.ANSWER_TIMEOUT_MILLIS

    // ---------------------------------------------------------------- 申请的状态机

    @Test
    fun firstRequestGoesOutAtOnceAndTheHotspotBelongsToItsOwner() {
        val m = HotspotRequestMachine()
        assertEquals(listOf(Request, WakeAt(1_000 + answer)), m.start(1, 1_000))
        assertTrue(m.busy)
        assertEquals(HotspotRequestMachine.Phase.REQUESTED, m.phase)
        assertEquals(listOf<HotspotEffect>(Started(1)), m.onStarted(1_800))
        assertEquals(HotspotRequestMachine.Phase.ACTIVE, m.phase)
        assertNull(m.nextWakeAt)
        // 同一个主人重复要：什么都不做；重复的「开好了」也不理。
        assertEquals(emptyList(), m.start(1, 1_900))
        assertEquals(emptyList(), m.onStarted(1_900))
        // 用完关掉：马上回到空闲，不等「手机离开」之类的通知。
        assertEquals(listOf<HotspotEffect>(Release), m.stop(1, 9_000))
        assertEquals(HotspotRequestMachine.Phase.IDLE, m.phase)
        assertFalse(m.busy)
    }

    @Test
    fun requestsRightAfterAReleaseWaitForTheGap() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.onStarted(500)
        m.stop(1, 10_000)
        // 关掉 1 秒后就要：推迟到关掉之后 3.5 秒才申请。
        assertEquals(listOf(Deferred(2, gap - 1_000), WakeAt(10_000 + gap)), m.start(2, 11_000))
        assertEquals(10_000 + gap, m.nextWakeAt)
        assertTrue(m.busy)
        assertEquals(emptyList(), m.onTick(10_000 + gap - 1))
        assertEquals(listOf(Request, WakeAt(10_000 + gap + answer)), m.onTick(10_000 + gap))
        assertEquals(listOf<HotspotEffect>(Started(2)), m.onStarted(10_000 + gap + 900))
        // 间隔已经过了：马上申请。
        m.stop(2, 20_000)
        assertEquals(listOf(Request, WakeAt(20_000 + gap + answer)), m.start(3, 20_000 + gap))
    }

    @Test
    fun stoppingDuringTheGapCancelsTheRequestButKeepsTheGap() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.onStarted(500)
        m.stop(1, 10_000)
        m.start(2, 10_500)
        assertEquals(emptyList(), m.stop(2, 11_000))
        assertEquals(HotspotRequestMachine.Phase.IDLE, m.phase)
        assertFalse(m.busy)
        assertEquals(emptyList(), m.onTick(10_000 + gap))
        // 下一次照样隔开。
        assertEquals(listOf(Deferred(3, gap - 2_000), WakeAt(10_000 + gap)), m.start(3, 12_000))
    }

    @Test
    fun anOwnerLeavingBeforeTheAnswerLeavesTheRequestToBeClosedOnArrival() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        // 主人走了，申请撤不回：留着，期间还算忙（回话时热点会短暂打开）。
        assertEquals(emptyList(), m.stop(1, 300))
        assertNull(m.owner)
        assertTrue(m.busy)
        assertNull(m.nextWakeAt)
        // 回话说开好了：马上关掉，并从这时起算间隔。
        assertEquals(listOf<HotspotEffect>(Release), m.onStarted(1_200))
        assertFalse(m.busy)
        assertEquals(listOf(Deferred(2, gap - 800), WakeAt(1_200 + gap)), m.start(2, 2_000))
    }

    @Test
    fun aNewOwnerTakesOverAnUnansweredRequest() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.stop(1, 300)
        // 新连接来了：不再申请（系统按进程只认一个申请），接手等回话，重新起算等回话的时限。
        assertEquals(listOf(WakeAt(800 + answer)), m.start(2, 800))
        assertEquals(2, m.owner)
        assertEquals(listOf<HotspotEffect>(Started(2)), m.onStarted(1_500))
        assertEquals(HotspotRequestMachine.Phase.ACTIVE, m.phase)
    }

    @Test
    fun theHotspotTakenAwayBySystemIsReportedAndCountsAsARelease() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.onStarted(600)
        assertEquals(listOf<HotspotEffect>(Lost(1)), m.onStopped(30_000))
        assertEquals(HotspotRequestMachine.Phase.IDLE, m.phase)
        assertNull(m.owner)
        // 之后主人再来关：不是主人了，什么都不做。
        assertEquals(emptyList(), m.stop(1, 30_100))
        assertEquals(listOf(Deferred(2, gap - 1_000), WakeAt(30_000 + gap)), m.start(2, 31_000))
    }

    @Test
    fun failuresAreReportedOnceAndAlsoKeepTheGap() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        assertEquals(listOf(Failed(1, HotspotFailure.SYSTEM)), m.onFailed(400))
        assertEquals(HotspotRequestMachine.Phase.IDLE, m.phase)
        assertEquals(emptyList(), m.onFailed(450))
        assertEquals(Deferred(2, gap - 600), m.start(2, 1_000).first())
        // 申请的调用本身被拒，和系统回失败一样处理。
        m.onTick(400 + gap)
        assertEquals(listOf(Failed(2, HotspotFailure.SYSTEM)), m.onRequestRejected(400 + gap))
        // 开好之前系统就停了：也按失败。
        val n = HotspotRequestMachine()
        n.start(5, 0)
        assertEquals(listOf(Failed(5, HotspotFailure.SYSTEM)), n.onStopped(100))
    }

    @Test
    fun noAnswerInTimeFailsTheOwnerAndALateAnswerIsClosed() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        assertEquals(emptyList(), m.onTick(answer - 1))
        assertEquals(listOf(Failed(1, HotspotFailure.NO_ANSWER)), m.onTick(answer))
        assertNull(m.owner)
        assertTrue(m.busy, "申请还没了结")
        assertNull(m.nextWakeAt)
        assertEquals(emptyList(), m.onTick(answer + 1_000))
        assertEquals(listOf<HotspotEffect>(Release), m.onStarted(30_000))
    }

    @Test
    fun aRequestLeftUnansweredForTooLongIsReplaced() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.stop(1, 100)
        val stale = HotspotRequestMachine.STALE_REQUEST_MILLIS
        assertEquals(listOf(Request, WakeAt(stale + answer)), m.start(2, stale))
        assertEquals(2, m.owner)
    }

    @Test
    fun onlyOneConnectionCanOwnTheHotspot() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        assertEquals(listOf(Failed(2, HotspotFailure.BUSY)), m.start(2, 100))
        assertEquals(1, m.owner)
        assertEquals(emptyList(), m.stop(2, 200))
        assertEquals(listOf<HotspotEffect>(Started(1)), m.onStarted(500))
    }

    @Test
    fun anEarlierRequestAnsweringDuringTheGapIsHandedToTheWaitingOwner() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.onStarted(100)
        m.stop(1, 1_000)
        m.start(2, 1_500)
        assertEquals(HotspotRequestMachine.Phase.GAP, m.phase)
        // 不会正常出现，但万一来了，热点已经开好，直接交给等着的主人。
        assertEquals(listOf<HotspotEffect>(Started(2)), m.onStarted(2_000))
        assertEquals(emptyList(), m.onTick(1_000 + gap))
        assertEquals(HotspotRequestMachine.Phase.ACTIVE, m.phase)
    }

    // ---------------------------------------------------------------- 认热点网卡

    @Test
    fun aHeldHotspotIsOpenedAheadAndKeptBetweenConnections() {
        val m = HotspotRequestMachine()
        // 无线开始：没有连接要用也先申请；没有主人时不替谁计时。
        assertEquals(listOf(Request, WakeAt(1_000 + answer)), m.hold(1_000))
        assertTrue(m.busy)
        assertNull(m.nextWakeAt)
        assertEquals(emptyList(), m.onStarted(2_000))
        assertEquals(HotspotRequestMachine.Phase.ACTIVE, m.phase)
        assertTrue(m.busy)
        // 连接直接接手开好的热点，结束时不关；下一次连接马上接手，不用等间隔。
        assertEquals(listOf<HotspotEffect>(Started(1)), m.start(1, 5_000))
        assertEquals(emptyList(), m.stop(1, 9_000))
        assertEquals(HotspotRequestMachine.Phase.ACTIVE, m.phase)
        assertEquals(listOf<HotspotEffect>(Started(2)), m.start(2, 9_500))
        m.stop(2, 12_000)
        // 无线停了才关。
        assertEquals(listOf<HotspotEffect>(Release), m.unhold(12_100))
        assertEquals(HotspotRequestMachine.Phase.IDLE, m.phase)
        assertFalse(m.busy)
    }

    @Test
    fun unholdingWhileAConnectionUsesTheHotspotClosesItWhenTheConnectionEnds() {
        val m = HotspotRequestMachine()
        m.hold(0)
        m.onStarted(500)
        m.start(1, 1_000)
        assertEquals(emptyList(), m.unhold(2_000))
        assertTrue(m.busy)
        assertEquals(listOf<HotspotEffect>(Release), m.stop(1, 3_000))
        assertFalse(m.busy)
    }

    @Test
    fun holdingRightAfterAReleaseWaitsForTheGap() {
        val m = HotspotRequestMachine()
        m.start(1, 0)
        m.onStarted(500)
        m.stop(1, 10_000)
        assertEquals(listOf<HotspotEffect>(WakeAt(10_000 + gap)), m.hold(11_000))
        assertEquals(10_000 + gap, m.nextWakeAt)
        assertEquals(listOf(Request, WakeAt(10_000 + gap + answer)), m.onTick(10_000 + gap))
        // 还没回话就不留了：回话说开好了时关掉。
        assertEquals(emptyList(), m.unhold(14_000))
        assertEquals(listOf<HotspotEffect>(Release), m.onStarted(15_000))
        assertFalse(m.busy)
    }

    @Test
    fun aConnectionArrivingWhileTheHeldRequestIsUnansweredWaitsWithItsOwnTimeout() {
        val m = HotspotRequestMachine()
        m.hold(0)
        assertEquals(listOf<HotspotEffect>(WakeAt(3_000 + answer)), m.start(1, 3_000))
        assertEquals(3_000 + answer, m.nextWakeAt)
        assertEquals(listOf<HotspotEffect>(Failed(1, HotspotFailure.NO_ANSWER)), m.onTick(3_000 + answer))
    }

    @Test
    fun aFailedHeldRequestReportsNothingAndTheNextConnectionTriesAgain() {
        val m = HotspotRequestMachine()
        m.hold(0)
        assertEquals(emptyList(), m.onFailed(1_000))
        assertEquals(HotspotRequestMachine.Phase.IDLE, m.phase)
        assertTrue(m.busy)
        assertEquals(listOf(Deferred(1, gap - 1_000), WakeAt(1_000 + gap)), m.start(1, 2_000))
    }

    @Test
    fun singleRadioCarSwapsItsWifiAddressForTheHotspotAddressOnTheSameInterface() {
        val before = mapOf("wlan0" to setOf("192.168.43.7"), "rmnet_data0" to setOf("10.0.0.2"))
        val after = mapOf("wlan0" to setOf("192.168.43.1"), "rmnet_data0" to setOf("10.0.0.2"))
        assertEquals("wlan0" to "192.168.43.1", LocalHotspotRules.pickInterface(before, after))
    }

    @Test
    fun aSeparateHotspotInterfaceWinsOverAnotherNewAddress() {
        val before = mapOf("wlan0" to setOf("192.168.1.20"))
        // 车机 WiFi 正好重新拿了地址、热点也开了：热点网卡名更像，选它。
        val after = mapOf("wlan0" to setOf("192.168.1.21"), "swlan0" to setOf("192.168.123.45"))
        assertEquals("swlan0" to "192.168.123.45", LocalHotspotRules.pickInterface(before, after))
        val withAp = mapOf("wlan0" to setOf("192.168.1.20"), "ap0" to setOf("192.168.200.1"), "wlan1" to setOf("192.168.201.1"))
        assertEquals("ap0" to "192.168.200.1", LocalHotspotRules.pickInterface(before, withAp))
    }

    @Test
    fun interfacesThatAreNeverTheHotspotAreIgnored() {
        val after = mapOf(
            "tun0" to setOf("10.8.0.2"), "rmnet0" to setOf("10.1.1.1"), "p2p-wlan0-0" to setOf("192.168.49.1"),
            "rndis0" to setOf("192.168.42.129"), "eth0" to setOf("172.16.0.5"), "lo" to setOf("127.0.0.1"),
        )
        assertNull(LocalHotspotRules.pickInterface(emptyMap(), after))
        assertNull(LocalHotspotRules.rankOf("ccmni1"))
        assertEquals(0, LocalHotspotRules.rankOf("AP0"))
        assertEquals(1, LocalHotspotRules.rankOf("wlan0"))
        assertEquals(2, LocalHotspotRules.rankOf("br0"))
    }

    @Test
    fun withoutANewAddressOnlyAnUnambiguousInterfaceIsTaken() {
        // 热点在快照之前就开着（例如本机别的应用开的）：在最像热点的网卡里找。
        val ap = mapOf("wlan0" to setOf("192.168.1.20"), "ap0" to setOf("192.168.44.1"))
        assertEquals("ap0" to "192.168.44.1", LocalHotspotRules.pickInterface(ap, ap))
        // 只有一块 wlan 网卡、一个地址：用它。
        val single = mapOf("wlan0" to setOf("192.168.43.1"))
        assertEquals("wlan0" to "192.168.43.1", LocalHotspotRules.pickInterface(single, single))
        // 两个都可能：分不出来，不猜。
        val two = mapOf("wlan0" to setOf("192.168.1.20"), "wlan1" to setOf("192.168.43.1"))
        assertNull(LocalHotspotRules.pickInterface(two, two))
        assertNull(LocalHotspotRules.pickInterface(emptyMap(), emptyMap()))
    }

    @Test
    fun onlyUsableIpv4Counts() {
        assertTrue(LocalHotspotRules.usableIpv4("192.168.43.1"))
        for (bad in listOf("0.0.0.0", "127.0.0.1", "169.254.3.4", "224.0.0.1", "255.255.255.255", "192.168.1", "1.2.3.256", "a.b.c.d", "1..2.3")) {
            assertFalse(LocalHotspotRules.usableIpv4(bad), bad)
        }
        val after = mapOf("wlan0" to setOf("169.254.10.10", "192.168.43.1"))
        assertEquals("wlan0" to "192.168.43.1", LocalHotspotRules.pickInterface(emptyMap(), after))
    }

    // ---------------------------------------------------------------- 频率

    @Test
    fun frequencyIsTakenFromAConfiguredChannelOtherwiseGuessedByBand() {
        assertEquals(
            2412 to HotspotFrequencySource.CONFIGURED,
            LocalHotspotRules.estimateFrequency(HotspotBandHint(WifiBand.GHZ_2, 1)),
        )
        assertEquals(
            5180 to HotspotFrequencySource.CONFIGURED,
            LocalHotspotRules.estimateFrequency(HotspotBandHint(WifiBand.GHZ_5, 36)),
        )
        assertEquals(
            2437 to HotspotFrequencySource.ASSUMED_BAND,
            LocalHotspotRules.estimateFrequency(HotspotBandHint(WifiBand.GHZ_2, 0)),
        )
        assertEquals(
            5745 to HotspotFrequencySource.ASSUMED_BAND,
            LocalHotspotRules.estimateFrequency(HotspotBandHint(WifiBand.GHZ_5, 0)),
        )
        // 信道和频段对不上：不信信道，按频段推测。
        assertEquals(
            2437 to HotspotFrequencySource.ASSUMED_BAND,
            LocalHotspotRules.estimateFrequency(HotspotBandHint(WifiBand.GHZ_2, 149)),
        )
        assertEquals(2437 to HotspotFrequencySource.ASSUMED_DEFAULT, LocalHotspotRules.estimateFrequency(null))
        assertEquals(2437 to HotspotFrequencySource.ASSUMED_DEFAULT, LocalHotspotRules.estimateFrequency(HotspotBandHint(null, 6)))
        assertFalse(HotspotFrequencySource.ASSUMED_DEFAULT.verified)
        assertTrue(HotspotFrequencySource.CONFIGURED.verified)
    }

    @Test
    fun bandHintsFromTheLegacyConfigFields() {
        val two = LocalHotspotRules.bandHintFromLegacy(0, 0)!!
        assertEquals(WifiBand.GHZ_2, two.band)
        assertEquals(0, two.channel)
        assertEquals(WifiBand.GHZ_5, LocalHotspotRules.bandHintFromLegacy(1, 149)!!.band)
        assertEquals(149, LocalHotspotRules.bandHintFromLegacy(1, 149)!!.channel)
        assertNull(LocalHotspotRules.bandHintFromLegacy(-1, 0)!!.band, "任选频段")
        assertNull(LocalHotspotRules.bandHintFromLegacy(null, null))
    }

    @Test
    fun bandHintsFromTheConfigDescription() {
        val newer = "ssid = \"Synthetic\" \n Passphrase = <non-empty> \n HiddenSsid = false \n Channels = {1=0} \n SecurityType = 1"
        val hint = LocalHotspotRules.bandHintFromDescription(newer)!!
        assertEquals(WifiBand.GHZ_2, hint.band)
        assertEquals(0, hint.channel)
        val pinned = LocalHotspotRules.bandHintFromDescription("Channels = {2=149, 1=6}")!!
        assertEquals(WifiBand.GHZ_5, pinned.band)
        assertEquals(149, pinned.channel)
        val older = "ssid = Synthetic \n Band = 2 \n Channel = 36 \n SecurityType = 1"
        assertEquals(5180, LocalHotspotRules.estimateFrequency(LocalHotspotRules.bandHintFromDescription(older)).first)
        // 几个频段任选、6 GHz：当作不知道频段。
        assertNull(LocalHotspotRules.bandHintFromDescription("Channels = {3=0}")!!.band)
        assertNull(LocalHotspotRules.bandHintFromDescription("Channels = {4=5}")!!.band)
        assertNull(LocalHotspotRules.bandHintFromDescription("没有频段"))
        assertNull(LocalHotspotRules.bandHintFromDescription(null))
    }

    // ---------------------------------------------------------------- BSSID、加密方式、网络名

    @Test
    fun bssidPrefersTheConfigThenTheInterfaceThenAPlaceholder() {
        assertEquals("0a:1b:2c:3d:4e:5f" to HotspotMacSource.CONFIG, LocalHotspotRules.pickBssid("0A:1B:2C:3D:4E:5F", null))
        val mac = byteArrayOf(0x0a, 0x11, 0x22, 0x33, 0x44, 0x55)
        assertEquals("0a:11:22:33:44:55" to HotspotMacSource.INTERFACE, LocalHotspotRules.pickBssid(null, mac))
        assertEquals("0a:11:22:33:44:55" to HotspotMacSource.INTERFACE, LocalHotspotRules.pickBssid("02:00:00:00:00:00", mac))
        assertEquals(MacText.PLACEHOLDER to HotspotMacSource.PLACEHOLDER, LocalHotspotRules.pickBssid(null, null))
        assertEquals(MacText.PLACEHOLDER to HotspotMacSource.PLACEHOLDER, LocalHotspotRules.pickBssid("", ByteArray(6)))
    }

    @Test
    fun securityTypesThisProtocolCanUse() {
        assertEquals(HotspotSecurity.OK, LocalHotspotRules.securityOf(1))
        assertEquals(HotspotSecurity.OK, LocalHotspotRules.securityOf(2))
        assertEquals(HotspotSecurity.WPA3_ONLY, LocalHotspotRules.securityOf(3))
        for (open in listOf(0, 4, 5)) assertEquals(HotspotSecurity.NO_PASSPHRASE, LocalHotspotRules.securityOf(open))
        assertEquals(HotspotSecurity.UNKNOWN, LocalHotspotRules.securityOf(null))
        assertEquals(HotspotSecurity.UNKNOWN, LocalHotspotRules.securityOf(9))
    }

    @Test
    fun networkNamesAreUnquotedAndPlaceholdersRejected() {
        assertEquals("Synthetic_1234", LocalHotspotRules.networkName("\"Synthetic_1234\""))
        assertEquals("Synthetic_1234", LocalHotspotRules.networkName("Synthetic_1234"))
        assertNull(LocalHotspotRules.networkName("\"\""))
        assertNull(LocalHotspotRules.networkName("<unknown ssid>"))
        assertNull(LocalHotspotRules.networkName(null))
    }

    /** 能一边连 WiFi 一边开热点的车机（车主在安卓 13 车机上遇到）：车机自己 WiFi 的地址恰好在开热点时出现，不能当成热点地址。 */
    @Test
    fun stationAddressIsNeverTakenForTheHotspot() {
        val station = "192.168.1.23"
        val early = mapOf("wlan0" to setOf(station))
        assertNull(LocalHotspotRules.pickInterface(emptyMap(), early, stationIp = station), "热点网卡的地址还没出现，接着等")
        val later = early + ("ap0" to setOf("192.168.15.1"))
        assertEquals("ap0" to "192.168.15.1", LocalHotspotRules.pickInterface(emptyMap(), later, stationIp = station))
        assertEquals("ap0" to "192.168.15.1", LocalHotspotRules.pickInterface(early, later, stationIp = station))
        // 读不到车机 WiFi 的地址、前后快照一样只有 wlan0：车机 WiFi 还开着时兜底先不用，到点再用。
        assertNull(LocalHotspotRules.pickInterface(early, early, stationIp = null, wlanFallback = false))
        assertEquals("wlan0" to station, LocalHotspotRules.pickInterface(early, early, stationIp = null, wlanFallback = true))
        // 开热点时车机 WiFi 断开、热点就在 wlan0 上（比亚迪安卓 10）：照旧选 wlan0。
        assertEquals("wlan0" to "192.168.46.1", LocalHotspotRules.pickInterface(emptyMap(), mapOf("wlan0" to setOf("192.168.46.1")), stationIp = null))
    }

    @Test
    fun reportedFrequencyBeatsGuesses() {
        assertEquals(5180 to HotspotFrequencySource.REPORTED, LocalHotspotRules.estimateFrequency(HotspotBandHint(WifiBand.GHZ_2, 0), reportedMhz = 5180))
        assertEquals(2412 to HotspotFrequencySource.REPORTED, LocalHotspotRules.estimateFrequency(null, reportedMhz = 2412))
        assertEquals(2437 to HotspotFrequencySource.ASSUMED_DEFAULT, LocalHotspotRules.estimateFrequency(null, reportedMhz = 0))
        // 连接串只认 2.4 / 5 GHz：6 GHz 或不合法的值不用，照旧推测。
        assertEquals(2437 to HotspotFrequencySource.ASSUMED_DEFAULT, LocalHotspotRules.estimateFrequency(null, reportedMhz = 5955))
        assertEquals(2437 to HotspotFrequencySource.ASSUMED_DEFAULT, LocalHotspotRules.estimateFrequency(null, reportedMhz = 2413))
        assertEquals(true, LocalHotspotRules.isWifiChannelFrequency(2484))
        assertEquals(true, LocalHotspotRules.isWifiChannelFrequency(5745))
    }

    @Test
    fun reportedBssidComesAfterConfigBeforeInterface() {
        val mac = byteArrayOf(0x0A, 0x11, 0x22, 0x33, 0x44, 0x55)
        assertEquals("1a:2b:3c:4d:5e:6f" to HotspotMacSource.REPORTED, LocalHotspotRules.pickBssid(null, mac, reportedBssid = "1A:2B:3C:4D:5E:6F"))
        assertEquals("0a:1b:2c:3d:4e:5f" to HotspotMacSource.CONFIG, LocalHotspotRules.pickBssid("0a:1b:2c:3d:4e:5f", mac, reportedBssid = "1a:2b:3c:4d:5e:6f"))
        // 系统回报的是占位值：跳过，用网卡地址；都没有才用占位值。
        assertEquals("0a:11:22:33:44:55" to HotspotMacSource.INTERFACE, LocalHotspotRules.pickBssid(null, mac, reportedBssid = MacText.PLACEHOLDER))
        assertEquals(MacText.PLACEHOLDER to HotspotMacSource.PLACEHOLDER, LocalHotspotRules.pickBssid(null, null, reportedBssid = null))
        // 读不到网卡硬件地址（安卓 11 起）：用 IPv6 链路本地地址反推的；硬件地址读得到时仍用硬件地址。
        assertEquals("1a:2b:3c:4d:5e:6f" to HotspotMacSource.LINK_LOCAL, LocalHotspotRules.pickBssid(null, null, null, "1a:2b:3c:4d:5e:6f"))
        assertEquals("0a:11:22:33:44:55" to HotspotMacSource.INTERFACE, LocalHotspotRules.pickBssid(null, mac, null, "1a:2b:3c:4d:5e:6f"))
        assertEquals(MacText.PLACEHOLDER to HotspotMacSource.PLACEHOLDER, LocalHotspotRules.pickBssid(null, null, null, MacText.PLACEHOLDER))
    }
}
