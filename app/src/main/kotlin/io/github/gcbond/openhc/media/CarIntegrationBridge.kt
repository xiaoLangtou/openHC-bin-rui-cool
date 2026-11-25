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
 * 初始化对齐原厂 NavCtrMgr.initNaviAPI / VrHandler.initVrVoiceAPI：ECARX 的 `NaviAPI` 和 `VrVoiceAPI`
 * 必须先 `init(context, ECarXApiClient.Callback)`、等 `onAPIReady(true)` 后调用才生效。本桥在
 * [warmUp]（设备会话就绪时调）提前预热；没等 ready 就来了调用请求时先触发 init 并挂起待执行动作，
 * ready 回调里补执行。[ECarXInteractiveManager] 只 new 一次缓存单例。
 *
 * 所有调用都是反射 + `runCatching`：车型没有对应系统库（非吉利车机、ECARX SDK 不在）时静默降级、零崩溃。
 */
internal object CarIntegrationBridge {
    private const val TAG = "OpenHC-CarIntegration"

    // 原厂类名（HiCarInteractiveBridge / NavCtrMgr / VrHandler 反汇编确认）。
    private const val INTERACTIVE_MANAGER = "com.ecarx.ecarxinteractiveapi.ECarXInteractiveManager"
    private const val NAVI_API = "com.ecarx.eas.framework.sdk.navi.NaviAPI"
    private const val VR_VOICE_API = "com.ecarx.sdk.eas.vr.internal.voice.api.VrVoiceAPI"
    private const val ECARX_CALLBACK = "com.ecarx.eas.sdk.ECarXApiClient\$Callback"

    // ---- ECarXInteractiveManager 单例（构造要 Context，缓存复用，原厂 HiCarInteractiveBridge 同款）。----
    @Volatile
    private var interactiveManager: Any? = null

    // ---- NaviAPI 懒加载单例 + ready 门闩。pendingCancelNavi = ready 前来了 cancelNavi 请求。----
    private val naviLock = Any()
    private var naviApi: Any? = null
    private var naviReady = false
    private var pendingCancelNavi = false

    // ---- VrVoiceAPI 懒加载单例 + ready 门闩。pendingVrEnable = ready 前来了 enableVR 请求（null = 没有）。----
    private val vrLock = Any()
    private var vrApi: Any? = null
    private var vrReady = false
    private var pendingVrEnable: Boolean? = null

    /**
     * 提前预热（设备会话就绪时调，任意线程）：把 `NaviAPI` / `VrVoiceAPI` 的 `init(context, callback)`
     * 发出去，等 `onAPIReady(true)`。之后 503/505 到达时 API 已经就绪、立即生效。
     */
    fun warmUp(context: Context) {
        val app = context.applicationContext ?: context
        initNavi(app)
        initVr(app)
    }

    /** 503：手机导航拿到焦点，让出车机原厂导航。任何一步失败只记日志。 */
    fun yieldNativeNavi(context: Context) {
        val app = context.applicationContext ?: context
        requestNaviFocus(app)
        cancelNativeNavi(app)
    }

    /**
     * `ECarXInteractiveManager.requestNaviFocus()`（无参方法，构造要 Context）。单例缓存：
     * 只在第一次调用时构造并激活，之后复用。
     */
    private fun requestNaviFocus(app: Context) {
        runCatching {
            val mgr = synchronized(this) {
                interactiveManager ?: Class.forName(INTERACTIVE_MANAGER)
                    .getConstructor(Context::class.java)
                    .newInstance(app)
                    .also { interactiveManager = it }
            }
            val cls = mgr.javaClass
            val pkg = app.packageName
            // 激活状态顺手补一遍（首次构造后还没通知过的部分）。
            runCatching {
                cls.getMethod("notifyPhoneConnectStatuschanged", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(mgr, pkg, 1)
            }
            runCatching { cls.getMethod("requestNaviFocus").invoke(mgr) }
            Log.i(TAG, "已请求互联导航焦点（ECarXInteractiveManager.requestNaviFocus）")
        }.onFailure {
            Log.i(TAG, "请求互联导航焦点跳过（本机无 ECarXInteractiveManager：${it.javaClass.simpleName}）")
        }
    }

    /** 生成 `com.ecarx.eas.sdk.ECarXApiClient$Callback` 的动态代理，只处理 `onAPIReady(boolean)`。 */
    private fun ecarxCallback(onReady: (Boolean) -> Unit): Any {
        val cls = Class.forName(ECARX_CALLBACK)
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, method, args ->
            if (method.name == "onAPIReady" && args != null && args.size == 1 && args[0] is Boolean) {
                onReady(args[0] as Boolean)
            }
            null
        }
    }

    /**
     * 懒初始化 `NaviAPI`（原厂 NavCtrMgr.initNaviAPI 同款）：`NaviAPI.get(context)` 拿单例后
     * `init(context, callback)`，`onAPIReady(true)` 时把挂起的 cancelNavi 补执行。已就绪时是空操作。
     */
    private fun initNavi(app: Context) {
        synchronized(naviLock) {
            if (naviReady) return
            runCatching {
                val api = naviApi ?: Class.forName(NAVI_API)
                    .getMethod("get", Context::class.java).invoke(null, app)
                    .also { naviApi = it }
                val callback = ecarxCallback { ready ->
                    synchronized(naviLock) {
                        naviReady = ready
                        Log.i(TAG, "NaviAPI onAPIReady=$ready")
                        if (ready && pendingCancelNavi) {
                            pendingCancelNavi = false
                            invokeCancelNaviLocked(api)
                        }
                    }
                }
                api.javaClass.getMethod("init", Context::class.java, Class.forName(ECARX_CALLBACK))
                    .invoke(api, app, callback)
            }.onFailure {
                Log.i(TAG, "NaviAPI 初始化跳过（本机无 NaviAPI：${it.javaClass.simpleName}），503 让出导航不可用")
                naviReady = true // SDK 不存在：不再重试，避免每条 503 都白初始化一遍
            }
        }
    }

