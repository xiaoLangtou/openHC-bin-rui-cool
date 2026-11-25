package io.github.gcbond.openhc.core.session

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 执行器在本机回环上的收发、监听、连接、UDP、关闭与线程回收。 */
class SocketRunnerTest {

    /** 按脚本回应的会话；记下每次调用和调用所在的线程。 */
    private class ScriptedSession : SessionLogic {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
        @Volatile var ended = false
        var onStart: () -> List<SessionOutput> = { emptyList() }
        var onListen: (Int, Int) -> List<SessionOutput> = { _, _ -> emptyList() }
        var onAccept: (Int, String) -> List<SessionOutput> = { _, _ -> emptyList() }
        var onConnect: (Int) -> List<SessionOutput> = { emptyList() }
        var onBytes: (Int, ByteArray) -> List<SessionOutput> = { _, _ -> emptyList() }
        var onPacket: (Int, ByteArray) -> List<SessionOutput> = { _, _ -> emptyList() }
        var onEof: (Int) -> List<SessionOutput> = { emptyList() }
        var onError: (Int, String) -> List<SessionOutput> = { _, _ -> end() }
        var onStop: () -> List<SessionOutput> = { end() }

        fun end(vararg before: SessionOutput): List<SessionOutput> =
            before.toList() + SessionEvent.Ended(EndReason.LocalStop)

        private fun <T : List<SessionOutput>> record(call: String, outputs: () -> T): List<SessionOutput> {
            if (ended) return emptyList()
            calls += call
            threads += Thread.currentThread().name
            val out = outputs()
            if (out.any { it is SessionEvent.Ended }) ended = true
            return out
        }

