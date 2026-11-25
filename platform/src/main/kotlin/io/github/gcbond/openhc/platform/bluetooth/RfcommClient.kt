package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * 经典蓝牙 RFCOMM 客户端：主动连手机的 RFCOMM 服务（不加密、不要求认证），用于车机主动回连，和 [RfcommServer] 相对。
 *
 * 怎么连：[channel] 为 null 时按 [uuid] 查手机的 SDP 记录再连（例如手机的蓝牙控制服务 FE35）。
 * 不为 null 时先按这个通道号直连（手机在应答里给的认证子通道；原厂也这么连，用的是系统的隐藏接口），
 * 拿不到这个接口时退回按 [uuid] 查 SDP（手机给这个通道也登记了 SDP 记录）。走的是哪条路经 [Events.onNote] 报出来。
 * 连之前先停掉本机的蓝牙扫描（扫描会拖慢连接）。
 *
 * 线程：[start] 之后一个线程「取设备 → 建 socket → connect → onConnected → 读」；写在另一个线程上排队。回调按
 * `onConnected` → `onData`… → `onClosed` / `onConnectionError` 的顺序发生；连不上是 `onConnectFailed`。
 * 读写循环、排队写、关闭逻辑和 [RfcommServer] 一致。本机关掉之后不再回调。
 *
 * 安卓 12 起连接要「附近的设备」权限（BLUETOOTH_CONNECT），没有时报 onConnectFailed。
 */
