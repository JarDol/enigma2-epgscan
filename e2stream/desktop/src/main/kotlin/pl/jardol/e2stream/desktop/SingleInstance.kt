package pl.jardol.e2stream.desktop

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

internal class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
) : AutoCloseable {
    private val handler = AtomicReference<(() -> Unit)?>(null)
    private val pendingRequests = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var closed = false

    init {
        Thread(::acceptRequests, "E2Stream-instance").apply {
            isDaemon = true
            start()
        }
    }

    fun onPowerRequest(callback: () -> Unit) {
        handler.set(callback)
        repeat(pendingRequests.getAndSet(0).coerceAtMost(4)) {
            callback()
        }
    }

    private fun acceptRequests() {
        while (!closed) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    val receivedToken = reader.readLine().orEmpty()
                    val request = reader.readLine().orEmpty()
                    val accepted = constantTimeEquals(token, receivedToken) && request == POWER_REQUEST
                    writer.write(if (accepted) "OK\n" else "REJECTED\n")
                    writer.flush()
                    if (accepted) {
                        val callback = handler.get()
                        if (callback == null) pendingRequests.updateAndGet { (it + 1).coerceAtMost(4) }
                        else callback()
                    }
                }
            } catch (e: IOException) {
                if (!closed) System.err.println("E2Stream: błąd kanału aktywacji: ${e.message}")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val POWER_REQUEST = "POWER"
        private val loopback = InetAddress.getLoopbackAddress()

        fun startOrSignal(lockPath: Path = instancePath()): SingleInstance? {
            Files.createDirectories(lockPath.parent)
            repeat(30) {
                val channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return startPrimary(lockPath, channel, lock)
                channel.close()
                if (signalPrimary(lockPath)) return null
                Thread.sleep(100)
            }
            throw IOException("Nie udało się przekazać polecenia do uruchomionej aplikacji E2Stream.")
        }

        private fun startPrimary(path: Path, channel: FileChannel, lock: FileLock): SingleInstance {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(loopback, 0), 8)
                }
                val tokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
                val token = tokenBytes.joinToString("") { "%02x".format(it) }
                val state = "${server.localPort}\n$token\n".toByteArray(StandardCharsets.UTF_8)
                channel.truncate(0)
                channel.position(0)
                val buffer = ByteBuffer.wrap(state)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
                return SingleInstance(channel, lock, server, token)
            } catch (e: Exception) {
                runCatching { lock.release() }
                runCatching { channel.close() }
                throw e
            }
        }

        private fun signalPrimary(path: Path): Boolean {
            val state = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }.getOrNull()
                ?: return false
            val port = state.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            val token = state.getOrNull(1)?.takeIf { it.length == 64 } ?: return false
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(loopback, port), 250)
                    socket.soTimeout = 500
                    val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                    writer.write("$token\n$POWER_REQUEST\n")
                    writer.flush()
                    socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine() == "OK"
                }
            } catch (_: IOException) {
                false
            }
        }

        private fun constantTimeEquals(expected: String, actual: String): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                actual.toByteArray(StandardCharsets.UTF_8),
            )

        private fun instancePath(): Path {
            val appData = System.getenv("APPDATA")
            val base = if (!appData.isNullOrBlank()) Path.of(appData) else
                Path.of(System.getProperty("user.home"), ".config")
            return base.resolve("E2Stream").resolve("instance.lock")
        }
    }
}
