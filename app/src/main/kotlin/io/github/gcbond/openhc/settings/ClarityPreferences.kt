package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit
import io.github.gcbond.openhc.connection.VideoClarity

/**
 * 清晰度设置（单独一个 SharedPreferences 文件）。改动在下一次连接时生效，有线、无线都用。任意线程可读写。
 */
class ClarityPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 未设置时按 [VideoClarity] 的默认档（高）。 */
    var clarity: VideoClarity
        get() = VideoClarity.parse(prefs.getString(KEY_CLARITY, null))
        set(value) = prefs.edit { putString(KEY_CLARITY, value.key) }

    private companion object {
        const val FILE = "openhc-clarity"
        const val KEY_CLARITY = "clarity"
    }
}
