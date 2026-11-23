package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.HiSightConfig
import java.math.BigDecimal

/** 手机选定的视频参数。 */
internal class VideoSelection(val width: Int, val height: Int, val fps: Int)

/** SETUP 完成后交给会话的结果。[remoteControlPort] 为 -1 表示手机没给回控端口。 */
internal class MediaSetup(
    val selection: VideoSelection,
    val videoPort: Int,
    val remoteControlPort: Int,
    val inputEvents: List<String>,
    val inputVendors: List<String>,
    val sourceDeviceType: Int?,
)

/**
 * 能力协商：按配置生成 GET_PARAMETER 应答，校验手机 SET_PARAMETER 的选择。
 *
 * 选择要么整体通过、要么什么都不改：失败的一次选择不会污染上一次的有效结果。
 */
internal class Capabilities(private val config: HiSightConfig) {
    /** 声明的通用输入事件；单指和多指共用三项触摸事件，多指没有单独的能力名。 */
    val advertisedEvents: List<String> =
        (if (config.touchInput) TOUCH_EVENTS else emptyList()) +
            (if (config.keyInput || config.mediaKeyInput) KEY_EVENTS else emptyList())

    /** 手机可以不选回的事件：只为媒体键声明的两个按键事件（见 [HiSightConfig.mediaKeyInput]）。 */
    private val optionalEvents: Set<String> =
        if (config.mediaKeyInput && !config.keyInput) KEY_EVENTS.toSet() else emptySet()

    /** 手机必须完整选回的事件。 */
    private val requiredEvents: Set<String> = advertisedEvents.toSet() - optionalEvents

    /** 成文输入单独放在 vendor 列表里，不混进 GENERIC。 */
    val advertisedVendors: List<String> = if (config.textInput) listOf(TEXT_VENDOR) else emptyList()

    var selection: VideoSelection? = null
        private set
    private var inputEvents: List<String> = emptyList()
    private var inputVendors: List<String> = emptyList()
    private var sourceDeviceType: Int? = null

    /** 按查询顺序拼应答；查询了但没实现的名字直接省略，不声称支持。 */
    fun responseBody(query: ByteArray): String {
        val supported = HashMap<String, String>()
        supported["his_version"] = "1.1"
        supported["his_video_formats"] = videoFormats()
        supported["his_feature"] = "input_feature_set=;"
        supported["his_device_type"] = "device_type ${config.deviceType};subtype 0;"
        if (advertisedEvents.isNotEmpty()) {
            var value = "input_category_list=GENERIC;generic_cap_list=${advertisedEvents.joinToString(", ")};"
            if (advertisedVendors.isNotEmpty()) value += "vendor_cap_list=${advertisedVendors.joinToString(", ")};"
            supported["his_uibc_capability"] = value
        }
        val out = StringBuilder()
        for (name in ParameterText.queryNames(query)) {
            val value = supported[name] ?: continue
            out.append(name).append(": ").append(value).append("\r\n")
        }
        return out.toString()
    }

    private fun videoFormats(): String {
        val items = ArrayList<String>()
        items += "codecs 1"
        items += "fps ${config.fps}"
        config.gop?.let { items += "gop $it" }
        config.bitrate?.let { items += "bitrate $it" }
        items += "dpi ${config.dpi}"
        items += "scr-w ${config.screenWidth}"
        items += "scr-h ${config.screenHeight}"
        items += "width ${config.videoWidth}"
        items += "height ${config.videoHeight}"
        return items.joinToString(";")
    }

