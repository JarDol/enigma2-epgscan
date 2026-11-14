package pl.jardol.e2stream.desktop

import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

enum class BackButtonMode(val label: String) {
    AUTO("automatycznie — po użyciu myszy"),
    ALWAYS("zawsze widoczny"),
}

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
    val videoScaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val vlcDeint: String = "auto",
    val vlcHwMode: String = "auto",
    val bufferMs: Int = 1000,
    val audioLang: String = "pl",
    val uiListFont: Int = 100,
    val uiBannerFont: Int = 100,
    val hiddenBouquets: Set<String> = emptySet(),
    val resumeLast: Boolean = true,
    val lastBouquetRef: String = "",
    val lastChannelRef: String = "",
    val startBouquetRef: String = "",
    val startChannelRef: String = "",
    val startChannelName: String = "",
    val invertUpDown: Boolean = false,
    val bannerSec: Int = 5,
    val channelListSec: Int = 30,
    val pin: String = "",
    val pinMin: Int = 1,
    val navNoBanner: String = "",
    val navBanner: String = "",
    val backButtonMode: BackButtonMode = BackButtonMode.AUTO,
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
            setProperty("videoScaleMode", videoScaleMode.name)
            setProperty("vlcDeint", vlcDeint)
            setProperty("vlcHwMode", vlcHwMode)
            setProperty("bufferMs", bufferMs.toString())
            setProperty("audioLang", audioLang)
            setProperty("uiListFont", uiListFont.toString())
            setProperty("uiBannerFont", uiBannerFont.toString())
            setProperty("hiddenBouquets", JSONArray(hiddenBouquets.sorted()).toString())
            setProperty("resumeLast", resumeLast.toString())
            setProperty("lastBouquetRef", lastBouquetRef)
            setProperty("lastChannelRef", lastChannelRef)
            setProperty("startBouquetRef", startBouquetRef)
            setProperty("startChannelRef", startChannelRef)
            setProperty("startChannelName", startChannelName)
            setProperty("invertUpDown", invertUpDown.toString())
            setProperty("bannerSec", bannerSec.toString())
            setProperty("channelListSec", channelListSec.toString())
            setProperty("pin", pin)
            setProperty("pinMin", pinMin.toString())
            setProperty("navNoBanner", navNoBanner)
            setProperty("navBanner", navBanner)
            setProperty("backButtonMode", backButtonMode.name)
        }
        Files.newOutputStream(path).use { properties.store(it, "E2Stream settings") }
    }

    companion object {
        val deinterlaceModes = linkedMapOf(
            "auto" to "automatycznie",
            "off" to "wyłączone",
            "yadif" to "Yadif",
            "yadif2x" to "Yadif (2x)",
            "bob" to "Bob",
            "linear" to "Linear",
            "blend" to "Blend",
            "x" to "X",
        )

        val hardwareModes = linkedMapOf(
            "auto" to "automatycznie",
            "off" to "wyłączona",
            "d3d11va" to "Direct3D 11 (D3D11VA)",
            "dxva2" to "DirectX VA 2 (DXVA2)",
        )

        fun load(path: Path = configPath()): DesktopConfig {
            if (!Files.exists(path)) return DesktopConfig()
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            val defaults = DesktopConfig()
            fun string(name: String, default: String) = properties.getProperty(name, default)
            fun int(name: String, default: Int, range: IntRange) =
                properties.getProperty(name)?.toIntOrNull()?.coerceIn(range) ?: default
            fun bool(name: String, default: Boolean) =
                properties.getProperty(name)?.toBooleanStrictOrNull() ?: default
            return DesktopConfig(
                host = string("host", ""),
                httpPort = int("httpPort", 80, 1..65535),
                streamPort = int("streamPort", 8001, 1..65535),
                pluginPort = int("pluginPort", 8770, 1..65535),
                username = string("username", ""),
                password = string("password", ""),
                pluginToken = string("pluginToken", ""),
                remoteBindings = string("remoteBindings", ""),
                universalRemoteBindings = string("universalRemoteBindings", ""),
                videoScaleMode = VideoScaleMode.entries.firstOrNull { it.name == properties.getProperty("videoScaleMode") }
                    ?: VideoScaleMode.FIT,
                vlcDeint = string("vlcDeint", defaults.vlcDeint).takeIf { it in deinterlaceModes } ?: defaults.vlcDeint,
                vlcHwMode = string("vlcHwMode", defaults.vlcHwMode).takeIf { it in hardwareModes } ?: defaults.vlcHwMode,
                bufferMs = int("bufferMs", defaults.bufferMs, 250..10_000),
                audioLang = string("audioLang", defaults.audioLang),
                uiListFont = int("uiListFont", defaults.uiListFont, 50..140),
                uiBannerFont = int("uiBannerFont", defaults.uiBannerFont, 50..140),
                hiddenBouquets = parseSet(properties.getProperty("hiddenBouquets")),
                resumeLast = bool("resumeLast", defaults.resumeLast),
                lastBouquetRef = string("lastBouquetRef", ""),
                lastChannelRef = string("lastChannelRef", ""),
                startBouquetRef = string("startBouquetRef", ""),
                startChannelRef = string("startChannelRef", ""),
                startChannelName = string("startChannelName", ""),
                invertUpDown = bool("invertUpDown", defaults.invertUpDown),
                bannerSec = int("bannerSec", defaults.bannerSec, 1..30),
                channelListSec = int("channelListSec", defaults.channelListSec, 0..600),
                pin = string("pin", "").filter(Char::isDigit).take(6),
                pinMin = int("pinMin", defaults.pinMin, 0..600),
                navNoBanner = string("navNoBanner", ""),
                navBanner = string("navBanner", ""),
                backButtonMode = BackButtonMode.entries.firstOrNull { it.name == properties.getProperty("backButtonMode") }
                    ?: BackButtonMode.AUTO,
            )
        }

        private fun parseSet(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return try {
                val array = JSONArray(value)
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }

        private fun configPath(): Path {
            val appData = System.getenv("APPDATA")
            val base = if (!appData.isNullOrBlank()) Paths.get(appData) else
                Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("E2Stream").resolve("settings.properties")
        }
    }
}
