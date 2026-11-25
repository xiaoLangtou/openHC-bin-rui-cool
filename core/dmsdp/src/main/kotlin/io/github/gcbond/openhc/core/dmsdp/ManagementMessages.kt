package io.github.gcbond.openhc.core.dmsdp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 管理通道（module 1）上手机发来的 JSON 消息。type 17 是文件分享管理（[IncomingFileTransfer]），不在这里。
 *
 * 已抓到的线上样本（鸿蒙手机，全部只是通知、不需要应答，此前一直落在 UnhandledDeviceData）：
 * - 500 能力查询：`{"VehicleControlCapability":{}}`
 * - 502 车机品牌图标：`{"brandIconData":"PNG"}`
 * - 503 导航焦点：`{"navFocus":"HICAR_STOP","navPackageName":"..."}`
 * - 519 语音提示支持：`{"isVoicePromptSupport":1}`
 * - 523 虚拟调制解调器能力：`{"service":"virtualModem","subService":"capability","command":0}`
 * - 528 应用列表互通请求：`{"RequestCaller":"..."}`（吉利包 AppDataProcessor 反汇编；回
 *   `{"RequestData":"all","RequestCaller":...,"RequestCode":0}`，见 [appTransRequestReply]）
 * - 533 手机应用状态：`{"status":1,"appType":"video","appName":"..."}`
 * - 529 应用操作（APP_OPERATION 通道）：`{"RequestApp":{"Action":"start","AppPackage":"com.huawei.hicar"}}`
 *   （逆向官方车机 APK `ec/d`（CarAppStartMgr）确认：缺 `RequestApp` 键报 "invalid data struct"；
 *   `Action=="pause"` 是"重置 dock 和 activity 尺寸"，不是退出投屏；只有 `Action=="start"` 且
 *   `AppPackage=="com.huawei.hicar"`（即 HiCar 应用列表的"车辆"虚拟磁贴 BackToHiCarLauncher）表示
 *   用户点了磁贴、要求车机回到自己的桌面。）
 *
 * 应用元数据（华为官方文档《元数据能力》《音频元数据》，dataType 514/515）：
 * - 514 车机→手机请求：`{"RequestAAData":7,"RequestCaller":"<车型modelId>"}`。RequestAAData 按位：
 *   bit1 音频、bit2 通话、bit3 导航（7=全要）；RequestCaller 必须是车型 modelId（同 2003 用的 00048900）。
 *   手机不主动推元数据——**车机必须先发这条请求**（2026-11-23 实弹验证：发出后手机秒回 ResponseAAData）。
 * - 514 手机→车机响应：`{"ResponseAAData":{"MEDIA":true,"CALL":true,"NAVIGATION":false}}`（按用户授权）。
 * - 515 音频元数据：`{"MediaData":{Name,Artist,TotalTime,ElapsedTime,AlbumName,Status,AppPackage,...}}`。
 *   HarmonyOS 6.0+ 的华为音乐/QQ 音乐带 `Lyric` 字段（标准 LRC：[mm:ss.xx]歌词）。车机收到必须回
 *   `{"MediaDataResp":{"RespCode":100000}}`，否则手机可能停止推送。
 * - 515 播放进度：`{"ElapsedTime":50}`（播放中每秒一条）。
 *
 * 「语义上等于退出投屏回车机桌面」的消息——529 车辆磁贴点击，以及 [CAR_DESKTOP_KEYS]
 * 兜底的未知字段——识别成 [DmsdpEvent.CarDesktopRequested]，上层收到就结束投屏、复用回车机桌面链路。
 *
 * 磁贴能不能点得动由手机侧决定：手机拿车机声明的 CAR_MODE_ID（设备信息 101 属性 2003，即
 * fullModel）比对官方 HiCarConfigs.xml 的 ConvergedLauncherController 白名单，不在名单里时
 * 手机判 "not support"、点击零发送。v10 起身份用名单内的 00048900。
 */
internal object ManagementMessages {

    /** 一条解析好的管理消息：[summary] 给诊断日志；[carDesktop] 表示手机要求退出投屏回车机桌面；
     *  [reply] 非空时调用方要把它作为同类型消息发回手机（如 515 的 MediaDataResp）；
     *  [mediaData] 非空表示这是一条音频元数据（515 MediaData），结构化数据已提取。 */
    class Parsed(
        val type: Int,
        val summary: String,
        val carDesktop: Boolean,
        val reply: JsonObject? = null,
        val mediaData: JsonObject? = null,
    )

