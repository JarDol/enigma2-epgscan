package pl.jardol.e2stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object MdnsDiscovery {
    private const val SERVICE_TYPE = "_epgscan._tcp."

    data class Service(val ip: String, val port: Int, val webifPort: Int?, val streamPort: Int?)
    data class Result(val services: List<Service>, val warning: Boolean)

    internal fun portAttribute(attributes: Map<String, ByteArray>, name: String): Int? {
        val raw = attributes[name] ?: return null
        return raw.toString(Charsets.UTF_8).toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /** Discovery i resolve są sekwencyjne: starszy Android dopuszcza tylko jeden resolve naraz. */
    fun find(context: Context): Result {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return Result(emptyList(), true).also { AppLog.i("mDNS: usługa Android NSD niedostępna") }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        var multicast: WifiManager.MulticastLock? = null
        var warning = false
        try {
            if (wifi?.isWifiEnabled == true) {
                multicast = wifi.createMulticastLock("E2Stream-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: SecurityException) {
            AppLog.i("mDNS: nie można włączyć multicast: ${e.message}")
            warning = true
        } catch (e: UnsupportedOperationException) {
            AppLog.i("mDNS: multicast Wi-Fi niedostępny: ${e.message}")
            warning = true
        }
        val services = LinkedHashMap<String, NsdServiceInfo>()
        val started = CountDownLatch(1)
        val startError = AtomicInteger(-1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { started.countDown() }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                startError.set(code)
                started.countDown()
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) {
                    synchronized(services) { services[info.serviceName] = info }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(services) { services.remove(info.serviceName) }
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) { AppLog.i("mDNS: błąd zatrzymania $code") }
        }
        var registered = false
        try {
            try {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                registered = true
            } catch (e: IllegalArgumentException) {
                AppLog.i("mDNS: nie można rozpocząć wyszukiwania: ${e.message}")
                return Result(emptyList(), true)
            } catch (e: SecurityException) {
                AppLog.i("mDNS: brak uprawnień: ${e.message}")
                return Result(emptyList(), true)
            }
            if (!started.await(3, TimeUnit.SECONDS) || startError.get() != -1) {
                AppLog.i("mDNS: uruchomienie wyszukiwania nieudane (${startError.get()})")
                return Result(emptyList(), true)
            }
            Thread.sleep(3000)
            val candidates = synchronized(services) { services.values.toList() }
            val resolved = ArrayList<Service>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            for (candidate in candidates.take(32)) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) { warning = true; break }
                val done = CountDownLatch(1)
                var service: Service? = null
                var failed = false
                try {
                    @Suppress("DEPRECATION")
                    manager.resolveService(candidate, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                            AppLog.i("mDNS: nie można rozwiązać ${info.serviceName}: $code")
                            failed = true
                            done.countDown()
                        }
                        @Suppress("DEPRECATION")
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = if (Build.VERSION.SDK_INT >= 34) {
                                info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.host
                            } else info.host
                            if (address is Inet4Address && info.port in 1..65535) {
                                val webifPort = portAttribute(info.attributes, "webif-port")
                                val streamPort = portAttribute(info.attributes, "stream-port")
                                if (info.attributes.containsKey("webif-port") && webifPort == null ||
                                    info.attributes.containsKey("stream-port") && streamPort == null) {
                                    AppLog.i("mDNS: nieprawidłowy port w ogłoszeniu ${info.serviceName}")
                                    failed = true
                                }
                                service = Service(
                                    address.hostAddress ?: "", info.port, webifPort, streamPort,
                                )
                            } else {
                                AppLog.i("mDNS: brak poprawnego adresu IPv4 lub portu dla ${info.serviceName}")
                                failed = true
                            }
                            done.countDown()
                        }
                    })
                } catch (e: IllegalArgumentException) {
                    AppLog.i("mDNS: nie można rozwiązać ${candidate.serviceName}: ${e.message}")
                    warning = true
                    continue
                } catch (e: SecurityException) {
                    AppLog.i("mDNS: brak uprawnień do rozwiązywania: ${e.message}")
                    warning = true
                    break
                }
                if (!done.await(minOf(1500, remaining), TimeUnit.MILLISECONDS)) {
                    AppLog.i("mDNS: przekroczono czas rozwiązywania ${candidate.serviceName}")
                    warning = true
                    break
                }
                warning = warning || failed
                service?.let { resolved.add(it) }
            }
            if (candidates.size > 32) {
                AppLog.i("mDNS: pominięto usługi powyżej limitu 32")
                warning = true
            }
            return Result(resolved, warning)
        } finally {
            if (registered) {
                try { manager.stopServiceDiscovery(listener) }
                catch (e: IllegalArgumentException) { AppLog.i("mDNS: zatrzymanie: ${e.message}") }
                catch (e: SecurityException) { AppLog.i("mDNS: brak uprawnień do zatrzymania: ${e.message}") }
            }
            if (multicast?.isHeld == true) multicast.release()
        }
    }
}
