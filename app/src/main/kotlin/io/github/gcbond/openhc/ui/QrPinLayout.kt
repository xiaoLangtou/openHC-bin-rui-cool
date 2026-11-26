package io.github.gcbond.openhc.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 首页等扫码时二维码那块的尺寸，单位 dp。[pinBox] 为 0 表示放不下、不显示连接码。
 *
 * @property qr 二维码边长。
 * @property pinBox 连接码每个方框的边长。
 * @property pinGap 方框左右各留的间距。
 * @property pinTop 二维码底到连接码顶的距离。
 */
internal data class QrPinSize(val qr: Float, val pinBox: Float, val pinGap: Float, val pinTop: Float)

/**
 * 按可用的宽高算二维码和连接码画多大。纯逻辑，有单元测试。
 *
 * 规则：二维码有个看着舒服的下限，空间够时先保住它，剩下的才给连接码；再挤就把连接码缩到最小，
 * 还放不下就不显示连接码（连接码已经在二维码里，扫码不用手输），二维码永远完整。二维码有上限，
 * 太大会把连接码挤掉。连接码一整行不能比可用宽度还宽，放不下时左右间距按方框大小等比缩。
 */
internal object QrPinLayout {
    const val MAX_QR = 260f
    const val MIN_QR = 110f
    const val FLOOR_QR = 72f
    const val PIN_BOX = 100f
    const val PIN_BOX_COMPACT = 56f
    const val MIN_PIN = 32f
    const val PIN_GAP = 16f
    const val PIN_GAP_RATIO = 0.16f
    const val PIN_TOP = 47f
    const val PIN_TOP_COMPACT = 12f

    /** 二维码上下各留的空。 */
    const val QR_MARGIN = 8f

    /**
     * 二维码 + 连接码一起放进 [width] × [height]。[digits] 为 0 表示没有连接码，二维码独占。
     * 高度不受限时传一个很大的数，二维码按上限和宽度取。
     */
    fun plan(width: Float, height: Float, digits: Int, compact: Boolean): QrPinSize {
        val w = width.coerceAtLeast(0f)
        val h = height.coerceAtLeast(0f)
        val margins = QR_MARGIN * 2
        val pinTop = if (compact) PIN_TOP_COMPACT else PIN_TOP
        val target = if (digits <= 0) 0f else min(if (compact) PIN_BOX_COMPACT else PIN_BOX, rowLimit(w, digits))

        var pin = 0f
        var qr: Float
        if (target < MIN_PIN) {
            // 一行放不下连接码：不显示，二维码用全部空间
            qr = h - margins
        } else if (h - pinTop - MIN_PIN - margins >= MIN_QR) {
            // 空间够：先保二维码的舒适下限，剩下的才给连接码
            val roomForPin = h - MIN_QR - margins - pinTop
            pin = max(MIN_PIN, min(target, roomForPin))
            qr = h - pinTop - pin - margins
        } else {
            // 矮：连接码缩到最小，二维码拿剩下的；剩下的连保底都不到，就不显示连接码
            pin = MIN_PIN
            qr = h - pinTop - MIN_PIN - margins
            if (qr < FLOOR_QR) {
                pin = 0f
                qr = h - margins
            }
        }
        qr = min(MAX_QR, min(qr, w)).coerceAtLeast(1f)
        if (pin <= 0f) return QrPinSize(qr, 0f, 0f, 0f)
        return QrPinSize(qr, pin, gapFor(pin, w, digits), pinTop)
    }

    /**
     * 只有连接码、没有二维码时（有线配对、无线手机已连上蓝牙还没认证完）：方框按宽度取，不超过 [maxBox]。
     * 返回 (方框边长, 左右间距)。
     */
    fun pinRow(width: Float, digits: Int, maxBox: Float): Pair<Float, Float> {
        val w = width.coerceAtLeast(0f)
        val box = min(maxBox, rowLimit(w, digits)).coerceAtLeast(1f)
        return box to gapFor(box, w, digits)
    }

    /** 一行 [digits] 个方框（每个左右各留 [PIN_GAP_RATIO] 倍边长）放进 [width] 时，方框最大能多大。 */
    private fun rowLimit(width: Float, digits: Int): Float = width / (max(1, digits) * (1f + 2f * PIN_GAP_RATIO))

    /** 一行放得下就用固定间距，放不下按方框边长等比缩。 */
    private fun gapFor(box: Float, width: Float, digits: Int): Float =
        if (max(1, digits) * (box + 2 * PIN_GAP) <= width) PIN_GAP else box * PIN_GAP_RATIO
}
