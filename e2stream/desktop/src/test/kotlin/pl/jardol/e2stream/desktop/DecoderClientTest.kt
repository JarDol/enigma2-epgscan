package pl.jardol.e2stream.desktop

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecoderClientTest {
    @Test
    fun loadsCurrentEpgAndNormalizesServiceReferences() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestedQuery = ""
        var authorization = ""
        server.createContext("/api/epgnow") { exchange ->
            requestedQuery = exchange.requestURI.rawQuery
            authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val response = """{"events":[
                {"sref":"1:0:1:a:","title":"Film &amp; serial","begin_timestamp":100,"duration_sec":60},
                {"sref":"1:0:1:b:","title":"None"},
                {"sref":"","title":"Nieznany kanał"}
            ]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = DecoderClient(DesktopConfig(
                host = "127.0.0.1", httpPort = server.address.port, username = "user", password = "pass",
            ))
            val events = client.loadNow(Bouquet("TV", "bouquet:ref"))
            assertEquals(mapOf("1:0:1:A" to NowEvent("Film & serial", 100, 160)), events)
            assertEquals(events["1:0:1:A"], events[serviceKey("1:0:1:a::")])
            assertEquals("bRef=bouquet%3Aref", requestedQuery)
            assertEquals("Basic dXNlcjpwYXNz", authorization)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun reportsInvalidEpgResponse() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/epgnow") { exchange ->
            val response = """{"error":"unavailable"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = DecoderClient(DesktopConfig(host = "127.0.0.1", httpPort = server.address.port))
            assertFailsWith<IOException> { client.loadNow(Bouquet("TV", "bouquet")) }
        } finally {
            server.stop(0)
        }
    }

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