    /** module 1 上除文件分享管理（17）以外的消息都尝试按 JSON 解析。 */
    fun accepts(type: Int): Boolean = type != TYPE_MANAGEMENT

    /** 应用元数据请求的类型号（514），见 [metadataRequest]。 */
    const val METADATA_REQUEST_TYPE = 514

    /**
     * 解析成结构化消息。不是合法 JSON（二进制等）返回 null，调用方照旧走 UnhandledDeviceData。
     * 解析失败不抛异常：管理消息只通知，不能因为一条坏消息影响会话。
     */
    fun parse(type: Int, data: ByteArray): Parsed? {
        val json = runCatching { DeviceMessageHandler.parseObject(data, "管理消息 $type") }.getOrNull() ?: return null
        val mediaData = if (type == TYPE_MEDIA_DATA) {
            (json[KEY_MEDIA_DATA] as? JsonObject)
                ?: json.takeIf {
                    it.containsKey(KEY_ELAPSED_TIME) || it.containsKey("Name") || it.containsKey("Status")
                }
        } else {
            null
        }
        val needsResp = mediaData != null || (type == TYPE_MEDIA_DATA && json.containsKey(KEY_ELAPSED_TIME))
        val reply = when {
            needsResp -> mediaDataResp()
            // 官方 AppDataProcessor 反汇编：手机发 528 {"RequestCaller":...} 请求应用列表互通，
            // 车机回 {"RequestData":"all","RequestCaller":...,"RequestCode":0}（0=允许推送，-57=无数据）。
            type == TYPE_APP_TRANS && json.containsKey(KEY_REQUEST_CALLER) ->
                appTransRequestReply(value(json, KEY_REQUEST_CALLER))
            else -> null
        }
        return Parsed(
            type,
            describe(type, json),
            carDesktop = json.keys.any { it in CAR_DESKTOP_KEYS } || isCarDesktopAppOperation(type, json),
            reply = reply,
            mediaData = mediaData,
        )
    }

    /**
     * type 514 元数据请求（车机→手机）：请求全量应用元数据掩码 127（0x7F，与吉利原厂一致，含 bit0 音频、bit1 通话、bit2 导航等）。
     * [RequestCaller] 按官方文档必须是车型 modelId，与设备信息 101 属性 2003 一致。
     */
    fun metadataRequest(fullModel: String): JsonObject = buildJsonObject {
        put(KEY_REQUEST_AA_DATA, REQUEST_AA_DATA_ALL)
        put(KEY_REQUEST_CALLER, fullModel)
    }

    /** 从 515 JSON 对象（可能是 MediaData 子对象，也可能是顶层 ElapsedTime 进度对象）提取结构化字段。 */
    fun extractMediaData(media: JsonObject): DmsdpMediaData {
        val artArray = media["AlbumArtIcon"] as? JsonArray
        val artBytes = if (artArray != null && artArray.isNotEmpty()) {
            ByteArray(artArray.size) { idx ->
                ((artArray[idx] as? JsonPrimitive)?.intOrNull ?: 0).toByte()
            }
        } else {
            null
        }
        return DmsdpMediaData(
            title = optString(media, "Name"),
            artist = optString(media, "Artist"),
            album = optString(media, "AlbumName"),
            appName = optString(media, "AppName"),
            appPackage = optString(media, "AppPackageName") ?: optString(media, "AppPackage"),
            totalTimeSec = optInt(media, "TotalTime"),
            elapsedTimeSec = optInt(media, KEY_ELAPSED_TIME),
            status = optInt(media, "Status"),
            albumArtBytes = artBytes,
            albumArtUrl = optString(media, "AlbumArtURL"),
            lyricContent = optString(media, "lyricContent") ?: optString(media, "Lyrics") ?: optString(media, "Lyric"),
            currentLyric = optString(media, "currentLyricSentence") ?: optString(media, "CurrentLyric"),
        )
    }

