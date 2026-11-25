package io.github.gcbond.openhc.core.dmsdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 原厂吉利 HiCar 对齐（需求文档模块 A/B）：管理通道 502/519 的自动应答、503/504/505 的结构化事件、
 * 501/506 的主动发送。报文样本来自原厂 HiCarService2 / HiCarDataProcessor 反汇编。
 */
class CarIntegrationMessagesTest {

    private val brandIcon = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun config(
        brandIcon: ByteArray? = null,
        voicePromptSupport: Int = 0,
        speaker: DmsdpConfig.Speaker? = null,
    ): DmsdpConfig = DmsdpConfig(
        host = Synthetic.host(),
        deviceId = Synthetic.SELF_ID,
        bluetoothMac = Synthetic.MAC,
        localIp = Synthetic.LOCAL_IP,
        phoneIp = Synthetic.PHONE_IP,
        brandIcon = brandIcon,
        voicePromptSupport = voicePromptSupport,
        speaker = speaker,
    )

    /** 已加密的会话；喂一条 module 1 管理消息，返回输出。 */
    private fun feedManagement(
        type: Int,
        json: String,
        config: DmsdpConfig,
    ): List<DmsdpOutput> {
        val (session, _) = encryptedSession(config)
        return session.feed(DmsdpChannel.CONTROL, Phone().encryptedRecord(1L, type, json.toByteArray()), 100_500L)
    }

    private fun sentRecords(outputs: List<DmsdpOutput>, type: Int): List<ByteArray> =
        outputs.filterIsInstance<DmsdpEvent.RawRecord>()
            .filter { it.sent && it.module == 1L && it.type == type }
            .map { it.data }

    @Test
    fun voicePrompt519IsAnsweredWithConfiguredSupport() {
        val outputs = feedManagement(519, """{"isVoicePromptSupport":1}""", config(voicePromptSupport = 0))
        // 应答同类型号 519，正文 {"isVoicePromptSupport":0}（文档约定 0 = 手机侧播提示音）。
        val replies = sentRecords(outputs, 519)
        assertEquals(1, replies.size, "519 应答应恰好一条: $outputs")
        assertEquals("""{"isVoicePromptSupport":0}""", String(replies[0]))
        assertTrue(outputs.filterIsInstance<DmsdpEvent.ManagementParsed>().any { it.type == 519 })

        // 配置成原厂实测的 2 时回 2。
        val custom = feedManagement(519, """{"isVoicePromptSupport":1}""", config(voicePromptSupport = 2))
        assertEquals("""{"isVoicePromptSupport":2}""", String(sentRecords(custom, 519).single()))
    }

    @Test
    fun brandIcon502IsAnsweredWithRawPngBytesWhenConfigured() {
        // 配了图标：应答同类型号 502，正文是原始 PNG 字节（不是 JSON）。
        val outputs = feedManagement(502, """{"brandIconData":"PNG"}""", config(brandIcon = brandIcon))
        val replies = sentRecords(outputs, 502)
        assertEquals(1, replies.size, "502 应答应恰好一条: $outputs")
        assertTrue(replies[0].contentEquals(brandIcon), "应答字节应与配置的图标一致")

        // 没配图标：只记诊断事件，不回。
        val without = feedManagement(502, """{"brandIconData":"PNG"}""", config())
        assertTrue(sentRecords(without, 502).isEmpty())
        assertTrue(without.filterIsInstance<DmsdpEvent.ManagementParsed>().any { it.type == 502 })
    }

    @Test
    fun navFocus503HiCarEmitsStructuredEvent() {
        val outputs = feedManagement(503, """{"navFocus":"HICAR","navPackageName":"com.autonavi.minimap"}""", config())
        val events = outputs.filterIsInstance<DmsdpEvent>()
        assertTrue(events.any { it is DmsdpEvent.NaviFocusRequested }, "navFocus=HICAR 应给事件: $events")

        // HICAR_STOP 不触发。
        val stop = feedManagement(503, """{"navFocus":"HICAR_STOP"}""", config())
        assertTrue(stop.filterIsInstance<DmsdpEvent.NaviFocusRequested>().isEmpty())
    }

