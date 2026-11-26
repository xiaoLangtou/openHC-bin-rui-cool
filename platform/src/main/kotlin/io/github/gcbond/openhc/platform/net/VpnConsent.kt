package io.github.gcbond.openhc.platform.net

import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.Process
import io.github.gcbond.openhc.platform.log.Log

/**
 * 有线连接前要先拿到用户的 VPN 授权（[WiredVpnService] 建 TUN 需要）。授权是持久的，同意一次之后不再弹框，
 * 但别的 VPN 应用被用户启用后，本应用的授权会被取代，需要重新同意。
 *
 * 平台密钥签名（共享 `android.uid.system`）的版本在系统身份进程里运行：授权框还没弹出来就静默自授
 * （先把 ACTIVATE_VPN 的 appop 置 allow，再直调系统的 VPN 授权接口），不再依赖
 * `adb shell appops set <pkg> ACTIVATE_VPN allow`，也不用系统里有没有 `com.android.vpndialogs`。
 * 自授失败（普通签名、权限被裁、接口不存在）时无感回退，照常走系统授权框。
 *
 * 应用层用法（在开始有线会话之前）：
 * ```
 * when (val status = VpnConsent.check(context)) {
 *     VpnConsent.Status.Granted -> 开始会话
 *     is VpnConsent.Status.NeedsUserConsent -> 用 Activity 拉起 status.intent，结果为 RESULT_OK 即同意
 *     is VpnConsent.Status.Unavailable -> 提示这台车机不能用有线连接
 * }
 * ```
 * 用 ComponentActivity 时可以 `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
 * 拉起 `status.intent`；也可以直接用 [request]。
 */
object VpnConsent {

    private const val TAG = "OpenHC-VpnConsent"

    sealed class Status {
        /** 已授权。 */
        object Granted : Status()

        /** 需要用户在系统框里同意：用 Activity 拉起 [intent]，结果为 [Activity.RESULT_OK] 即同意。 */
        class NeedsUserConsent(val intent: Intent) : Status()

        /** 系统不让用 VPN（例如设备策略禁止、正处于别的 VPN 的锁定模式）。 */
        class Unavailable(val reason: String) : Status()
    }

    // ---- 测试钩子（生产路径固定为下面的默认实现，单元测试里替换成可控桩）。 ----
    internal var prepare: (Context) -> Intent? = { VpnService.prepare(it) }
    internal var isSystemUid: () -> Boolean = { Process.myUid() == Process.SYSTEM_UID }
    internal var autoGrant: (Context) -> Boolean = { trySystemAutoGrant(it) }

    fun check(context: Context): Status = checkWith(
        prepare = { prepare(context) },
        isSystemUid = { isSystemUid() },
        autoGrant = { autoGrant(context) },
    )

    /**
     * [check] 的决策内核，参数化成桩便于单元测试覆盖各分支：
     * prepare 为 null 即已授权；否则系统身份先自授一次、重查通过才算数，非系统身份保持弹窗。
     */
    internal fun checkWith(
        prepare: () -> Intent?,
        isSystemUid: () -> Boolean,
        autoGrant: () -> Boolean,
    ): Status = try {
        val intent = prepare()
        if (intent == null) {
            Status.Granted
        } else if (!isSystemUid()) {
            Status.NeedsUserConsent(intent)
        } else {
            Log.i(TAG, "系统签名进程，尝试静默自授 VPN 权限")
            val granted = try {
                autoGrant()
            } catch (e: Throwable) {
                Log.w(TAG, "VPN 静默自授出错，回退系统授权框", e)
                false
            }
            // 重查不能吞异常：prepare 抛异常说明系统侧出问题（策略禁止、服务异常），让最外层
            // catch 转成 Unavailable，绝不能把异常当成 null 放行。
            val rechecked = prepare()
            if (rechecked == null) {
                Log.i(TAG, "VPN 静默自授成功，跳过授权框（重查通过）")
                Status.Granted
            } else {
                Log.i(TAG, "VPN 静默自授未生效（granted=$granted），走系统授权框")
                Status.NeedsUserConsent(intent)
            }
        }
    } catch (e: RuntimeException) {
        Status.Unavailable("${e.javaClass.simpleName}: ${e.message}")
    }

    fun isGranted(context: Context): Boolean = check(context) === Status.Granted

    /**
     * 已授权返回 [Status.Granted]；否则用 [activity] 拉起系统授权框（结果在 `onActivityResult(requestCode, …)`），
     * 返回 [Status.NeedsUserConsent]。车机系统里没有授权框时返回 [Status.Unavailable]。
     */
    fun request(activity: Activity, requestCode: Int): Status {
        val status = check(activity)
        if (status is Status.NeedsUserConsent) {
            try {
                activity.startActivityForResult(status.intent, requestCode)
            } catch (e: ActivityNotFoundException) {
                return Status.Unavailable("系统里没有 VPN 授权页")
            }
        }
        return status
    }