    private fun optString(json: JsonObject, key: String): String? =
        (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun optInt(json: JsonObject, key: String): Int? =
        (json[key] as? JsonPrimitive)?.intOrNull

    /** type 515 元数据接收应答（车机→手机）：100000 = 接收成功。 */
    private fun mediaDataResp(): JsonObject = buildJsonObject {
        put(KEY_MEDIA_DATA_RESP, buildJsonObject { put(KEY_RESP_CODE, RESP_CODE_OK) })
    }

    /**
     * type 528 应用列表互通请求应答（车机→手机）。官方流程（吉利包 com.huawei.managementsdk.launcher
     * AppDataProcessor 反汇编实锤 2026-11-23）：手机发 `{"RequestCaller":...}` 询问，车机回
     * `{"RequestData":"all","RequestCaller":...,"RequestCode":0}` 允许推送；随后手机发
     * `{"RequestAppResp"/"AppDataUpdate":{"TotalNum":N,"App":[...]}}`（增删改带 `Action`），
     * 车机回执 `RequestCode` 0/-57 + `ReceiptAction`。先回 0 观察手机是否真的推列表。
     */
    private fun appTransRequestReply(caller: String): JsonObject = buildJsonObject {
        put(KEY_REQUEST_DATA, "all")
        put(KEY_REQUEST_CALLER, caller)
        put(KEY_REQUEST_CODE, REQUEST_CODE_OK)
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
        TYPE_APP_TRANS ->
            "应用列表互通（RequestCaller=${value(json, KEY_REQUEST_CALLER)}，字段：${json.keys.joinToString()}）"
        TYPE_METADATA_REQUEST ->
            if (json.containsKey(KEY_RESPONSE_AA_DATA)) {
                val granted = json[KEY_RESPONSE_AA_DATA] as? JsonObject
                "元数据授权（MEDIA=${value(granted ?: json, "MEDIA")}，CALL=${value(granted ?: json, "CALL")}，" +
                    "NAVIGATION=${value(granted ?: json, "NAVIGATION")}）"
            } else {
                "元数据请求（RequestAAData=${value(json, KEY_REQUEST_AA_DATA)}，" +
                    "RequestCaller=${value(json, KEY_REQUEST_CALLER)}）"
            }
        TYPE_MEDIA_DATA ->
            (json[KEY_MEDIA_DATA] as? JsonObject)?.let { media ->
                "音频元数据（Name=${value(media, "Name")}，Artist=${value(media, "Artist")}，" +
                    "Status=${value(media, "Status")}，AppPackage=${value(media, "AppPackage")}，" +
                    "TotalTime=${value(media, "TotalTime")}，ElapsedTime=${value(media, "ElapsedTime")}，" +
                    "含歌词=${media.containsKey("Lyric")}）"
            } ?: if (json.containsKey(KEY_ELAPSED_TIME)) {
                "播放进度（ElapsedTime=${value(json, KEY_ELAPSED_TIME)}）"
            } else {
                "元数据应答（字段：${json.keys.joinToString()}）"
            }
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
    private const val TYPE_APP_TRANS = 528
    private const val TYPE_APP_OPERATION = 529
    private const val TYPE_APP_STATUS = 533

    private const val REQUEST_APP = "RequestApp"

    /** HiCar 车机应用包名："车辆"磁贴（BackToHiCarLauncher）的 AppPackage。 */
    private const val CAR_LAUNCHER_PACKAGE = "com.huawei.hicar"

    // ---- 应用列表互通（type 528，官方 AppDataProcessor/AppTransManager 反汇编）----

    private const val KEY_REQUEST_DATA = "RequestData"
    private const val KEY_REQUEST_CODE = "RequestCode"

    /** RequestCode：0 = 允许/成功，-57 = 无数据（官方 replyRequestResult 失败分支）。 */
    private const val REQUEST_CODE_OK = 0

    // ---- 应用元数据（dataType 514/515，华为官方文档《元数据能力》《音频元数据》）----

    private const val TYPE_METADATA_REQUEST = 514
    private const val TYPE_MEDIA_DATA = 515
    private const val KEY_REQUEST_AA_DATA = "RequestAAData"
    private const val KEY_REQUEST_CALLER = "RequestCaller"
    private const val KEY_RESPONSE_AA_DATA = "ResponseAAData"
    private const val KEY_MEDIA_DATA = "MediaData"
    private const val KEY_MEDIA_DATA_RESP = "MediaDataResp"
    private const val KEY_ELAPSED_TIME = "ElapsedTime"
    private const val KEY_RESP_CODE = "RespCode"

    /** RequestAAData 位定义：bit0 音频(1)、bit1 通话(2)、bit2 导航(4)；吉利原厂请求 127 (0x7F)。 */
    private const val REQUEST_AA_DATA_MEDIA = 1
    private const val REQUEST_AA_DATA_CALL = 2
    private const val REQUEST_AA_DATA_NAVIGATION = 4
    private const val REQUEST_AA_DATA_ALL = 127

    /** MediaDataResp.RespCode：100000 接收成功（100001 接收失败）。 */
    private const val RESP_CODE_OK = 100000
}
