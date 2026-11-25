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

    /** 手机的输入选择用不了（没选、选得不对），这次不发触控和按键。 */
    private var inputOff = false

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
        if (fields.isEmpty()) throw MalformedMessageException("能力选择为空")
        val unknown = fields.keys.filter { it !in ParameterText.CAPABILITY_FIELDS && it !in ParameterText.METADATA_FIELDS }
        if (unknown.isNotEmpty()) notes += "能力选择里有不认识的字段（${unknown.joinToString("、") { shown(it) }}），不按它们处理"
        fields["his_version"]?.let {
            if (!isAcceptedVersion(it)) notes += "手机报的投屏版本是「${shown(it)}」（车机声明 1.1），按 1.1 的流程继续"
        }
        fields["his_audio_codecs"]?.let {
            if (it.lowercase() != "none") {
                notes += "手机选了投屏通道上的音频「${shown(it)}」（车机没声明），照常继续；这条连接不从投屏通道收声音"
            }
        }

        var events = inputEvents
        var vendors = inputVendors
        var off = inputOff
        val uibc = fields["his_uibc_capability"]
        if (advertisedEvents.isNotEmpty()) {
            // 只声明了可选的按键事件时，手机不选（不给或给 none）只是这次没有媒体键。
            val optionalOnly = requiredEvents.isEmpty() && advertisedVendors.isEmpty()
            if (uibc == null || uibc.lowercase() == "none") {
                if (!optionalOnly) notes += "手机没有选择输入能力，这次不能触控"
                events = emptyList()
                vendors = emptyList()
                off = !optionalOnly
            } else {
                try {
                    val chosen = inputSelection(uibc, notes)
                    events = chosen.first
                    vendors = chosen.second
                    off = false
                } catch (e: MalformedMessageException) {
                    notes += "手机的输入选择用不了（${e.message}：${shown(uibc)}），这次不能触控"
                    events = emptyList()
                    vendors = emptyList()
                    off = true
                }
            }
        } else if (uibc != null && uibc.lowercase() != "none") {
            notes += "手机选了输入能力「${shown(uibc)}」（车机没声明），不按它处理"
        }
        fields["his_media_capability"]?.let {
            val v = it.lowercase()
            if (v != "" && v != "none") notes += "手机选了扩展媒体能力「${shown(it)}」（车机没声明），照常继续，不按它处理"
        }
        fields["his_vtp"]?.let {
            notes += "手机选了 VTP「${shown(it)}」（车机没声明、也不支持），照常按 TCP 收视频"
        }
        fields["his_feature"]?.let {
            if (it != "input_feature_set=;") notes += "手机选了输入功能「${shown(it)}」（车机没声明），照常继续，不按它处理"
        }

        var source = sourceDeviceType
        val deviceValue = fields["his_device_type"]
        if (config.deviceType == 5 && deviceValue == null) notes += "手机没有选回接收端类型 5，照常继续"
        if (deviceValue != null) {
            val parsed = lenientIntegers(deviceValue, DEVICE_FIELDS, notes, "接收端类型")
            if ((parsed["device_type"] ?: config.deviceType) != config.deviceType || (parsed["subtype"] ?: 0) != 0) {
                notes += "手机选的接收端类型「${shown(deviceValue)}」与声明不符，照常继续"
            }
            source = parsed["source_device_type"] ?: source
        }
        // 按键包按源设备类型 7 编码（安卓键码）；没有 7 时编码器只放行基础键、修饰位为 0。
        if (config.keyInput && source != 7) notes += "手机没有选源设备类型 7，按键只发基础键"

        val formats = fields["his_video_formats"]
        if (formats == null) {
            inputEvents = events
            inputVendors = vendors
            inputOff = off
            sourceDeviceType = source
            return notes
        }
        val video = lenientIntegers(formats, VIDEO_FIELDS, notes, "视频格式")
        val codecs = video["codecs"]
        if (codecs == null) notes += "手机的视频格式没写编码，按 H.264 收"
        if (codecs != null && codecs != 1) throw MalformedMessageException("手机选了 H.264 以外的视频编码（codecs $codecs），车机解不了")
        var width = video["width"]?.takeIf { it > 0 }
        var height = video["height"]?.takeIf { it > 0 }
        if (width == null || height == null) {
            notes += "手机的视频格式没写有效的尺寸，按声明的 ${config.videoWidth}x${config.videoHeight} 收"
            width = config.videoWidth
            height = config.videoHeight
        }
        var fps = video["fps"]?.takeIf { it > 0 }
        if (fps == null) {
            notes += "手机的视频格式没写有效的帧率，按声明的 ${config.fps} 收"
            fps = config.fps
        }
        if (width > config.videoWidth || height > config.videoHeight || fps > config.fps) {
            notes += "手机选的 ${width}x$height@$fps 超出声明的 ${config.videoWidth}x${config.videoHeight}@${config.fps}，照手机选的收"
        }
        selection = VideoSelection(width, height, fps)
        inputEvents = events
        inputVendors = vendors
        inputOff = off
        sourceDeviceType = source
        return notes
    }

    /** SETUP 触发时手机还没选过视频参数：按声明的尺寸和帧率收，返回要写的说明。 */
    fun selectDeclared(): String {
        selection = VideoSelection(config.videoWidth, config.videoHeight, config.fps)
        return "SETUP 前手机没有选视频参数，按声明的 ${config.videoWidth}x${config.videoHeight}@${config.fps} 收"
    }

    /**
     * SETUP 应答到达后生成媒体参数。输入能力用不了（手机没选、选得不全、没给回控端口）时不断开，
     * 这次不发触控和按键，把原因写进 [notes]。只有可选的按键事件（媒体键）时手机没给回控端口不写说明。
     */
    fun mediaSetup(videoPort: Int, remoteControlPort: Int, notes: MutableList<String>): MediaSetup {
        val chosen = selection ?: throw MalformedMessageException("SETUP 前缺少视频参数选择")
        var events = inputEvents
        var vendors = inputVendors
        if (advertisedEvents.isNotEmpty() && !inputOff) {
            val picked = inputEvents.toSet()
            val mandatory = requiredEvents.isNotEmpty() || advertisedVendors.isNotEmpty()
            if (!picked.containsAll(requiredEvents) || !advertisedEvents.containsAll(picked)) {
                notes += "手机选的输入事件不完整，这次不能触控"
                events = emptyList()
                vendors = emptyList()
            } else if (remoteControlPort !in 1..65535) {
                if (mandatory) notes += "手机没有给回控端口，这次不能触控"
                events = emptyList()
                vendors = emptyList()
            }
        }
        if (inputOff) {
            events = emptyList()
            vendors = emptyList()
        }
        return MediaSetup(chosen, videoPort, remoteControlPort, events, vendors, sourceDeviceType)
    }

    /**
     * 解析手机的输入选择。鸿蒙 3.0 的手机会把自己支持的事件全列上（含车机没声明的鼠标事件，触摸、按键各列两遍，
     * 2026-11 实机），所以没声明的事件和重复项丢掉、只用声明过的，并写一条说明；不认识的子字段忽略。
     * 必须的事件没选全、类别不是 GENERIC 时抛异常，由 [select] 改成「这次不能触控」。
     */
    private fun inputSelection(value: String, notes: MutableList<String>): Pair<List<String>, List<String>> {
        val parts = value.split(";").toMutableList()
        if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        val fields = HashMap<String, String>()
        val ignored = ArrayList<String>(0)
        for (part in parts) {
            val eq = part.indexOf('=')
            val name = ParameterText.trimSpaceTab(if (eq >= 0) part.substring(0, eq) else part)
            if (eq < 0 || name !in INPUT_SELECTION_FIELDS || name in fields) {
                ignored += part
                continue
            }
            fields[name] = part.substring(eq + 1)
        }
        if (ignored.isNotEmpty()) notes += "手机的输入选择里有不认识或重复的项（${ignored.joinToString("；") { shown(it) }}），忽略"
        val generic = fields["generic_cap_list"]
        if (fields["input_category_list"]?.let { ParameterText.trimSpaceTab(it) } != "GENERIC" || generic == null) {
            throw MalformedMessageException("不是已声明的 GENERIC 类别")
        }
        val listed = if (generic.isEmpty()) emptyList() else generic.split(", ")
        val declared = listed.filter { it in advertisedEvents }
        var events = declared.distinct()
        val extra = listed.filter { it !in advertisedEvents }.distinct()
        if (extra.isNotEmpty() || events.size != declared.size) {
            val shownExtra = extra.take(EXTRA_EVENTS_SHOWN).joinToString("、") { it.filter { c -> c in ' '..'~' }.take(EVENT_NAME_MAX) }
            notes += "手机的输入选择里有车机没声明的事件（${if (extra.isEmpty()) "无" else shownExtra}）或重复项，只用声明过的：" +
                events.joinToString("、")
        }
        if (!events.containsAll(requiredEvents)) throw MalformedMessageException("缺少已声明的事件")
        // 可选的按键事件只选了一半：当作没选，免得按下发得出去、抬起发不出去。
        if (!events.containsAll(optionalEvents)) events = events.filter { it !in optionalEvents }
        var vendors = emptyList<String>()
        fields["vendor_cap_list"]?.let { list ->
            val all = list.split(", ")
            vendors = all.filter { it in advertisedVendors }.distinct()
            if (vendors.size != all.size) notes += "手机的 vendor 输入选择有重复或没声明的项（${shown(list)}），只用声明过的"
        }
        if (vendors.toSet() != advertisedVendors.toSet()) {
            notes += "手机没有选回成文输入，这次不能直接发文字"
        }
        return events to vendors
    }

    /**
     * 分号分隔的 `名字 整数` 列表，宽松读：[known] 以外的名字、值不是非负整数的、重复的都跳过（写一条说明），
     * 只留读得出的。
     */
    private fun lenientIntegers(value: String, known: Set<String>, notes: MutableList<String>, what: String): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        val skipped = ArrayList<String>(0)
        val parts = value.split(";")
        for ((i, part) in parts.withIndex()) {
            val tokens = ParameterText.trimSpaceTab(part).split(' ', '\t').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                // 末尾的分号后面本来就是空的；中间的空项是格式不规范，记一下。
                if (i != parts.size - 1) skipped += "（空项）"
                continue
            }
            val number = if (tokens.size == 2) decimalOrNull(tokens[1]) else null
            if (tokens[0] !in known || number == null || tokens[0] in result) {
                if (tokens[0] !in ECHO_ONLY_VIDEO_FIELDS) skipped += part
                continue
            }
            result[tokens[0]] = number
        }
        if (skipped.isNotEmpty()) notes += "手机的${what}里有读不了或不用的项（${skipped.joinToString("；") { shown(it) }}），忽略"
        return result
    }

    private fun decimalOrNull(text: String): Int? {
        if (text.isEmpty() || text.length > 10 || !text.all { it in '0'..'9' }) return null
        return text.toLong().takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** 写进说明的手机原文：只留可见 ASCII，最多 [AUDIO_NOTE_MAX] 个字符。 */
    private fun shown(text: String): String = text.filter { it in ' '..'~' }.take(AUDIO_NOTE_MAX)

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
