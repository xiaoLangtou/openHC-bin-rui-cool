package io.github.gcbond.openhc.diagnostics

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.UsbAutoConnect
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.wireless.WirelessPermissions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「导出诊断日志」：开头一段摘要（版本、系统、车机型号、关键设置、缺的权限，不含地址和密钥），接着上次崩溃的记录和
 * [LogRecorder] 记下的运行日志，存成一个文本文件，车主报告问题时附上。
 *
 * 存到哪：安卓 10 起经系统的媒体库存进公共的「下载/OpenHC」（不要权限，文件管理里能看到）；
 * 更早的系统写公共下载目录要存储权限，所以存进本应用的外部目录（`Android/data/…/files/OpenHC`），那些系统的文件管理一般也看得到。
 *
 * 阻塞：要读写几 MB 文件，别在界面线程上调。
 */
internal object DiagnosticsExport {
    sealed class Result {
        /** 存好了；[location] 是给车主看的位置。 */
        class Saved(val location: String) : Result()

        class Failed(val reason: String) : Result()
    }

    fun export(context: Context): Result {
        val app = context.applicationContext ?: context
        LogRecorder.flush()
        val now = Date()
        val name = DiagnosticsReport.fileName(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToDownloads(app, name, now) else saveToAppFolder(app, name, now)
        } catch (e: IOException) {
            Result.Failed(describe(e))
        } catch (e: RuntimeException) {
            Result.Failed(describe(e))
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(app: Context, name: String, now: Date): Result {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${DiagnosticsReport.FOLDER}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Failed("系统不让在「下载」里建文件")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("打不开要写的文件")
            out.use { writeReport(app, it, now) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Result.Saved("下载/${DiagnosticsReport.FOLDER}/$name")
    }

    private fun saveToAppFolder(app: Context, name: String, now: Date): Result {
        val base = app.getExternalFilesDir(null) ?: return Result.Failed("读不到存储（可能没挂载）")
        val dir = File(base, DiagnosticsReport.FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Failed("建不了文件夹 ${dir.absolutePath}")
        val file = File(dir, name)
        FileOutputStream(file).use { writeReport(app, it, now) }
        return Result.Saved(file.absolutePath)
    }

    private fun writeReport(app: Context, out: OutputStream, now: Date) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(DiagnosticsReport.header(facts(app, now)))
        LogRecorder.crashFile()?.let {
            writer.write(DiagnosticsReport.section("上次崩溃的记录"))
            copy(it, writer)
        }
        val logs = LogRecorder.logFiles()
        writer.write(DiagnosticsReport.section(if (logs.isEmpty()) "运行日志（没有记录：可能这台车机不让本应用读日志）" else "运行日志（旧的在前）"))
        for (file in logs) copy(file, writer)
        writer.flush()
    }

    /** 逐行抄进导出文件，抄的时候遮掉完整地址（[DiagnosticsReport.redact]）。 */
    private fun copy(file: File, writer: Writer) {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                writer.write(DiagnosticsReport.redact(line))
                writer.write("\n")
            }
        }
    }

    private fun facts(app: Context, now: Date): List<Pair<String, String>> {
        val sdk = Build.VERSION.SDK_INT
        val wireless = WirelessPreferences(app)
        val metrics = app.resources.displayMetrics
        val missing = WirelessPermissions.missing(sdk) { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return listOf(
            "导出时间" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now),
            "OpenHC" to "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}，${BuildConfig.BUILD_TYPE}）",
            "系统" to "Android ${Build.VERSION.RELEASE}（API $sdk）",
            "车机" to "${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL}（${Build.DEVICE}，${Build.HARDWARE}）",
            "固件" to Build.DISPLAY,
            "屏幕" to "${metrics.widthPixels}x${metrics.heightPixels}，${metrics.densityDpi} dpi",
            "界面大小" to "${AppSettings(app).uiScalePercent}%",
            "连接参数" to if (IdentityLoader.load(app) is IdentityLoader.Result.Loaded) "已带" else "缺",
            "系统读得到车机蓝牙地址" to yesNo(DeviceInfo.systemBluetoothAddress() != null),
            "只用数据线连接" to yesNo(wireless.wiredOnly),
            "无线方式" to when {
                wireless.usesWifiDirect -> "WiFi 直连（试验）"
                wireless.wifiDirect -> "选了 WiFi 直连，但系统低于安卓 10，实际用热点"
                else -> "车机开热点"
            },
            "自动连接手机" to yesNo(wireless.connectOnBluetooth),
            "用无线连成功过的手机" to "${wireless.knownPhones.size} 台",
            "插线时不再弹窗" to yesNo(UsbAutoConnect.isEnabled(app)),
            "开机自动打开" to yesNo(BootStart.isEnabled(app)),
            "悬浮窗权限" to yesNo(BootStart.canDrawOverlays(app)),
            "无线缺的权限" to if (missing.isEmpty()) "无" else missing.joinToString("、") { it.substringAfterLast('.') },
        )
    }

    private fun yesNo(value: Boolean) = if (value) "是" else "否"

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
