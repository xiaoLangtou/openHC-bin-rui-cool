package io.github.gcbond.openhc.core.common.adb

import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbProtocolTest {
    private val keys: KeyPair by lazy {
        // 合成钥匙：固定种子的随机源只是为了让测试稳定，与任何真实设备无关。
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        KeyPairGenerator.getInstance("RSA").apply { initialize(AdbAuth.KEY_BITS, random) }.generateKeyPair()
    }

    @Test
    fun messageRoundTrip() {
        val m = AdbMessage(AdbMessage.WRTE, 7, 9, "abc".toByteArray())
        val bytes = m.encode()
        assertEquals(AdbMessage.HEADER + 3, bytes.size)
        assertContentEquals(byteArrayOf(0x57, 0x52, 0x54, 0x45), bytes.copyOfRange(0, 4), "命令按小端写：WRTE")
        val h = AdbMessage.parseHeader(bytes)
        assertEquals(AdbMessage.WRTE, h.command)
        assertEquals(7, h.arg0)
        assertEquals(9, h.arg1)
        assertEquals(3, h.length)
        assertEquals('a'.code + 'b'.code + 'c'.code, h.checksum)
    }

    @Test
    fun badHeadersAreRejected() {
        val bytes = AdbMessage(AdbMessage.OKAY, 1, 2).encode()
        bytes[20] = 0
        assertFailsWith<MalformedMessageException> { AdbMessage.parseHeader(bytes) }
        assertFailsWith<MalformedMessageException> { AdbMessage.parseHeader(ByteArray(10)) }
        val huge = AdbMessage(AdbMessage.OKAY, 1, 2).encode()
        AdbMessage.putLe(huge, 12, AdbMessage.MAX_INCOMING_PAYLOAD + 1)
        assertFailsWith<MalformedMessageException> { AdbMessage.parseHeader(huge) }
    }

    @Test
    fun signatureIsPkcs1Sha1OverTheToken() {
        val token = ByteArray(AdbAuth.TOKEN_BYTES) { (it * 13 + 1).toByte() }
        val sig = AdbAuth.sign(token, keys.private as RSAPrivateKey)
        assertEquals(256, sig.size)
        // 验签：用公钥还原，得到 00 01 FF… 00 ‖ SHA-1 DigestInfo ‖ 令牌。
        val pub = keys.public as RSAPublicKey
        val em = BigInteger(1, sig).modPow(pub.publicExponent, pub.modulus).toByteArray().let { raw ->
            ByteArray(256 - raw.size) + raw
        }
        assertEquals(0, em[0].toInt())
        assertEquals(1, em[1].toInt())
        assertContentEquals(token, em.copyOfRange(256 - 20, 256))
        assertEquals(0x30, em[256 - 35].toInt())
        assertTrue(em.copyOfRange(2, 256 - 36).all { it == 0xFF.toByte() })
        assertEquals(0, em[256 - 36].toInt())
        assertFailsWith<IllegalArgumentException> { AdbAuth.sign(ByteArray(19), keys.private as RSAPrivateKey) }
    }

    @Test
    fun publicKeyUsesTheAdbdLayout() {
        val pub = keys.public as RSAPublicKey
        val text = String(AdbAuth.publicKey(pub, "openhc@car"), Charsets.UTF_8)
        assertTrue(text.endsWith(" openhc@car\u0000"), text.takeLast(20))
        val struct = java.util.Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(4 + 4 + 256 + 256 + 4, struct.size)
        assertEquals(64, AdbMessage.getLe(struct, 0))
        val n = words(struct, 8)
        assertEquals(pub.modulus, n)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = BigInteger.valueOf(AdbMessage.getLe(struct, 4).toLong() and 0xFFFFFFFFL)
        assertEquals(r32.subtract(BigInteger.ONE), n.mod(r32).multiply(n0inv).mod(r32), "n0inv × n0 ≡ -1 (mod 2^32)")
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), words(struct, 8 + 256))
        assertEquals(pub.publicExponent.toInt(), AdbMessage.getLe(struct, 8 + 512))
    }

    @Test
    fun handshakeSignsTheTokenAndRunsAShellCommand() {
        val token = ByteArray(20) { it.toByte() }
        val adbd = script(
            AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token),
            AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "device::\u0000".toByteArray()),
            AdbMessage(AdbMessage.OKAY, 55, 1),
            AdbMessage(AdbMessage.WRTE, 55, 1, "hello ".toByteArray()),
            AdbMessage(AdbMessage.WRTE, 55, 1, "world".toByteArray()),
            AdbMessage(AdbMessage.CLSE, 55, 1),
        )
        val sent = ByteArrayOutputStream()
        val client = AdbClient(adbd, sent, keys, "openhc@car")
        assertEquals(AdbClient.Access.READY, client.connect(mayAsk = false))
        assertEquals("hello world", client.shell("echo"))
        val out = parseAll(sent.toByteArray())
        assertEquals(
            listOf("CNXN", "AUTH", "OPEN", "OKAY", "OKAY", "CLSE"),
            out.map { AdbMessage.name(it.command) },
        )
        assertEquals(AdbMessage.AUTH_SIGNATURE, out[1].arg0)
        assertEquals("shell:echo\u0000", String(out[2].payload))
        assertEquals(1 to 55, out[5].arg0 to out[5].arg1)
    }

    @Test
    fun unknownKeyIsOfferedOnlyWhenAllowedToAsk() {
        val token = ByteArray(20) { 7 }
        val tokens = arrayOf(
            AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token),
            AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token),
        )
        val quiet = ByteArrayOutputStream()
        assertEquals(AdbClient.Access.NOT_APPROVED, AdbClient(script(*tokens), quiet, keys, "c").connect(mayAsk = false))
        assertTrue(parseAll(quiet.toByteArray()).none { it.arg0 == AdbMessage.AUTH_RSAPUBLICKEY && it.command == AdbMessage.AUTH })

        val asked = ByteArrayOutputStream()
        val approved = script(*tokens, AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD))
        assertEquals(AdbClient.Access.READY, AdbClient(approved, asked, keys, "c").connect(mayAsk = true))
        assertTrue(parseAll(asked.toByteArray()).any { it.command == AdbMessage.AUTH && it.arg0 == AdbMessage.AUTH_RSAPUBLICKEY })

        // 车主没点允许：读超时算没授权。
        val timeout = object : InputStream() {
            private val first = script(*tokens)
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val r = first.read(b, off, len)
                if (r < 0) throw InterruptedIOException("超时")
                return r
            }
        }
        assertEquals(AdbClient.Access.NOT_APPROVED, AdbClient(timeout, ByteArrayOutputStream(), keys, "c").connect(mayAsk = true))
    }

    @Test
    fun tlsOnlyAndClosedConnections() {
        val tls = script(AdbMessage(AdbMessage.STLS, 1, 0))
        assertEquals(AdbClient.Access.TLS_ONLY, AdbClient(tls, ByteArrayOutputStream(), keys, "c").connect(mayAsk = false))
        assertEquals(AdbClient.Access.FAILED, AdbClient(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), keys, "c").connect(false))
    }

    private fun script(vararg messages: AdbMessage): InputStream =
        ByteArrayInputStream(messages.fold(ByteArray(0)) { acc, m -> acc + m.encode() })

    private fun parseAll(bytes: ByteArray): List<AdbMessage> {
        val out = ArrayList<AdbMessage>()
        var at = 0
        while (at < bytes.size) {
            val h = AdbMessage.parseHeader(bytes, at)
            out += AdbMessage(h.command, h.arg0, h.arg1, bytes.copyOfRange(at + 24, at + 24 + h.length))
            at += 24 + h.length
        }
        return out
    }

    private fun words(struct: ByteArray, at: Int): BigInteger {
        var v = BigInteger.ZERO
        for (i in 63 downTo 0) {
            v = v.shiftLeft(32).or(BigInteger.valueOf(AdbMessage.getLe(struct, at + i * 4).toLong() and 0xFFFFFFFFL))
        }
        return v
    }
}
