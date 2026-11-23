package pl.jardol.e2stream

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StreamWatchdogTest {
    private class Playback {
        var state = Player.STATE_READY
        var playing = true
        var video = true
        val counters = DecoderCounters()
        var recoveries = 0
        val stalls = mutableListOf<String>()
        private val tracks = Tracks(listOf(Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("video/avc").build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
        )))
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayWhenReady" -> playing
                "getPlaybackState" -> state
                "getCurrentPosition" -> SystemClock.elapsedRealtime()
                "getTotalBufferedDuration" -> 2000L
                "getVideoDecoderCounters" -> counters
                "getCurrentTracks" -> if (video) tracks else Tracks.EMPTY
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ExoPlayer

        fun watchdog(fast: Boolean = false): StreamWatchdog = StreamWatchdog(
            player, 2000, 20_000, { stalls += it }, {}, startMs = 1000, reserveMs = 2000,
            onMissingVideo = { recoveries++ },
        ).also {
            it.start()
            it.newStream(fast, "channel-a")
        }
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test fun movingAudioClockDoesNotHideMissingFirstVideoEvenAfterFastStartEnds() {
        val playback = Playback()
        val watchdog = playback.watchdog(fast = true)
        try {
            advance(7999)
            assertEquals(0, playback.recoveries)
            advance(501)
            assertEquals(1, playback.recoveries)
            assertTrue(playback.stalls.isEmpty())
            advance(30_000)
            assertEquals(1, playback.recoveries)
        } finally { watchdog.stop() }
    }

    @Test fun firstFrameCallbackWithUnreportedTunneledCountersPreventsRecovery() {
        val playback = Playback()
        val watchdog = playback.watchdog()
        try {
            advance(4000)
            watchdog.onRenderedFirstFrame()
            advance(15_000)
            assertEquals(0, playback.recoveries)
            assertTrue(playback.stalls.isEmpty())
        } finally { watchdog.stop() }
    }

    @Test fun actualRenderedCounterProgressAlsoConfirmsVideo() {
        val playback = Playback()
        val watchdog = playback.watchdog()
        try {
            repeat(24) {
                playback.counters.renderedOutputBufferCount++
                advance(500)
            }
            assertEquals(0, playback.recoveries)
            assertTrue(playback.stalls.isEmpty())
        } finally { watchdog.stop() }
    }

    @Test fun bufferingAndPauseDoNotConsumeTheReadyGracePeriod() {
        val playback = Playback()
        val watchdog = playback.watchdog()
        try {
            advance(5000)
            playback.state = Player.STATE_BUFFERING
            advance(5000)
            playback.state = Player.STATE_READY
            advance(5000)
            assertEquals(0, playback.recoveries)
            watchdog.paused = true
            advance(10_000)
            watchdog.paused = false
            advance(7500)
            assertEquals(0, playback.recoveries)
            advance(1000)
            assertEquals(1, playback.recoveries)
        } finally { watchdog.stop() }
    }

    @Test fun radioRecordingsAndManualTimeshiftDoNotTriggerVideoRecovery() {
        val playback = Playback().also { it.video = false }
        val watchdog = playback.watchdog()
        try {
            advance(10_000)
            assertEquals(0, playback.recoveries)
            playback.video = true
            watchdog.newStream(false)
            advance(10_000)
            assertEquals(0, playback.recoveries)
            watchdog.newStream(false, "channel-a")
            watchdog.endStart()
            advance(10_000)
            assertEquals(0, playback.recoveries)
        } finally { watchdog.stop() }
    }

    @Test fun repeatedRestartsCannotCauseRepeatedRewindsOnTheSameChannel() {
        val playback = Playback()
        val watchdog = playback.watchdog()
        try {
            advance(8500)
            assertEquals(1, playback.recoveries)
            watchdog.newStream(false, "channel-a")
            advance(10_000)
            assertEquals(1, playback.recoveries)
            watchdog.newStream(false, "channel-b")
            advance(8500)
            assertEquals(2, playback.recoveries)
        } finally { watchdog.stop() }
    }
}