    /** 503 的 `NaviAPI.cancelNavi(callback)`：callback 是接口，用动态代理给一个空实现。没 ready 时先 init 再挂起。 */
    private fun cancelNativeNavi(app: Context) {
        val api = synchronized(naviLock) {
            if (naviReady) {
                naviApi
            } else {
                // 未 ready：挂起请求 + 触发 init，onAPIReady(true) 回调里补执行。
                pendingCancelNavi = true
                initNavi(app)
                naviApi?.takeIf { naviReady }
            }
        } ?: return
        synchronized(naviLock) { invokeCancelNaviLocked(api) }
    }

    /** 调用 `cancelNavi(callback)`；须持有 naviLock（或明确无并发）时调用。 */
    private fun invokeCancelNaviLocked(api: Any) {
        runCatching {
            val callbackClass = runCatching { Class.forName("com.ecarx.sdk.navi.INaviAPICallback") }.getOrNull()
            val callback = callbackClass?.let { cls ->
                Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, _, _ -> null }
            }
            val method = api.javaClass.methods.firstOrNull { it.name == "cancelNavi" && it.parameterCount == 1 }
            if (method == null) {
                Log.i(TAG, "结束原厂导航跳过（cancelNavi 方法不在）")
                return
            }
            method.invoke(api, callback)
            Log.i(TAG, "已结束车机原厂导航（NaviAPI.cancelNavi）")
        }.onFailure {
            Log.i(TAG, "结束原厂导航跳过（调用失败：${it.javaClass.simpleName}）")
        }
    }

    /**
     * 懒初始化 `VrVoiceAPI`（原厂 VrHandler.initVrVoiceAPI 同款）：`VrVoiceAPI.get()` 拿单例后
     * `init(context, callback)`，`onAPIReady(true)` 时把挂起的 enableVR 补执行。已就绪时是空操作。
     */
    private fun initVr(app: Context) {
        synchronized(vrLock) {
            if (vrReady) return
            runCatching {
                val api = vrApi ?: Class.forName(VR_VOICE_API).getMethod("get").invoke(null)
                    .also { vrApi = it }
                val callback = ecarxCallback { ready ->
                    synchronized(vrLock) {
                        vrReady = ready
                        Log.i(TAG, "VrVoiceAPI onAPIReady=$ready")
                        if (ready) {
                            pendingVrEnable?.let { enable ->
                                pendingVrEnable = null
                                invokeEnableVrLocked(api, enable)
                            }
                        }
                    }
                }
                api.javaClass.getMethod("init", Context::class.java, Class.forName(ECARX_CALLBACK))
                    .invoke(api, app, callback)
            }.onFailure {
                Log.i(TAG, "VrVoiceAPI 初始化跳过（本机无 VrVoiceAPI：${it.javaClass.simpleName}），防抢麦不可用")
                vrReady = true // SDK 不存在：不再重试
            }
        }
    }

    /**
     * 505：抑制/恢复车机本地语音拾音（`VrVoiceAPI.get().enableVR(enable, reason, 101)`）。
     * [enable] false = 手机语音拾音中、抑制车机语音；true = 结束或断连、恢复车机语音。
     * 没 ready 时先 init 再挂起，`onAPIReady(true)` 回调里补执行。
     */
    fun enableVr(context: Context, enable: Boolean) {
        val app = context.applicationContext ?: context
        val api = synchronized(vrLock) {
            if (vrReady) {
                vrApi
            } else {
                // 未 ready：挂起请求 + 触发 init，onAPIReady(true) 回调里补执行。
                pendingVrEnable = enable
                initVr(app)
                vrApi?.takeIf { vrReady }
            }
        } ?: return
        synchronized(vrLock) { invokeEnableVrLocked(api, enable) }
    }

    /** 调用 `enableVR(enable, reason, 101)`；须持有 vrLock（或明确无并发）时调用。 */
    private fun invokeEnableVrLocked(api: Any, enable: Boolean) {
        runCatching {
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
            Log.i(TAG, "车机语音${if (enable) "恢复" else "抑制"}跳过（调用失败：${it.javaClass.simpleName}）")
        }
    }

    /**
     * 504：写 `Settings.Global link_incall_state`。值域对齐原厂 HiCarService2.notifyPhoneStateChanged：
     * 3（微信通话）映射为 2、999（挂断）映射为 0，**只写 0/1/2**，其他值不写（不污染系统设置）。
     * 系统签名才写得动，写不进（普通应用）不影响任何功能。
     */
    fun writeLinkIncallState(context: Context, state: Int) {
        val mapped = when (state) {
            3 -> 2 // 微信通话等三方通话：按通话中处理
            999 -> 0 // 挂断信号
            0, 1, 2 -> state
            else -> {
                Log.i(TAG, "callState=$state 不在 0/1/2 值域（原厂同款过滤），不写 link_incall_state")
                return
            }
        }
        runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "link_incall_state", mapped)
            Log.i(TAG, "link_incall_state=$mapped（callState=$state）")
        }.onFailure {
            Log.i(TAG, "link_incall_state 写不进（${it.javaClass.simpleName}），不影响功能")
        }
    }
}