        override val nextTickAtMillis: Long? get() = null
        override val isEnded: Boolean get() = ended
        override fun start(nowMillis: Long) = record("start") { onStart() }
        override fun onListening(id: Int, port: Int, nowMillis: Long) = record("listening $id") { onListen(id, port) }
        override fun onAccepted(id: Int, remoteHost: String, nowMillis: Long) = record("accepted $id $remoteHost") { onAccept(id, remoteHost) }
        override fun onConnected(id: Int, nowMillis: Long) = record("connected $id") { onConnect(id) }
        override fun onData(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
            record("data $id") { onBytes(id, data.copyOfRange(offset, offset + length)) }
        override fun onDatagram(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
            record("datagram $id ${data.text(offset, length)}") { onPacket(id, data.copyOfRange(offset, offset + length)) }
        override fun onClosed(id: Int, nowMillis: Long) = record("closed $id") { onEof(id) }
        override fun onIoError(id: Int, message: String, nowMillis: Long) = record("error $id") { onError(id, message) }
        override fun onTick(nowMillis: Long) = record("tick") { emptyList() }
        override fun stop(nowMillis: Long) = record("stop") { onStop() }
    }

    private val prefix = "runner-test-${COUNTER.incrementAndGet()}"
    private val runners = ArrayList<SocketRunner>()
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(ArrayList())

    private fun runner(session: SessionLogic, options: SocketRunner.Options = SocketRunner.Options(threadName = prefix)) =
        SocketRunner(session, { events += it }, options).also {
            runners += it
            it.start()
        }

    @AfterTest
    fun cleanUp() {
        for (r in runners) r.close()
        assertTrue(runners.all { it.isTerminated })
        assertNoThreadsLeft()
    }

    private fun assertNoThreadsLeft() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name.startsWith(prefix) && it.isAlive }
            if (alive.isEmpty()) return
            if (System.nanoTime() > deadline) throw AssertionError("线程没有退出: ${alive.map { it.name }}")
            Thread.sleep(20)
        }
    }

    private class Box<T> {
        private val latch = CountDownLatch(1)
        @Volatile private var value: T? = null
        fun set(v: T) {
            value = v
            latch.countDown()
        }
        fun get(): T {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "等待超时")
            return value!!
        }
    }

    private fun InputStream.readExactly(n: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(n)
        while (out.size() < n) {
            val r = read(buf, 0, n - out.size())
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray().text()
    }

    @Test
    fun listensAcceptsEchoesAndEndsOnPeerClose() {
        val session = ScriptedSession()
        val port = Box<Int>()
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "127.0.0.1", 0, 2)) }
        session.onListen = { _, p ->
            port.set(p)
            listOf(SessionEvent.ControlListening(p))
        }
        session.onAccept = { id, _ -> listOf(SessionAction.Write(id, "hello".bytes())) }
        session.onBytes = { id, data -> listOf(SessionAction.Write(id, data)) }
        session.onEof = { id -> session.end(SessionAction.Close(id), SessionAction.Close(1)) }
        val runner = runner(session)

        Socket("127.0.0.1", port.get()).use { client ->
            val input = client.getInputStream()
            assertEquals("hello", input.readExactly(5))
            client.getOutputStream().write("ping".bytes())
            assertEquals("ping", input.readExactly(4))
            // 在协议线程上执行的任务：动作照样执行。
            runner.submit { listOf(SessionAction.Write(2, "task".bytes())) }
            assertEquals("task", input.readExactly(4))
        }
        assertTrue(runner.awaitTermination(5_000))
        assertNull(runner.failure)
        assertEquals(listOf("start", "listening 1", "accepted 2 127.0.0.1", "data 2", "closed 2"), session.calls)
        assertEquals(setOf(prefix), session.threads, "会话的方法都在协议线程上调用")
        assertTrue(events.first() is SessionEvent.ControlListening)
        assertTrue(events.last() is SessionEvent.Ended)
    }

    @Test
    fun connectsWritesAndClosesOnLocalStop() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { phone ->
            val session = ScriptedSession()
            session.onStart = { listOf(SessionAction.Connect(1, SessionChannel.VIDEO, "127.0.0.1", phone.localPort)) }
            session.onConnect = { id -> listOf(SessionAction.Write(id, "hi".bytes())) }
            session.onStop = { session.end(SessionAction.Write(1, "bye".bytes()), SessionAction.Close(1)) }
            val runner = runner(session)
            phone.accept().use { peer ->
                val input = peer.getInputStream()
                assertEquals("hi", input.readExactly(2))
                runner.close()
                assertEquals("bye", input.readExactly(3))
                assertEquals(-1, input.read(), "本机收尾后关闭连接")
            }
            assertTrue(runner.isTerminated)
            assertEquals(listOf("start", "connected 1", "stop"), session.calls)
        }
    }

    @Test
    fun udpSendsAndReceivesDatagrams() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { phone ->
            phone.soTimeout = 5_000
            val session = ScriptedSession()
            session.onStart = {
                listOf(
                    SessionAction.OpenUdp(1, SessionChannel.KEEPALIVE, "127.0.0.1", "127.0.0.1", phone.localPort),
                    SessionAction.SendDatagram(1, "ka".bytes()),
                )
            }
            session.onPacket = { id, _ -> session.end(SessionAction.Close(id)) }
            val runner = runner(session)
            val packet = DatagramPacket(ByteArray(64), 64)
            phone.receive(packet)
            assertEquals("ka", packet.data.text(0, packet.length))
            phone.send(DatagramPacket("pong".bytes(), 4, packet.socketAddress))
            assertTrue(runner.awaitTermination(5_000))
            assertEquals(listOf("start", "datagram 1 pong"), session.calls)
        }
    }

    @Test
    fun listenerOnlyAcceptsConnectionsToTheRequiredLocalAddress() {
        val session = ScriptedSession()
        val port = Box<Int>()
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "127.0.0.1", 0, 2, requireLocalHost = "192.0.2.1")) }
        session.onListen = { _, p ->
            port.set(p)
            emptyList()
        }
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        runner(session, SocketRunner.Options(threadName = prefix, log = { logs += it }))
        Socket("127.0.0.1", port.get()).use { client ->
            client.soTimeout = 5_000
            val first = try {
                client.getInputStream().read()
            } catch (e: java.net.SocketException) {
                -1
            }
            assertEquals(-1, first, "从别的地址连进来的连接被直接关掉")
        }
        Thread.sleep(100)
        assertTrue(session.calls.none { it.startsWith("accepted") }, session.calls.toString())
        assertTrue(logs.any { "已关闭" in it }, logs.toString())
    }

    /**
     * 每个监听只接受一条连接：再连进来的被立刻关掉（对端马上读到结束，不会挂着没人理）、记日志，第一条照常收发；
     * 监听本身留到会话关它为止。
     */
    @Test
    fun extraConnectionsToAnAcceptedListenerAreClosedAtOnce() {
        val session = ScriptedSession()
        val port = Box<Int>()
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "127.0.0.1", 0, 2)) }
        session.onListen = { _, p ->
            port.set(p)
            emptyList()
        }
        session.onAccept = { id, _ -> listOf(SessionAction.Write(id, "ok".bytes())) }
        session.onBytes = { id, data -> listOf(SessionAction.Write(id, data)) }
        session.onEof = { id -> session.end(SessionAction.Close(id), SessionAction.Close(1)) }
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val runner = runner(session, SocketRunner.Options(threadName = prefix, log = { logs += it }))
        Socket("127.0.0.1", port.get()).use { first ->
            first.soTimeout = 5_000
            val input = first.getInputStream()
            assertEquals("ok", input.readExactly(2))
            repeat(4) {
                Socket("127.0.0.1", port.get()).use { extra ->
                    extra.soTimeout = 5_000
                    val read = try {
                        extra.getInputStream().read()
                    } catch (e: java.net.SocketException) {
                        -1
                    }
                    assertEquals(-1, read, "第 ${it + 2} 条连接被关掉")
                }
            }
            first.getOutputStream().write("ping".bytes())
            assertEquals("ping", input.readExactly(4), "第一条连接不受影响")
        }
        assertTrue(runner.awaitTermination(5_000))
        assertNull(runner.failure)
        assertEquals(listOf("start", "listening 1", "accepted 2 127.0.0.1", "data 2", "closed 2"), session.calls)
        val rejected = logs.filter { "又连进来一条" in it }
        assertEquals(3, rejected.size, logs.toString())
        assertTrue("之后的不再记" in rejected.last(), rejected.toString())
    }

    /** 真机默认的走法：通配地址监听，本端地址对得上的连接照常接受。 */
    @Test
    fun wildcardListenerAcceptsConnectionsToTheRequiredLocalAddress() {
        val session = ScriptedSession()
        val port = Box<Int>()
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "0.0.0.0", 0, 2, requireLocalHost = "127.0.0.1")) }
        session.onListen = { _, p ->
            port.set(p)
            emptyList()
        }
        session.onAccept = { id, _ -> listOf(SessionAction.Write(id, "ok".bytes())) }
        session.onEof = { id -> session.end(SessionAction.Close(id), SessionAction.Close(1)) }
        val runner = runner(session)
        Socket("127.0.0.1", port.get()).use { client ->
            client.soTimeout = 5_000
            assertEquals("ok", client.getInputStream().readExactly(2))
        }
        assertTrue(runner.awaitTermination(5_000))
        assertEquals(listOf("start", "listening 1", "accepted 2 127.0.0.1", "closed 2"), session.calls)
    }

    @Test
    fun connectFailureIsReportedToTheSession() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val session = ScriptedSession()
        val error = Box<String>()
        session.onStart = { listOf(SessionAction.Connect(1, SessionChannel.HISIGHT_CONTROL, "127.0.0.1", closedPort)) }
        session.onError = { _, message ->
            error.set(message)
            session.end()
        }
        val runner = runner(session)
        assertTrue(error.get().startsWith("连接 127.0.0.1:$closedPort 失败"), error.get())
        assertTrue(runner.awaitTermination(5_000))
        assertEquals(listOf("start", "error 1"), session.calls)
    }

    @Test
    fun listenFailureIsReportedToTheSession() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
            val session = ScriptedSession()
            session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "127.0.0.1", taken.localPort, 2)) }
            val runner = runner(session)
            assertTrue(runner.awaitTermination(5_000))
            assertEquals(listOf("start", "error 1"), session.calls)
        }
    }

    @Test
    fun largeTransferArrivesInOrderWithReusedBuffers() {
        val total = 4 * 1024 * 1024
        val session = ScriptedSession()
        val port = Box<Int>()
        var received = 0
        var mismatch = -1
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.VIDEO, "127.0.0.1", 0, 2)) }
        session.onListen = { _, p ->
            port.set(p)
            emptyList()
        }
        session.onBytes = { _, data ->
            for (b in data) {
                if (mismatch < 0 && b != (received % 251).toByte()) mismatch = received
                received++
            }
            emptyList()
        }
        session.onEof = { session.end() }
        val runner = runner(session, SocketRunner.Options(threadName = prefix, readBufferBytes = 4096, buffersPerConnection = 2))
        Socket("127.0.0.1", port.get()).use { client ->
            val chunk = ByteArray(10_000)
            var sent = 0
            val out = client.getOutputStream()
            while (sent < total) {
                val n = minOf(chunk.size, total - sent)
                for (i in 0 until n) chunk[i] = ((sent + i) % 251).toByte()
                out.write(chunk, 0, n)
                sent += n
            }
        }
        assertTrue(runner.awaitTermination(10_000))
        assertEquals(total, received)
        assertEquals(-1, mismatch, "第 $mismatch 字节内容不对")
    }

    @Test
    fun closeForcesShutdownWhenTheSessionDoesNotEnd() {
        val session = ScriptedSession()
        val port = Box<Int>()
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "127.0.0.1", 0, 2)) }
        session.onListen = { _, p ->
            port.set(p)
            emptyList()
        }
        // 会话不肯结束：stop 什么都不返回。
        session.onStop = { emptyList() }
        val runner = runner(session, SocketRunner.Options(threadName = prefix, stopGraceMillis = 300))
        Socket("127.0.0.1", port.get()).use { client ->
            val began = System.nanoTime()
            runner.close()
            assertTrue(runner.isTerminated)
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(3))
            client.soTimeout = 5_000
            val read = try {
                client.getInputStream().read()
            } catch (e: java.net.SocketException) {
                -1
            }
            assertEquals(-1, read, "强制关闭后连接也关了")
        }
    }

    @Test
    fun unexpectedSessionExceptionStopsTheRunnerAndClosesSockets() {
        val session = ScriptedSession()
        val port = Box<Int>()
        session.onStart = { listOf(SessionAction.Listen(1, SessionChannel.CONTROL, "127.0.0.1", 0, 2)) }
        session.onListen = { _, p ->
            port.set(p)
            emptyList()
        }
        session.onAccept = { _, _ -> throw IllegalStateException("缺陷") }
        val runner = runner(session)
        Socket("127.0.0.1", port.get()).use { client ->
            client.soTimeout = 5_000
            assertTrue(runner.awaitTermination(5_000))
            val read = try {
                client.getInputStream().read()
            } catch (e: java.net.SocketException) {
                -1
            }
            assertEquals(-1, read)
        }
        assertNotNull(runner.failure)
    }

    @Test
    fun throwingEventCallbackIsIgnored() {
        val session = ScriptedSession()
        session.onStart = { listOf(SessionEvent.Diagnostic(SessionLayer.SESSION, "x")) }
        val runner = SocketRunner(session, { throw IllegalStateException("回调出错") }, SocketRunner.Options(threadName = prefix))
        runners += runner
        runner.start()
        runner.close()
        assertTrue(runner.isTerminated)
        assertNull(runner.failure)
        assertEquals(listOf("start", "stop"), session.calls)
    }

    @Test
    fun closeBeforeStartIsHarmless() {
        val runner = SocketRunner(ScriptedSession(), { }, SocketRunner.Options(threadName = prefix))
        runner.close()
        assertTrue(runner.isTerminated)
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
