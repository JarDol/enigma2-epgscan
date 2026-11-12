package pl.jardol.e2stream.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConfigTest {
    @Test
    fun savesAndLoadsDecoderAndRemoteSettings() {
        val directory = Files.createTempDirectory("e2stream-config-test")
        val path = directory.resolve("settings.properties")
        val config = DesktopConfig(
            host = "192.168.1.20",
            httpPort = 8080,
            streamPort = 8001,
            pluginPort = 8770,
            username = "receiver",
            password = "test-password",
            pluginToken = "test-token",
            remoteBindings = "119=CHANNEL_UP",
        )

        try {
            config.save(path)

            assertEquals(config, DesktopConfig.load(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