    /**
     * 校验一次能力选择（不含触发命令）。没有 `his_video_formats` 时只更新输入选择。
     * 返回要写进诊断日志的说明（没有就是空表）。
     *
     * 音频、扩展媒体能力：车机不声明（声音走 DMSDP），但鸿蒙 3.0 / 4.x 的手机仍会选（2026-11 用户报告和实机），
     * 照常接受、把选的值写进说明，这条连接不按它处理。
     */
    fun select(fields: Map<String, String>): List<String> {
        val notes = ArrayList<String>(1)
        if (fields.isEmpty() ||
            fields.keys.any { it !in ParameterText.CAPABILITY_FIELDS && it !in ParameterText.METADATA_FIELDS }
        ) {
            throw MalformedMessageException("能力选择为空或含未实现字段")
        }
        fields["his_version"]?.let { if (!isAcceptedVersion(it)) throw MalformedMessageException("手机版本不是 1.0 或 1.1") }
        fields["his_audio_codecs"]?.let {
            if (it.lowercase() != "none") {
                val shown = it.filter { c -> c in ' '..'~' }.take(AUDIO_NOTE_MAX)
                notes += "手机选了投屏通道上的音频「$shown」（车机没声明），照常继续；这条连接不从投屏通道收声音"
            }
        }

        var events = inputEvents
        var vendors = inputVendors
        val uibc = fields["his_uibc_capability"]
        if (advertisedEvents.isNotEmpty()) {
            // 只声明了可选的按键事件时，手机不选（不给或给 none）只是这次没有媒体键。
            val optionalOnly = requiredEvents.isEmpty() && advertisedVendors.isEmpty()
            if (uibc == null || (optionalOnly && uibc.lowercase() == "none")) {
                if (!optionalOnly) throw MalformedMessageException("手机没有选择已声明的输入能力")
                events = emptyList()
                vendors = emptyList()
            } else {
                val chosen = inputSelection(uibc, notes)
                events = chosen.first
                vendors = chosen.second
            }
        } else if (uibc != null && uibc.lowercase() != "none") {
            throw MalformedMessageException("手机选择了未声明的输入能力")
        }
        fields["his_media_capability"]?.let {
            val v = it.lowercase()
            if (v != "" && v != "none") {
                // 车机不声明扩展媒体能力，鸿蒙 3.0 的手机仍会选（2026-11 实机）：照常继续，选的值写进说明。
                val shown = it.filter { c -> c in ' '..'~' }.take(AUDIO_NOTE_MAX)
                notes += "手机选了扩展媒体能力「$shown」（车机没声明），照常继续，不按它处理"
            }
        }
        if ("his_vtp" in fields) throw MalformedMessageException("没有声明 VTP，手机却选择了它")
        fields["his_feature"]?.let {
            if (it != "input_feature_set=;") {
                // 车机声明的输入功能是空的，鸿蒙 3.0 的手机仍会列上自己的（2026-11 实机）：照常继续，不按它处理。
                val shown = it.filter { c -> c in ' '..'~' }.take(AUDIO_NOTE_MAX)
                notes += "手机选了输入功能「$shown」（车机没声明），照常继续，不按它处理"
            }
        }

        var source = sourceDeviceType
        val deviceValue = fields["his_device_type"]
        if (config.deviceType == 5 && deviceValue == null) throw MalformedMessageException("手机没有选回接收端类型 5")
        var device: Map<String, Int>? = null
        if (deviceValue != null) {
            val parsed = ParameterText.integerFields(deviceValue, DEVICE_FIELDS)
            if ((parsed["device_type"] ?: 4) != config.deviceType || (parsed["subtype"] ?: 0) != 0) {
                throw MalformedMessageException("手机选择的接收端类型与声明不符")
            }
            source = parsed["source_device_type"] ?: source
            device = parsed
        }
        // 按键包按源设备类型 7 编码（安卓键码），每次选择都必须明确给出。只声明了媒体键时不要求：
        // 没有 7 时编码器只放行基础键、修饰位为 0，这组键在两种源设备类型下字节相同。
        if (config.keyInput && (device == null || device["source_device_type"] != 7)) {
            throw MalformedMessageException("按键输入要求手机明确选择 source_device_type 7")
        }

        val formats = fields["his_video_formats"]
        if (formats == null) {
            inputEvents = events
            inputVendors = vendors
            sourceDeviceType = source
            return notes
        }
        val video = ParameterText.integerFields(formats, VIDEO_FIELDS, ECHO_ONLY_VIDEO_FIELDS)
        val width = video["width"]
        val height = video["height"]
        val fps = video["fps"]
        if (video["codecs"] != 1 || width == null || height == null || fps == null) {
            throw MalformedMessageException("手机没有明确选择 H.264、尺寸和帧率")
        }
        if (width !in 1..config.videoWidth || height !in 1..config.videoHeight || fps !in 1..config.fps) {
            throw MalformedMessageException("手机选择的尺寸或帧率超出声明范围")
        }
        selection = VideoSelection(width, height, fps)
        inputEvents = events
        inputVendors = vendors
        sourceDeviceType = source
        return notes
    }

