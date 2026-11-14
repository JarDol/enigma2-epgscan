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

/** Okno główne: zwykłe okno, zmaksymalizowane (widać pasek zadań) albo pełny ekran bez ramki i paska zadań. */
enum class WindowMode(val label: String) {
    WINDOW("Okno"),
    MAXIMIZED("Zmaksymalizowane"),
    FULLSCREEN("Pełny ekran"),
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
    val ftpUser: String = "root",
    val ftpPass: String = "",
    val ftpDir: String = DEFAULT_FTP_DIR,
    val uiListIcon: Int = 80,
    val uiBannerIcon: Int = 100,
    val uiEpgFont: Int = 100,
    val epgUpdateMode: String = EPG_UPDATE_DAILY,
    val epgDaily: Boolean = true,
    val epgHour: String = "05:00",
    val epgDays: Int = 3,
    val picons: Boolean = true,
    val missingPiconLogo: Boolean = false,
    val piconDir: String = "",
    val showStats: Boolean = false,
    val logDir: String = "",
    val logFtp: Boolean = false,
    val lircEnabled: Boolean = false,
    val lircHost: String = "127.0.0.1",
    val lircPort: Int = 8765,
    val lastPiconFetchDay: String = "",
    val tsMin: Int = 0,
    val tsDir: String = "",
    val tsStepSec: Int = 5,
    val tsStepMin: Int = 1,
    val tsBackLive: Boolean = false,
    val windowMode: WindowMode = WindowMode.MAXIMIZED,
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
            setProperty("ftpUser", ftpUser)
            setProperty("ftpPass", ftpPass)
            setProperty("ftpDir", ftpDir)
            setProperty("uiListIcon", uiListIcon.toString())
            setProperty("uiBannerIcon", uiBannerIcon.toString())
            setProperty("uiEpgFont", uiEpgFont.toString())
            setProperty("epgUpdateMode", epgUpdateMode)
            setProperty("epgDaily", epgDaily.toString())
            setProperty("epgHour", epgHour)
            setProperty("epgDays", epgDays.toString())
            setProperty("picons", picons.toString())
            setProperty("missingPiconLogo", missingPiconLogo.toString())
            setProperty("piconDir", piconDir)
            setProperty("showStats", showStats.toString())
            setProperty("logDir", logDir)
            setProperty("logFtp", logFtp.toString())
            setProperty("lircEnabled", lircEnabled.toString())
            setProperty("lircHost", lircHost)
            setProperty("lircPort", lircPort.toString())
            setProperty("lastPiconFetchDay", lastPiconFetchDay)
            setProperty("tsMin", tsMin.toString())
            setProperty("tsDir", tsDir)
            setProperty("tsStepSec", tsStepSec.toString())
            setProperty("tsStepMin", tsStepMin.toString())
            setProperty("tsBackLive", tsBackLive.toString())
            setProperty("windowMode", windowMode.name)
        }
        Files.newOutputStream(path).use { properties.store(it, "E2Stream settings") }
    }

    companion object {
        const val DEFAULT_FTP_DIR = "/media/hdd/e2stream"
        const val EPG_UPDATE_DAILY = "daily"
        const val EPG_UPDATE_STARTUP = "startup"
        private val hourPattern = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

        /** "H:MM" / "HH:MM" → "HH:MM"; null, gdy format jest błędny. */
        fun normalizeHour(value: String): String? {
            val trimmed = value.trim()
            if (!hourPattern.matches(trimmed)) return null
            val (hour, minute) = trimmed.split(':')
            return "%02d:%s".format(hour.toInt(), minute)
        }

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
                ftpUser = string("ftpUser", defaults.ftpUser).ifBlank { defaults.ftpUser },
                ftpPass = string("ftpPass", ""),
                ftpDir = string("ftpDir", defaults.ftpDir).ifBlank { defaults.ftpDir },
                uiListIcon = int("uiListIcon", defaults.uiListIcon, 50..140),
                uiBannerIcon = int("uiBannerIcon", defaults.uiBannerIcon, 50..140),
                uiEpgFont = int("uiEpgFont", defaults.uiEpgFont, 50..140),
                epgUpdateMode = string("epgUpdateMode", defaults.epgUpdateMode)
                    .takeIf { it == EPG_UPDATE_DAILY || it == EPG_UPDATE_STARTUP } ?: defaults.epgUpdateMode,
                epgDaily = bool("epgDaily", defaults.epgDaily),
                epgHour = normalizeHour(string("epgHour", defaults.epgHour)) ?: defaults.epgHour,
                epgDays = int("epgDays", defaults.epgDays, 1..14),
                picons = bool("picons", defaults.picons),
                missingPiconLogo = bool("missingPiconLogo", defaults.missingPiconLogo),
                piconDir = string("piconDir", ""),
                showStats = bool("showStats", defaults.showStats),
                logDir = string("logDir", ""),
                logFtp = bool("logFtp", defaults.logFtp),
                lircEnabled = bool("lircEnabled", defaults.lircEnabled),
                lircHost = string("lircHost", defaults.lircHost).ifBlank { defaults.lircHost },
                lircPort = int("lircPort", defaults.lircPort, 1..65535),
                lastPiconFetchDay = string("lastPiconFetchDay", ""),
                tsMin = int("tsMin", defaults.tsMin, 0..600),
                tsDir = string("tsDir", ""),
                tsStepSec = int("tsStepSec", defaults.tsStepSec, 1..120),
                tsStepMin = int("tsStepMin", defaults.tsStepMin, 1..30),
                tsBackLive = bool("tsBackLive", defaults.tsBackLive),
                windowMode = WindowMode.entries.firstOrNull { it.name == properties.getProperty("windowMode") }
                    ?: defaults.windowMode,
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

        private fun configPath(): Path = appDataDir().resolve("settings.properties")

        /** %APPDATA%\E2Stream — ustawienia, logi i ikony kanałów. */
        fun appDataDir(): Path {
            val appData = System.getenv("APPDATA")
            val base = if (!appData.isNullOrBlank()) Paths.get(appData) else
                Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("E2Stream")
        }
    }
}
