package io.github.gcbond.openhc.core.session

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用 java.net 阻塞 I/O 执行 [SessionLogic] 给出的动作，安卓和电脑通用（不引用安卓类）。
 *
 * 线程：
 * - 一个**协议线程**：会话的所有方法、全部写操作、事件回调都在这个线程上串行执行；
 * - 每个监听、连接、UDP socket 一个线程：监听线程 accept 到连接后就接着当这条连接的读线程，
 *   连接线程 connect 成功后同样接着读。读到的数据经队列交给协议线程。
 * - 每个监听只接受一条连接；之后监听仍开着（端口留着，不被系统分给别的监听），另起一个线程把再连进来的立刻关掉并记日志，
 *   免得对端以为连上了、却一直没人理。
 *
 * 读缓冲：每条连接固定几块缓冲轮流用，协议线程处理完一块才还回去，不为每次读另外分配或复制；
 * 协议线程跟不上时读线程会停下来等，由 TCP 自己做流控，内存有上限。
 *
 * 关闭顺序：会话结束时自己会按层由上到下输出关闭动作（见 [ReceiverSession]），执行器照做；
 * 之后协议线程再把还没关的资源按创建的反序关掉（正常情况下已经没有了），等所有线程退出，才算结束。
 * [close] 先请会话正常收尾，超时（例如某次写被对端卡住）就从调用线程直接关掉全部 socket。
 *
 * 用法：`start()` 之后，用 [stop] 请求结束（非阻塞），[awaitTermination] 等结束；或者直接 [close]。
 */
