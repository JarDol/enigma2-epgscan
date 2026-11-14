package pl.jardol.e2stream

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object DecoderProbe {
    data class Endpoint(val url: String, val headers: Map<String, String> = emptyMap())
    data class Result(val reachable: Boolean, val detail: String)

    fun check(endpoints: List<Endpoint>, timeoutMs: Int = 1500): Result {
        require(endpoints.isNotEmpty())
        val errors = mutableListOf<String>()
        for (endpoint in endpoints) {
            val c = URL(endpoint.url).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            endpoint.headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
            try {
                val code = c.responseCode
                if (code > 0) return Result(true, "HTTP $code")
                errors += "brak odpowiedzi HTTP"
            } catch (e: IOException) {
                errors += "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                c.disconnect()
            }
        }
        return Result(false, errors.joinToString("; "))
    }
}
