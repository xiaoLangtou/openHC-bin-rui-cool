package io.github.gcbond.openhc.core.dmsdp.audio

/**
 * 加密控制连接上送来的声音（不走音频数据连接）：鸿蒙 3.0 / 4.x 的手机建不起音频数据会话时，整段声音都这样送；
 * 纯血鸿蒙在数据连接连上之前也会先送一点。
 *
 * 一片的格式（多字节整数大端）：
 * - 0～7：前缀，不用；
 * - 8：这一组分几片（1～[MAX_FRAGMENTS]）；9：第几片（从 1 起）；
 * - 10～13：组号；14～15：片序号（从 0 起，等于「第几片 − 1」）；
 * - 16～17：本片数据长度，等于整片长度 − [HEADER]；之后是数据。
 *
 * 同一组的各片按片序号依次拼起来是一组：开头 [GROUP_HEADER] 字节是组头，组头最后两字节等于整组长度，之后是 PCM
 * （16 位小端交错，采样参数和手机在声音请求里写的一样，2026-11 实机为 48 kHz 双声道、一组 1024 帧）。
 *
 * 结构对不上的载荷不认（[feed] 返回 false），交给调用方按别的消息处理；认得出但拼不成一组的（缺片、乱序、组头不对）丢掉。
 * 纯逻辑，非线程安全。
 */
internal class ControlChannelAudio {
    private var sequence = -1L
    private var expected = 0
    private var count = 0
    private val buffer = java.io.ByteArrayOutputStream()

    /** 认出过的片数、拼成的组数、丢掉的组数（诊断用）。 */
    var fragments = 0L
        private set
    var groups = 0L
        private set
    var dropped = 0L
        private set

    /**
     * 喂入一条控制载荷。是这种声音片就返回 true；拼成一组时先调 [onPcm]（数据只在回调期间有效）。
     */
    fun feed(payload: ByteArray, onPcm: (data: ByteArray, offset: Int, length: Int) -> Unit): Boolean {
        if (payload.size < HEADER) return false
        val total = payload[8].toInt() and 0xFF
        val number = payload[9].toInt() and 0xFF
        val index = u16(payload, 14)
        val length = u16(payload, 16)
        if (total !in 1..MAX_FRAGMENTS || number !in 1..total || index != number - 1 || length != payload.size - HEADER) return false
        fragments++
        val seq = u32(payload, 10)
        if (index == 0) {
            if (count in 1 until expected) dropped++
            start(seq, total)
        } else if (seq != sequence || total != expected || index != count) {
            if (count > 0) dropped++
            reset()
            return true
        }
        buffer.write(payload, HEADER, length)
        count++
        if (count < expected) return true
        val group = buffer.toByteArray()
        reset()
        if (group.size < GROUP_HEADER || u16(group, GROUP_HEADER - 2) != group.size) {
            dropped++
            return true
        }
        val pcm = group.size - GROUP_HEADER
        if (pcm <= 0 || pcm % 2 != 0) {
            dropped++
            return true
        }
        groups++
        onPcm(group, GROUP_HEADER, pcm)
        return true
    }

    private fun start(seq: Long, total: Int) {
        sequence = seq
        expected = total
        count = 0
        buffer.reset()
    }

    private fun reset() {
        sequence = -1
        expected = 0
        count = 0
        buffer.reset()
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        ((u16(b, at).toLong()) shl 16) or u16(b, at + 2).toLong()

    companion object {
        /** 每片的头长度。 */
        const val HEADER = 18

        /** 拼好的一组开头的组头长度。 */
        const val GROUP_HEADER = 14

        /** 一组最多几片（实机是 3）。 */
        const val MAX_FRAGMENTS = 8
    }
}
