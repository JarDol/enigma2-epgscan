package pl.jardol.e2stream

import androidx.media3.common.C

internal fun audioUnderrunBufferLabel(bufferSizeMs: Long): String =
    if (bufferSizeMs == C.TIME_UNSET) "nieznany" else "$bufferSizeMs ms"
