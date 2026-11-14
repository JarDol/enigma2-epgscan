package pl.jardol.e2stream.desktop

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Wyszukiwanie dekoderów z wtyczką EPG Scan przez mDNS / DNS-SD (usługa „_epgscan._tcp”, jak w Androidzie).
 * Zapytanie „legacy unicast” z losowego portu: Avahi na dekoderze odpowiada wprost na ten port, więc nie trzeba
 * nasłuchiwać na 5353 ani otwierać zapory.
 */
internal object MdnsDiscovery {
    const val SERVICE = "_epgscan._tcp.local"
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

    data class Service(val ip: String, val port: Int, val webifPort: Int?, val streamPort: Int?, val name: String)

    fun find(timeoutMs: Int = 2500): List<Service> {
        val found = linkedMapOf<String, Service>()
        val addresses = localAddresses().ifEmpty { listOf<InetAddress?>(null) }
        for (local in addresses) {
            runCatching {
                DatagramSocket(InetSocketAddress(local, 0)).use { socket ->
                    socket.soTimeout = 300
                    val query = query(SERVICE)
                    repeat(2) { socket.send(DatagramPacket(query, query.size, GROUP, 5353)) }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buffer = ByteArray(9000)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val source = (packet.address as? Inet4Address)?.hostAddress
                        parseResponse(packet.data.copyOf(packet.length), source).forEach { found[it.ip + ":" + it.port] = it }
                    }
                }
            }.onFailure { AppLog.i("mDNS: ${local?.hostAddress ?: "domyślny interfejs"}: ${it.message}") }
        }
        return found.values.toList()
    }

    private fun localAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    /** Zapytanie PTR o usługę (bez bitu QU — odpowiedź „legacy unicast” na port nadawcy). */
    internal fun query(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(out, name)
        out.write(byteArrayOf(0, TYPE_PTR.toByte(), 0, 1))
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private class Record(val name: String, val type: Int, val dataOffset: Int, val dataLength: Int)

    /** Usługi z odpowiedzi DNS-SD: PTR → instancja, SRV → port i host, TXT → porty OpenWebif, A → adres. */
    internal fun parseResponse(data: ByteArray, source: String?): List<Service> {
        if (data.size < 12 || (data[2].toInt() and 0x80) == 0) return emptyList()
        val counts = (0 until 4).map { u16(data, 4 + it * 2) }
        var offset = 12
        repeat(counts[0]) {
            offset = readName(data, offset).second + 4
        }
        val records = ArrayList<Record>()
        repeat(counts[1] + counts[2] + counts[3]) {
            if (offset >= data.size) return@repeat
            val (name, next) = readName(data, offset)
            if (next + 10 > data.size) return@repeat
            val type = u16(data, next)
            val length = u16(data, next + 8)
            val start = next + 10
            if (start + length > data.size) return@repeat
            records += Record(name.lowercase(), type, start, length)
            offset = start + length
        }
        val instances = records.filter { it.type == TYPE_PTR && it.name == SERVICE.lowercase() }
            .map { readName(data, it.dataOffset).first.lowercase() }
            .ifEmpty { records.filter { it.type == TYPE_SRV && it.name.endsWith(SERVICE.lowercase()) }.map { it.name } }
        val hosts = records.filter { it.type == TYPE_A && it.dataLength == 4 }.associate { record ->
            record.name to (0 until 4).joinToString(".") { (data[record.dataOffset + it].toInt() and 0xff).toString() }
        }
        return instances.distinct().mapNotNull { instance ->
            val srv = records.firstOrNull { it.type == TYPE_SRV && it.name == instance } ?: return@mapNotNull null
            val port = u16(data, srv.dataOffset + 4)
            val target = readName(data, srv.dataOffset + 6).first.lowercase()
            val txt = records.firstOrNull { it.type == TYPE_TXT && it.name == instance }?.let { txt(data, it) }.orEmpty()
            val ip = hosts[target] ?: source ?: return@mapNotNull null
            if (port !in 1..65535) return@mapNotNull null
            Service(
                ip = ip,
                port = port,
                webifPort = txt["webif-port"]?.toIntOrNull()?.takeIf { it in 1..65535 },
                streamPort = txt["stream-port"]?.toIntOrNull()?.takeIf { it in 1..65535 },
                name = instance.removeSuffix("." + SERVICE.lowercase()),
            )
        }
    }

    private fun txt(data: ByteArray, record: Record): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var index = record.dataOffset
        val end = record.dataOffset + record.dataLength
        while (index < end) {
            val length = data[index].toInt() and 0xff
            index++
            if (index + length > end) break
            val entry = String(data, index, length, Charsets.UTF_8)
            index += length
            val key = entry.substringBefore('=')
            if (key.isNotBlank()) out[key.lowercase()] = entry.substringAfter('=', "")
        }
        return out
    }

    /** Nazwa DNS z kompresją wskaźników; zwraca nazwę i pozycję za nazwą. */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var index = start
        var end = -1
        var jumps = 0
        while (index < data.size) {
            val length = data[index].toInt() and 0xff
            when {
                length == 0 -> {
                    if (end < 0) end = index + 1
                    break
                }
                (length and 0xC0) == 0xC0 -> {
                    if (index + 1 >= data.size || ++jumps > 20) break
                    if (end < 0) end = index + 2
                    index = ((length and 0x3F) shl 8) or (data[index + 1].toInt() and 0xff)
                }
                else -> {
                    if (index + 1 + length > data.size) break
                    labels += String(data, index + 1, length, Charsets.UTF_8)
                    index += 1 + length
                }
            }
        }
        return labels.joinToString(".") to (if (end < 0) data.size else end)
    }

    private fun u16(data: ByteArray, index: Int): Int =
        if (index + 1 >= data.size) 0 else ((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33
}
