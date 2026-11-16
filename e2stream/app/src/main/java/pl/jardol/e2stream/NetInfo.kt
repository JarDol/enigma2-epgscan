package pl.jardol.e2stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Łącze urządzenia: typ, prędkość, sygnał i pasmo WiFi — do limitu strumieni (bufory w tle, okna PiP).
 * Limit: WiFi 50% prędkości łącza (35% przy sygnale słabszym niż −75 dBm), Ethernet 80 Mb/s.
 */
object NetInfo {
    class Link(val type: String, val mbps: Int, val rssi: Int, val band: String) {
        val budgetMbps: Double?
            get() = when (type) {
                "WiFi" -> if (mbps <= 0) null else mbps * (if (rssi < -75) 0.35 else 0.5)
                "Ethernet" -> 80.0
                else -> null
            }

        override fun toString(): String = when (type) {
            "WiFi" -> "WiFi $band $mbps Mb/s, $rssi dBm"
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
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link("Ethernet", 0, 0, "")
            else -> Link("inne", 0, 0, "")
        }
    } catch (_: Exception) { null }
}
