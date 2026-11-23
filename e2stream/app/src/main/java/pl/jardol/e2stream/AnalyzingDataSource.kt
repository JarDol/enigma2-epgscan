package pl.jardol.e2stream

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Przepuszcza dane z sieci do ExoPlayera, po drodze podając je analizatorowi TS. */
class AnalyzingDataSource(
    private val upstream: DataSource,
    private val seekable: Boolean = false,
    private val analyzer: () -> TsAnalyzer?,
) : DataSource {
    private val sourceAnalyzer = analyzer()
    private val analyzerSession = sourceAnalyzer?.sessionId
    private var bytes = 0L
    private var reads = 0L
    private var sourceMs = 0L
    private var analysisMs = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val size = upstream.open(dataSpec)
        if (seekable && analyzer() === sourceAnalyzer) sourceAnalyzer?.seekRead(analyzerSession)
        return size
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val t0 = SystemClock.elapsedRealtime()
        val n = upstream.read(buffer, offset, length)
        val sourceTime = SystemClock.elapsedRealtime() - t0
        val a = analyzer()
        if (a != null && a === sourceAnalyzer) {
            a.readTime(sourceTime, analyzerSession)
            val before = SystemClock.elapsedRealtime()
            if (n > 0) a.feed(buffer, offset, n, analyzerSession)
            if (seekable) analysisMs += SystemClock.elapsedRealtime() - before
        }
        if (seekable && n > 0) {
            bytes += n
            reads++
            sourceMs += sourceTime
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try { upstream.close() }
        finally {
            if (seekable && bytes >= 1_000_000) {
                AppLog.i("nagranie: odczyt ${bytes / 1_000_000} MB, $reads żądań, źródło $sourceMs ms, analiza TS $analysisMs ms")
            }
            bytes = 0; reads = 0; sourceMs = 0; analysisMs = 0
        }
    }
}
