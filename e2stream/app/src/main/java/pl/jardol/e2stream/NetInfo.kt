package pl.jardol.e2stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Łącze urządzenia: typ, prędkość, sygnał i pasmo WiFi — do logu i statystyk (bez blokowania strumieni:
 * Android 9 podaje tylko prędkość wysyłania WiFi, niemiarodajną dla odbioru). Ethernet — prędkość karty z /sys.
 */
object NetInfo {
    class Link(val type: String, val mbps: Int, val rssi: Int, val band: String) {
        override fun toString(): String = when (type) {
            "WiFi" -> "WiFi $band $mbps Mb/s, $rssi dBm"
            "Ethernet" -> if (mbps > 0) "Ethernet $mbps Mb/s" else "Ethernet"
            else -> type
        }
    }

    fun read(ctx: Context): Link? = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            nc == null -> null
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION")
                val wi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                val rx = if (Build.VERSION.SDK_INT >= 29) wi.rxLinkSpeedMbps else -1
                val speed = if (rx > 0) rx else wi.linkSpeed
                Link("WiFi", speed, wi.rssi, if (wi.frequency > 4900) "5 GHz" else "2,4 GHz")
            }
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link("Ethernet", ethSpeed(), 0, "")
            else -> Link("inne", 0, 0, "")
        }
    } catch (_: Exception) { null }

    /** Prędkość karty Ethernet (100 / 1000 Mb/s) z systemu; 0 — nieznana. */
    private fun ethSpeed(): Int = try {
        java.io.File("/sys/class/net").listFiles { f -> f.name.startsWith("eth") }?.firstNotNullOfOrNull { d ->
            java.io.File(d, "speed").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        } ?: 0
    } catch (_: Exception) { 0 }
}
