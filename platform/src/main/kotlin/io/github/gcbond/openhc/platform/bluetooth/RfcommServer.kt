package io.github.gcbond.openhc.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * 经典蓝牙 RFCOMM 服务端：不加密、不要求认证，带服务记录（SDP 里登记 [uuid]；服务名 [serviceName] 随意，手机不看），
 * 只接受一条连接。无线连接的蓝牙控制通道、认证子通道各用一个。
 *
 * 线程：[start] 之后一个线程依次「开监听 → 读通道号（[RfcommChannel]）→ onListening → accept → onAccepted → 读」，
 * 回调都在这个线程上按顺序发生；写在另一个线程上排队按顺序做，[write] 不阻塞。
 *
 * 关闭：关监听只撤掉服务记录、不再接受连接，已接受的连接不受影响；关连接先把排着的写发完（最多等 [FLUSH_LIMIT_MS]），
 * 刚写过就再稍等一下让数据发出去，然后关掉。本机关掉的监听和连接不再回调。
 *
 * 安卓 12 起开监听要先拿到「附近的设备」权限（BLUETOOTH_CONNECT），没有时报 onListenFailed。
 */
class RfcommServer(
    private val adapter: BluetoothAdapter,
    private val serviceName: String,
    private val uuid: UUID,
    private val events: RfcommEvents,
    private val threadName: String = "OpenHC-Rfcomm",
) : RfcommEndpoint {

    private val lock = Any()
    private var started = false
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var listenerClosed = false
    private var connectionClosed = false

    /** 待写的数据（ByteArray）和关闭标记 [CLOSE]，写线程按顺序处理。 */
    private val writes = LinkedBlockingQueue<Any>()

    @Volatile
    private var lastWriteAt = 0L

    override fun start() {
        synchronized(lock) {
            check(!started) { "start 只能调用一次" }
            started = true
        }
        daemon(threadName) { run() }
    }

    override fun write(bytes: ByteArray) {
        if (isConnectionClosed()) return
        writes.add(bytes)
    }

    override fun closeListener() {
        val s = synchronized(lock) {
            if (listenerClosed) return
            listenerClosed = true
            server
        }
        // 关掉就撤掉了服务记录；正卡在 accept 上的线程随之出错退出，不再上报。
        s?.let { closeQuietly(it) }
    }

    override fun closeConnection() {
        val client = synchronized(lock) {
            if (connectionClosed) return
            connectionClosed = true
            socket
        } ?: return // 还没接受连接：之后接受到的直接关掉。
        writes.add(CLOSE)
        // 对端不收、写被卡住时兜底：到时间直接关，卡住的写随之出错。
        daemon("$threadName-close") {
            sleepQuietly(FLUSH_LIMIT_MS)
            closeQuietly(client)
        }
    }

    // ---------------------------------------------------------------- 监听线程

    // 权限由调用方在开始前确认；没有时系统抛 SecurityException，下面按开监听失败上报。
    // 开监听的 IOException/RuntimeException（典型：快速重启后通道或 SDP 记录还没释放，IDException -7）
    // 通常是瞬时的：有限次退避重试（issue #12），权限问题不重试。
    @SuppressLint("MissingPermission")
    private fun run() {
        var opened: BluetoothServerSocket? = null
        var attempt = 0
        while (opened == null) {
            attempt++
            try {
                opened = adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid)
            } catch (e: SecurityException) {
                return failListen("没有蓝牙权限（安卓 12 起要「附近的设备」权限）：${describe(e)}")
            } catch (e: IOException) {
                if (attempt > LISTEN_RETRY_LIMIT || isListenerClosed()) return failListen("开监听失败：${describe(e)}")
            } catch (e: RuntimeException) {
                if (attempt > LISTEN_RETRY_LIMIT || isListenerClosed()) return failListen("开监听失败：${describe(e)}")
            }
            if (opened == null) {
                sleepQuietly(attempt * LISTEN_RETRY_DELAY_MS)
                if (isListenerClosed()) return
            }
        }
        val keep = synchronized(lock) {
            if (!listenerClosed) server = opened
            !listenerClosed
        }
        if (!keep) return closeQuietly(opened)
        val channel = RfcommChannel.read(opened)
        if (!isListenerClosed()) events.onListening(channel)

        val client = try {
            opened.accept()
        } catch (e: IOException) {
            if (!isListenerClosed()) events.onListenFailed("等连接时出错：${describe(e)}")
            return
        }
        val accepted = synchronized(lock) {
            if (!connectionClosed) socket = client
            !connectionClosed
        }
        if (!accepted) return closeQuietly(client)
        val address = try {
            client.remoteDevice?.address
        } catch (e: RuntimeException) {
            null
        } ?: ""
        daemon("$threadName-write") { writeLoop(client) }
        if (!isConnectionClosed()) events.onAccepted(address)
        readLoop(client)
    }

    private fun readLoop(client: BluetoothSocket) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            val input = client.inputStream
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    if (!isConnectionClosed()) events.onClosed()
                    return
                }
                if (n > 0 && !isConnectionClosed()) events.onData(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            if (isConnectionClosed()) return
            // 对端关闭时安卓的蓝牙 socket 不返回 -1，而是抛出带「read return: -1」的异常。
            if (e.message?.contains(PEER_CLOSED_TEXT) == true) events.onClosed() else events.onConnectionError("读失败：${describe(e)}")
        }
    }

    // ---------------------------------------------------------------- 写线程

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
            // 关闭前刚写过：稍等一下，免得最后一帧还在系统缓冲里就随连接一起被丢掉。
            val sinceWrite = SystemClock.elapsedRealtime() - lastWriteAt
            if (lastWriteAt != 0L && sinceWrite < LINGER_MS) sleepQuietly(LINGER_MS - sinceWrite)
        } catch (e: IOException) {
            if (!isConnectionClosed()) events.onConnectionError("写失败：${describe(e)}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeQuietly(client)
    }

    // ---------------------------------------------------------------- 工具

    private fun failListen(message: String) {
        if (!isListenerClosed()) events.onListenFailed(message)
    }

    private fun isListenerClosed() = synchronized(lock) { listenerClosed }

    private fun isConnectionClosed() = synchronized(lock) { connectionClosed }

    private companion object {
        /** 关闭标记。 */
        val CLOSE = Any()

        /** 关连接时等排着的写发完的上限。 */
        const val FLUSH_LIMIT_MS = 1_500L

        /** 刚写完就关时多等这么久。 */
        const val LINGER_MS = 200L

        /** RFCOMM 一帧通常不到 1 KB，读缓冲给足。 */
        const val READ_BUFFER_BYTES = 4096

        /** 开监听瞬时失败（通道/SDP 记录未释放）的重试次数与基础退避：0.5s、1s、1.5s、2s（2026-11-23 收紧：
         *  上次会话刚收尾就回连时，等满 1+2+3+4 秒太久；失败原因（系统释放记录）一般亚秒级就绪）。 */
        const val LISTEN_RETRY_LIMIT = 4
        const val LISTEN_RETRY_DELAY_MS = 500L

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
