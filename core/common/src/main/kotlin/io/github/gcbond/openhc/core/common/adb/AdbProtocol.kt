package io.github.gcbond.openhc.core.common.adb

import io.github.gcbond.openhc.core.common.Base64
import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.math.BigInteger
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * ADB 协议的一条消息（按 AOSP 公开的 adb 协议说明）。线上格式：24 字节头 + 载荷。头是 6 个小端 32 位整数：
 * 命令、参数 0、参数 1、载荷长度、载荷校验和（载荷各字节无符号值之和）、命令按位取反。
 *
 * 只用于连车机本机的网络 ADB（明文协议）；安卓 11 起「无线调试」用的 TLS 方式不支持（对端回 [STLS]）。
 */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = EMPTY) {

    fun encode(): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        putLe(out, 0, command)
        putLe(out, 4, arg0)
        putLe(out, 8, arg1)
        putLe(out, 12, payload.size)
        putLe(out, 16, checksum(payload))
        putLe(out, 20, command.inv())
        System.arraycopy(payload, 0, out, HEADER, payload.size)
        return out
    }

    override fun toString() = "AdbMessage(${name(command)}, $arg0, $arg1, ${payload.size} 字节)"

    /** 解析出的消息头：[length] 是随后载荷的字节数。 */
    class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int, val checksum: Int)

    companion object {
        const val HEADER = 24

        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453

        /** 协议版本：用最早的版本，对端会照它校验和、按 [MAX_PAYLOAD] 分包，新老 adbd 都接受。 */
        const val VERSION = 0x01000000
        const val MAX_PAYLOAD = 4096

        /** 收消息时载荷长度的上限：对端可能按更大的包长发。 */
        const val MAX_INCOMING_PAYLOAD = 1024 * 1024

        /** AUTH 的类型（参数 0）。 */
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        private val EMPTY = ByteArray(0)

        /** 解析 24 字节头；命令和取反对不上、长度不合理时抛 [MalformedMessageException]。 */
        fun parseHeader(bytes: ByteArray, offset: Int = 0): Header {
            if (bytes.size - offset < HEADER) throw MalformedMessageException("ADB 消息头不足 $HEADER 字节")
            val command = getLe(bytes, offset)
            if (getLe(bytes, offset + 20) != command.inv()) throw MalformedMessageException("ADB 消息头的校验字段不对")
            val length = getLe(bytes, offset + 12)
            if (length < 0 || length > MAX_INCOMING_PAYLOAD) throw MalformedMessageException("ADB 载荷长度不合理：$length")
            return Header(command, getLe(bytes, offset + 4), getLe(bytes, offset + 8), length, getLe(bytes, offset + 16))
        }

        fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) sum += b.toInt() and 0xFF
            return sum
        }

        fun name(command: Int): String = when (command) {
            CNXN -> "CNXN"
            AUTH -> "AUTH"
            OPEN -> "OPEN"
            OKAY -> "OKAY"
            CLSE -> "CLSE"
            WRTE -> "WRTE"
            STLS -> "STLS"
            else -> "0x" + Integer.toHexString(command)
        }

        internal fun putLe(out: ByteArray, at: Int, value: Int) {
            for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
        }

        internal fun getLe(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * ADB 认证用的 RSA 运算：
 * - 车机 adbd 发来随机令牌，车机端用 SHA-1 的 PKCS#1 v1.5 签名格式校验——令牌本身当作摘要，前面加 SHA-1 的
 *   DigestInfo 前缀，再做私钥运算（[sign]）；
 * - 车机不认识这把钥匙时，把公钥按 adbd 的格式发过去（[publicKey]），车机弹「允许调试吗」让车主确认。
 *
 * 纯数学实现，不依赖具体的加密提供方。
 */
object AdbAuth {
    /** 令牌长度：adbd 发的就是 SHA-1 摘要的长度。 */
    const val TOKEN_BYTES = 20

    /** SHA-1 的 DigestInfo 前缀（PKCS#1）。 */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /** adbd 的公钥结构只认 2048 位。 */
    const val KEY_BITS = 2048
    private const val WORDS = KEY_BITS / 32

    /** 对 [token] 签名：返回和模数等长的大端字节。 */
    fun sign(token: ByteArray, key: RSAPrivateKey): ByteArray {
        require(token.size == TOKEN_BYTES) { "ADB 令牌应为 $TOKEN_BYTES 字节，实际 ${token.size}" }
        val k = (key.modulus.bitLength() + 7) / 8
        val digestInfo = SHA1_DIGEST_INFO + token
        require(k >= digestInfo.size + 11) { "RSA 钥匙太短" }
        // EM = 00 01 FF…FF 00 ‖ DigestInfo
        val em = ByteArray(k)
        em[1] = 0x01
        for (i in 2 until k - digestInfo.size - 1) em[i] = 0xFF.toByte()
        System.arraycopy(digestInfo, 0, em, k - digestInfo.size, digestInfo.size)
        val s = BigInteger(1, em).modPow(key.privateExponent, key.modulus)
        return toFixed(s, k)
    }

    /**
     * 公钥按 adbd 的格式：小端结构「字数 64、n0inv、模数 64 字、R² mod n 64 字、公钥指数」做 Base64，后接空格、
     * [comment] 和结尾的 0 字节。只接受 2048 位的钥匙。
     */
    fun publicKey(key: RSAPublicKey, comment: String): ByteArray {
        val n = key.modulus
        require(n.bitLength() == KEY_BITS) { "ADB 只认 $KEY_BITS 位的 RSA 钥匙" }
        val struct = ByteArray(4 + 4 + WORDS * 4 + WORDS * 4 + 4)
        AdbMessage.putLe(struct, 0, WORDS)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32)
        AdbMessage.putLe(struct, 4, n0inv.toInt())
        putWords(struct, 8, n)
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        putWords(struct, 8 + WORDS * 4, rr)
        AdbMessage.putLe(struct, 8 + WORDS * 8, key.publicExponent.toInt())
        return (Base64.encode(struct) + " " + comment + "\u0000").toByteArray(Charsets.UTF_8)
    }

    /** [value] 按 32 位小端字、低位字在前写 [WORDS] 个字。 */
    private fun putWords(out: ByteArray, at: Int, value: BigInteger) {
        val mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
        var v = value
        for (i in 0 until WORDS) {
            AdbMessage.putLe(out, at + i * 4, v.and(mask).toInt())
            v = v.shiftRight(32)
        }
    }

    private fun toFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == length) return raw
        val out = ByteArray(length)
        if (raw.size > length) System.arraycopy(raw, raw.size - length, out, 0, length)
        else System.arraycopy(raw, 0, out, length - raw.size, raw.size)
        return out
    }
}
