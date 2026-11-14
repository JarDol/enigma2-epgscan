package pl.jardol.e2stream

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerRecoveryTest {
    private fun set(activity: PlayerActivity, name: String, value: Any?) {
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(activity: PlayerActivity, name: String): T =
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun call(activity: PlayerActivity, name: String) {
        PlayerActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    private fun awaitProbe(activity: PlayerActivity) {
        get<ExecutorService>(activity, "probeIo").submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun respond(server: ServerSocket) = thread {
        server.accept().use { socket ->
            val input = socket.getInputStream().bufferedReader()
            while (input.readLine()?.isNotEmpty() == true) { }
            socket.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
            )
        }
    }

    private fun fakePlayer(context: Context, onStop: () -> Unit, onSource: () -> Unit): ExoPlayer {
        val parameters = TrackSelectionParameters.Builder(context).build()
        return Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getTrackSelectionParameters" -> parameters
                "stop" -> { onStop(); null }
                "setMediaSource" -> { onSource(); null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as ExoPlayer
    }

    @Test
    fun disconnectedReceiverWaitsAndResumesSameStreamWithoutSafeMode() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.engine = "vlc"
        prefs.host = "127.0.0.1"
        val port = ServerSocket(0).use { it.localPort }
        prefs.httpPort = port
        prefs.channelSync = false
        prefs.logFtp = false
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog().dismiss()
        set(activity, "safeMode", false)
        var stops = 0
        var starts = 0
        set(activity, "player", fakePlayer(activity, { stops++ }, { starts++ }))
        val recovery = get<PlaybackRecovery>(activity, "playbackRecovery")
        recovery.markHealthy()
        recovery.recordVlcRestart()
        recovery.recordVlcRestart()
        val analyzer = TsAnalyzer(0) {}
        analyzer.feed(ByteArray(188) { if (it == 0) 0x47 else 0 }, 0, 188)
        set(activity, "analyzer", analyzer)
        val url = "http://127.0.0.1:8001/channel"
        set(activity, "currentUrl", url)
        set(activity, "streamStartAt", SystemClock.elapsedRealtime())
        set(activity, "pendingReason", "VLC stopped")

        call(activity, "doRestart")
        awaitProbe(activity)

        assertTrue(get(activity, "decoderUnavailable"))
        assertFalse(get(activity, "safeMode"))
        assertEquals(1, stops)
        assertEquals(0, starts)
        assertEquals("", prefs.playbackAttempt)
        assertEquals(VlcFailureAction.RETRY, recovery.vlcFailureAction())

        get<Runnable>(activity, "connectionGuardTick").run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2999))
        assertEquals(0, starts)

        ServerSocket(port).use { server ->
            val responder = respond(server)
            prefs.engine = "exo"
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            awaitProbe(activity)
            responder.join(5000)
            assertFalse(responder.isAlive)
        }
        assertFalse(get(activity, "decoderUnavailable"))
        assertFalse(get(activity, "safeMode"))
        assertEquals(url, get<String>(activity, "currentUrl"))
        assertEquals(1, starts)
        assertEquals("", prefs.playbackAttempt)
        controller.pause().stop().destroy()
    }

    @Test
    fun liveVlcFailureShowsPlaybackOptionsInsteadOfSafeStartup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.engine = "vlc"
        prefs.host = "127.0.0.1"
        ServerSocket(0).use { server ->
            prefs.httpPort = server.localPort
            prefs.channelSync = false
            prefs.logFtp = false
            SyncManager.init(context)
            val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
            val activity = controller.get()
            ShadowDialog.getLatestDialog().dismiss()
            set(activity, "safeMode", false)
            val recovery = get<PlaybackRecovery>(activity, "playbackRecovery")
            recovery.markHealthy()
            recovery.recordVlcRestart()
            recovery.recordVlcRestart()
            val analyzer = TsAnalyzer(0) {}
            analyzer.feed(ByteArray(188) { if (it == 0) 0x47 else 0 }, 0, 188)
            set(activity, "analyzer", analyzer)
            set(activity, "currentUrl", "http://127.0.0.1:8001/channel")
            set(activity, "streamStartAt", SystemClock.elapsedRealtime())
            set(activity, "pendingReason", "VLC stopped")
            val responder = respond(server)

            call(activity, "doRestart")
            awaitProbe(activity)
            responder.join(5000)

            assertFalse(get(activity, "safeMode"))
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals(activity.getString(R.string.player_vlc_failure_title),
                dialog.window!!.decorView.findViewWithTag<TextView>("title").text.toString())
            assertEquals(activity.getString(R.string.message_retry),
                dialog.window!!.decorView.findViewWithTag<Button>("chip_0").text.toString())
            assertEquals(activity.getString(R.string.message_settings),
                dialog.window!!.decorView.findViewWithTag<Button>("chip_1").text.toString())
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun vlcFailureBeforeFirstPlaybackStillOffersSafeStartup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.engine = "vlc"
        prefs.host = "127.0.0.1"
        ServerSocket(0).use { server ->
            prefs.httpPort = server.localPort
            prefs.channelSync = false
            prefs.logFtp = false
            SyncManager.init(context)
            val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
            val activity = controller.get()
            ShadowDialog.getLatestDialog().dismiss()
            set(activity, "safeMode", false)
            val recovery = get<PlaybackRecovery>(activity, "playbackRecovery")
            recovery.recordVlcRestart()
            recovery.recordVlcRestart()
            val analyzer = TsAnalyzer(0) {}
            analyzer.feed(ByteArray(188) { if (it == 0) 0x47 else 0 }, 0, 188)
            set(activity, "analyzer", analyzer)
            set(activity, "currentUrl", "http://127.0.0.1:8001/channel")
            set(activity, "streamStartAt", SystemClock.elapsedRealtime())
            set(activity, "pendingReason", "VLC stopped")
            val responder = respond(server)

            call(activity, "doRestart")
            awaitProbe(activity)
            responder.join(5000)

            assertTrue(get(activity, "safeMode"))
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals(activity.getString(R.string.message_starting_in_safe_mode),
                dialog.window!!.decorView.findViewWithTag<TextView>("title").text.toString())
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun firstStreamByteTimestampRemainsStableUntilReset() {
        val analyzer = TsAnalyzer(0) {}
        val packet = ByteArray(188) { if (it == 0) 0x47 else 0 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        analyzer.feed(packet, 0, packet.size)
        val first = analyzer.firstFeedAt
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        analyzer.feed(packet, 0, packet.size)
        assertTrue(first > 0)
        assertEquals(first, analyzer.firstFeedAt)
        assertTrue(analyzer.lastFeedAt > first)
        val oldSession = analyzer.sessionId
        analyzer.reset()
        assertEquals(0L, analyzer.firstFeedAt)
        analyzer.feed(packet, 0, packet.size, oldSession)
        assertEquals(0L, analyzer.bytes)
        assertEquals(0L, analyzer.firstFeedAt)
        analyzer.readTime(5000, oldSession)
        assertEquals(0L, analyzer.maxGapMs)
        analyzer.feed(packet, 0, packet.size, analyzer.sessionId)
        assertEquals(packet.size.toLong(), analyzer.bytes)
    }

    @Test
    fun oldExoSourceCannotAddBytesToNewChannelMetrics() {
        val analyzer = TsAnalyzer(0) {}
        val upstream = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec) = 188L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = 0x47
                return 188
            }
            override fun getUri(): Uri? = null
            override fun close() {}
        }
        val oldSource = AnalyzingDataSource(upstream) { analyzer }
        analyzer.reset()
        val packet = ByteArray(188)
        oldSource.read(packet, 0, packet.size)
        assertEquals(0L, analyzer.bytes)
        AnalyzingDataSource(upstream) { analyzer }.read(packet, 0, packet.size)
        assertEquals(188L, analyzer.bytes)
    }

    @Test
    fun timeshiftBannerStartsAtTheEarliestSeekablePosition() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.channelSync = false
        prefs.logFtp = false
        prefs.tsMin = 5
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog().dismiss()
        set(activity, "safeMode", false)
        set(activity, "player", fakePlayer(activity, {}, {}))
        val buffer = TimeshiftBuffer(File(context.cacheDir, "timeshift-banner-test"), 5)
        set(activity, "tsBuf", buffer)
        call(activity, "tsCtlOpen")
        assertEquals("", get<TextView>(activity, "bannerStart").text.toString())
        assertEquals(1000, get<ProgressBar>(activity, "bannerProgress").progress)
        val packets = ByteArray(188 * 20_000) { if (it % 188 == 0) 0x47 else 0 }
        buffer.write(packets, 0, packets.size)
        val oldest = buffer.oldestWall
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        call(activity, "tsCtlOpen")
        assertEquals("", get<TextView>(activity, "bannerStart").text.toString())
        assertTrue(get<TextView>(activity, "bannerNow").text.toString().contains("0:00"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(38))
        set(activity, "tsMode", true)
        set(activity, "tsShifted", true)
        set(activity, "tsBaseWall", oldest + 15_000)

        val seek = PlayerActivity::class.java.getDeclaredMethod("tsSeek", java.lang.Long.TYPE)
        seek.isAccessible = true
        repeat(3) { seek.invoke(activity, -5_000L) }

        assertEquals(0, get<ProgressBar>(activity, "bannerProgress").progress)
        assertEquals("−0:33", get<TextView>(activity, "bannerStart").text.toString())
        assertTrue(get<TextView>(activity, "bannerNow").text.toString().endsWith("−0:33"))
        call(activity, "tsCtlOpen")
        assertEquals(1000, get<ProgressBar>(activity, "bannerProgress").progress)
        assertEquals("−0:33", get<TextView>(activity, "bannerStart").text.toString())
        assertTrue(get<TextView>(activity, "bannerNow").text.toString().contains("0:33"))
        get<Runnable>(activity, "tsApply").run()
        assertEquals(activity.getString(R.string.player_ts_start, "0:33"), get<TextView>(activity, "message").text.toString())
        assertEquals(0, get<ProgressBar>(activity, "bannerProgress").progress)
        assertEquals("−0:33", get<TextView>(activity, "bannerStart").text.toString())
        assertTrue(get<TextView>(activity, "bannerNow").text.toString().endsWith("−0:33"))
        controller.pause().stop().destroy()
    }
}
