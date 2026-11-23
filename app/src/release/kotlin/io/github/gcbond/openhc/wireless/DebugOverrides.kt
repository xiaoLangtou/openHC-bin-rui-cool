package io.github.gcbond.openhc.wireless

/** 正式包：试验开关恒为关（调试构建里的同名对象才读系统属性）。 */
internal object DebugOverrides {
    const val forceRfcommSdp: Boolean = false
}
