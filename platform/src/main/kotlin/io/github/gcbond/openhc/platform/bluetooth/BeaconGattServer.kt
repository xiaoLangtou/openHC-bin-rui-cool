package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.gcbond.openhc.core.nearby.NearbyGatt
import io.github.gcbond.openhc.core.nearby.NearbyGattResponder
import io.github.gcbond.openhc.platform.wifi.MacText
import java.util.ArrayDeque
import java.util.Timer
import java.util.TimerTask

/**
 * 和 BLE 广播一起挂出来的 GATT 服务端：按 [NearbyGatt] 的布局加两个主服务（前一个的添加回调到了再加下一个），
 * 请求按 [NearbyGattResponder] 的规则回应。
 *
 * 只「照布局挂好、按规则回应」，**不在 GATT 上跑控制通道**：实测的手机回连时不连 GATT；另一类手机系统可能会连上来
 * 取车机地址，那条路要先在真机上看到再做。所以每次连接、MTU、读、写、CCCD 都经 [log] 记下来
 * （特征、长度、是否像控制帧，不记内容），真机上据此判断有没有手机在用。
 *
 * **加服务有超时兜底**：本车机的 GOC 定制蓝牙栈（吉利/亿咖通 IHU536G 实测）对个别服务（046A，Nearby 的
 * legacy 服务）的添加请求会静默吞掉——既不回调成功也不回调失败，按「回调到了再加下一个」的顺序加就会永远卡住、
 * 广播也跟着起不来。所以每个服务挂上看门狗：超过 [ADD_TIMEOUT_MS] 没等到 onServiceAdded 就记日志跳过它，继续加
 * 后面的，保证 onReady 必达（跳过的服务在 GATT 表里不存在，走主服务 FE35 的通路不受影响）。
 *
 * 线程：[open]、[close] 任意线程；系统回调在系统的线程上，[Events] 也在那里回调，里面不要阻塞。[close] 可重复调用，
 * 之后不再回调。
 *
 * 权限：安卓 12 起要「附近的设备」权限（BLUETOOTH_CONNECT），没有时报 onFailed。
 */
