package io.github.gcbond.openhc.core.dmsdp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 管理通道（module 1）上手机发来的 JSON 消息。type 17 是文件分享管理（[IncomingFileTransfer]），不在这里。
 *
 * 已抓到的线上样本（鸿蒙手机，全部只是通知、不需要应答，此前一直落在 UnhandledDeviceData）：
 * - 500 能力查询：`{"VehicleControlCapability":{}}`
 * - 502 车机品牌图标：`{"brandIconData":"PNG"}`
 * - 503 导航焦点：`{"navFocus":"HICAR_STOP","navPackageName":"..."}`
 * - 519 语音提示支持：`{"isVoicePromptSupport":1}`
 * - 523 虚拟调制解调器能力：`{"service":"virtualModem","subService":"capability","command":0}`
 * - 533 手机应用状态：`{"status":1,"appType":"video","appName":"..."}`
 * - 529 应用操作（APP_OPERATION 通道）：`{"RequestApp":{"Action":"start","AppPackage":"com.huawei.hicar"}}`
 *   （逆向官方车机 APK `ec/d`（CarAppStartMgr）确认：缺 `RequestApp` 键报 "invalid data struct"；
 *   `Action=="pause"` 是"重置 dock 和 activity 尺寸"，不是退出投屏；只有 `Action=="start"` 且
 *   `AppPackage=="com.huawei.hicar"`（即 HiCar 应用列表的"车辆"虚拟磁贴 BackToHiCarLauncher）表示
 *   用户点了磁贴、要求车机回到自己的桌面。）
 *
 * 「语义上等于退出投屏回车机桌面」的消息——529 车辆磁贴点击，以及 [CAR_DESKTOP_KEYS]
 * 兜底的未知字段——识别成 [DmsdpEvent.CarDesktopRequested]，上层收到就结束投屏、复用回车机桌面链路。
 *
 * 磁贴能不能点得动由手机侧决定：手机拿车机声明的 CAR_MODE_ID（设备信息 101 属性 2003，即
 * fullModel）比对官方 HiCarConfigs.xml 的 ConvergedLauncherController 白名单，不在名单里时
 * 手机判 "not support"、点击零发送。v10 起身份用名单内的 00048900。
 */
internal object ManagementMessages {

    /** 一条解析好的管理消息：[summary] 给诊断日志；[carDesktop] 表示手机要求退出投屏回车机桌面。 */
    class Parsed(val type: Int, val summary: String, val carDesktop: Boolean)

    /** module 1 上除文件分享管理（17）以外的消息都尝试按 JSON 解析。 */
    fun accepts(type: Int): Boolean = type != TYPE_MANAGEMENT

    /**
     * 解析成结构化消息。不是合法 JSON（二进制等）返回 null，调用方照旧走 UnhandledDeviceData。
     * 解析失败不抛异常：管理消息只通知，不能因为一条坏消息影响会话。
     */
    fun parse(type: Int, data: ByteArray): Parsed? {
        val json = runCatching { DeviceMessageHandler.parseObject(data, "管理消息 $type") }.getOrNull() ?: return null
        return Parsed(
            type,
            describe(type, json),
            carDesktop = json.keys.any { it in CAR_DESKTOP_KEYS } || isCarDesktopAppOperation(type, json),
        )
    }

    /**
     * type 529 车辆磁贴点击：`RequestApp.Action=="start"` 且 `AppPackage` 是 HiCar 车机应用包名。
     * 其他包名的 start 是手机要求车机开某个应用，不是退出投屏；`pause` 只是应用暂停。
     * 宁可漏认不可误认——误认会把正常会话拆掉。
     */
    private fun isCarDesktopAppOperation(type: Int, json: JsonObject): Boolean {
        if (type != TYPE_APP_OPERATION) return false
        val request = json[REQUEST_APP] as? JsonObject ?: return false
        return value(request, "Action") == "start" && value(request, "AppPackage") == CAR_LAUNCHER_PACKAGE
    }

    /** 已知类型给结构化摘要；未知类型列字段名，内容预览由 UnhandledDeviceData 的旧路径兜底。 */
    private fun describe(type: Int, json: JsonObject): String = when (type) {
        TYPE_CAPABILITY_QUERY -> "手机能力查询（${json.keys.joinToString()}）"
        TYPE_BRAND_ICON -> "车机品牌图标"
        TYPE_NAV_FOCUS ->
            "导航焦点（navFocus=${value(json, "navFocus")}，navPackageName=${value(json, "navPackageName")}）"
        TYPE_VOICE_PROMPT -> "语音提示支持（isVoicePromptSupport=${value(json, "isVoicePromptSupport")}）"
        TYPE_VIRTUAL_MODEM ->
            "虚拟调制解调器（service=${value(json, "service")}，subService=${value(json, "subService")}，" +
                "command=${value(json, "command")}）"
        TYPE_APP_STATUS ->
            "手机应用状态（appType=${value(json, "appType")}，appName=${value(json, "appName")}，" +
                "status=${value(json, "status")}）"
        TYPE_APP_OPERATION ->
            (json[REQUEST_APP] as? JsonObject)
                ?.let { request ->
                    "应用操作请求（Action=${value(request, "Action")}，AppPackage=${value(request, "AppPackage")}）"
                }
                ?: "应用操作请求（缺 $REQUEST_APP）"
        else -> "未知类型，字段（${json.keys.joinToString()}）"
    }

    private fun value(json: JsonObject, key: String): String = (json[key] as? JsonPrimitive)?.content ?: "?"

    /**
     * 车辆磁贴触发消息的兜底候选字段（529 已有确定协议，见 [isCarDesktopAppOperation]）：
     * 若手机还有别的退出报文形态，抓到后按真实字段扩充。宁可漏认不可误认。
     */
    private val CAR_DESKTOP_KEYS = setOf(
        "exitProjection",
        "stopProjection",
        "backToCarDesktop",
        "carDesktop",
        "exitMirror",
    )

    private const val TYPE_MANAGEMENT = 17
    private const val TYPE_CAPABILITY_QUERY = 500
    private const val TYPE_BRAND_ICON = 502
    private const val TYPE_NAV_FOCUS = 503
    private const val TYPE_VOICE_PROMPT = 519
    private const val TYPE_VIRTUAL_MODEM = 523
    private const val TYPE_APP_OPERATION = 529
    private const val TYPE_APP_STATUS = 533

    private const val REQUEST_APP = "RequestApp"

    /** HiCar 车机应用包名："车辆"磁贴（BackToHiCarLauncher）的 AppPackage。 */
    private const val CAR_LAUNCHER_PACKAGE = "com.huawei.hicar"
}