    /**
     * SETUP 应答到达后生成媒体参数；声明了输入就必须有完整的输入选择和有效回控端口。
     * 只有可选的按键事件（媒体键）时例外：手机没给回控端口，只是这次不能发键。
     */
    fun mediaSetup(videoPort: Int, remoteControlPort: Int): MediaSetup {
        val chosen = selection ?: throw MalformedMessageException("SETUP 前缺少视频参数选择")
        var events = inputEvents
        if (advertisedEvents.isNotEmpty()) {
            val picked = inputEvents.toSet()
            val mandatory = requiredEvents.isNotEmpty() || advertisedVendors.isNotEmpty()
            if (!picked.containsAll(requiredEvents) || !advertisedEvents.containsAll(picked) ||
                inputVendors.toSet() != advertisedVendors.toSet() || (mandatory && remoteControlPort !in 1..65535)
            ) {
                throw MalformedMessageException("输入协商不完整或手机没有给回控端口")
            }
            if (remoteControlPort !in 1..65535) events = emptyList()
        }
        return MediaSetup(chosen, videoPort, remoteControlPort, events, inputVendors, sourceDeviceType)
    }

    /**
     * 解析手机的输入选择，必须与声明完全一致（顺序可以不同），返回手机给出的顺序。
     * 可选的按键事件（媒体键）例外：可以不选；只选了其中一个时当作都没选。
     */
    /**
     * 解析手机的输入选择。鸿蒙 3.0 的手机会把自己支持的事件全列上（含车机没声明的鼠标事件，触摸、按键各列两遍，
     * 2026-11 实机），所以没声明的事件和重复项丢掉、只用声明过的，并写一条说明；必须的事件仍要选上。
     */
    private fun inputSelection(value: String, notes: MutableList<String>): Pair<List<String>, List<String>> {
        val parts = value.split(";").toMutableList()
        if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        val fields = HashMap<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            val name = if (eq >= 0) part.substring(0, eq) else part
            if (eq < 0 || name !in INPUT_SELECTION_FIELDS || name in fields) {
                throw MalformedMessageException("输入选择含未知、重复或格式错误的字段")
            }
            fields[name] = part.substring(eq + 1)
        }
        val generic = fields["generic_cap_list"]
        if (fields["input_category_list"] != "GENERIC" || generic == null) {
            throw MalformedMessageException("输入选择必须是已声明的 GENERIC 类别")
        }
        val listed = if (generic.isEmpty()) emptyList() else generic.split(", ")
        val declared = listed.filter { it in advertisedEvents }
        var events = declared.distinct()
        val extra = listed.filter { it !in advertisedEvents }.distinct()
        if (extra.isNotEmpty() || events.size != declared.size) {
            val shown = extra.take(EXTRA_EVENTS_SHOWN).joinToString("、") { it.filter { c -> c in ' '..'~' }.take(EVENT_NAME_MAX) }
            notes += "手机的输入选择里有车机没声明的事件（${if (extra.isEmpty()) "无" else shown}）或重复项，只用声明过的：" +
                events.joinToString("、")
        }
        if (!events.containsAll(requiredEvents)) throw MalformedMessageException("输入选择缺少已声明的事件")
        // 可选的按键事件只选了一半：当作没选，免得按下发得出去、抬起发不出去。
        if (!events.containsAll(optionalEvents)) events = events.filter { it !in optionalEvents }
        var vendors = emptyList<String>()
        fields["vendor_cap_list"]?.let { list ->
            vendors = list.split(", ")
            if (vendors.toSet().size != vendors.size || vendors.any { it !in advertisedVendors }) {
                throw MalformedMessageException("vendor 输入选择含重复或未声明的能力")
            }
        }
        if (vendors.toSet() != advertisedVendors.toSet()) throw MalformedMessageException("vendor 输入选择不完整")
        return events to vendors
    }

    private fun isAcceptedVersion(text: String): Boolean {
        val dot = text.indexOf('.')
        val intPart = if (dot >= 0) text.substring(0, dot) else text
        val fraction = if (dot >= 0) text.substring(dot + 1) else null
        if (intPart.isEmpty() || !intPart.all { it in '0'..'9' }) return false
        if (fraction != null && (fraction.isEmpty() || !fraction.all { it in '0'..'9' })) return false
        val value = BigDecimal(text)
        return value.compareTo(BigDecimal("1.0")) == 0 || value.compareTo(BigDecimal("1.1")) == 0
    }

    companion object {
        /** 说明里最多列这么多个没声明的事件、每个最多这么多字符。 */
        private const val EXTRA_EVENTS_SHOWN = 8
        private const val EVENT_NAME_MAX = 40

        /** 说明里最多写手机选的音频值的这么多字符。 */
        private const val AUDIO_NOTE_MAX = 120

        val TOUCH_EVENTS = listOf("left_point_down", "left_point_up", "left_move")
        val KEY_EVENTS = listOf("left_key_down", "left_key_up")
        const val TEXT_VENDOR = "input_content"
        private val VIDEO_FIELDS = setOf(
            "codecs", "fps", "gop", "bitrate", "vbr-min", "vbr-max", "dpi",
            "scr-w", "scr-h", "width", "height", "color-standard",
        )
        /** 手机回显、本端不用的视频字段（GOP、码率）：格式不合规只忽略，不让协商因此失败。 */
        private val ECHO_ONLY_VIDEO_FIELDS = setOf("gop", "bitrate")
        private val DEVICE_FIELDS = setOf("device_type", "subtype", "source_device_type", "source_subtype")
        private val INPUT_SELECTION_FIELDS = setOf("input_category_list", "generic_cap_list", "vendor_cap_list")
    }
}

