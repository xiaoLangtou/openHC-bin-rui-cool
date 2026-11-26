package io.github.gcbond.openhc.platform.net

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VpnConsent 的系统签名静默自授决策链：
 * prepare 为 null 即已授权 → 系统身份先自授（成功还要重查一次）→ 非系统身份保持弹窗 → 异常转 Unavailable。
 */
class VpnConsentTest {

    /** 模拟 `VpnService.prepare` 返回的「待同意」Intent。 */
    private fun consentIntent(): Intent = Intent()

    @Test
    fun alreadyGrantedWhenPrepareReturnsNull() {
        val status = VpnConsent.checkWith(prepare = { null }, isSystemUid = { true }, autoGrant = { true })
        assertEquals(VpnConsent.Status.Granted, status)
    }

    @Test
    fun systemUidAutoGrantsAndRechecksBeforeGranting() {
        val dialog = consentIntent()
        var prepareCalls = 0
        var grantCalls = 0
        val status = VpnConsent.checkWith(
            prepare = {
                prepareCalls++
                if (prepareCalls == 1) dialog else null // 自授生效后重查通过
            },
            isSystemUid = { true },
            autoGrant = { grantCalls++; true },
        )
        assertEquals(VpnConsent.Status.Granted, status)
        assertEquals(2, prepareCalls, "自授之后必须再重查一次 prepare")
        assertEquals(1, grantCalls)
    }

    @Test
    fun autoGrantWithoutEffectStillAsksForConsent() {
        val dialog = consentIntent()
        // 自授两步都调了但没生效（权限被裁、接口不存在），重查 prepare 还是弹框。
        var prepareCalls = 0
        val status = VpnConsent.checkWith(
            prepare = { prepareCalls++; dialog },
            isSystemUid = { true },
            autoGrant = { true },
        )
        assertTrue(status is VpnConsent.Status.NeedsUserConsent, "自授未生效要保持既有弹窗行为")
        assertEquals(2, prepareCalls)
    }

    @Test
    fun autoGrantFailureFallsBackToDialog() {
        val dialog = consentIntent()
        var prepareCalls = 0
        val status = VpnConsent.checkWith(
            prepare = { prepareCalls++; dialog },
            isSystemUid = { true },
            autoGrant = { false },
        )
        assertTrue(status is VpnConsent.Status.NeedsUserConsent)
        assertEquals(2, prepareCalls, "自授失败也要重查一次再回退弹窗")
    }

    @Test
    fun autoGrantCrashFallsBackToDialogInsteadOfCrashing() {
        val dialog = consentIntent()
        var prepareCalls = 0
        val status = VpnConsent.checkWith(
            prepare = { prepareCalls++; dialog },
            isSystemUid = { true },
            autoGrant = { throw IllegalStateException("IConnectivityManager 不可用") },
        )
        assertTrue(status is VpnConsent.Status.NeedsUserConsent, "自授抛异常绝不能带崩会话")
        assertEquals(2, prepareCalls)
    }

    @Test
    fun recheckPrepareFailureIsUnavailableNeverGranted() {
        val dialog = consentIntent()
        var prepareCalls = 0
        val status = VpnConsent.checkWith(
            prepare = {
                prepareCalls++
                if (prepareCalls == 1) dialog else throw RuntimeException("策略禁止 / 服务异常")
            },
            isSystemUid = { true },
            autoGrant = { true },
        )
        assertTrue(status is VpnConsent.Status.Unavailable, "二次 prepare 抛异常必须转 Unavailable")
        assertEquals(2, prepareCalls)
    }

    @Test
    fun recheckNullMeansGrantedEvenIfAutoGrantUnsure() {
        val dialog = consentIntent()
        var prepareCalls = 0
        // 自授两步的结果只是「尽力而为」，最终以重查 prepare 的系统权威结果放行。
        val status = VpnConsent.checkWith(
            prepare = {
                prepareCalls++
                if (prepareCalls == 1) dialog else null
            },
            isSystemUid = { true },
            autoGrant = { false },
        )
        assertEquals(VpnConsent.Status.Granted, status, "重查通过即授权，不绑自授步骤的结果")
    }

    @Test
    fun nonSystemUidKeepsTheExistingDialogBehavior() {
        val dialog = consentIntent()
        var grantCalls = 0
        val status = VpnConsent.checkWith(
            prepare = { dialog },
            isSystemUid = { false },
            autoGrant = { grantCalls++ ; false },
        )
        assertTrue(status is VpnConsent.Status.NeedsUserConsent, "非系统签名进程完全保持既有弹窗行为")
        assertEquals(0, grantCalls, "非系统身份不尝试自授")
    }

    @Test
    fun prepareFailureBecomesUnavailableNotACrash() {
        val status = VpnConsent.checkWith(
            prepare = { throw RuntimeException("Stub!") },
            isSystemUid = { true },
            autoGrant = { true },
        )
        assertTrue(status is VpnConsent.Status.Unavailable)
    }

    @Test
    fun opCodePrefersStrOpToOpThenFieldThenFallsBackTo47() {
        // strOpToOp 成功就优先用它（随系统版本最稳）。
        assertEquals(47, VpnConsent.pickOpCode(strOp = 47, fieldOp = 999))
        // strOpToOp 失败读静态字段。
        assertEquals(47, VpnConsent.pickOpCode(strOp = null, fieldOp = 47))
        // 两条路都取不到，兜底 47（安卓 9 上 ACTIVATE_VPN 就是这个值）。
        assertEquals(47, VpnConsent.pickOpCode(strOp = null, fieldOp = null))
        // 返回负数视为无效，不采用。
        assertEquals(47, VpnConsent.pickOpCode(strOp = -1, fieldOp = -1))
        assertEquals(47, VpnConsent.pickOpCode(strOp = -1, fieldOp = null))
    }

    @Test
    fun fallbackOpCodeMatchesAndroid9() {
        assertEquals(47, VpnConsent.FALLBACK_ACTIVATE_VPN_OP)
    }
}
