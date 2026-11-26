package io.github.gcbond.openhc.settings

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.JsonCodec
import io.github.gcbond.openhc.core.dmsdp.audio.PcmVerdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 一台手机在 PCM 声明下的表现（按手机编号记，见 [io.github.gcbond.openhc.core.dmsdp.audio.PhoneRef]）。
 *
 * @property name 手机自报的名字，只在设置页显示。
 * @property pcmWorked 这台手机在 PCM 下正常出过声。
 * @property failures 连续「不出声」的次数（出过一次声就清零）。
 * @property useAac 下次连接改声明 AAC。
 * @property updatedAtMillis 最近一次改动的墙上时间。
 * @property lastFailure 最近一次「不出声」的原因（一句话）。
 */
class PcmPhoneRecord(
    val key: String,
    val name: String?,
    val pcmWorked: Boolean,
    val failures: Int,
    val useAac: Boolean,
    val updatedAtMillis: Long,
    val lastFailure: String?,
)

/**
 * 按手机记住「PCM 能不能出声」，决定下次连接要不要退回 AAC（纯逻辑，不引用安卓类）。
 *
 * 规则（宁可多退回 AAC，也不让一台本来能用的手机因为一次偶然变成没声音；AAC 是验证过的路）：
 * - 收到「能出声」：记下出过声，连续失败清零；
 * - 收到「不出声」：连续失败加一。没在 PCM 下出过声的手机，「想出声却一直没开数据会话」一次就退回 AAC；
 *   「载荷不像声音」和「手机不应答 PCM 的 SETUP」（单次可能是偶发超时）都要连续两次；出过声的手机不论哪种都要连续两次；
 * - 退回之后这台手机一直声明 AAC，直到用户在设置里恢复（不再自动试 PCM）；
 * - 最多记 [MAX_PHONES] 台，多了丢最久没动的。
 */
class PcmFallbackBook(records: Collection<PcmPhoneRecord> = emptyList()) {
    private val byKey = LinkedHashMap<String, PcmPhoneRecord>()

    init {
        for (record in records) byKey[record.key] = record
    }

    val records: List<PcmPhoneRecord> get() = byKey.values.sortedByDescending { it.updatedAtMillis }

    /** 要退回 AAC 的手机编号。 */
    fun aacPhones(): Set<String> = byKey.values.filter { it.useAac }.map { it.key }.toSet()

    /** 记一次判定，返回这台手机更新后的记录。 */
    fun record(key: String, name: String?, verdict: PcmVerdict, nowMillis: Long): PcmPhoneRecord {
        val old = byKey[key]
        val updated = if (verdict.failure) {
            val failures = (old?.failures ?: 0) + 1
            val worked = old?.pcmWorked ?: false
            PcmPhoneRecord(
                key = key,
                name = name ?: old?.name,
                pcmWorked = worked,
                failures = failures,
                useAac = (old?.useAac ?: false) || failures >= threshold(worked, verdict),
                updatedAtMillis = nowMillis,
                lastFailure = verdict.label,
            )
        } else {
            PcmPhoneRecord(
                key = key,
                name = name ?: old?.name,
                pcmWorked = true,
                failures = 0,
                useAac = old?.useAac ?: false,
                updatedAtMillis = nowMillis,
                lastFailure = old?.lastFailure,
            )
        }
        byKey[key] = updated
        while (byKey.size > MAX_PHONES) {
            byKey.remove(byKey.values.minByOrNull { it.updatedAtMillis }!!.key)
        }
        return updated
    }

    /** 用户在设置里恢复：忘掉这台手机，下次连接重新按 PCM 声明。 */
    fun forget(key: String) {
        byKey.remove(key)
    }

    /** 存成一段 JSON 文本。 */
    fun encode(): String = String(
        JsonCodec.encodeToUtf8(
            buildJsonArray {
                for (r in byKey.values) {
                    add(
                        buildJsonObject {
                            put("key", r.key)
                            r.name?.let { put("name", it) }
                            put("pcmWorked", r.pcmWorked)
                            put("failures", r.failures)
                            put("useAac", r.useAac)
                            put("updatedAt", r.updatedAtMillis)
                            r.lastFailure?.let { put("lastFailure", it) }
                        },
                    )
                }
            },
        ),
        Charsets.UTF_8,
    )

    companion object {
        const val MAX_PHONES = 20

        /** 出过声的手机、或「载荷不像声音」这种靠我们自己判断的结论，要连续这么多次才退回 AAC。 */
        const val CAUTIOUS_FAILURES = 2

        private fun threshold(worked: Boolean, verdict: PcmVerdict): Int =
            if (worked || verdict == PcmVerdict.UNPLAYABLE || verdict == PcmVerdict.SETUP_UNANSWERED) CAUTIOUS_FAILURES else 1

        /** 读回 [encode] 的内容；内容坏了或不认识就当空的（最多是这些手机重新试一次 PCM）。 */
        fun decode(text: String?): PcmFallbackBook {
            if (text.isNullOrEmpty()) return PcmFallbackBook()
            val array = try {
                JsonCodec.parse(text, "PCM 退回记录") as? JsonArray
            } catch (e: MalformedMessageException) {
                null
            } ?: return PcmFallbackBook()
            val records = array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                fun string(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val key = string("key")?.takeIf { KEY.matches(it) } ?: return@mapNotNull null
                PcmPhoneRecord(
                    key = key,
                    name = string("name"),
                    pcmWorked = (o["pcmWorked"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    failures = (o["failures"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
                    useAac = (o["useAac"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    updatedAtMillis = (o["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    lastFailure = string("lastFailure"),
                )
            }
            return PcmFallbackBook(records)
        }

        private val KEY = Regex("[0-9a-f]{16}")
    }
}
