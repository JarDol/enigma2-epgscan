package pl.jardol.e2stream

import android.os.SystemClock
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

internal class StartupLoadControl(private val delegate: LoadControl) : LoadControl by delegate {
    @Volatile private var expiresAt = 0L

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, tracks: TrackGroupArray, selections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, tracks, selections)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    fun begin(enabled: Boolean) {
        expiresAt = if (enabled) SystemClock.elapsedRealtime() + 10_000 else 0
    }

    fun cancel() { expiresAt = 0 }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!parameters.rebuffering || expiresAt == 0L || now >= expiresAt) {
            return delegate.shouldStartPlayback(parameters)
        }
        expiresAt = 0
        AppLog.i("start kanału: niedobór — oczekiwanie na pełny zapas")
        return delegate.shouldStartPlayback(parameters)
    }
}
