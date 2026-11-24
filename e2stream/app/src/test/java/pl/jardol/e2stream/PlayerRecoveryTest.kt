package pl.jardol.e2stream

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
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
    @Test
    fun messageBarDurationSettingFollowsToggle() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.channelSync = false
        prefs.messageBarSec = 7
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        SettingsActivity::class.java.getDeclaredMethod("openSection", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Ogólne")
        val list = activity.findViewById<ListView>(R.id.settingsList)
        fun indexOf(label: String): Int = (0 until list.adapter.count).firstOrNull { index ->
            val row = list.adapter.getView(index, null, list)
            val text = if (row is TextView) row else row.findViewById<TextView>(R.id.label)
            text.text.toString() == label
        } ?: -1
        val toggle = indexOf(activity.getString(R.string.setting_message_bar))
        val duration = activity.getString(R.string.setting_message_bar_duration)
        assertTrue(toggle >= 0)
        assertEquals(toggle + 1, indexOf(duration))
        list.performItemClick(list.adapter.getView(toggle, null, list), toggle, list.adapter.getItemId(toggle))
        assertFalse(prefs.messageBar)
        assertEquals(-1, indexOf(duration))
        list.performItemClick(list.adapter.getView(toggle, null, list), toggle, list.adapter.getItemId(toggle))
        assertTrue(prefs.messageBar)
        assertEquals(toggle + 1, indexOf(duration))
        assertEquals(7, prefs.messageBarSec)
        controller.pause().stop().destroy()
    }

    @Test
    fun messageBarUsesConfiguredDurationAndCanBeDisabled() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        assertTrue(prefs.messageBar)
        assertEquals(5, prefs.messageBarSec)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.channelSync = false
        prefs.logFtp = false
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog().dismiss()
        val show = PlayerActivity::class.java.getDeclaredMethod("showMessage", String::class.java)
        show.isAccessible = true
        val message = get<TextView>(activity, "message")
        show.invoke(activity, "Test")
        assertEquals(View.VISIBLE, message.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999))
        assertEquals(View.VISIBLE, message.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(View.GONE, message.visibility)
        prefs.messageBarSec = 10
        show.invoke(activity, "Test")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(View.VISIBLE, message.visibility)
        prefs.messageBar = false
        show.invoke(activity, "Hidden")
        assertEquals(View.GONE, message.visibility)
        prefs.messageBarSec = 0
        assertEquals(1, prefs.messageBarSec)
        prefs.messageBarSec = 11
        assertEquals(10, prefs.messageBarSec)
        controller.pause().stop().destroy()
    }

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

    private fun fakePlayer(context: Context, onStop: () -> Unit, onSource: (MediaSource) -> Unit, tracks: Tracks = Tracks.EMPTY): ExoPlayer {
        val parameters = TrackSelectionParameters.Builder(context).build()
        return Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getTrackSelectionParameters" -> parameters
                "getCurrentTracks" -> tracks
                "stop" -> { onStop(); null }
                "setMediaSource" -> { onSource(args!![0] as MediaSource); null }
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
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999))
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
    fun missingVideoRecoveryReusesRecorderAndStartsAtEarlierKeyframeWithSameAudio() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.channelSync = false
        prefs.logFtp = false
        prefs.bufferMs = 1000
        prefs.rebufferMs = 2000
        prefs.tsMin = 0
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog().dismiss()
        val buffer = TimeshiftBuffer(File(context.cacheDir, "video-recovery-${System.nanoTime()}"), 5)
        val recorder = TimeshiftRecorder("http://example.test/live", null, { null }, onFailure = {})
        try {
            val packets = ByteArray(188 * 2) { 0xff.toByte() }
            val pcr = byteArrayOf(0x47, 0x41, 0, 0x30, 7, 0x10, 0, 0, 0, 0, 0, 0)
            val key = byteArrayOf(0x47, 0x41, 0, 0x10, 0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0, 0, 0, 0, 1, 0x65)
            pcr.copyInto(packets)
            key.copyInto(packets, 188)
            buffer.pcrPid = { 0x100 }
            buffer.write(packets, 0, packets.size)
            val keyframe = buffer.keyframeAt(Long.MAX_VALUE)!!
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            buffer.write(packets, 0, packets.size)
            val total = buffer.total
            var sources = 0
            var sourceUri: Uri? = null
            val tracks = Tracks(listOf(Tracks.Group(
                TrackGroup(Format.Builder().setId("15037/634").setSampleMimeType("audio/mpeg-L2").build()),
                false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
            )))
            set(activity, "player", fakePlayer(activity, {}, {
                sources++
                sourceUri = it.mediaItem.localConfiguration?.uri
            }, tracks))
            set(activity, "safeMode", false)
            set(activity, "currentUrl", "http://example.test/live")
            set(activity, "tsLiveUrl", "http://example.test/live")
            set(activity, "bufLive", true)
            set(activity, "tsBuf", buffer)
            set(activity, "tsRec", recorder)
            call(activity, "recoverMissingVideo")
            assertEquals(1, sources)
            assertTrue(get<Boolean>(activity, "tsMode"))
            assertTrue(get<Boolean>(activity, "tsShifted"))
            assertEquals(keyframe.second, get<Long>(activity, "tsBaseWall"))
            assertEquals(keyframe.first.toString(), sourceUri?.lastPathSegment)
            assertEquals(634, get<Int>(activity, "tsAudioPid"))
            assertTrue(buffer === get<TimeshiftBuffer>(activity, "tsBuf"))
            assertTrue(recorder === get<TimeshiftRecorder>(activity, "tsRec"))
            assertEquals(total, buffer.total)
            assertFalse(get<Boolean>(activity, "restartPending"))
        } finally {
            controller.pause().stop().destroy()
            recorder.stop()
            buffer.close()
        }
    }

    @Test
    fun missingVideoWithoutBufferFallsBackButDoesNotRewindRecordingOrShiftedPlayback() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.channelSync = false
        prefs.logFtp = false
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog().dismiss()
        try {
            var sources = 0
            set(activity, "player", fakePlayer(activity, {}, { sources++ }))
            set(activity, "safeMode", false)
            set(activity, "currentUrl", "http://example.test/live")
            set(activity, "recMode", true)
            call(activity, "recoverMissingVideo")
            assertFalse(get<Boolean>(activity, "restartPending"))
            set(activity, "recMode", false)
            set(activity, "tsMode", true)
            call(activity, "recoverMissingVideo")
            assertFalse(get<Boolean>(activity, "restartPending"))
            set(activity, "tsMode", false)
            call(activity, "recoverMissingVideo")
            assertTrue(get<Boolean>(activity, "restartPending"))
            assertFalse(get<Boolean>(activity, "tsMode"))
            assertEquals(0, sources)
        } finally { controller.pause().stop().destroy() }
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
        val packets = ByteArray(188 * 20_000).apply {
            for (offset in indices step 188) {
                this[offset] = 0x47.toByte()
                this[offset + 3] = 0x20.toByte()
                this[offset + 4] = 7
                this[offset + 5] = 0x10
            }
        }
        buffer.write(packets, 0, packets.size)
        assertTrue(buffer.total > 0)
        val oldest = buffer.oldestWall
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        call(activity, "tsCtlOpen")
        assertEquals("", get<TextView>(activity, "bannerStart").text.toString())
        assertTrue(get<TextView>(activity, "bannerNow").text.toString().contains("0:04"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(38))
        set(activity, "tsMode", true)
        set(activity, "tsShifted", true)
        set(activity, "tsBaseWall", oldest + 15_000)
        call(activity, "tsUpdateBanner")
        assertEquals(activity.getString(R.string.player_ts_shifted_buffer, "⏸", "0:28", "0:42"),
            get<TextView>(activity, "bannerNow").text.toString())

        val seek = PlayerActivity::class.java.getDeclaredMethod("tsSeek", java.lang.Long.TYPE)
        seek.isAccessible = true
        repeat(3) { seek.invoke(activity, -5_000L) }

        assertEquals(0, get<ProgressBar>(activity, "bannerProgress").progress)
        assertEquals("", get<TextView>(activity, "bannerStart").text.toString())
        assertEquals(activity.getString(R.string.player_ts_shifted_buffer, "⏸", "0:42", "0:42"),
            get<TextView>(activity, "bannerNow").text.toString())
        call(activity, "tsCtlOpen")
        assertEquals(1000, get<ProgressBar>(activity, "bannerProgress").progress)
        assertEquals("", get<TextView>(activity, "bannerStart").text.toString())
        assertTrue(get<TextView>(activity, "bannerNow").text.toString().contains("0:42"))
        call(activity, "pvCancel")
        get<Runnable>(activity, "tsApply").run()
        assertEquals(activity.getString(R.string.player_ts_start, "0:42"), get<TextView>(activity, "message").text.toString())
        assertEquals(0, get<ProgressBar>(activity, "bannerProgress").progress)
        assertEquals("", get<TextView>(activity, "bannerStart").text.toString())
        assertEquals(activity.getString(R.string.player_ts_shifted_buffer, "⏸", "0:43", "0:42"),
            get<TextView>(activity, "bannerNow").text.toString())
        controller.pause().stop().destroy()
    }
}
