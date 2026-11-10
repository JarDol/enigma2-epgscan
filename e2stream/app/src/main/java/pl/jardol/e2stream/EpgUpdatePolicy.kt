package pl.jardol.e2stream

object EpgUpdatePolicy {
    const val DAILY = "daily"
    const val STARTUP = "startup"

    fun shouldCheck(mode: String, startupPending: Boolean, lastCheck: Long, today: Long): Boolean =
        if (mode == STARTUP) startupPending else lastCheck < today

    fun packIdentity(host: String, port: Int, at: Long, size: Long, sha256: String): String {
        val file = if (sha256.isNotBlank()) "sha256:$sha256" else "legacy:$at:$size"
        return "${host.trim().lowercase()}:$port/$file"
    }

    fun needsImport(current: String, applied: String, manual: Boolean): Boolean =
        manual || current != applied
}
