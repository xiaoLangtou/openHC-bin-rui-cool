package io.github.gcbond.openhc.identity

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 仓库里的示例 docs/identity.example.json 能被解析：真实的连接参数不进仓库（构建时注入，见 app/build.gradle.kts），
 * 示例给从源码构建的人照着写，格式必须一直和解析器对得上。
 */
class ExampleIdentityTest {

    private fun exampleFile(): File {
        // 单元测试的工作目录是模块目录（app/）；从仓库根目录跑也找得到。
        val candidates = listOf("../docs", "docs").map { File(it, "identity.example.json") }
        return candidates.firstOrNull { it.isFile } ?: error("找不到 identity.example.json：$candidates")
    }

    @Test
    fun exampleParses() {
        val assets = IdentityJson.parseText(exampleFile().readText())
        assertEquals("OpenHC", assets.host.deviceName)
    }
}