class RfcommClient(
    private val adapter: BluetoothAdapter,
    private val remoteAddress: String,
    private val uuid: UUID,
    private val events: Events,
    private val threadName: String = "OpenHC-RfcommClient",
    private val channel: Int? = null,
) : RfcommEndpoint {

    /** 主动连接的事件，在连接线程上按顺序回调，回调里不要阻塞。 */
    interface Events {
        /** 连上了。 */
        fun onConnected()

        /** 没连上（地址不对、对端没在听、没权限、SDP 查不到服务等）。 */
        fun onConnectFailed(message: String)

        /** 收到的数据，新数组，归调用方。 */
        fun onData(data: ByteArray)

        /** 对端关闭了连接。 */
        fun onClosed()

        /** 连接上读写出错（不是本机关的）。 */
        fun onConnectionError(message: String)

        /** 只用于诊断的说明（走的是哪条路之类），可以直接写日志，不含设备地址。 */
        fun onNote(message: String) {}
    }

    private val lock = Any()
    private var started = false
    private var socket: BluetoothSocket? = null

    /** 正在 connect 的 socket（还没连上）：关闭时一起关掉，免得停止之后才连上手机又马上断开。 */
    private var connecting: BluetoothSocket? = null
    private var closed = false

    private val writes = LinkedBlockingQueue<Any>()

    @Volatile
    private var lastWriteAt = 0L

    /** 开始：取设备、连接。只能调一次。 */
    override fun start() {
        synchronized(lock) {
            check(!started) { "start 只能调用一次" }
            started = true
        }
        daemon(threadName) { run() }
    }

    /** 把 [bytes] 按顺序写到连接上；已关闭时丢掉，还没连上时排队等连上再写。 */
    override fun write(bytes: ByteArray) {
        if (isClosed()) return
        writes.add(bytes)
    }

    /** 客户端没有监听，什么都不做。 */
    override fun closeListener() = Unit

    /** 关连接：先把排着的写发完（有上限），再关。还没连上时直接打断正在进行的连接。 */
    override fun closeConnection() = close()

    fun close() {
        val (client, pending) = synchronized(lock) {
            if (closed) return
            closed = true
            socket to connecting
        }
        if (client == null) {
            // 还在连：关掉它，connect 会抛异常退出（关闭后不再上报失败）。
            pending?.let { closeQuietly(it) }
            return
        }
        writes.add(CLOSE)
        daemon("$threadName-close") {
            sleepQuietly(FLUSH_LIMIT_MS)
            closeQuietly(client)
        }
    }

    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，按连接失败上报。
    @SuppressLint("MissingPermission")
    private fun run() {
        val device = try {
            adapter.getRemoteDevice(remoteAddress)
        } catch (e: IllegalArgumentException) {
            return failConnect("蓝牙地址不对：${describe(e)}")
        }
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // 安卓 12 起停扫描要扫描权限；没有就不停，只是连得慢些。
        }
        val client = try {
            openByChannel(device) ?: device.createInsecureRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            return failConnect("建 socket 失败：${describe(e)}")
        } catch (e: SecurityException) {
            return failConnect("没有蓝牙权限（安卓 12 起要「附近的设备」权限）：${describe(e)}")
        }
        val proceed = synchronized(lock) {
            if (!closed) connecting = client
            !closed
        }
        if (!proceed) return closeQuietly(client)
        // 按 SDP 连时 connect 先查服务的通道号，再建链；对端没在听或查不到服务都在这里失败。
        try {
            client.connect()
        } catch (e: IOException) {
            closeQuietly(client)
            return failConnect("连接失败：${describe(e)}")
        } catch (e: SecurityException) {
            closeQuietly(client)
            return failConnect("没有蓝牙权限：${describe(e)}")
        }
        val keep = synchronized(lock) {
            connecting = null
            if (!closed) socket = client
            !closed
        }
        if (!keep) return closeQuietly(client)
        daemon("$threadName-write") { writeLoop(client) }
        if (!isClosed()) events.onConnected()
        readLoop(client)
    }

    /** 按 [channel] 直连用的 socket；没给通道号或拿不到系统接口时返回 null（改按 SDP）。 */
    private fun openByChannel(device: BluetoothDevice): BluetoothSocket? {
        val ch = channel ?: return null
        return try {
            val method = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            (method.invoke(device, ch) as BluetoothSocket).also { note("按通道号 $ch 直连") }
        } catch (e: ReflectiveOperationException) {
            note("按通道号直连的系统接口用不了（${describe(e)}），改按 SDP 查 $uuid")
            null
        } catch (e: SecurityException) {
            note("按通道号直连被系统拦了（${describe(e)}），改按 SDP 查 $uuid")
            null
        }
    }

    private fun readLoop(client: BluetoothSocket) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            val input = client.inputStream
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    if (!isClosed()) events.onClosed()
                    return
                }
                if (n > 0 && !isClosed()) events.onData(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            if (isClosed()) return
            if (e.message?.contains(PEER_CLOSED_TEXT) == true) events.onClosed() else events.onConnectionError("读失败：${describe(e)}")
        }
    }

    private fun writeLoop(client: BluetoothSocket) {
        try {
            val output = client.outputStream
            while (true) {
                val item = writes.take()
                if (item === CLOSE) break
                output.write(item as ByteArray)
                output.flush()
                lastWriteAt = SystemClock.elapsedRealtime()
            }
            val sinceWrite = SystemClock.elapsedRealtime() - lastWriteAt
            if (lastWriteAt != 0L && sinceWrite < LINGER_MS) sleepQuietly(LINGER_MS - sinceWrite)
        } catch (e: IOException) {
            if (!isClosed()) events.onConnectionError("写失败：${describe(e)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly(client)
    }

    private fun failConnect(message: String) {
        if (!isClosed()) events.onConnectFailed(message)
    }

    private fun note(message: String) {
        if (!isClosed()) events.onNote(message)
    }

    private fun isClosed() = synchronized(lock) { closed }

    private companion object {
        val CLOSE = Any()
        const val FLUSH_LIMIT_MS = 1_500L
        const val LINGER_MS = 200L
        const val READ_BUFFER_BYTES = 4096
        const val PEER_CLOSED_TEXT = "read return: -1"

        fun daemon(name: String, body: () -> Unit) {
            val t = Thread(body, name)
            t.isDaemon = true
            t.start()
        }

        fun sleepQuietly(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // 关闭时的错误没有意义。
            }
        }

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
