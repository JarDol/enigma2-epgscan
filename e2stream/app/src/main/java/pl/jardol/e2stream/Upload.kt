package pl.jardol.e2stream

/**
 * Wysyłanie logu / zrzutu TS na dekoder: przez wtyczkę EPG Scan (2.15.0+), a gdy jej nie ma
 * albo jest starsza — przez FTP. Zwraca opis, dokąd poszło.
 */
object Upload {
    fun send(prefs: Prefs, name: String, data: ByteArray): String {
        val dir = prefs.ftpDir
        if (prefs.pluginOk) {
            try {
                PluginApi(prefs).upload(dir, name, data)
                return "$dir/$name (przez wtyczkę EPG Scan)"
            } catch (e: Exception) {
                AppLog.i("wysyłanie przez wtyczkę nieudane ($e) — próba przez FTP")
            }
        }
        FtpUpload.upload(prefs.host, 21, prefs.ftpUser, prefs.ftpPass, dir, name, data)
        return "$dir/$name (FTP)"
    }
}
