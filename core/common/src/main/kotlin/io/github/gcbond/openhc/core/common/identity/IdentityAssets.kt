package io.github.gcbond.openhc.core.common.identity

import io.github.gcbond.openhc.core.common.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 车机（本应用）对手机声明的身份。
 *
 * 取值来自安装包里的 `openhc-identity.json`：不进仓库，构建时从本机目录打进安装包（见 app/build.gradle.kts）。
 */
data class HostIdentity(
    /** 车型编号的前 6 位十六进制（3 字节），大写。 */
    val modelId: String,
    /** 车型编号的后 2 位十六进制（1 字节），大写。 */
    val subModelId: String,
    /** 手机上显示的车机品牌。 */
    val brand: String,
    /** 手机上显示的车机名称。 */
    val deviceName: String,
) {
    init {
        require(HEX6.matches(modelId)) { "modelId 必须是 6 位大写十六进制" }
        require(HEX2.matches(subModelId)) { "subModelId 必须是 2 位大写十六进制" }
        require(brand.isNotBlank()) { "brand 不能为空" }
        require(deviceName.isNotBlank()) { "deviceName 不能为空" }
    }

    /** 完整车型编号（8 位十六进制）。 */
    val fullModel: String get() = modelId + subModelId

    private companion object {
        val HEX6 = Regex("[0-9A-F]{6}")
        val HEX2 = Regex("[0-9A-F]{2}")
    }
}

/**
 * nearby 帧加密用的三段密钥材料。三段长度必须相同（推导时要逐字节异或）。
 * [toString] 不输出内容，避免进日志。
 */
class NearbyKeyMaterial(rootKey: ByteArray, constA: ByteArray, constB: ByteArray) {
    private val root = rootKey.copyOf()
    private val a = constA.copyOf()
    private val b = constB.copyOf()

    init {
        // 推导时要按字节轮转（最多 5 字节），短于 5 字节没有定义。
        require(root.size >= MIN_LENGTH) { "rootKey 至少 $MIN_LENGTH 字节" }
        require(root.size == a.size && a.size == b.size) { "rootKey/constA/constB 长度必须相同" }
    }

    val rootKey: ByteArray get() = root.copyOf()
    val constA: ByteArray get() = a.copyOf()
    val constB: ByteArray get() = b.copyOf()

    override fun toString(): String = "NearbyKeyMaterial(${root.size} 字节 ×3，内容不显示)"

    companion object {
        /** 三段密钥材料的最短长度。 */
        const val MIN_LENGTH = 5
    }
}

/** 配对认证时双方约定的服务身份（参与会话密钥推导，必须与手机一致）。 */
data class AuthServiceIdentity(val packageName: String, val serviceType: String) {
    init {
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        require(serviceType.isNotBlank()) { "serviceType 不能为空" }
    }
}

/** 一份完整的身份资产：车机身份、nearby 密钥材料、认证服务身份。 */
class IdentityAssets(
    val host: HostIdentity,
    val nearbyKeys: NearbyKeyMaterial,
    val authService: AuthServiceIdentity,
    /** 认证外层消息里的 version 取值（协议约定的厂商版本串）。 */
    val authEnvelopeVersion: String,
) {
    init {
        require(authEnvelopeVersion.isNotBlank()) { "envelopeVersion 不能为空" }
    }


    companion object {
        /** 当前支持的文件格式版本。 */
        const val FORMAT = 1

        /** 打包进应用 assets 时的文件名。 */
        const val ASSET_FILE_NAME = "openhc-identity.json"

        /**
         * 解析 `openhc-identity.json` 的内容。
         * 缺字段、类型不对或取值非法时抛 [IllegalArgumentException]，错误信息不含密钥内容。
         */
        fun parse(json: String): IdentityAssets {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("身份资产不是合法 JSON", e)
            }
            val obj = root.asObject("根节点")
            val format = (obj["format"] as? JsonPrimitive)?.intOrNull
            require(format == FORMAT) { "身份资产 format 必须是 $FORMAT" }

            val host = obj.child("host")
            val keys = obj.child("nearbyKeys")
            val auth = obj.child("authService")
            return IdentityAssets(
                host = HostIdentity(
                    modelId = host.string("modelId").uppercase(),
                    subModelId = host.string("subModelId").uppercase(),
                    brand = host.string("brand"),
                    deviceName = host.string("deviceName"),
                ),
                nearbyKeys = NearbyKeyMaterial(
                    rootKey = keys.base64("rootKey"),
                    constA = keys.base64("constA"),
                    constB = keys.base64("constB"),
                ),
                authService = AuthServiceIdentity(
                    packageName = auth.string("packageName"),
                    serviceType = auth.string("serviceType"),
                ),
                authEnvelopeVersion = auth.string("envelopeVersion"),
            )
        }

        private fun JsonElement.asObject(name: String): JsonObject =
            this as? JsonObject ?: throw IllegalArgumentException("$name 必须是 JSON 对象")

        private fun JsonObject.child(key: String): JsonObject =
            (this[key] ?: throw IllegalArgumentException("缺少 $key")).asObject(key)

        private fun JsonObject.string(key: String): String {
            val p = this[key] as? JsonPrimitive
            require(p != null && p.isString) { "$key 必须是字符串" }
            return p.content
        }

        private fun JsonObject.base64(key: String): ByteArray {
            val text = string(key)
            return try {
                Base64.decode(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$key 不是合法 Base64", e)
            }
        }
    }
}
