package pl.jardol.e2stream

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StartupLoadControlTest {
    private val delegate = object : LoadControl {
        override fun getAllocator(playerId: PlayerId): Allocator = DefaultAllocator(true, 65536)
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
            parameters.bufferedDurationUs >= if (parameters.rebuffering) 4_500_000L else 1_500_000L
    }

    private val timeline = SinglePeriodTimeline(C.TIME_UNSET, false, false, false, null, MediaItem.fromUri("http://example.test/live"))
    private fun params(rebuffering: Boolean, bufferUs: Long) = LoadControl.Parameters(
        PlayerId.UNSET, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, bufferUs, 1f,
        true, rebuffering, C.TIME_UNSET, C.TIME_UNSET,
    )

    @Test fun firstEarlyRebufferUsesStartThresholdOnlyOnce() {
        val control = StartupLoadControl(delegate)
        control.begin(true)
        assertFalse(control.shouldStartPlayback(params(true, 1_000_000)))
        assertTrue(control.shouldStartPlayback(params(true, 1_500_000)))
        assertFalse(control.shouldStartPlayback(params(true, 1_500_000)))
        assertTrue(control.shouldStartPlayback(params(true, 4_500_000)))
    }

    @Test fun disabledCancelledOrExpiredStartupKeepsNormalRebuffer() {
        val control = StartupLoadControl(delegate)
        control.begin(false)
        assertFalse(control.shouldStartPlayback(params(true, 1_500_000)))
        control.begin(true)
        control.cancel()
        assertFalse(control.shouldStartPlayback(params(true, 1_500_000)))
        control.begin(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(control.shouldStartPlayback(params(true, 1_500_000)))
        assertTrue(control.shouldStartPlayback(params(false, 1_500_000)))
    }

    @Test fun defaultLoadControlUsesTheConfiguredStartupThreshold() {
        val base = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 20_000, 1_500, 4_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val control = StartupLoadControl(base)
        control.onPrepared(PlayerId.UNSET)
        control.begin(true)
        assertFalse(control.shouldStartPlayback(params(true, 1_000_000)))
        assertTrue(control.shouldStartPlayback(params(true, 1_500_000)))
        assertFalse(control.shouldStartPlayback(params(true, 1_500_000)))
        assertTrue(control.shouldStartPlayback(params(true, 4_500_000)))
        control.onReleased(PlayerId.UNSET)
    }
}
