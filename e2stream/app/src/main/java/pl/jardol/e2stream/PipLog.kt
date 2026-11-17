package pl.jardol.e2stream

/**
 * Wpisy PiP — w logu głównym z prefiksem „PiP:” (jeden plik: wysyłka na dekoder, kopia na dysku, pobieranie
 * przez APK Updatera). Osobny e2stream-pip.log już nie powstaje.
 */
object PipLog {
    fun newSession(header: String) = i("=== sesja PiP | $header")

    fun i(msg: String) = AppLog.i("PiP: $msg")
}
