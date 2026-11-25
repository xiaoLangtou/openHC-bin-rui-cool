package io.github.gcbond.openhc.media

import android.content.Context
import io.github.gcbond.openhc.platform.log.Log
import java.lang.reflect.Proxy

/**
 * 吉利/ECARX 车机深度联动的反射桥（原厂 HiCar 对齐，需求文档模块 B）：
 *
 * - 503 `navFocus=HICAR`：手机导航拿到焦点时，让出车机原厂导航——`ECarXInteractiveManager.requestNaviFocus()`
 *   请求互联导航焦点 + `NaviAPI.cancelNavi(callback)` 结束车机正在进行的导航（原厂 NavCtrMgr /
 *   HiCarInteractiveBridge 同款路线），避免两边同时播报。
 * - 504 `callState`：把手机通话状态写进 `Settings.Global link_incall_state`（原厂 HiCarService2 同款，
 *   供车机系统其他模块读取），连接断开复位 0。
 * - 505 `voiceState`：手机语音拾音时抑制车机本地语音（`VrVoiceAPI.enableVR(false,…)`），结束或断连恢复。
 *
 * 所有调用都是反射 + `runCatching`：车型没有对应系统库（非吉利车机、ECARX SDK 不在）时静默降级、零崩溃。
 */
internal object CarIntegrationBridge {
    private const val TAG = "OpenHC-CarIntegration"

    // 原厂类名（HiCarInteractiveBridge / NavCtrMgr / VrHandler 反汇编确认）。
    private const val INTERACTIVE_MANAGER = "com.ecarx.ecarxinteractiveapi.ECarXInteractiveManager"
    private const val NAVI_API = "com.ecarx.eas.framework.sdk.navi.NaviAPI"
    private const val NAVI_CALLBACK = "com.ecarx.sdk.navi.INaviAPICallback"
    private const val VR_VOICE_API = "com.ecarx.sdk.eas.vr.internal.voice.api.VrVoiceAPI"

    /** 503：手机导航拿到焦点，让出车机原厂导航。任何一步失败只记日志。 */
    fun yieldNativeNavi(context: Context) {
        val app = context.applicationContext ?: context
        requestNaviFocus(app)
        cancelNativeNavi(app)
    }

    /** `ECarXInteractiveManager.requestNaviFocus()`（无参方法，构造要 Context）。 */
    private fun requestNaviFocus(app: Context) {
        runCatching {
            val mgr = Class.forName(INTERACTIVE_MANAGER)
                .getConstructor(Context::class.java)
                .newInstance(app)
            mgr.javaClass.getMethod("requestNaviFocus").invoke(mgr)
            Log.i(TAG, "已请求互联导航焦点（ECarXInteractiveManager.requestNaviFocus）")
        }.onFailure {
            Log.i(TAG, "请求互联导航焦点跳过（本机无 ECarXInteractiveManager：${it.javaClass.simpleName}）")
        }
    }

    /** `NaviAPI.get(context).cancelNavi(callback)`：callback 是接口，用动态代理给一个空实现。 */
    private fun cancelNativeNavi(app: Context) {
        runCatching {
            val naviClass = Class.forName(NAVI_API)
            val navi = naviClass.getMethod("get", Context::class.java).invoke(null, app)
            val callbackClass = runCatching { Class.forName(NAVI_CALLBACK) }.getOrNull()
            val callback = callbackClass?.let { cls ->
                Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, _, _ -> null }
            }
            val method = navi.javaClass.methods.firstOrNull { it.name == "cancelNavi" && it.parameterCount == 1 }
            if (method == null) {
                Log.i(TAG, "结束原厂导航跳过（cancelNavi 方法不在）")
                return
            }
            method.invoke(navi, callback)
            Log.i(TAG, "已结束车机原厂导航（NaviAPI.cancelNavi）")
        }.onFailure {
            Log.i(TAG, "结束原厂导航跳过（本机无 NaviAPI：${it.javaClass.simpleName}）")
        }
    }

    /**
     * 504：写 `Settings.Global link_incall_state`（0 空闲 / 1 响铃 / 2 通话中）。系统签名才写得动，
     * 写不进（普通应用）不影响任何功能。
     */
    fun writeLinkIncallState(context: Context, state: Int) {
        runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "link_incall_state", state)
            Log.i(TAG, "link_incall_state=$state")
        }.onFailure {
            Log.i(TAG, "link_incall_state 写不进（${it.javaClass.simpleName}），不影响功能")
        }
    }

    /**
     * 505：抑制/恢复车机本地语音拾音（`VrVoiceAPI.get().enableVR(enable, reason, 101)`）。
     * [enable] false = 手机语音拾音中、抑制车机语音；true = 结束或断连、恢复车机语音。
     */
    fun enableVr(context: Context, enable: Boolean) {
        val app = context.applicationContext ?: context
        runCatching {
            val api = Class.forName(VR_VOICE_API).getMethod("get").invoke(null)
            val reason = if (enable) "carlink语音拾音结束打开车机语音" else "carlink语音拾音禁用车机语音"
            val method = api.javaClass.methods.firstOrNull {
                it.name == "enableVR" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (method == null) {
                Log.i(TAG, "车机语音${if (enable) "恢复" else "抑制"}跳过（enableVR(bool,String,int) 不在）")
                return
            }
            method.invoke(api, enable, reason, 101)
            Log.i(TAG, "车机本地语音已${if (enable) "恢复" else "抑制"}（VrVoiceAPI.enableVR）")
        }.onFailure {
            Log.i(TAG, "车机语音${if (enable) "恢复" else "抑制"}跳过（本机无 VrVoiceAPI：${it.javaClass.simpleName}）")
        }
    }
}
