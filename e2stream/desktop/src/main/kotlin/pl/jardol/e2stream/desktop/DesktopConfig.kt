package pl.jardol.e2stream.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

data class DesktopConfig(
    val host: String = "",
    val httpPort: Int = 80,
    val streamPort: Int = 8001,
    val pluginPort: Int = 8770,
    val username: String = "",
    val password: String = "",
    val pluginToken: String = "",
    val remoteBindings: String = "",
    val universalRemoteBindings: String = "",
) {
    fun save(path: Path = configPath()) {
        Files.createDirectories(path.parent)
        val properties = Properties().apply {
            setProperty("host", host)
            setProperty("httpPort", httpPort.toString())
            setProperty("streamPort", streamPort.toString())
            setProperty("pluginPort", pluginPort.toString())
            setProperty("username", username)
            setProperty("password", password)
            setProperty("pluginToken", pluginToken)
            setProperty("remoteBindings", remoteBindings)
            setProperty("universalRemoteBindings", universalRemoteBindings)
        }
        Files.newOutputStream(path).use { properties.store(it, "E2Stream settings") }
    }

    companion object {
        fun load(path: Path = configPath()): DesktopConfig {
            if (!Files.exists(path)) return DesktopConfig()
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            return DesktopConfig(
                host = properties.getProperty("host", ""),
                httpPort = properties.getProperty("httpPort", "80").toIntOrNull() ?: 80,
                streamPort = properties.getProperty("streamPort", "8001").toIntOrNull() ?: 8001,
                pluginPort = properties.getProperty("pluginPort", "8770").toIntOrNull() ?: 8770,
                username = properties.getProperty("username", ""),
                password = properties.getProperty("password", ""),
                pluginToken = properties.getProperty("pluginToken", ""),
                remoteBindings = properties.getProperty("remoteBindings", ""),
                universalRemoteBindings = properties.getProperty("universalRemoteBindings", ""),
            )
        }

        private fun configPath(): Path {
            val appData = System.getenv("APPDATA")
            val base = if (!appData.isNullOrBlank()) Paths.get(appData) else
                Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("E2Stream").resolve("settings.properties")
        }
    }
}