    @Test
    fun callState504AndVoiceState505EmitStructuredEvents() {
        val call = feedManagement(504, """{"callState":2}""", config())
        assertEquals(2, call.filterIsInstance<DmsdpEvent.PhoneCallStateChanged>().single().state)

        val idle = feedManagement(504, """{"callState":0}""", config())
        assertEquals(0, idle.filterIsInstance<DmsdpEvent.PhoneCallStateChanged>().single().state)

        val picking = feedManagement(505, """{"voiceState":1}""", config())
        assertEquals(1, picking.filterIsInstance<DmsdpEvent.PhoneVoiceStateChanged>().single().state)

        val done = feedManagement(505, """{"voiceState":0}""", config())
        assertEquals(0, done.filterIsInstance<DmsdpEvent.PhoneVoiceStateChanged>().single().state)
    }

    @Test
    fun callAndVoiceStatesEmitEventsEvenWhenSpeakerIsEnabled() {
        // P0 修复回归：实车声明扬声器时，504/505 在扬声器分支被拦截处理，但 app 层的通话保护/防抢麦
        // 依赖结构化事件——扬声器处理完之后仍必须补发（module 1 与 module 4 上都验证）。
        val config = config(speaker = DmsdpConfig.Speaker())
        val call = feedManagement(504, """{"callState":1}""", config)
        assertEquals(1, call.filterIsInstance<DmsdpEvent.PhoneCallStateChanged>().single().state, "module 1 的 504")
        val voice = feedManagement(505, """{"voiceState":1}""", config)
        assertEquals(1, voice.filterIsInstance<DmsdpEvent.PhoneVoiceStateChanged>().single().state, "module 1 的 505")

        val (session, _) = encryptedSession(config)
        val callOnAudio = session.feed(
            DmsdpChannel.CONTROL,
            Phone().encryptedRecord(4L, 504, """{"callState":2}""".toByteArray()),
            100_700L,
        )
        assertEquals(2, callOnAudio.filterIsInstance<DmsdpEvent.PhoneCallStateChanged>().single().state, "module 4 的 504")
        val voiceOnAudio = session.feed(
            DmsdpChannel.CONTROL,
            Phone().encryptedRecord(4L, 505, """{"voiceState":0}""".toByteArray()),
            100_710L,
        )
        assertEquals(0, voiceOnAudio.filterIsInstance<DmsdpEvent.PhoneVoiceStateChanged>().single().state, "module 4 的 505")
    }

    @Test
    fun dayNightMode501AndDrivingMode506AreSentAsJson() {
        val (session, _) = encryptedSession(config())

        val nightOutputs = session.sendDayNightMode(true)
        val nightRecords = sentRecords(nightOutputs, ManagementMessages.DAY_NIGHT_TYPE)
        assertEquals("""{"dayNightMode":"night"}""", String(nightRecords.single()))

        val dayOutputs = session.sendDayNightMode(false)
        assertEquals("""{"dayNightMode":"day"}""", String(sentRecords(dayOutputs, ManagementMessages.DAY_NIGHT_TYPE).single()))

        val parked = session.sendDrivingMode(false)
        assertEquals("""{"drivingMode":"0"}""", String(sentRecords(parked, ManagementMessages.DRIVING_MODE_TYPE).single()))

        val driving = session.sendDrivingMode(true)
        assertEquals("""{"drivingMode":"1"}""", String(sentRecords(driving, ManagementMessages.DRIVING_MODE_TYPE).single()))
    }

    @Test
    fun outboundCarIntegrationMessagesAreSilentBeforeEncryption() {
        // 没走完加密协商（刚建会话）时 501/506 静默不发、不抛异常。
        val session = DmsdpSession(config(), Synthetic.KEY, SequenceRandom())
        assertTrue(session.sendDayNightMode(true).isEmpty())
        assertTrue(session.sendDrivingMode(false).isEmpty())
    }
}
