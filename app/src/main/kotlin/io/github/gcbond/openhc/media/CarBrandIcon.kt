package io.github.gcbond.openhc.media

import android.content.Context
import io.github.gcbond.openhc.R

/**
 * 车机品牌图标（`icon_geely.png`，吉利原厂同款资源）：手机发 502 `{"brandIconData":"PNG"}` 请求时，
 * 把这份 PNG 字节原样回给手机，HiCar 桌面的「车辆」磁贴显示成吉利车标（原厂 HiCarService2 L987-L995）。
 *
 * 读一次缓存（几 KB 的小图，整条连接共用）；资源缺失或读失败返回 null（502 请求就不回，不影响连接）。
 */
internal object CarBrandIcon {
    @Volatile
    private var cached: ByteArray? = null

    /** 品牌图标 PNG 字节；读不到时为 null。 */
    fun bytes(context: Context): ByteArray? {
        cached?.let { return it }
        val app = context.applicationContext ?: context
        return runCatching {
            app.resources.openRawResource(R.raw.icon_geely).use { it.readBytes() }
        }.getOrNull()?.also { cached = it }
    }
}
