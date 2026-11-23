package io.github.gcbond.openhc.identity

import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 仓库里固定的身份参数文件（所有安装包都打进去的那份）能解析，车型、品牌、名称是定下来的值。 */
class CommittedIdentityTest {

    private fun committedFile(): File {
        // 单元测试的工作目录是模块目录（app/）；从仓库根目录跑也找得到。
        val candidates = listOf("src/main/assets", "app/src/main/assets").map { File(it, IdentityAssets.ASSET_FILE_NAME) }
        return candidates.firstOrNull { it.isFile } ?: error("找不到 ${IdentityAssets.ASSET_FILE_NAME}：$candidates")
    }

    @Test
    fun committedIdentityParsesWithTheFixedValues() {
        val assets = IdentityJson.parseText(committedFile().readText())
        assertEquals("00048900", assets.host.fullModel)
        assertEquals("car", assets.host.brand)
        assertEquals("OpenHC", assets.host.deviceName)
        assertTrue(assets.nearbyKeys.rootKey.size >= 5)
    }
}
