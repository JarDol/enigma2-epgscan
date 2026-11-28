package pl.jardol.e2stream

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.X509KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.asn1.x509.BasicConstraints
import org.spongycastle.asn1.x509.Extension
import org.spongycastle.asn1.x509.GeneralName
import org.spongycastle.asn1.x509.GeneralNames
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Experimental Android TV Remote v2 client. Credentials live only for this test session.
 */
internal class AndroidTvRemoteTest(
    private val host: String = "127.0.0.1",
    private val pairPort: Int = 6467,
    private val remotePort: Int = 6466,
) : PhilipsPairingServer.PairingTarget {
    override val pinPattern = Regex("[0-9a-fA-F]{6}")
    private val provider = BouncyCastleProvider()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val certificate: X509Certificate = run {
        val name = X500Name("CN=E2Stream")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()),
            Date(now - 60_000), Date(now + 365L * 24 * 3600 * 1000), name, keyPair.public)
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(true))
        builder.addExtension(Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, "E2Stream")))
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private)
        JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
    }
    private val pairingLock = Any()
    @Volatile private var pairingSocket: SSLSocket? = null
    private var pairingCancelled = false
    @Volatile private var peerCertificate: X509Certificate? = null
    @Volatile private var paired = false

    private fun connect(port: Int, expected: X509Certificate?, pairing: Boolean = false): SSLSocket {
        val keys = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
                if (keyType == "RSA") arrayOf("e2stream") else null
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: java.net.Socket?): String? =
                if (keyType == null || "RSA" in keyType) "e2stream" else null
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: java.net.Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == "e2stream") arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == "e2stream") keyPair.private else null
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing server certificate")
                if (expected != null && !MessageDigest.isEqual(expected.encoded, chain[0].encoded))
                    throw java.security.cert.CertificateException("Server certificate changed")
            }
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        try {
            if (pairing) synchronized(pairingLock) {
                if (pairingCancelled) throw IOException("Pairing cancelled")
                pairingSocket = socket
            }
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 10000
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            if (pairing) synchronized(pairingLock) {
                if (pairingSocket === socket) pairingSocket = null
            }
            socket.close()
            throw e
        }
    }

    override fun requestPin() {
        close()
        paired = false
        val socket = connect(pairPort, null, pairing = true)
        try {
            peerCertificate = socket.session.peerCertificates.first() as X509Certificate
            val input = socket.inputStream
            val output = socket.outputStream
            sendPair(output, 10, Wire.message { string(1, "atvremote"); string(2, "E2Stream") })
            expectPair(input, 11)
            val encoding = Wire.message { number(1, 3); number(2, 6) }
            sendPair(output, 20, Wire.message { bytes(1, encoding); number(3, 1) })
            expectPair(input, 20)
            sendPair(output, 30, Wire.message { bytes(1, encoding); number(2, 1) })
            expectPair(input, 31)
            socket.soTimeout = 180_000
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun confirmPin(pin: String) {
        require(pin.matches(pinPattern)) { "Invalid pairing code" }
        val socket = pairingSocket ?: throw IOException("Pairing not started")
        val peer = peerCertificate ?: throw IOException("Missing TV certificate")
        val secret = pairingSecret(certificate, peer, pin)
        sendPair(socket.outputStream, 40, Wire.message { bytes(1, secret) })
        expectPair(socket.inputStream, 41)
        paired = true
        close()
    }

    fun standby(beforeSend: () -> Unit): Boolean {
        if (!paired) throw IOException("Not paired")
        val peer = peerCertificate ?: throw IOException("Missing TV certificate")
        connect(remotePort, peer).use { socket ->
            val input = socket.inputStream
            val output = socket.outputStream
            var features = 35
            repeat(30) {
                val message = Wire.parse(Wire.read(input))
                when {
                    1 in message -> {
                        val config = Wire.parse(message.getValue(1))
                        features = (config.int(1) ?: 0) and features
                        if (features and 2 == 0) throw IOException("Remote key commands unavailable")
                        val info = Wire.message {
                            number(3, 1); string(4, "1"); string(5, "atvremote"); string(6, "1.0.0")
                        }
                        Wire.write(output, Wire.message {
                            bytes(1, Wire.message { number(1, features); bytes(2, info) })
                        })
                    }
                    2 in message -> Wire.write(output, Wire.message {
                        bytes(2, Wire.message { number(1, features) })
                    })
                    8 in message -> {
                        val ping = Wire.parse(message.getValue(8)).int(1) ?: 0
                        Wire.write(output, Wire.message { bytes(9, Wire.message { number(1, ping) }) })
                    }
                    3 in message -> throw IOException("Android TV Remote reported an error")
                    40 in message -> {
                        val on = Wire.parse(message.getValue(40)).int(1) == 1
                        if (!on) return false
                        beforeSend()
                        Wire.write(output, Wire.message { bytes(10, Wire.message { number(1, 26); number(2, 3) }) })
                        return true
                    }
                }
            }
            throw IOException("Android TV Remote did not report power state")
        }
    }

    override fun close() {
        val socket = synchronized(pairingLock) {
            val current = pairingSocket
            pairingSocket = null
            current
        }
        socket?.close()
    }

    override fun cancel() {
        val socket = synchronized(pairingLock) {
            pairingCancelled = true
            val current = pairingSocket
            pairingSocket = null
            current
        }
        socket?.close()
    }

    private fun sendPair(output: OutputStream, field: Int, payload: ByteArray) {
        Wire.write(output, Wire.message { number(1, 2); number(2, 200); bytes(field, payload) })
    }

    private fun expectPair(input: InputStream, field: Int) {
        val reply = Wire.parse(Wire.read(input))
        if (reply.int(2) != 200 || field !in reply) throw IOException("Pairing failed at step $field")
    }

    companion object {
        internal fun pairingSecret(client: X509Certificate, server: X509Certificate, pin: String): ByteArray {
            require(pin.matches(Regex("[0-9a-fA-F]{6}"))) { "Invalid pairing code" }
            val c = client.publicKey as RSAPublicKey
            val s = server.publicKey as RSAPublicKey
            fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let {
                if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
            }
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(c.modulus, c.publicExponent, s.modulus, s.publicExponent).forEach { digest.update(unsigned(it)) }
            digest.update(pin.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            val secret = digest.digest()
            if ((secret[0].toInt() and 255) != pin.substring(0, 2).toInt(16))
                throw IllegalArgumentException("Invalid pairing code")
            return secret
        }
    }
}

internal object Wire {
    class Writer {
        private val buffer = ByteArrayOutputStream()
        private val out = CodedOutputStream.newInstance(buffer)
        fun number(field: Int, value: Int) = out.writeUInt32(field, value)
        fun string(field: Int, value: String) = out.writeString(field, value)
        fun bytes(field: Int, value: ByteArray) = out.writeByteArray(field, value)
        fun result(): ByteArray { out.flush(); return buffer.toByteArray() }
    }

    class Fields(private val bytes: Map<Int, ByteArray>, private val ints: Map<Int, Int>) {
        operator fun contains(field: Int) = field in bytes || field in ints
        fun getValue(field: Int) = bytes[field] ?: throw IOException("Missing field $field")
        fun int(field: Int) = ints[field]
    }

    fun message(block: Writer.() -> Unit) = Writer().apply(block).result()
    fun parse(data: ByteArray): Fields {
        val input = CodedInputStream.newInstance(data)
        val bytes = mutableMapOf<Int, ByteArray>()
        val ints = mutableMapOf<Int, Int>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            when (tag and 7) {
                0 -> ints[tag ushr 3] = input.readUInt32()
                2 -> bytes[tag ushr 3] = input.readByteArray()
                else -> if (!input.skipField(tag)) throw IOException("Invalid protobuf field")
            }
        }
        return Fields(bytes, ints)
    }
    fun write(output: OutputStream, data: ByteArray) {
        val header = CodedOutputStream.newInstance(output)
        header.writeUInt32NoTag(data.size)
        header.flush()
        output.write(data)
        output.flush()
    }
    fun read(input: InputStream): ByteArray {
        var size = 0
        var shift = 0
        while (shift < 35) {
            val b = input.read()
            if (b < 0) throw EOFException("Disconnected")
            size = size or ((b and 127) shl shift)
            if (b and 128 == 0) {
                if (size < 0 || size > 1_048_576) throw IOException("Invalid message length")
                val data = ByteArray(size)
                var pos = 0
                while (pos < size) {
                    val n = input.read(data, pos, size - pos)
                    if (n < 0) throw EOFException("Incomplete message")
                    pos += n
                }
                return data
            }
            shift += 7
        }
        throw IOException("Invalid message length")
    }
}