/** SETUP 应答 Transport 里的端口。 */
internal class TransportPorts(val serverPort: Int, val remoteControlPort: Int)

/**
 * 解析 SETUP 应答的 Transport：前缀只接受 `RTP/AVP/UDP` 或 `RTP/AVP/TCP`；字段只认
 * server_port、remotectl_port、rtcp_port、client_port 和 unicast。rtcp_port、remotectl_port
 * 可以是 -1（不启用）；server_port 必须是有效端口。
 */
internal fun parseTransport(value: String): TransportPorts {
    val parts = value.split(";")
    if (parts[0] != "RTP/AVP/UDP" && parts[0] != "RTP/AVP/TCP") {
        throw MalformedMessageException("SETUP 应答的 Transport 前缀不符")
    }
    val values = HashMap<String, Int>()
    for (i in 1 until parts.size) {
        val part = parts[i]
        if (part == "unicast") continue
        val eq = part.indexOf('=')
        val name = if (eq >= 0) part.substring(0, eq) else part
        if (eq < 0 || name !in TRANSPORT_FIELDS || name in values) {
            throw MalformedMessageException("SETUP 应答的 Transport 含未知或重复字段")
        }
        val text = part.substring(eq + 1)
        values[name] = if (text == "-1" && (name == "rtcp_port" || name == "remotectl_port")) {
            -1
        } else {
            val n = RtspParser.decimal(text, 5)
            if (n < 0 || n > 65535) throw MalformedMessageException("SETUP 应答的 Transport 端口不是有效整数")
            n.toInt()
        }
    }
    val server = values["server_port"] ?: 0
    if (server !in 1..65535) throw MalformedMessageException("SETUP 应答缺少有效的视频端口")
    val remote = values["remotectl_port"] ?: -1
    if (remote != -1 && remote !in 1..65535) throw MalformedMessageException("SETUP 应答的回控端口必须是 -1 或有效端口")
    return TransportPorts(server, remote)
}

private val TRANSPORT_FIELDS = setOf("server_port", "remotectl_port", "rtcp_port", "client_port")
