package io.github.gcbond.openhc.core.common.adb

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * 连车机本机网络 ADB 的最小客户端：握手认证、跑一条 shell 命令拿输出。阻塞式，收发用调用方给的流
 * （平台层传入套接字的流，读超时由套接字的超时控制，超时以 [InterruptedIOException] 抛出）。
 *
 * 认证：先用自己的钥匙签车机发来的令牌；车机不认这把钥匙时（又发一次令牌），只有 [connect] 的 `mayAsk` 为真才把
 * 公钥发过去，车机上弹「允许调试吗」等车主点允许——后台使用一律不发，免得开车时弹框。
 *
 * 不是线程安全的：同一个实例只在一个线程上用。
 */
class AdbClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val keys: KeyPair,
    /** 公钥附带的说明，车机的授权框里会显示。 */
    private val comment: String,
) {
    enum class Access {
        /** 连上并通过认证，可以跑命令。 */
        READY,

        /** 车机不认这把钥匙：没请求授权，或者请求了但车主没点允许（等到超时）。 */
        NOT_APPROVED,

        /** 车机只接受加密的「无线调试」方式，这个客户端不支持。 */
        TLS_ONLY,

        /** 对端不按协议回、连接断了。 */
        FAILED,
    }

    private var nextLocalId = 1

    fun connect(mayAsk: Boolean): Access {
        var signed = false
        var offered = false
        return try {
            send(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray()))
            while (true) {
                val m = read()
                when (m.command) {
                    AdbMessage.CNXN -> return Access.READY
                    AdbMessage.STLS -> return Access.TLS_ONLY
                    AdbMessage.AUTH -> {
                        if (m.arg0 != AdbMessage.AUTH_TOKEN) continue
                        when {
                            !signed -> {
                                signed = true
                                val signature = AdbAuth.sign(m.payload, keys.private as RSAPrivateKey)
                                send(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, signature))
                            }
                            mayAsk && !offered -> {
                                offered = true
                                val key = AdbAuth.publicKey(keys.public as RSAPublicKey, comment)
                                send(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, key))
                            }
                            else -> return Access.NOT_APPROVED
                        }
                    }
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Access.FAILED
        } catch (e: InterruptedIOException) {
            // 读超时：已经请求了授权就是车主没点允许；签名后车机不回也按没授权算。
            if (offered || signed) Access.NOT_APPROVED else Access.FAILED
        } catch (e: IOException) {
            Access.FAILED
        } catch (e: MalformedMessageException) {
            Access.FAILED
        } catch (e: IllegalArgumentException) {
            Access.FAILED
        }
    }

    /**
     * 在车机上跑一条 shell 命令，等它结束，返回它的输出（UTF-8，标准输出和标准错误混在一起）。连接断开、超时时
     * 抛 [IOException]。只能在 [connect] 返回 [Access.READY] 之后调用。
     */
    fun shell(command: String): String {
        val local = nextLocalId++
        var remote = 0
        val out = java.io.ByteArrayOutputStream()
        send(AdbMessage(AdbMessage.OPEN, local, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8)))
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            when (m.command) {
                AdbMessage.OKAY -> if (remote == 0) remote = m.arg0
                AdbMessage.WRTE -> {
                    if (remote == 0) remote = m.arg0
                    out.write(m.payload)
                    send(AdbMessage(AdbMessage.OKAY, local, remote))
                }
                AdbMessage.CLSE -> {
                    if (remote != 0) send(AdbMessage(AdbMessage.CLSE, local, remote))
                    return String(out.toByteArray(), Charsets.UTF_8)
                }
                else -> Unit
            }
        }
    }

    private fun send(message: AdbMessage) {
        output.write(message.encode())
        output.flush()
    }

    private fun read(): AdbMessage {
        val head = readFully(AdbMessage.HEADER)
        val h = AdbMessage.parseHeader(head)
        val payload = if (h.length == 0) ByteArray(0) else readFully(h.length)
        return AdbMessage(h.command, h.arg0, h.arg1, payload)
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw EOFException("ADB 连接被对端关闭")
            got += r
        }
        return buf
    }
}