    // ---------------------------------------------------------------- 系统身份静默自授

    /**
     * 两步走，任何一步成功都算数（之后还要再 [prepare] 确认一次才算真的授权）：
     * A. 把本应用的 `ACTIVATE_VPN` appop 置 allow —— 等价 `adb shell appops set <pkg> ACTIVATE_VPN allow`；
     * B. 直调系统的 VPN 授权接口 —— 等价 `com.android.vpndialogs` 弹框里点「确定」，双保险。
     */
    private fun trySystemAutoGrant(context: Context): Boolean {
        val viaAppOps = grantVpnAppOp(context)
        val viaConnectivity = grantViaConnectivity(context)
        return viaAppOps || viaConnectivity
    }

    /** A：把 ACTIVATE_VPN 的 appop 置 allow。 */
    private fun grantVpnAppOp(context: Context): Boolean {
        val uid = context.applicationInfo.uid
        val pkg = context.packageName
        val opCode = resolveOpActivateVpn()
        // 先试框架里的 AppOpsManager.setMode。
        try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (ops != null) {
                val setMode = AppOpsManager::class.java.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                setMode.invoke(ops, opCode, uid, pkg, AppOpsManager.MODE_ALLOWED)
                Log.i(TAG, "ACTIVATE_VPN appop 已置 allow（AppOpsManager，op=$opCode）")
                return true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AppOpsManager.setMode 不可用，改走 IAppOpsService", e)
        }
        // 再试 binder 直连 IAppOpsService。
        try {
            val ops = systemService("appops", "com.android.internal.app.IAppOpsService")
            val setMode = ops.javaClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            setMode.invoke(ops, opCode, uid, pkg, AppOpsManager.MODE_ALLOWED)
            Log.i(TAG, "ACTIVATE_VPN appop 已置 allow（IAppOpsService，op=$opCode）")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "IAppOpsService.setMode 不可用", e)
        }
        return false
    }

    /**
     * ACTIVATE_VPN 的 op 序号：优先 `strOpToOp("android:activate_vpn")`（接口随系统版本最稳），
     * 失败读静态字段 `OP_ACTIVATE_VPN`，都取不到就兜底 47（安卓 9 上就是这个值）。
     */
    private fun resolveOpActivateVpn(): Int = try {
        pickOpCode(
            strOp = try {
                val m = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
                m.invoke(null, "android:activate_vpn") as? Int
            } catch (e: Throwable) {
                Log.w(TAG, "strOpToOp 解析失败", e)
                null
            },
            fieldOp = try {
                val f = AppOpsManager::class.java.getField("OP_ACTIVATE_VPN")
                f.getInt(null)
            } catch (e: Throwable) {
                Log.w(TAG, "OP_ACTIVATE_VPN 字段读取失败", e)
                null
            },
        )
    } catch (e: Throwable) {
        Log.w(TAG, "ACTIVATE_VPN op 序号解析失败，兜底 47", e)
        FALLBACK_ACTIVATE_VPN_OP
    }

    /**
     * B：直调 ConnectivityService 的 VPN 授权接口（点确认的等价动作）。
     * 两个步骤都要跑：setVpnPackageAuthorization 只把授权记录改成自己，别的 VPN 占坑时还要靠
     * prepareVpn(null, pkg, user) 把旧连接一并断开切过来，所以不能提前返回。
     */
    private fun grantViaConnectivity(context: Context): Boolean {
        var ok = false
        try {
            val cm = systemService("connectivity", "android.net.IConnectivityManager")
            val pkg = context.packageName
            val userId = context.applicationInfo.uid / 100_000
            // 步骤 1：setVpnPackageAuthorization —— API 28～29 用 boolean 版，API 30+ 用 int 版。
            try {
                val m = cm.javaClass.getMethod(
                    "setVpnPackageAuthorization",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                m.invoke(cm, pkg, userId, true)
                Log.i(TAG, "setVpnPackageAuthorization(pkg, user=$userId, true) 已调用")
                ok = true
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "没有 boolean 版 setVpnPackageAuthorization，试 int 版")
            } catch (e: Throwable) {
                Log.w(TAG, "boolean 版 setVpnPackageAuthorization 调用失败", e)
            }
            if (!ok) {
                try {
                    val m = cm.javaClass.getMethod(
                        "setVpnPackageAuthorization",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    m.invoke(cm, pkg, userId, 1)
                    Log.i(TAG, "setVpnPackageAuthorization(pkg, user=$userId, 1) 已调用")
                    ok = true
                } catch (e: Throwable) {
                    Log.w(TAG, "int 版 setVpnPackageAuthorization 不可用", e)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ConnectivityService 不可用，跳过 setVpnPackageAuthorization", e)
        }
        // 步骤 2：prepareVpn 两步换手。安卓 9 的 Vpn.java 开机默认 mPackage="[Legacy VPN]"、
        // mOwnerUID=1000，isCurrentPreparedPackage 只比对 getAppUid(pkg)==mOwnerUID；本应用共享
        // android.uid.system（uid=1000）会被 1000==1000 短路、跳过 prepareInternal(pkg)，mPackage
        // 永远换不成自己，establish() 检查「[Legacy VPN]」的预授权必败返回 null。
        // 先用 uid 2000 的 com.android.shell 把 mOwnerUID 切出 1000，再夺回，强制系统真正执行 prepareInternal(pkg)。
        try {
            val cm = systemService("connectivity", "android.net.IConnectivityManager")
            val pkg = context.packageName
            val userId = context.applicationInfo.uid / 100_000
            val prepare = cm.javaClass.getMethod(
                "prepareVpn",
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            // 2a：换手给 shell。失败（接口裁剪、SELinux 拦等）不拦 2b——没有撞号问题的设备直接走 2b 就够。
            val bounced = try {
                prepare.invoke(cm, null, BOUNCE_PACKAGE, userId)
            } catch (e: Throwable) {
                Log.w(TAG, "换手 prepareVpn(null, $BOUNCE_PACKAGE, user=$userId) 调用失败", e)
                null
            }
            Log.i(TAG, "换手 prepareVpn(null, $BOUNCE_PACKAGE, user=$userId) 返回 $bounced")
            // 2b：夺回——此时 mOwnerUID 已是 2000（或原本就不是 1000），1000 != 2000 强制走 prepareInternal(pkg)。
            val granted = prepare.invoke(cm, null, pkg, userId)
            Log.i(TAG, "夺回 prepareVpn(null, pkg, user=$userId) 返回 $granted")
            ok = true
        } catch (e: Throwable) {
            Log.w(TAG, "prepareVpn 两步换手失败", e)
        }
        return ok
    }

    /**
     * 进程内按需夺回 VPN 归属（建 TUN 返回 null 时的兜底，见 [VpnTunnel.open]）：
     * 仅系统身份执行自授两步走（appop 置 allow + prepareVpn 两步换手），把安卓 9 上被
     * `uid 1000` 与 `LEGACY_VPN`（`mOwnerUID=1000`）撞号卡住的归属真正切回本应用包名。
     * 所有异常安全捕获，绝不抛出；非系统身份直接返回 false（普通签名版走弹窗流程）。
     */
    internal fun reclaimSystemVpnOwner(context: Context): Boolean = reclaimWith(
        isSystemUid = { isSystemUid() },
        autoGrant = { autoGrant(context) },
    )

    /** [reclaimSystemVpnOwner] 的决策内核，参数化成桩便于单元测试。 */
    internal fun reclaimWith(isSystemUid: () -> Boolean, autoGrant: () -> Boolean): Boolean = try {
        if (!isSystemUid()) {
            Log.i(TAG, "非系统身份，不尝试夺回 VPN 归属")
            false
        } else {
            Log.i(TAG, "系统身份，两步换手夺回 VPN 归属（appop 置 allow + prepareVpn 换手）")
            try {
                autoGrant()
            } catch (e: Throwable) {
                Log.w(TAG, "夺回 VPN 归属出错", e)
                false
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "夺回 VPN 归属流程出错", e)
        false
    }

    /** `ServiceManager.getService(name)` → `接口名$Stub.asInterface(binder)`。 */
    private fun systemService(name: String, interfaceName: String): Any {
        val binder = try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        } catch (e: Throwable) {
            throw IllegalStateException("ServiceManager.getService($name) 失败", e)
        }
        if (binder == null) throw IllegalStateException("系统服务 $name 不存在")
        // 死进程的 binder 再查询一次（一般发生在刚开机服务还没起来的时候）。
        val target: IBinder = if (binder.pingBinder()) binder else {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
                ?: throw IllegalStateException("系统服务 $name 重查仍不存在")
        }
        try {
            val stub = Class.forName("$interfaceName\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            return asInterface.invoke(null, target)
        } catch (e: Throwable) {
            throw IllegalStateException("$interfaceName\$Stub.asInterface 失败", e)
        }
    }

    internal const val FALLBACK_ACTIVATE_VPN_OP = 47

    /**
     * 两步换手的中转包：com.android.shell 固定 uid 2000（!= 1000）。
     * 先把 VPN 归属切给它、把 mOwnerUID 从 1000（LEGACY_VPN）挪开，再夺回本应用。
     */
    internal const val BOUNCE_PACKAGE = "com.android.shell"

    /**
     * op 序号的取舍：strOpToOp 的结果（≥0 才算数）优先，其次静态字段，都没有兜底 47。
     * 单独拎出来方便单元测试这条兜底链。
     */
    internal fun pickOpCode(strOp: Int?, fieldOp: Int?): Int = when {
        strOp != null && strOp >= 0 -> strOp
        fieldOp != null && fieldOp >= 0 -> fieldOp
        else -> FALLBACK_ACTIVATE_VPN_OP
    }
}
