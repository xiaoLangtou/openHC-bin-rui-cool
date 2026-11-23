package io.github.gcbond.openhc.demo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.gcbond.openhc.OpenHcApp

/**
 * 仅调试构建：让无线调度马上走一次车机主动回连（和手机蓝牙连上车机时走的是同一条路，[io.github.gcbond.openhc.wireless.WirelessController.reconnectPhone]），
 * 不用等手机蓝牙重连。正式包里没有。
 *
 * ```
 * adb shell am start -n io.github.gcbond.openhc/.demo.ReconnectNowActivity                    # 拨车机已配对的华为/荣耀手机
 * adb shell am start -n io.github.gcbond.openhc/.demo.ReconnectNowActivity -e mac AA:BB:CC:DD:EE:FF
 * adb shell am start -n io.github.gcbond.openhc/.demo.ReconnectNowActivity -e wait wifi_off -e after 5   # 等车机 WiFi 断开再拨
 * ```
 * 注意：同一台手机 60 秒内只拨一次（防重复触发的冷却）。
 */
class ReconnectNowActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 32) }
        setContentView(log)
        val mac = intent.getStringExtra("mac") ?: pairedHuaweiPhone()
        if (mac == null) {
            show("找不到车机已配对的华为/荣耀手机，请用 -e mac 指定")
            return
        }
        val dial: () -> Unit = {
            show("请无线调度拨手机 ${mac.take(8)}…（之后看首页和日志 OpenHC-Wireless）")
            (application as OpenHcApp).wireless.reconnectPhone(mac, "调试入口")
            main.postDelayed({ finish() }, 1_500)
            Unit
        }
        if (intent.getStringExtra("wait") == "wifi_off") {
            val after = intent.getStringExtra("after")?.toIntOrNull()?.coerceIn(1, 300) ?: 5
            show("等车机 WiFi 断开（关手机热点即可），断开 $after 秒后拨")
            DebugWifiWait.afterWifiOff(this, main, after, ::show, dial)
        } else {
            dial()
        }
    }

    private fun show(line: String) {
        Log.i(TAG, line)
        main.post { log.append(line + "\n\n") }
    }

    @SuppressLint("MissingPermission")
    private fun pairedHuaweiPhone(): String? = try {
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bondedDevices?.firstOrNull { d ->
            val n = d.name ?: ""
            listOf("HUAWEI", "HONOR", "Mate", "nova", "Pura", "华为", "荣耀").any { n.contains(it, ignoreCase = true) }
        }?.address
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        const val TAG = "OpenHC-ReconnectNow"
    }
}
