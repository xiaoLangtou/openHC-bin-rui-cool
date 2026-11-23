package io.github.gcbond.openhc.core.hisight

/**
 * 一个已解密的 H.264 访问单元（一帧，Annex-B 格式，带起始码）。
 *
 * **生命周期**：对象本身和 [data] 指向的缓冲区都会被复用，只在 [AccessUnitSink.onAccessUnit]
 * 回调期间有效。回调返回后下一帧会覆盖它们；要留到回调之后用（例如解码器暂时没有空闲输入缓冲），
 * 必须在回调里调用 [copyBytes] 自己保留一份。
 */
class AccessUnit internal constructor() {
    /** 存放这一帧的缓冲区，有效区间是 `[offset, offset + length)`。 */
    var data: ByteArray = ByteArray(0)
        internal set
    var offset: Int = 0
        internal set
    var length: Int = 0
        internal set

    /** 这一帧 RTP 包头里的时间戳，无符号 32 位原值。 */
    var rtpTimestamp: Long = 0
        internal set

    /** PES 头里的 PTS，90kHz 计数。协议只保留了低 32 位（最高位不传）。 */
    var pts90k: Long = 0
        internal set

    /** 这一帧第一个和最后一个 RTP 包的序号。 */
    var firstSequence: Int = 0
        internal set
    var lastSequence: Int = 0
        internal set

    /**
     * 这一帧和上一帧之间视频断过档（RTP 序号跳变）时的情况；没断档为 null。断档时中间的帧丢了，
     * 之前的参考帧可能不全，解码要从能开始解码的帧（关键帧）接上。
     */
    var gapBefore: VideoGap? = null
        internal set

    /** [pts90k] 换算成微秒，可直接作为解码器的显示时间戳。 */
    val presentationTimeUs: Long get() = pts90k * 100 / 9

    fun copyBytes(): ByteArray = data.copyOfRange(offset, offset + length)

    /**
     * 按出现顺序列出各 NAL 单元的类型（起始码后一字节的低 5 位），例如 7=SPS、8=PPS、5=IDR。
     * 每次调用都会扫描整帧，不需要时不要调用。
     */
    fun nalTypes(): IntArray {
        val types = ArrayList<Int>()
        val end = offset + length
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val startCodeEnd = when {
                    i + 3 < end && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    data[i + 2].toInt() == 1 -> i + 3
                    else -> -1
                }
                if (startCodeEnd > 0) {
                    if (startCodeEnd < end) types += data[startCodeEnd].toInt() and 0x1F
                    i = startCodeEnd
                    continue
                }
            }
            i++
        }
        return types.toIntArray()
    }
}

/**
 * 两帧之间视频断过档：RTP 序号跳变。手机发送端拥塞时会把排着队的普通帧整批丢掉、直接发关键帧，序号随之跳过
 * （2026-11-22 Mate 70 无线实测：发送队列满 → 「key frame arrive, drop all P frame」，车机这边序号断档）。
 *
 * @property expectedSequence 第一次跳变时应该收到的序号。
 * @property receivedSequence 第一次跳变时实际收到的序号。
 * @property jumps 这两帧之间跳变了几次。
 * @property packetsLost 按序号推算手机那边少发的包数（只算往前跳的，重复、倒退的包不算）。
 * @property packetsDiscarded 本机丢掉的包数：拼了一半的帧，以及等下一帧开头时收到的、在一帧中间的包。
 */
class VideoGap internal constructor(
    val expectedSequence: Int,
    val receivedSequence: Int,
    val jumps: Int,
    val packetsLost: Long,
    val packetsDiscarded: Long,
) {
    override fun toString(): String =
        "序号 $expectedSequence 处收到 $receivedSequence" + (if (jumps > 1) "（共跳变 $jumps 次）" else "") +
            "，按序号推算手机少发 $packetsLost 包，本机丢掉 $packetsDiscarded 包"
}

/** 接收视频访问单元。在调用 [HiSightReceiver.feedVideo] 的线程上同步回调。 */
fun interface AccessUnitSink {
    fun onAccessUnit(unit: AccessUnit)
}
