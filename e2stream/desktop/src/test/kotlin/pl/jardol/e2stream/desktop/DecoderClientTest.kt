package pl.jardol.e2stream.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecoderClientTest {
    @Test
    fun readsPluginBouquetsAndChannels() {
        val requestedPaths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.toString()
            val response = when (exchange.requestURI.path) {
                "/api/info" -> """{"api":1,"version":"2.15.0"}"""
                "/api/bouquets" -> """{"bouquets":[{"name":"TV &amp; Radio","ref":"bouquet%3Aref"}]}"""
                "/api/services" -> """{"services":[{"num":4,"name":"Kanał 1","play":"1:0:1:100:200:300:4000000:0:0:0:"}]}"""
                else -> """{"error":"not found"}"""
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(if (exchange.requestURI.path.startsWith("/api/")) 200 else 404, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val port = server.address.port
            val client = DecoderClient(
                DesktopConfig(host = "127.0.0.1", httpPort = port, pluginPort = port),
            )
            val catalog = client.loadBouquets()
            val bouquet = catalog.bouquets.single()
            val channels = client.loadChannels(bouquet, catalog.usesPlugin)

            assertTrue(catalog.usesPlugin)
            assertEquals("TV & Radio", bouquet.name)
            assertEquals("Kanał 1", channels.single().name)
            assertTrue(requestedPaths.any { it.contains("bRef=bouquet%253Aref") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fallsBackToOpenWebifWhenPluginIsUnavailable() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val response = when (exchange.requestURI.path) {
                "/api/info" -> """{"error":"not found"}"""
                "/api/getservices" -> """{"services":[{"servicename":"Bukiet","servicereference":"1:7:1:0:0:0:0:0:0:0:"}]}"""
                else -> """{"services":[]}"""
            }.toByteArray(StandardCharsets.UTF_8)
            val status = if (exchange.requestURI.path == "/api/info") 404 else 200
            exchange.sendResponseHeaders(status, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val port = server.address.port
            val catalog = DecoderClient(
                DesktopConfig(host = "127.0.0.1", httpPort = port, pluginPort = port),
            ).loadBouquets()

            assertTrue(!catalog.usesPlugin)
            assertEquals("Bukiet", catalog.bouquets.single().name)
        } finally {
            server.stop(0)
        }
    }
}
