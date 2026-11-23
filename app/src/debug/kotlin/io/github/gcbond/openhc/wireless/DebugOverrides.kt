package io.github.gcbond.openhc.wireless

/**
 * 仅调试构建：无线连接的试验开关，用 adb 设系统属性，下次连接时生效。正式包里是同名的空实现，开关恒为关。
 */
internal object DebugOverrides {
    /**
     * `adb shell setprop debug.openhc.rfcomm_sdp 1`：车机主动回连时，认证子通道不按手机给的通道号直连，
     * 改按 SDP 查（验证拿不到直连接口的车机上那条退路）。设回 0 或清空恢复。
     */
    val forceRfcommSdp: Boolean get() = property("debug.openhc.rfcomm_sdp") == "1"

    private fun property(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (e: ReflectiveOperationException) {
        ""
    } catch (e: RuntimeException) {
        ""
    }
}
