package io.github.gcbond.openhc.wifi

import android.content.Context
import android.util.Log
import io.github.gcbond.openhc.core.common.adb.AdbAuth
import io.github.gcbond.openhc.core.common.adb.AdbClient
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 经车机本机的网络 ADB（127.0.0.1:5555，只连本机）以 shell 身份跑 [helper] 这个小程序。ADB 钥匙是本应用自己生成的
 * 2048 位 RSA，存在应用私有目录（不参与备份），几个功能共用一把；车主在车机上点过「允许」（勾一律允许）之后，后台使用不再弹框。
 *
 * 请求授权（[run] 的 ask 为真）：先连一次、把公钥发过去让车机弹框，这条连接留着；然后每 [APPROVAL_POLL_MS] 另开一条连接、
 * 只用签名试，通过了就在新连接上跑。有的车机点了允许之后原来那条连接一直不放行，要重新连才认（2026-11 实车）；
 * 后台使用本来就要钥匙已经存下（一律允许），所以只看新连接认不认就够了。
 */
internal class LocalAdbRunner(context: Context, private val helper: AdbHelper, private val tag: String) : AdbHelperRunner {
    private val app = context.applicationContext

    override fun run(command: String, ask: Boolean): AdbSwitchResult {
        // 系统签名进程（uid 1000）有系统权限：直接在进程内调小程序的静态入口，0 毫秒、不开网络 ADB、不弹授权框。
        // 结果是 DENIED/FAILED/UNSUPPORTED 时（没这个接口、系统不认）回退网络 ADB 再试一次，行为和普通应用一致。
        inProcess(command)?.let { return it }
        val keys = try {
            loadKeys(app)
        } catch (e: GeneralSecurityException) {
            Log.w(tag, "ADB 钥匙读不了：${e.javaClass.simpleName}")
            return AdbSwitchResult.FAILED
        } catch (e: IOException) {
            Log.w(tag, "ADB 钥匙读不了：${e.javaClass.simpleName}")
            return AdbSwitchResult.FAILED
        }
        if (!ask) return once(keys, command, mayAsk = false, handshakeMillis = HANDSHAKE_TIMEOUT_MS)
        // 请求授权：这条连接只用来让车机弹框（已经认这把钥匙时直接在上面跑），留到最后再关。
        val prompt = open() ?: return AdbSwitchResult.ADB_OFF
        try {
            val first = runOn(prompt, keys, command, mayAsk = true, handshakeMillis = OFFER_WAIT_MS)
            if (first != AdbSwitchResult.NOT_APPROVED) return first
            Log.i(tag, "已请求车机授权，等车主点「允许」")
            val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(APPROVAL_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AdbSwitchResult.FAILED
                }
                val result = once(keys, command, mayAsk = false, handshakeMillis = HANDSHAKE_TIMEOUT_MS)
                if (result != AdbSwitchResult.NOT_APPROVED) return result
            }
            return AdbSwitchResult.NOT_APPROVED
        } finally {
            prompt.close()
        }
    }

    /**
     * 进程内直调小程序（[AdbHelper.mainClass] 的静态 [runCommand]）：只在系统签名进程（uid 1000）里走；
     * 返回 null 表示这条路走不通（不是系统身份、类不在、调不了，或结果是 DENIED/FAILED/UNSUPPORTED 这类
     * 要交给网络 ADB 再核实的），调用方回退网络 ADB。
     */
    private fun inProcess(command: String): AdbSwitchResult? {
        if (android.os.Process.myUid() != android.os.Process.SYSTEM_UID) return null
        val answer = try {
            val method = Class.forName(helper.mainClass).getMethod("runCommand", String::class.java)
            method.invoke(null, command) as String
        } catch (e: Throwable) {
            Log.w(tag, "进程内直调 ${helper.mainClass} 失败（${e.javaClass.simpleName}），回退网络 ADB")
            return null
        }
        Log.i(tag, "系统身份进程内直调（$command）：$answer")
        return when (val parsed = helper.parse(answer)) {
            AdbSwitchResult.DENIED, AdbSwitchResult.FAILED, AdbSwitchResult.UNSUPPORTED -> null
            else -> parsed
        }
    }

    /** 开一条连接跑一次。 */
    private fun once(keys: KeyPair, command: String, mayAsk: Boolean, handshakeMillis: Int): AdbSwitchResult {
        val socket = open() ?: return AdbSwitchResult.ADB_OFF
        return socket.use { runOn(it, keys, command, mayAsk, handshakeMillis) }
    }

    private fun open(): Socket? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName(LOCALHOST), PORT), CONNECT_TIMEOUT_MS)
            socket
        } catch (e: IOException) {
            socket.close()
            null
        }
    }

    /** 在连好的 [socket] 上握手认证、跑小程序。不关 [socket]。 */
    private fun runOn(socket: Socket, keys: KeyPair, command: String, mayAsk: Boolean, handshakeMillis: Int): AdbSwitchResult =
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = handshakeMillis
            val client = AdbClient(BufferedInputStream(socket.getInputStream()), socket.getOutputStream(), keys, KEY_COMMENT)
            when (client.connect(mayAsk)) {
                AdbClient.Access.READY -> {
                    socket.soTimeout = COMMAND_TIMEOUT_MS
                    helper.parse(client.shell(helper.line(app.applicationInfo.sourceDir, command)))
                }
                AdbClient.Access.NOT_APPROVED -> AdbSwitchResult.NOT_APPROVED
                AdbClient.Access.TLS_ONLY -> AdbSwitchResult.TLS_ONLY
                AdbClient.Access.FAILED -> AdbSwitchResult.FAILED
            }
        } catch (e: IOException) {
            Log.w(tag, "车机 ADB 跑命令出错：${e.javaClass.simpleName}")
            AdbSwitchResult.FAILED
        }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val PORT = 5555
        const val CONNECT_TIMEOUT_MS = 2_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000

        /** 请求授权时，发出公钥后在原连接上等这么久（车机已经认这把钥匙、或者原连接上就放行时用得上）。 */
        const val OFFER_WAIT_MS = 3_000

        /** 等车主在车机上点「允许」，每隔 [APPROVAL_POLL_MS] 重新连一次看认不认。 */
        const val APPROVAL_TIMEOUT_MS = 60_000
        const val APPROVAL_POLL_MS = 2_000L

        /** 小程序 10 秒内自己退出，这里多留一些启动时间。 */
        const val COMMAND_TIMEOUT_MS = 20_000
        const val KEY_DIR = "adb"
        const val KEY_COMMENT = "OpenHC"
        val keyLock = Any()

        /** 读本应用的 ADB 钥匙，没有就生成一把。几个功能的后台线程可能同时来读，加锁免得各生成一把。 */
        fun loadKeys(app: Context): KeyPair = synchronized(keyLock) {
            val dir = File(app.noBackupFilesDir, KEY_DIR)
            val private = File(dir, "key")
            val public = File(dir, "key.pub")
            val factory = KeyFactory.getInstance("RSA")
            if (private.isFile && public.isFile) {
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(public.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())),
                )
            }
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("建不了钥匙目录")
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(AdbAuth.KEY_BITS) }.generateKeyPair()
            private.writeBytes(pair.private.encoded)
            public.writeBytes(pair.public.encoded)
            Log.i("OpenHC-Adb", "生成了本应用的 ADB 钥匙")
            pair
        }
    }
}

/** 单个后台线程（守护线程）。 */
internal class ExecutorWorker(private val name: String) : AdbWorker {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) {
        executor.execute(guard(task))
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
        val future = executor.schedule(guard(task), delayMillis, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    private fun guard(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (e: RuntimeException) {
            Log.e(name, "车机 ADB 后台任务出错", e)
        }
    }
}
