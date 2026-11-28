package pl.jardol.e2stream

import org.junit.Assert.*
import org.junit.Test
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class AndroidTvRemoteProtocolTest {
    private fun servers(): Pair<SSLServerSocket, SSLServerSocket> {
        val provider = BouncyCastleProvider()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Fake Android TV")
        val now = System.currentTimeMillis()
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 1000), Date(now + 60000), name, key.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(key.private)))
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tv", key.private, CharArray(0), arrayOf(cert))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), SecureRandom()) }
        fun create() = (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
            needClientAuth = true
            soTimeout = 10000
        }
        return create() to create()
    }

    private fun pin(client: X509Certificate, server: X509Certificate): String {
        val c = client.publicKey as RSAPublicKey
        val s = server.publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")
        for (n in listOf(c.modulus, c.publicExponent, s.modulus, s.publicExponent)) {
            val b = n.toByteArray()
            digest.update(if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b)
        }
        digest.update(byteArrayOf(0x12, 0x34))
        return "%02x1234".format(digest.digest()[0].toInt() and 255)
    }

    @Test fun pairsAndSendsPowerWhenDeviceReportsOn() = runSession(on = true, blocked = false)
    @Test fun doesNotSendPowerWhenDeviceReportsOff() = runSession(on = false, blocked = false)
    @Test fun recordingGuardPreventsPower() = runSession(on = true, blocked = true)

    private fun runSession(on: Boolean, blocked: Boolean) {
        val (pairServer, remoteServer) = servers()
        val executor = Executors.newSingleThreadExecutor()
        val code = ArrayBlockingQueue<String>(1)
        val run = executor.submit {
            (pairServer.accept() as SSLSocket).use { socket ->
                socket.startHandshake()
                val clientCert = socket.session.peerCertificates.first() as X509Certificate
                val serverCert = socket.session.localCertificates.first() as X509Certificate
                code.put(pin(clientCert, serverCert))
                fun receive(field: Int) {
                    val msg = Wire.parse(Wire.read(socket.inputStream))
                    assertEquals(200, msg.int(2))
                    assertTrue(field in msg)
                }
                fun send(field: Int) = Wire.write(socket.outputStream,
                    Wire.message { number(1, 2); number(2, 200); bytes(field, byteArrayOf()) })
                receive(10); send(11)
                receive(20); send(20)
                receive(30); send(31)
                receive(40); send(41)
            }
            (remoteServer.accept() as SSLSocket).use { socket ->
                socket.startHandshake()
                Wire.write(socket.outputStream, Wire.message {
                    bytes(1, Wire.message { number(1, 35) })
                })
                assertTrue(1 in Wire.parse(Wire.read(socket.inputStream)))
                Wire.write(socket.outputStream, Wire.message { bytes(2, byteArrayOf()) })
                assertTrue(2 in Wire.parse(Wire.read(socket.inputStream)))
                Wire.write(socket.outputStream, Wire.message { bytes(40, Wire.message { number(1, if (on) 1 else 0) }) })
                if (on && !blocked) {
                    val message = Wire.parse(Wire.read(socket.inputStream))
                    val key = Wire.parse(message.getValue(10))
                    assertEquals(26, key.int(1))
                    assertEquals(3, key.int(2))
                } else assertEquals(-1, socket.inputStream.read())
            }
        }
        try {
            val client = AndroidTvRemoteTest("127.0.0.1", pairServer.localPort, remoteServer.localPort)
            client.requestPin()
            client.confirmPin(code.poll(5, TimeUnit.SECONDS) ?: error("No pairing code"))
            var checked = false
            try {
                client.standby {
                    checked = true
                    if (blocked) throw IllegalStateException("Recording")
                }
                assertFalse(blocked)
            } catch (e: IllegalStateException) {
                assertTrue(blocked)
            }
            assertEquals(on, checked)
            run.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            try { run.get(3, TimeUnit.SECONDS) } catch (server: Exception) { e.addSuppressed(server) }
            throw e
        } finally {
            pairServer.close()
            remoteServer.close()
            executor.shutdownNow()
        }
    }
}