class BeaconGattServer(
    context: Context,
    private val events: Events,
    private val log: (String) -> Unit,
) {
    interface Events {
        /** 两个服务都加好了。 */
        fun onReady()

        /** 开不了或加服务失败（不是本机关的）。 */
        fun onFailed(message: String)

        /** 有客户端连上了（地址已打码前的原文，调用方自己决定怎么记）。 */
        fun onClientConnected(address: String)
    }

    private val app = context.applicationContext ?: context
    private val responder = NearbyGattResponder()
    private val lock = Any()
    private var server: BluetoothGattServer? = null
    private val pending = ArrayDeque<NearbyGatt.Service>()
    private var current: NearbyGatt.Service? = null
    private var retries = 0
    private var started = false
    private var closed = false

    /** 当前这次 addService 的代号：回调或超时先核对代号再行动，过期的不再理。 */
    private var addSeq = 0

    /** 加服务看门狗（懒建）。 */
    private var addTimer: Timer? = null

    /** 开服务端、加服务。只能调用一次。 */
    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，下面按失败上报。
    @SuppressLint("MissingPermission")
    fun open() {
        synchronized(lock) {
            check(!started) { "open 只能调用一次" }
            started = true
        }
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return fail("没有蓝牙服务")
        val opened = try {
            manager.openGattServer(app, callback)
        } catch (e: SecurityException) {
            return fail("没有蓝牙权限（安卓 12 起要「附近的设备」权限）：${describe(e)}")
        } catch (e: RuntimeException) {
            return fail("开不了 GATT 服务端：${describe(e)}")
        } ?: return fail("系统没有给 GATT 服务端（蓝牙没开？）")
        val keep = synchronized(lock) {
            if (!closed) {
                server = opened
                pending.addAll(NearbyGatt.SERVICES)
            }
            !closed
        }
        if (!keep) return closeQuietly(opened)
        addNext()
    }

    /** 关掉服务端（撤掉服务、断开客户端）。可重复调用。 */
    @SuppressLint("MissingPermission")
    fun close() {
        val s = synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            current = null
            addSeq++
            addTimer?.cancel()
            addTimer = null
            server.also { server = null }
        } ?: return
        closeQuietly(s)
    }

    // ---------------------------------------------------------------- 加服务

    @SuppressLint("MissingPermission")
    private fun addNext() {
        while (true) {
            val s: BluetoothGattServer
            val next: NearbyGatt.Service?
            synchronized(lock) {
                s = server ?: return
                next = pending.pollFirst()
                current = next
                retries = 0
            }
            if (next == null) {
                log("GATT：${NearbyGatt.SERVICES.size} 个服务都处理完了")
                if (!isClosed()) events.onReady()
                return
            }
            val short = NearbyGatt.short(next.uuid)
            if (short in swallowedServices) {
                log("GATT：服务 $short 上次就被 GOC 栈吞了，这次直接跳过")
                continue
            }
            add(s, next)
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun add(s: BluetoothGattServer, spec: NearbyGatt.Service) {
        val short = NearbyGatt.short(spec.uuid)
        val seq: Int
        val ok = try {
            seq = synchronized(lock) { addSeq++; addSeq }
            s.addService(build(spec))
        } catch (e: RuntimeException) {
            log("GATT：加服务 $short 出错：${describe(e)}")
            return fail("加服务 $short 失败")
        }
        if (!ok) {
            fail("加服务 $short 失败")
        } else {
            armAddTimeout(seq, short)
        }
    }

    /**
     * 给这次 addService 挂看门狗：超过时限还没等到 onServiceAdded（GOC 定制栈对个别服务会静默吞掉，
     * 见类注释），就当作失败跳过，继续加下一个——顺序不能被一个不响应的服务堵死。
     */
    private fun armAddTimeout(seq: Int, uuid: String) {
        val task = object : TimerTask() {
            override fun run() {
                val proceed = synchronized(lock) {
                    seq == addSeq && current != null && server != null && !closed
                }
                if (!proceed) return
                swallowedServices.add(uuid)
                log("GATT：服务 $uuid 加了 $ADD_TIMEOUT_MS 毫秒还没等到系统回调（GOC 定制栈把它吞了），跳过继续，之后不再试")
                addNext()
            }
        }
        val timer = synchronized(lock) {
            addTimer ?: Timer("openhc-gatt-add-watchdog").also { addTimer = it }
        }
        try {
            timer.schedule(task, ADD_TIMEOUT_MS)
        } catch (e: IllegalStateException) {
            // Timer 已被 cancel（close 之后才触发的补挂）：不看了。
        }
    }

    private fun onServiceAdded(status: Int, uuid: String) {
        synchronized(lock) { addSeq++ } // 让还挂着的看门狗作废。
        if (status == BluetoothGatt.GATT_SUCCESS) {
            log("GATT：服务 $uuid 已加上")
            addNext()
            return
        }
        // 偶尔会失败（系统里上一个服务端还没撤干净）：同一个服务再试几次。
        val retry: Pair<NearbyGatt.Service, BluetoothGattServer>? = synchronized(lock) {
            val spec = current
            val s = server
            if (spec == null || s == null || retries >= MAX_ADD_RETRIES) {
                null
            } else {
                retries++
                Pair(spec, s)
            }
        }
        if (retry == null) {
            fail("加服务 $uuid 失败（状态 $status）")
        } else {
            log("GATT：加服务 $uuid 失败（状态 $status），重试")
            add(retry.second, retry.first)
        }
    }

    private fun build(spec: NearbyGatt.Service): BluetoothGattService {
        val service = BluetoothGattService(spec.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for (c in spec.characteristics) {
            val permissions = (if (c.readable) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                (if (c.writable) BluetoothGattCharacteristic.PERMISSION_WRITE else 0)
            // 属性位就是蓝牙规范的取值，与 BluetoothGattCharacteristic.PROPERTY_* 相同。
            val characteristic = BluetoothGattCharacteristic(c.uuid, c.properties, permissions)
            if (c.hasClientConfig) {
                // CCCD 的值由 responder 按客户端记，读写请求都自己回应，这里不设初值。
                characteristic.addDescriptor(
                    BluetoothGattDescriptor(
                        NearbyGatt.CLIENT_CONFIG,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            }
            service.addCharacteristic(characteristic)
        }
        return service
    }

    // ---------------------------------------------------------------- 系统回调

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (isClosed()) return
            this@BeaconGattServer.onServiceAdded(status, service?.uuid?.let { NearbyGatt.short(it) } ?: "?")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (isClosed()) return
            val address = addressOf(device)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                responder.connected(address)
                log("GATT：${MacText.mask(address)} 连上了（状态 $status）")
                events.onClientConnected(address)
            } else {
                responder.disconnected(address)
                log("GATT：${MacText.mask(address)} 断开（状态 $status，新状态 $newState）")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            if (!isClosed()) log("GATT：${MacText.mask(addressOf(device))} 协商 MTU $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?,
        ) {
            if (device == null || characteristic == null || isClosed()) return
            val address = addressOf(device)
            val reply = responder.readCharacteristic(address, characteristic.uuid, offset)
            log("GATT：${MacText.mask(address)} 读 ${NearbyGatt.short(characteristic.uuid)}（偏移 $offset）→ 状态 ${reply.status}")
            respond(device, requestId, reply.status, offset, reply.value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (device == null || characteristic == null || isClosed()) return
            val address = addressOf(device)
            val write = responder.writeCharacteristic(address, characteristic.uuid, preparedWrite, offset, value)
            if (write.reply.status != NearbyGattResponder.STATUS_SUCCESS || preparedWrite) {
                log(
                    "GATT：${MacText.mask(address)} 写 ${NearbyGatt.short(characteristic.uuid)}" +
                        "（${if (preparedWrite) "分段，" else ""}偏移 $offset，${value?.size ?: 0} 字节）→ 状态 ${write.reply.status}",
                )
            }
            if (responseNeeded) respond(device, requestId, write.reply.status, offset, write.reply.value)
            logReceived(address, write)
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            if (device == null || isClosed()) return
            val address = addressOf(device)
            val write = responder.executeWrite(address, execute)
            if (!execute) log("GATT：${MacText.mask(address)} 取消分段写")
            respond(device, requestId, write.reply.status, 0, null)
            logReceived(address, write)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?,
        ) {
            if (device == null || descriptor == null || isClosed()) return
            val address = addressOf(device)
            val owner = descriptor.characteristic?.uuid
            val reply = if (owner == null) {
                NearbyGattResponder.Reply(NearbyGattResponder.STATUS_READ_NOT_PERMITTED, null)
            } else {
                responder.readDescriptor(address, owner, descriptor.uuid, offset)
            }
            log("GATT：${MacText.mask(address)} 读 ${owner?.let { NearbyGatt.short(it) }} 的 ${NearbyGatt.short(descriptor.uuid)} → 状态 ${reply.status}")
            respond(device, requestId, reply.status, offset, reply.value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (device == null || descriptor == null || isClosed()) return
            val address = addressOf(device)
            val owner = descriptor.characteristic?.uuid
            val reply = if (owner == null || preparedWrite) {
                NearbyGattResponder.Reply(NearbyGattResponder.STATUS_WRITE_NOT_PERMITTED, null)
            } else {
                responder.writeDescriptor(address, owner, descriptor.uuid, offset, value)
            }
            val enabled = owner != null && responder.notificationsEnabled(address, owner)
            log(
                "GATT：${MacText.mask(address)} 写 ${owner?.let { NearbyGatt.short(it) }} 的 ${NearbyGatt.short(descriptor.uuid)}" +
                    " → 状态 ${reply.status}，通知${if (enabled) "开" else "关"}",
            )
            if (responseNeeded) respond(device, requestId, reply.status, offset, reply.value)
        }
    }

    private fun logReceived(address: String, write: NearbyGattResponder.Write) {
        val data = write.received ?: return
        val target = write.characteristic?.let { NearbyGatt.short(it) } ?: "?"
        log("GATT：${MacText.mask(address)} 写入 $target：${NearbyGattResponder.describe(data)}")
    }

    @SuppressLint("MissingPermission")
    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val s = synchronized(lock) { server } ?: return
        try {
            if (!s.sendResponse(device, requestId, status, offset, value)) log("GATT：回应请求 $requestId 失败")
        } catch (e: RuntimeException) {
            log("GATT：回应请求 $requestId 出错：${describe(e)}")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun fail(message: String) {
        val report = !isClosed()
        log("GATT：$message")
        close()
        if (report) events.onFailed(message)
    }

    private fun isClosed() = synchronized(lock) { closed }

    private companion object {
        /** 同一个服务加失败时最多再试几次。 */
        const val MAX_ADD_RETRIES = 2

        /** 加服务的看门狗时限：正常回调远快于此（实测约 200 毫秒）；只有被 GOC 栈吞掉的才等到超时。 */
        const val ADD_TIMEOUT_MS = 1_000L

        /**
         * 被 GOC 栈吞掉过的服务（进程内记忆）：第一次等到超时确认后记进来，之后每次开服务端直接跳过，
         * 不再白等。健康设备上永远不会有条目，跳过逻辑等于不存在。
         */
        val swallowedServices = java.util.Collections.synchronizedSet(HashSet<String>())

        @SuppressLint("MissingPermission")
        fun closeQuietly(s: BluetoothGattServer) {
            try {
                s.clearServices()
            } catch (e: RuntimeException) {
                // 蓝牙已经关了等：服务端本来也没了。
            }
            try {
                s.close()
            } catch (e: RuntimeException) {
                // 同上。
            }
        }

        fun addressOf(device: BluetoothDevice?): String = try {
            device?.address
        } catch (e: RuntimeException) {
            null
        } ?: ""

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
