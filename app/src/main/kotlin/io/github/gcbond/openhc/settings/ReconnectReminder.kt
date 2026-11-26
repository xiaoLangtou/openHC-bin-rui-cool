package io.github.gcbond.openhc.settings

import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.wireless.WirelessPhase

/**
 * 「下次连接才生效」的设置：开始连接时读一次，之后这次连接一直按那一刻的值。
 * [wirelessOnly]：只有无线连接会读。[offIsImmediate]：关掉是马上生效的（只有打开要等下次连接）。
 */
enum class PendingSetting(val wirelessOnly: Boolean = false, val offIsImmediate: Boolean = false) {
    FPS,
    UI_SCALE,
    CLARITY,
    FULL_SCREEN,
    MEDIA_KEYS,
    BLUETOOTH_ADDRESS,
    AUDIO_ENABLED,
    AUDIO_DELAY,
    NAVIGATION_STREAM,
    MEDIA_STREAM,
    WIRELESS_MODE(wirelessOnly = true),
    CAR_HOTSPOT_NAME(wirelessOnly = true),
    CAR_HOTSPOT_PASSWORD(wirelessOnly = true),
    CAR_HOTSPOT_BAND(wirelessOnly = true),
    CAR_HOTSPOT_AUTO(wirelessOnly = true),
    SCAN_PAUSE(wirelessOnly = true, offIsImmediate = true),
}

/**
 * 离开设置页时提醒「这些改动要重新连接才生效」。只在有连接正用着旧设置时提醒：已经连上的，或者已经开始、还在等手机的
 * （等插线、等扫码、在拨手机——开始时就把设置读定了）。没在连接时，下一次连接自然按新设置，不提醒。
 */
object ReconnectReminder {
    /** 有线这次连接已经读定了设置。要拔线重插的那一下会重新开始、重新读，不算。 */
    fun wiredUsesOldSettings(phase: Phase): Boolean = when (phase) {
        Phase.IDLE, Phase.STOPPING, Phase.REPLUG_REQUIRED -> false
        else -> true
    }

    fun wirelessUsesOldSettings(phase: WirelessPhase): Boolean = when (phase) {
        WirelessPhase.IDLE, WirelessPhase.STOPPING -> false
        else -> true
    }

    /**
     * [before] 是进设置页时的值，[after] 是离开时的值（按 [PendingSetting] 存成文字）。返回要提醒的项，按 [PendingSetting]
     * 的顺序；没有要提醒的返回空表。
     */
    fun changed(
        before: Map<PendingSetting, String>,
        after: Map<PendingSetting, String>,
        wiredActive: Boolean,
        wirelessActive: Boolean,
    ): List<PendingSetting> {
        if (!wiredActive && !wirelessActive) return emptyList()
        return PendingSetting.values().filter { key ->
            val old = before[key]
            val new = after[key]
            when {
                old == new -> false
                key.wirelessOnly && !wirelessActive -> false
                key.offIsImmediate && new == false.toString() -> false
                else -> true
            }
        }
    }
}
