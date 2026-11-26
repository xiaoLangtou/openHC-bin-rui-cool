package io.github.gcbond.openhc.settings

import io.github.gcbond.openhc.core.dmsdp.audio.PcmVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 按手机记 PCM 结果：什么时候退回 AAC、出过声的手机要连续两次、恢复、存取和坏数据。编号都是合成的。 */
class PcmFallbackBookTest {

    private val phoneA = "00112233aabbccdd"
    private val phoneB = "0123456789abcdef"

    @Test
    fun aPhoneThatNeverWorkedFallsBackAfterOneFailure() {
        val book = PcmFallbackBook()
        val record = book.record(phoneA, "合成手机", PcmVerdict.NO_DATA_SESSION, 1_000)
        assertTrue(record.useAac)
        assertEquals(1, record.failures)
        assertEquals(PcmVerdict.NO_DATA_SESSION.label, record.lastFailure)
        assertEquals(setOf(phoneA), book.aacPhones())
    }

    @Test
    fun setupUnansweredNeedsTwoInARow() {
        // TCP 模式下 PCM SETUP 没收到应答：单次可能是偶发超时，无论新旧手机都需连续两次才退回 AAC。
        val book = PcmFallbackBook()
        assertFalse(book.record(phoneA, "华为手机", PcmVerdict.SETUP_UNANSWERED, 1_000).useAac)
        val second = book.record(phoneA, null, PcmVerdict.SETUP_UNANSWERED, 2_000)
        assertTrue(second.useAac)
        assertEquals(2, second.failures)
        assertEquals(PcmVerdict.SETUP_UNANSWERED.label, second.lastFailure)
        assertEquals(setOf(phoneA), book.aacPhones())

        // 出过声的手机同样需要连续两次。
        book.record(phoneB, null, PcmVerdict.WORKS, 3_000)
        assertFalse(book.record(phoneB, null, PcmVerdict.SETUP_UNANSWERED, 4_000).useAac)
        assertTrue(book.record(phoneB, null, PcmVerdict.SETUP_UNANSWERED, 5_000).useAac)
    }

    @Test
    fun unplayableNeedsTwoInARowEvenForANewPhone() {
        val book = PcmFallbackBook()
        assertFalse(book.record(phoneA, null, PcmVerdict.UNPLAYABLE, 1_000).useAac, "靠我们自己判断的结论，一次不算")
        assertTrue(book.record(phoneA, null, PcmVerdict.UNPLAYABLE, 2_000).useAac)
    }

    @Test
    fun aPhoneThatWorkedNeedsTwoFailuresInARow() {
        val book = PcmFallbackBook()
        book.record(phoneA, "合成手机", PcmVerdict.WORKS, 1_000)
        assertFalse(book.record(phoneA, null, PcmVerdict.UNPLAYABLE, 2_000).useAac, "出过声的手机一次可能是偶然")
        // 中间出过一次声：连续计数清零。
        book.record(phoneA, null, PcmVerdict.WORKS, 3_000)
        assertFalse(book.record(phoneA, null, PcmVerdict.NO_DATA_SESSION, 4_000).useAac)
        val second = book.record(phoneA, null, PcmVerdict.NO_DATA_SESSION, 5_000)
        assertTrue(second.useAac, "连续两次才退回")
        assertEquals("合成手机", second.name, "没报名字时沿用之前的")
        assertTrue(second.pcmWorked)
        // 退回以后再收到「能出声」也不自己改回去（那时声明的已经是 AAC，不会再有 PCM 的结论），只能用户恢复。
        assertTrue(book.record(phoneA, null, PcmVerdict.WORKS, 6_000).useAac)
    }

    @Test
    fun forgettingRestoresPcmForThatPhoneOnly() {
        val book = PcmFallbackBook()
        book.record(phoneA, null, PcmVerdict.NO_DATA_SESSION, 1_000)
        book.record(phoneB, null, PcmVerdict.NO_DATA_SESSION, 2_000)
        book.forget(phoneA)
        assertEquals(setOf(phoneB), book.aacPhones())
        assertEquals(listOf(phoneB), book.records.map { it.key })
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val book = PcmFallbackBook()
        book.record(phoneA, "合成手机 \"引号\"", PcmVerdict.WORKS, 1_000)
        book.record(phoneB, null, PcmVerdict.UNPLAYABLE, 1_500)
        book.record(phoneB, null, PcmVerdict.UNPLAYABLE, 2_000)
        val back = PcmFallbackBook.decode(book.encode())
        assertEquals(listOf(phoneB, phoneA), back.records.map { it.key }, "按最近改动排在前面")
        val a = back.records.single { it.key == phoneA }
        assertEquals("合成手机 \"引号\"", a.name)
        assertTrue(a.pcmWorked)
        assertFalse(a.useAac)
        assertNull(a.lastFailure)
        val b = back.records.single { it.key == phoneB }
        assertNull(b.name)
        assertTrue(b.useAac)
        assertEquals(2, b.failures)
        assertEquals(2_000, b.updatedAtMillis)
        assertEquals(PcmVerdict.UNPLAYABLE.label, b.lastFailure)
    }

    @Test
    fun brokenOrForeignContentIsTreatedAsEmpty() {
        for (bad in listOf(null, "", "not json", "{}", "[1,2]", "[{\"key\":\"BAD\",\"useAac\":true}]")) {
            assertTrue(PcmFallbackBook.decode(bad).aacPhones().isEmpty(), "「$bad」")
        }
        val partial = PcmFallbackBook.decode("[{\"key\":\"$phoneA\",\"useAac\":true},{\"key\":7}]")
        assertEquals(setOf(phoneA), partial.aacPhones(), "认得出的照用，认不出的跳过")
    }

    @Test
    fun onlyTheMostRecentPhonesAreKept() {
        val book = PcmFallbackBook()
        repeat(PcmFallbackBook.MAX_PHONES + 3) { i ->
            book.record("%016x".format(i), null, PcmVerdict.NO_DATA_SESSION, 1_000L + i)
        }
        assertEquals(PcmFallbackBook.MAX_PHONES, book.records.size)
        assertFalse("%016x".format(0) in book.aacPhones(), "最久没动的先丢")
        assertTrue("%016x".format(PcmFallbackBook.MAX_PHONES + 2) in book.aacPhones())
    }
}