class SocketRunner(
    private val session: SessionLogic,
    /** 会话事件，在协议线程上回调；回调里不要阻塞太久，抛出的异常只记日志。 */
    private val events: (SessionEvent) -> Unit,
    private val options: Options = Options(),
) : Closeable {

    class Options(
        /** 主动连接的超时（毫秒）。 */
        val connectTimeoutMillis: Int = 3_000,
        /** 每块读缓冲的大小。 */
        val readBufferBytes: Int = 64 * 1024,
        /** 每条连接轮流使用的读缓冲块数。 */
        val buffersPerConnection: Int = 2,
        val listenBacklog: Int = 4,
        /** [close] 等会话正常收尾的时间，以及收尾后等各线程退出的时间（毫秒）。 */
        val stopGraceMillis: Long = 3_000,
        /** 线程名前缀。 */
        val threadName: String = "openhc-session",
        /** 执行器自己的诊断日志（不含收发的内容）。 */
        val log: (String) -> Unit = {},
    ) {
        init {
            require(connectTimeoutMillis > 0) { "connectTimeoutMillis 必须为正" }
            require(readBufferBytes >= 1024) { "readBufferBytes 至少 1024" }
            require(buffersPerConnection >= 1) { "buffersPerConnection 至少 1" }
            require(listenBacklog >= 1) { "listenBacklog 至少 1" }
            require(stopGraceMillis > 0) { "stopGraceMillis 必须为正" }
        }
    }

    private sealed class Input {
        class Accepted(val id: Int, val remoteHost: String) : Input()
        class Connected(val id: Int) : Input()
        class Data(val id: Int, val buffer: ByteArray, val length: Int, val pool: BufferPool) : Input()
        class Datagram(val id: Int, val buffer: ByteArray, val length: Int, val pool: BufferPool) : Input()
        class Closed(val id: Int) : Input()
        class Failed(val id: Int, val message: String) : Input()
        class Task(val block: (Long) -> List<SessionOutput>) : Input()
        object Stop : Input()
    }

    /** 一条连接的读缓冲：固定几块轮流用。 */
    private class BufferPool(count: Int, size: Int) {
        private val free = ArrayBlockingQueue<ByteArray>(count)

        init {
            repeat(count) { free.add(ByteArray(size)) }
        }

        /** 等一块空闲缓冲；资源已关闭时返回 null。 */
        fun take(closed: AtomicBoolean): ByteArray? {
            while (!closed.get()) {
                val buffer = try {
                    free.poll(POOL_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    return null
                }
                if (buffer != null) return buffer
            }
            return null
        }

        fun give(buffer: ByteArray) {
            free.offer(buffer)
        }
    }

    private abstract class Resource(val id: Int) {
        /** 先置位再关 socket：读线程据此知道异常是本机关闭造成的，不再上报。 */
        val closed = AtomicBoolean(false)

        fun close() {
            if (closed.compareAndSet(false, true)) closeSocket()
        }

        protected abstract fun closeSocket()
    }

    private class ListenerResource(id: Int, val server: ServerSocket) : Resource(id) {
        override fun closeSocket() = closeQuietly(server)
    }

    private class ConnectionResource(id: Int, val socket: Socket, buffers: Int, size: Int) : Resource(id) {
        val pool = BufferPool(buffers, size)
        override fun closeSocket() = closeQuietly(socket)
    }

    private class UdpResource(id: Int, val socket: DatagramSocket, buffers: Int, size: Int) : Resource(id) {
        val pool = BufferPool(buffers, size)
        override fun closeSocket() = closeQuietly(socket)
    }

    private val queue = LinkedBlockingQueue<Input>()

    /** 同步动作的结果（监听开好、写失败等），在当前这批动作执行完后、处理下一个外部输入之前交给会话。只在协议线程上用。 */
    private val immediate = java.util.ArrayDeque<() -> List<SessionOutput>>()

    private val lock = Any()
    private val resources = LinkedHashMap<Int, Resource>()
    private val threads = ArrayList<Thread>()
    private var closingAll = false
    private var started = false

    @Volatile
    private var loopThread: Thread? = null

    /** [close] 等不到正常收尾时置位：协议线程不再等会话结束，直接退出。 */
    @Volatile
    private var abandoned = false
    private val terminated = CountDownLatch(1)

    /** 协议线程因意外异常结束时的异常（会话自己的错误不算，那些以 [SessionEvent.Ended] 结束）。 */
    @Volatile
    var failure: Throwable? = null
        private set

    val isTerminated: Boolean get() = terminated.count == 0L

    /** 启动协议线程；会话的 [SessionLogic.start] 在协议线程上调用。只能调用一次。 */
    fun start() {
        synchronized(lock) {
            check(!started) { "start 只能调用一次" }
            started = true
        }
        val thread = Thread({ runLoop() }, options.threadName)
        thread.isDaemon = true
        loopThread = thread
        thread.start()
    }

    /** 请求本机结束会话（会话按协议收尾）。不阻塞，可以从任何线程调用、重复调用。 */
    fun stop() {
        queue.add(Input.Stop)
    }

    /** 在协议线程上执行 [task]（例如 `session.sendInput(…)`），执行它返回的动作。参数是当前单调时钟毫秒。 */
    fun submit(task: (Long) -> List<SessionOutput>) {
        queue.add(Input.Task(task))
    }

    /** 等协议线程结束（全部资源已关闭、全部线程已退出）。 */
    fun awaitTermination(timeoutMillis: Long): Boolean = terminated.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /**
     * 结束并等待：先 [stop] 请会话正常收尾；[Options.stopGraceMillis] 内没结束，就从当前线程直接关掉全部 socket
     * （卡住的写随之失败），再等一次。在协议线程上（例如事件回调里）调用时只请求结束、不等待。
     */
    override fun close() {
        synchronized(lock) {
            if (!started) {
                started = true
                terminated.countDown()
                return
            }
        }
        stop()
        if (Thread.currentThread() === loopThread) return
        if (awaitTermination(options.stopGraceMillis)) return
        options.log("会话没有按时结束，强制关闭全部连接")
        abandoned = true
        val all = synchronized(lock) { resources.values.toList() }
        for (resource in all) resource.close()
        queue.add(Input.Stop)
        if (!awaitTermination(options.stopGraceMillis)) options.log("强制关闭后协议线程仍未结束")
    }

    // ---------------- 协议线程 ----------------

    private fun runLoop() {
        try {
            execute(session.start(now()))
            drainImmediate()
            while (!session.isEnded && !abandoned) {
                val input = queue.poll(waitMillis(), TimeUnit.MILLISECONDS)
                if (abandoned) break
                if (input != null) dispatch(input)
                drainImmediate()
                val due = session.nextTickAtMillis
                if (!session.isEnded && due != null) {
                    val now = now()
                    if (now >= due) {
                        execute(session.onTick(now))
                        drainImmediate()
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
            options.log("协议线程意外结束：${describe(t)}")
        } finally {
            closeEverything()
            joinThreads()
            terminated.countDown()
        }
    }

    private fun waitMillis(): Long {
        val due = session.nextTickAtMillis ?: return IDLE_WAIT_MILLIS
        return (due - now()).coerceIn(0, IDLE_WAIT_MILLIS)
    }

    private fun dispatch(input: Input) {
        when (input) {
            is Input.Accepted -> execute(session.onAccepted(input.id, input.remoteHost, now()))
            is Input.Connected -> execute(session.onConnected(input.id, now()))
            is Input.Data -> {
                val outputs = try {
                    session.onData(input.id, input.buffer, 0, input.length, now())
                } finally {
                    input.pool.give(input.buffer)
                }
                execute(outputs)
            }
            is Input.Datagram -> {
                val outputs = try {
                    session.onDatagram(input.id, input.buffer, 0, input.length, now())
                } finally {
                    input.pool.give(input.buffer)
                }
                execute(outputs)
            }
            is Input.Closed -> execute(session.onClosed(input.id, now()))
            is Input.Failed -> execute(session.onIoError(input.id, input.message, now()))
            is Input.Task -> execute(input.block(now()))
            Input.Stop -> execute(session.stop(now()))
        }
    }

    private fun drainImmediate() {
        while (immediate.isNotEmpty() && !session.isEnded && !abandoned) {
            execute(immediate.removeFirst().invoke())
        }
        immediate.clear()
    }

    private fun execute(outputs: List<SessionOutput>) {
        for (output in outputs) {
            when (output) {
                is SessionAction.Listen -> listen(output)
                is SessionAction.Connect -> connect(output)
                is SessionAction.Write -> write(output)
                is SessionAction.Close -> closeResource(output.id)
                is SessionAction.OpenUdp -> openUdp(output)
                is SessionAction.BindUdp -> bindUdp(output)
                is SessionAction.SendDatagram -> sendDatagram(output)
                is SessionEvent -> deliver(output)
            }
        }
    }

    private fun deliver(event: SessionEvent) {
        try {
            events(event)
        } catch (e: Exception) {
            options.log("事件回调抛出异常（${describe(e)}），已忽略")
        }
    }

    private fun listen(action: SessionAction.Listen) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(action.host), action.port), options.listenBacklog)
        } catch (e: IOException) {
            closeQuietly(server)
            val message = "在 ${action.host}:${action.port} 开监听失败：${describe(e)}"
            immediate.add { session.onIoError(action.id, message, now()) }
            return
        }
        val listener = ListenerResource(action.id, server)
        val thread = newThread(action.channel, action.id) { acceptLoop(listener, action) }
        if (!register(listener, thread)) {
            closeQuietly(server)
            return
        }
        thread.start()
        val port = server.localPort
        immediate.add { session.onListening(action.id, port, now()) }
    }

    private fun connect(action: SessionAction.Connect) {
        val socket = Socket()
        val connection = ConnectionResource(action.id, socket, options.buffersPerConnection, options.readBufferBytes)
        val thread = newThread(action.channel, action.id) {
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName(action.host), action.port), options.connectTimeoutMillis)
            } catch (e: IOException) {
                if (!connection.closed.get()) {
                    post(Input.Failed(action.id, "连接 ${action.host}:${action.port} 失败：${describe(e)}"))
                }
                return@newThread
            }
            post(Input.Connected(action.id))
            readLoop(connection)
        }
        if (!register(connection, thread)) {
            closeQuietly(socket)
            return
        }
        thread.start()
    }

    private fun write(action: SessionAction.Write) {
        val connection = lookup(action.id) as? ConnectionResource ?: return
        try {
            connection.socket.getOutputStream().write(action.bytes)
        } catch (e: IOException) {
            if (!connection.closed.get()) {
                val message = "写 ${action.bytes.size} 字节失败：${describe(e)}"
                immediate.add { session.onIoError(action.id, message, now()) }
            }
        }
    }

    private fun openUdp(action: SessionAction.OpenUdp) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(action.localHost), 0))
            socket.connect(InetSocketAddress(InetAddress.getByName(action.remoteHost), action.remotePort))
        } catch (e: IOException) {
            socket?.let { closeQuietly(it) }
            val message = "开 UDP（${action.localHost} → ${action.remoteHost}:${action.remotePort}）失败：${describe(e)}"
            immediate.add { session.onIoError(action.id, message, now()) }
            return
        }
        val udp = UdpResource(action.id, socket, options.buffersPerConnection, options.readBufferBytes)
        val thread = newThread(action.channel, action.id) { udpLoop(udp) }
        if (!register(udp, thread)) {
            closeQuietly(socket)
            return
        }
        thread.start()
    }

    private fun bindUdp(action: SessionAction.BindUdp) {
        val socket: DatagramSocket
        try {
            socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(action.localHost), action.localPort))
        } catch (e: IOException) {
            val message = "绑 UDP（${action.localHost}:${action.localPort}）失败：${describe(e)}"
            immediate.add { session.onIoError(action.id, message, now()) }
            return
        }
        val udp = UdpResource(action.id, socket, options.buffersPerConnection, options.readBufferBytes)
        val thread = newThread(action.channel, action.id) { udpLoop(udp) }
        if (!register(udp, thread)) {
            closeQuietly(socket)
            return
        }
        thread.start()
        val port = socket.localPort
        immediate.add { session.onListening(action.id, port, now()) }
    }

    private fun sendDatagram(action: SessionAction.SendDatagram) {
        val udp = lookup(action.id) as? UdpResource ?: return
        try {
            udp.socket.send(DatagramPacket(action.bytes, action.bytes.size))
        } catch (e: IOException) {
            if (!udp.closed.get()) {
                val message = "发 UDP 数据报失败：${describe(e)}"
                immediate.add { session.onIoError(action.id, message, now()) }
            }
        }
    }

    private fun closeResource(id: Int) {
        val resource = synchronized(lock) { resources.remove(id) } ?: return
        resource.close()
    }

    /** 协议线程退出前：把还没关的资源按创建的反序关掉，之后不再接受新资源。 */
    private fun closeEverything() {
        val remaining = synchronized(lock) {
            closingAll = true
            resources.values.toList().also { resources.clear() }
        }
        for (resource in remaining.asReversed()) resource.close()
    }

    private fun joinThreads() {
        val all = synchronized(lock) { threads.toList() }
        val deadline = now() + options.stopGraceMillis
        for (thread in all) {
            val left = deadline - now()
            if (left > 0) {
                try {
                    thread.join(left)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (thread.isAlive) options.log("线程 ${thread.name} 没能按时退出")
        }
    }

    // ---------------- 读线程 ----------------

    private fun acceptLoop(listener: ListenerResource, action: SessionAction.Listen) {
        val socket: Socket
        try {
            while (true) {
                val candidate = listener.server.accept()
                val local = candidate.localAddress?.hostAddress
                if (action.requireLocalHost == null || local == action.requireLocalHost) {
                    socket = candidate
                    break
                }
                options.log("${action.channel.label}：本端地址不是 ${action.requireLocalHost} 的连接（经 $local），已关闭")
                closeQuietly(candidate)
            }
        } catch (e: IOException) {
            if (!listener.closed.get()) post(Input.Failed(action.id, "${action.channel.label}监听出错：${describe(e)}"))
            return
        }
        val connection = ConnectionResource(action.acceptId, socket, options.buffersPerConnection, options.readBufferBytes)
        if (!register(connection, null)) {
            closeQuietly(socket)
            return
        }
        rejectExtraConnections(listener, action)
        val address = socket.inetAddress
        val remote = (address as? Inet4Address)?.hostAddress ?: address?.hostAddress ?: ""
        post(Input.Accepted(action.acceptId, remote))
        readLoop(connection)
    }

    /** 这个监听已经接受过一条连接：另起线程，之后再连进来的接受后立刻关掉（前 [MAX_REJECT_LOGS] 条记日志），直到监听关闭。 */
    private fun rejectExtraConnections(listener: ListenerResource, action: SessionAction.Listen) {
        val thread = newThread(action.channel, action.id) {
            var rejected = 0
            while (true) {
                val extra = try {
                    listener.server.accept()
                } catch (e: IOException) {
                    if (!listener.closed.get()) options.log("${action.channel.label}：等多余连接时出错（${describe(e)}），不再理会")
                    return@newThread
                }
                closeQuietly(extra)
                rejected++
                if (rejected <= MAX_REJECT_LOGS) {
                    options.log(
                        "${action.channel.label}：这个监听已经接受过一条连接，又连进来一条（来自 ${extra.inetAddress?.hostAddress}），" +
                            "已关闭" + if (rejected == MAX_REJECT_LOGS) "；之后的不再记" else "",
                    )
                }
            }
        }
        val keep = synchronized(lock) {
            if (!closingAll) threads += thread
            !closingAll
        }
        if (keep) thread.start()
    }

    private fun readLoop(connection: ConnectionResource) {
        val input = try {
            connection.socket.getInputStream()
        } catch (e: IOException) {
            if (!connection.closed.get()) post(Input.Failed(connection.id, "取输入流失败：${describe(e)}"))
            return
        }
        while (true) {
            val buffer = connection.pool.take(connection.closed) ?: return
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                connection.pool.give(buffer)
                if (!connection.closed.get()) post(Input.Failed(connection.id, "读失败：${describe(e)}"))
                return
            }
            if (n < 0) {
                connection.pool.give(buffer)
                if (!connection.closed.get()) post(Input.Closed(connection.id))
                return
            }
            if (n == 0) {
                connection.pool.give(buffer)
                continue
            }
            post(Input.Data(connection.id, buffer, n, connection.pool))
        }
    }

    private fun udpLoop(udp: UdpResource) {
        while (true) {
            val buffer = udp.pool.take(udp.closed) ?: return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.socket.receive(packet)
            } catch (e: IOException) {
                udp.pool.give(buffer)
                if (!udp.closed.get()) post(Input.Failed(udp.id, "收 UDP 失败：${describe(e)}"))
                return
            }
            post(Input.Datagram(udp.id, buffer, packet.length, udp.pool))
        }
    }

    // ---------------- 工具 ----------------

    private fun post(input: Input) {
        queue.add(input)
    }

    private fun lookup(id: Int): Resource? = synchronized(lock) { resources[id] }

    /** 登记资源和它的线程；协议线程已经在退出时返回 false，调用方自己关掉 socket。 */
    private fun register(resource: Resource, thread: Thread?): Boolean = synchronized(lock) {
        if (closingAll) return false
        resources[resource.id] = resource
        if (thread != null) threads += thread
        true
    }

    private fun newThread(channel: SessionChannel, id: Int, body: () -> Unit): Thread {
        val thread = Thread(body, "${options.threadName}-${channel.name.lowercase()}-$id")
        thread.isDaemon = true
        return thread
    }

    private companion object {
        /** 没有定时任务时协议线程最长等多久再看一眼。 */
        const val IDLE_WAIT_MILLIS = 1_000L

        /** 读线程等空闲缓冲时，每隔多久检查一次资源是否已关闭。 */
        const val POOL_WAIT_MILLIS = 200L

        /** 一个监听上被关掉的多余连接，最多记几条日志。 */
        const val MAX_REJECT_LOGS = 3

        fun now(): Long = System.nanoTime() / 1_000_000L

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // 关闭时的错误没有意义。
            }
        }

        fun closeQuietly(socket: DatagramSocket) {
            socket.close()
        }
    }
}
