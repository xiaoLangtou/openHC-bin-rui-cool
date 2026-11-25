package io.github.gcbond.openhc.core.hisight.control

import io.github.gcbond.openhc.core.common.MalformedMessageException

/**
 * GET_PARAMETER / SET_PARAMETER 的正文格式：每行以 CRLF 结束；GET 每行一个查询名，
 * SET 每行 `名字: 值`。末尾允许多余的空行，中间不允许空行。
 */
internal object ParameterText {
    /** SET 里能出现的参数名：能力选择、只识别不使用的附加信息、触发命令。 */
    val CAPABILITY_FIELDS = setOf(
        "his_version", "his_video_formats", "his_audio_codecs", "his_uibc_capability",
        "his_vtp", "his_feature", "his_device_type", "his_media_capability",
    )

    /** 手机选择时会附带的元数据：识别但不保存、不启用对应服务。 */
    val METADATA_FIELDS = setOf(
        "his_audio_formats", "his_support_uwb", "his_support_temp_notify",
        "his_presentation_url", "his_extended_field", "his_player_controller_capability",
    )

    const val TRIGGER = "his_trigger_method"

    fun lines(body: ByteArray): List<String> {
        if (body.isEmpty()) return emptyList()
        for (b in body) {
            val v = b.toInt() and 0xFF
            if (v >= 0x80) throw MalformedMessageException("参数正文含非 ASCII 字节")
            if ((v < 0x20 && v != 0x09 && v != 0x0D && v != 0x0A) || v == 0x7F) {
                throw MalformedMessageException("参数正文含控制字符")
            }
        }
        val text = String(body, Charsets.US_ASCII)
        if (!text.endsWith("\r\n")) throw MalformedMessageException("参数行必须以 CRLF 结束")
        val lines = text.substring(0, text.length - 2).split("\r\n").toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        if (lines.any { it.isEmpty() || '\r' in it || '\n' in it }) {
            throw MalformedMessageException("参数正文含空的中间行或单独的 CR/LF")
        }
        return lines
    }

    /** GET 的查询名，行尾可带一个冒号；名字是小写字母开头的 `[a-z0-9_]`，不得重复。 */
    fun queryNames(body: ByteArray): List<String> {
        val names = ArrayList<String>()
        for (line in lines(body)) {
            val name = if (line.endsWith(":")) line.substring(0, line.length - 1) else line
            val valid = name.isNotEmpty() && name[0] in 'a'..'z' &&
                name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            if (!valid || name in names) throw MalformedMessageException("能力查询名格式错误或重复")
            names += name
        }
        return names
    }

    /**
     * SET 的键值；名字转小写，值去掉两端空格和 TAB。缺冒号或重复的名字拒绝。
     * 不认识的名字：[unknown] 为 null 时拒绝；给了就跳过，把「名字: 值」（值截短）记进去，例如手机投屏期间发的事件通知
     * 带 `module_id`、`event`、`param`。
     */
    fun parameters(body: ByteArray, unknown: MutableList<String>? = null): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in lines(body)) {
            val colon = line.indexOf(':')
            val name = (if (colon >= 0) line.substring(0, colon) else line).lowercase()
            val known = name in CAPABILITY_FIELDS || name in METADATA_FIELDS || name == TRIGGER
            if (colon >= 0 && !known && unknown != null && name !in result) {
                unknown += "$name: " + trimSpaceTab(line.substring(colon + 1)).filter { it in ' '..'~' }.take(UNKNOWN_VALUE_SHOWN)
                continue
            }
            if (colon < 0 || !known || name in result) {
                throw MalformedMessageException("能力参数缺少冒号、名字未知或重复")
            }
            result[name] = trimSpaceTab(line.substring(colon + 1))
        }
        return result
    }

    /** 不认识的参数记说明时，值最多留这么多字符。 */
    private const val UNKNOWN_VALUE_SHOWN = 80

    /**
     * 分号分隔的 `名字 整数` 列表（末尾分号可有可无）；整数是 0..2^31-1 的十进制。
     * [lenient] 里的名字是对端回显、本端不使用的字段：值不合规或重复时跳过，不让整段解析失败。
     */
    fun integerFields(value: String, known: Set<String>, lenient: Set<String> = emptySet()): Map<String, Int> {
        val parts = value.split(";").toMutableList()
        if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        val result = LinkedHashMap<String, Int>()
        for (part in parts) {
            val tokens = trimSpaceTab(part).split(' ', '\t').filter { it.isNotEmpty() }
            val name = tokens.firstOrNull()
            if (name != null && name in lenient) {
                val number = if (tokens.size == 2) RtspParser.decimal(tokens[1], 10) else -1L
                if (number in 0..Int.MAX_VALUE.toLong() && name !in result) result[name] = number.toInt()
                continue
            }
            if (tokens.size != 2 || tokens[0] !in known || tokens[0] in result) {
                throw MalformedMessageException("参数子字段未知、重复或格式错误")
            }
            val number = RtspParser.decimal(tokens[1], 10)
            if (number < 0 || number > Int.MAX_VALUE) throw MalformedMessageException("参数子字段不是有效的非负整数")
            result[tokens[0]] = number.toInt()
        }
        return result
    }

    fun trimSpaceTab(s: String): String {
        var from = 0
        var to = s.length
        while (from < to && (s[from] == ' ' || s[from] == '\t')) from++
        while (to > from && (s[to - 1] == ' ' || s[to - 1] == '\t')) to--
        return s.substring(from, to)
    }
}
