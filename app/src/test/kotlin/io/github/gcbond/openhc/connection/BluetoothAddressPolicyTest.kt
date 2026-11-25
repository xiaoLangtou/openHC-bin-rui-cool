package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.nearby.NearbySession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** 车机蓝牙地址的来源顺序与格式整理。地址都是合成值。 */
class BluetoothAddressPolicyTest {

    private val property = "0A:1B:2C:3D:4E:5F"
    private val adapter = "C4:06:83:11:22:33"
    private val manual = "02:12:34:56:78:9A"

    @Test
    fun wirelessPrefersSystemPropertyThenAdapterThenManual() {
        assertEquals(
            BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY),
            BluetoothAddressPolicy.chooseForWireless(property, adapter, manual),
        )
        assertEquals(
            BluetoothChoice(adapter, BluetoothSource.LOCAL_ADAPTER),
            BluetoothAddressPolicy.chooseForWireless(null, adapter, manual),
            "没有车机系统属性时用本机适配器",
        )
        assertEquals(
            BluetoothChoice(manual, BluetoothSource.MANUAL),
            BluetoothAddressPolicy.chooseForWireless("02:00:00:00:00:00", "02:00:00:00:00:00", manual),
            "系统属性和本机适配器都是占位值时退到手填的",
        )
        assertEquals(BluetoothChoice(null, BluetoothSource.UNAVAILABLE), BluetoothAddressPolicy.chooseForWireless(null, null, ""))
    }

    @Test
    fun locallyAdministeredBitIsDetected() {
        assertEquals(true, BluetoothAddressPolicy.isLocallyAdministered("02:12:34:56:78:9A"))
        assertEquals(true, BluetoothAddressPolicy.isLocallyAdministered("06:11:22:33:44:55"))
        assertEquals(true, BluetoothAddressPolicy.isLocallyAdministered("0A:1B:2C:3D:4E:5F"), "0x0A 的本地管理位为 1")
        assertEquals(false, BluetoothAddressPolicy.isLocallyAdministered("00:1B:2C:3D:4E:5F"))
        assertEquals(false, BluetoothAddressPolicy.isLocallyAdministered("A8:1B:2C:3D:4E:5F"))
    }

    @Test
    fun manualWins() {
        assertEquals(BluetoothChoice(manual, BluetoothSource.MANUAL), BluetoothAddressPolicy.choose(property, adapter, manual))
    }

    @Test
    fun systemPropertyIsUsedWhenManualIsMissingOrUnusable() {
        val expected = BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY)
        for (bad in listOf(null, "", "   ", "garbage", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) {
            assertEquals(expected, BluetoothAddressPolicy.choose(property, adapter, bad), "手填值：$bad")
        }
    }

    @Test
    fun localAdapterIsUsedWhenPropertyIsUnusable() {
        assertEquals(
            BluetoothChoice(adapter, BluetoothSource.LOCAL_ADAPTER),
            BluetoothAddressPolicy.choose("02:00:00:00:00:00", adapter, null),
            "系统属性是占位值时退到本机适配器",
        )
        // 有线路径手填优先于本机适配器（可以借手填换一个身份）。
        assertEquals(
            BluetoothChoice(manual, BluetoothSource.MANUAL),
            BluetoothAddressPolicy.choose(null, adapter, manual),
        )
    }

    @Test
    fun manualIsUsedWhenNothingElseIsUsable() {
        val expected = BluetoothChoice(manual, BluetoothSource.MANUAL)
        for (bad in listOf(null, "", "   ", "garbage", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) {
            assertEquals(expected, BluetoothAddressPolicy.choose(bad, null, manual), "属性值：$bad")
        }
    }

    @Test
    fun unavailableWhenNeitherIsUsable() {
        assertEquals(BluetoothChoice(null, BluetoothSource.UNAVAILABLE), BluetoothAddressPolicy.choose(null, null, null))
        assertEquals(BluetoothChoice(null, BluetoothSource.UNAVAILABLE), BluetoothAddressPolicy.choose("", null, "12:34"))
    }

    @Test
    fun normalizesCommonSpellings() {
        assertEquals(property, BluetoothAddressPolicy.normalize("0a:1b:2c:3d:4e:5f"))
        assertEquals(property, BluetoothAddressPolicy.normalize("0A-1B-2C-3D-4E-5F"))
        assertEquals(property, BluetoothAddressPolicy.normalize("0a1b2c3d4e5f"))
        assertEquals(property, BluetoothAddressPolicy.normalize("  0A:1B:2C:3D:4E:5F\n"))
    }

    @Test
    fun rejectsMalformedAndPlaceholderAddresses() {
        for (bad in listOf(
            "0A:1B:2C:3D:4E", "0A:1B:2C:3D:4E:5G", "0A:1B-2C:3D:4E:5F", "0A1B2C3D4E5", "0A:1B:2C:3D:4E:5F:60",
            "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "02:00:00:00:00:00",
        )) {
            assertNull(BluetoothAddressPolicy.normalize(bad), bad)
        }
    }

    @Test
    fun unavailableAddressDerivesTheSameDeviceIdAsThePlaceholder() {
        // 取不到地址时传 null：设备 ID 按退化规则算，与显式写 02:00:00:00:00:00 相同；换了地址就是另一台车。
        val host = HostIdentity("000000", "00", "合成品牌", "合成车机")
        assertEquals(NearbySession.deviceId(host, "02:00:00:00:00:00"), NearbySession.deviceId(host, null))
        assertNotEquals(NearbySession.deviceId(host, null), NearbySession.deviceId(host, property))
    }
}
