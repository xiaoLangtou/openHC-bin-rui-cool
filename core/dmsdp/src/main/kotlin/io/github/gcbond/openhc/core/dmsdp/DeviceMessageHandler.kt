package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.JsonCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 设备消息 module 3 / type -1：数据开头是 EA 编码的命令号，后面是命令体。
 *
 * | 命令 | 命令体 | 处理 |
 * |---|---|---|
 * | 101 设备信息 | UTF-8 JSON 对象 | 第一次收到时回本机 101，之后只更新 |
 * | 102 服务信息 | UTF-8 JSON 对象 | 记录，不回 |
 * | 103 服务状态 | BE32 有符号动作 ‖ 服务 JSON | 不回 |
 * | 104 服务请求 | BE32 服务类型掩码 | 对掩码命中的本机服务逐条回 102 |
 * | 105 断开类型 | BE32 有符号整数 | 不回 |
 *
 * 本机 101 每条连接只发一次，避免双方互相回 101 形成回声（本地约束，不是手机规范）。
 *
 * 扬声器服务的属性 2102 由 [speakerProfile] 给出：声明 PCM 还是 AAC 要看连上的是哪台手机（见 [DmsdpConfig.Speaker]）。
 */
internal class DeviceMessageHandler(
    private val config: DmsdpConfig,
    private val speakerProfile: () -> String = { config.speaker?.audioProfile ?: "" },
) {
    private var peerDeviceId: String? = null
    private var localInfoSent = false

    /** [reply] 收到的是待发的设备记录数据（EA 命令 + 命令体，不含 `$` 头）。 */
    fun handle(data: ByteArray, reply: (ByteArray) -> Unit, emit: (DmsdpEvent) -> Unit) {
        if (data.isEmpty()) throw MalformedMessageException("设备命令为空")
        val command = Ea.decodeComplete(data, 0, "设备命令")
        val body = data.copyOfRange(command.next, data.size)
        when (command.value) {
            CMD_DEVICE_INFO -> onDeviceInfo(body, reply, emit)
            CMD_SERVICE_INFO -> {
                val service = parseService(body)
                emit(DmsdpEvent.PeerServiceInfo(service.type, service.id, service.json))
            }
            CMD_SERVICE_STATUS -> {
                if (body.size <= 4) throw MalformedMessageException("服务状态 103 缺少动作或服务 JSON")
                val action = readS32Be(body, 0)
                val service = parseService(body.copyOfRange(4, body.size))
                if (!service.json.containsKey("deviceName")) throw MalformedMessageException("服务状态 103 缺少 deviceName")
                emit(DmsdpEvent.ServiceStatusChanged(action, service.type, service.id, service.json))
            }
            CMD_SERVICE_REQUEST -> {
                if (body.size != 4) throw MalformedMessageException("服务请求 104 必须是 4 字节掩码")
                val mask = readS32Be(body, 0).toLong() and 0xFFFF_FFFFL
                val answered = ArrayList<String>()
                for (service in localServices()) {
                    if (mask and service.serviceType.toLong() != 0L) {
                        reply(command(CMD_SERVICE_INFO, serviceObject(service)))
                        answered += service.serviceId
                    }
                }
                emit(DmsdpEvent.ServicesRequested(mask, answered))
            }
            CMD_DISCONNECT_TYPE -> {
                if (body.size != 4) throw MalformedMessageException("断开类型 105 必须是 4 字节整数")
                emit(DmsdpEvent.DisconnectTypeNotified(readS32Be(body, 0)))
            }
            else -> emit(DmsdpEvent.UnsupportedDeviceCommand(MODULE_DEVICE, command.value, body))
        }
    }

    private fun onDeviceInfo(body: ByteArray, reply: (ByteArray) -> Unit, emit: (DmsdpEvent) -> Unit) {
        val device = parseObject(body, "设备信息 101")
        val deviceId = requireIdentity(device, "deviceId")
        val deviceType = (device["deviceType"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw MalformedMessageException("设备信息 101 缺少整数 deviceType")
        val properties = device["properties"]
        if (properties != null && properties !is JsonObject) throw MalformedMessageException("设备信息 101 的 properties 不是对象")
        val known = peerDeviceId
        if (known != null && known != deviceId) throw MalformedMessageException("同一连接里手机的设备 ID 变了")
        peerDeviceId = deviceId
        if (!localInfoSent) {
            reply(command(CMD_DEVICE_INFO, localDeviceObject()))
            localInfoSent = true
        }
        emit(DmsdpEvent.PeerDeviceInfo(deviceId, deviceType, device))
    }

    /** 本机设备信息 101：字段顺序与取值都是线上格式的一部分。 */
    fun localDeviceObject(): JsonObject = buildJsonObject {
        put("deviceId", config.deviceId)
        put("deviceName", config.host.deviceName)
        put("deviceType", DEVICE_TYPE_CAR)
        put("bluetoothMac", config.bluetoothMac)
        put("btName", config.host.deviceName)
        put("localIp", config.localIp)
        put("remoteIp", config.phoneIp)
        put("port", -1)
        put("businessId", BUSINESS_ID)
        put("properties", buildJsonObject {
            config.display?.let { d ->
                put("1005", d.videoWidth)
                put("1006", d.videoHeight)
                put("1012", d.width)
                put("1013", d.height)
                put("1011", d.dpi)
                put("1015", d.fps)
                put("1014", d.codec)
                d.gop?.let { put("1016", it) }
                d.bitrate?.let { put("1017", it) }
            }
            // 手机按这个值（设备信息 detail 里的 CAR_MODE_ID）比对 ConvergedLauncherController
            // 白名单，决定 HiCar 应用列表"车辆"磁贴是否可点；必须用白名单内的型号（v10 起 00048900）。
            put("2003", config.host.fullModel)
        })
    }

    /** 本机声明的服务：显示在前，扬声器其次，麦克风再次，其余按配置顺序。 */
    fun localServices(): List<DmsdpConfig.Service> {
        val list = ArrayList<DmsdpConfig.Service>()
        config.display?.let { d ->
            list += DmsdpConfig.Service(
                serviceType = DmsdpConfig.SERVICE_TYPE_DISPLAY,
                serviceId = DmsdpConfig.DISPLAY_SERVICE_ID,
                properties = buildJsonObject {
                    put("4001", d.density)
                    put("1009", d.width)
                    put("1010", d.height)
                },
            )
        }
        if (config.speaker != null) {
            list += DmsdpConfig.Service(
                serviceType = DmsdpConfig.SERVICE_TYPE_SPEAKER,
                serviceId = DmsdpConfig.SPEAKER_SERVICE_ID,
                // 键的顺序是线上格式的一部分；2102 是字符串，里面的 JSON 序列化后带转义引号。
                properties = buildJsonObject {
                    put("2005", "")
                    put("2004", "")
                    put("2003", "")
                    put("2102", speakerProfile())
                },
            )
        }
        config.mic?.let { mic ->
            list += DmsdpConfig.Service(
                serviceType = DmsdpConfig.SERVICE_TYPE_MIC,
                serviceId = DmsdpConfig.MIC_SERVICE_ID,
                // 与扬声器同样的三个空串属性；2102 只在给了能力时才挂（不挂时手机按自己的默认参数要）。
                properties = buildJsonObject {
                    put("2005", "")
                    put("2004", "")
                    put("2003", "")
                    mic.profile?.let { put("2102", it.json) }
                },
            )
        }
        list += config.additionalServices
        return list
    }

    fun serviceObject(service: DmsdpConfig.Service): JsonObject = buildJsonObject {
        put("deviceId", config.deviceId)
        put("deviceName", config.host.deviceName)
        put("serviceType", service.serviceType)
        put("serviceId", service.serviceId)
        put("properties", service.properties)
        put("status", service.status)
    }

    private class ParsedService(val type: Long, val id: String, val json: JsonObject)

    private fun parseService(body: ByteArray): ParsedService {
        val service = parseObject(body, "服务 JSON")
        requireIdentity(service, "deviceId")
        val id = requireIdentity(service, "serviceId")
        val type = (service["serviceType"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw MalformedMessageException("服务 JSON 缺少整数 serviceType")
        for (field in listOf("properties", "status")) {
            val value = service[field]
            if (value != null && value !is JsonObject) throw MalformedMessageException("服务 JSON 的 $field 不是对象")
        }
        return ParsedService(type, id, service)
    }

    companion object {
        const val MODULE_DEVICE = 3L
        const val MODULE_DISPLAY = 5L
        const val TYPE_DEVICE_DATA = -1
        const val CMD_DEVICE_INFO = 101
        const val CMD_SERVICE_INFO = 102
        const val CMD_SERVICE_STATUS = 103
        const val CMD_SERVICE_REQUEST = 104
        const val CMD_DISCONNECT_TYPE = 105

        /** module 5 里手机交付 HiSight 端口的命令号：`EA(101) ‖ EA(端口)`。 */
        const val CMD_DISPLAY_PORT = 101

        /** 车机在设备信息里的角色。 */
        const val DEVICE_TYPE_CAR = 4

        /** 设备信息里的业务号（不是业务 20）。 */
        const val BUSINESS_ID = 1

        fun command(command: Int, json: JsonObject): ByteArray = Ea.encode(command) + JsonCodec.encodeToUtf8(json)

        /** 严格 UTF-8 解码后解析成 JSON 对象；报错不带 JSON 原文（手机标识、名称、地址），见 [JsonCodec]。 */
        fun parseObject(body: ByteArray, what: String): JsonObject =
            JsonCodec.parseObject(strictUtf8(body, what = what), what)

        private fun requireIdentity(json: JsonObject, field: String): String {
            val value = json[field] as? JsonPrimitive
            if (value == null || !value.isString || value.content.isEmpty()) {
                throw MalformedMessageException("JSON 缺少有效的 $field")
            }
            return value.content
        }

        /**
         * module 5 / type -1：`EA(101) ‖ EA(端口)`，必须正好用完。命令不是 101 返回 null
         * （交给调用方按未支持处理）；端口编码不对抛 [MalformedMessageException]。
         */
        fun parseDisplayPort(data: ByteArray): Int? {
            if (data.isEmpty()) throw MalformedMessageException("显示消息为空")
            val command = Ea.decodeComplete(data, 0, "显示命令")
            if (command.value != CMD_DISPLAY_PORT) return null
            val port = Ea.decodeComplete(data, command.next, "显示端口")
            if (port.next != data.size || port.value !in 1..0xFFFF) {
                throw MalformedMessageException("显示端口 EA 有多余数据或超出范围")
            }
            return port.value
        }
    }
}
