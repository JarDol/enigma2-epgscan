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
    /** Ścieżki dźwięku kanałów wspólne z Androidem (przez wtyczkę EPG Scan). */
    val audioSync: Boolean = true,
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
    val tsBannerSec: Int = 6,
    val actionDialogSec: Int = 30,
    val confirmDialogSec: Int = 30,
    val epgChannelSec: Int = 30,
    val epgMultiSec: Int = 30,
    val infoDialogSec: Int = 7,
    val hideInfoDialogs: Boolean = false,
    val zapHistoryDays: Int = 28,
    val menuOrder: List<String> = emptyList(),
    val menuHidden: Set<String> = emptySet(),
    val appLanguage: String = AppLanguage.SYSTEM,
    val appVolume: Int = 100,
    val appMuted: Boolean = false,
    val volumeKeysApp: Boolean = true,
    val volumeStep: Int = 5,
    val lastEpgFetch: Long = 0,
    val epgPackIdentity: String = "",
    val epgPackCheckAt: Long = 0,
    /** EIT „teraz/następne” ze strumienia dekodera (Enigma2: Include EIT in http streams). */
    val eitStream: Boolean = false,
    /** Podmiana EPG danymi z EIT — zapis w EPG kanału do następnej aktualizacji z dekodera. */
    val eitReplace: Boolean = true,
    /** Czy po wybraniu dekodera zapadła już decyzja o imporcie kopii ustawień (jak w Androidzie). */
    val backupDecision: Boolean = false,
    /** Watchdog: ponowne otwarcie strumienia, gdy obraz stoi; przy braku dekodera — czekanie i wznowienie. */
    val watchdog: Boolean = true,
    /** Ile sekund stojącego obrazu (+2 s zapasu) to zawieszenie. */
    val stallSec: Int = 3,
    /** Katalog nagrań zapisanych z bufora timeshiftu (pusty = Wideo\E2Stream). */
    val recLocalDir: String = "",
    /** Sprawdzanie nowych wersji raz na dobę przy uruchomieniu. */
    val autoUpdate: Boolean = true,
    /** "stable" albo "test" (także wersje testowe). */
    val updateChannel: String = "stable",
    val lastUpdateCheck: Long = 0,
    /** Najdłuższe nagranie z timeshiftu (godziny, 1–6). */
    val recMaxH: Int = 3,
    /** Gdy E2Stream jest w tle, dowolny klawisz pilota HID / IR przywołuje okno (pierwsze naciśnięcie). */
    val activateAnyRemoteKey: Boolean = true,
    /** Synchronizacja ustawień i danych kanałów z dekoderem (wtyczka EPG Scan), jak w Androidzie. */
    val channelSync: Boolean = true,
    /** Nazwa tego komputera w profilach na dekoderze (pusta = nazwa komputera). */
    val deviceName: String = "",
    /** Dodatkowa podsieć do wyszukiwania dekoderów, np. 192.168.2.0/24. */
    val discoverySubnet: String = "",
    /** Przy braku komunikacji z dekoderem — okno blokujące zamiast samego komunikatu. */
    val blockOnDisconnect: Boolean = false,
    /** Śledzenie klawiszy pilota w logu. */
    val remoteTrace: Boolean = false,
    /** VLC dołączony do instalatora (true) albo zainstalowany w systemie (false). */
    val vlcInternal: Boolean = true,
    /** Klatki VLC w pełnej rozdzielczości (false — zmniejszane do rozmiaru ekranu, np. 4K → 1080p). */
    val vlcFullRes: Boolean = false,
    /** Wyświetlanie obrazu: "gpu" — klatki YUV, kolory i skalowanie na karcie graficznej; "cpu" — klatki RGB z VLC. */
    val vlcRender: String = "gpu",
    /** Lżejsze dekodowanie (bez filtra wygładzającego HEVC/H.264): "uhd" — kanały 4K, "always", "off". */
    val lightDecode: String = "uhd",
) {
    fun save(path: Path = configPath()) {
        Files.createDirectories(path.parent)
        Files.newOutputStream(path).use { toProperties().store(it, "E2Stream settings") }
    }

    /** Ustawienia jako właściwości (te same nazwy co w pliku). */
    fun toProperties(): Properties = Properties().apply {
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
            setProperty("audioSync", audioSync.toString())
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
            setProperty("tsBannerSec", tsBannerSec.toString())
            setProperty("actionDialogSec", actionDialogSec.toString())
            setProperty("confirmDialogSec", confirmDialogSec.toString())
            setProperty("epgChannelSec", epgChannelSec.toString())
            setProperty("epgMultiSec", epgMultiSec.toString())
            setProperty("infoDialogSec", infoDialogSec.toString())
            setProperty("hideInfoDialogs", hideInfoDialogs.toString())
            setProperty("zapHistoryDays", zapHistoryDays.toString())
            setProperty("menuOrder", menuOrder.joinToString(","))
            setProperty("menuHidden", JSONArray(menuHidden.sorted()).toString())
            setProperty("appLanguage", appLanguage)
            setProperty("appVolume", appVolume.toString())
            setProperty("appMuted", appMuted.toString())
            setProperty("volumeKeysApp", volumeKeysApp.toString())
            setProperty("volumeStep", volumeStep.toString())
            setProperty("lastEpgFetch", lastEpgFetch.toString())
            setProperty("epgPackIdentity", epgPackIdentity)
            setProperty("epgPackCheckAt", epgPackCheckAt.toString())
            setProperty("eitStream", eitStream.toString())
            setProperty("eitReplace", eitReplace.toString())
            setProperty("backupDecision", backupDecision.toString())
            setProperty("watchdog", watchdog.toString())
            setProperty("stallSec", stallSec.toString())
            setProperty("recLocalDir", recLocalDir)
            setProperty("autoUpdate", autoUpdate.toString())
            setProperty("updateChannel", updateChannel)
            setProperty("lastUpdateCheck", lastUpdateCheck.toString())
            setProperty("recMaxH", recMaxH.toString())
            setProperty("activateAnyRemoteKey", activateAnyRemoteKey.toString())
            setProperty("channelSync", channelSync.toString())
            setProperty("deviceName", deviceName)
            setProperty("discoverySubnet", discoverySubnet)
            setProperty("blockOnDisconnect", blockOnDisconnect.toString())
            setProperty("remoteTrace", remoteTrace.toString())
            setProperty("vlcInternal", vlcInternal.toString())
            setProperty("vlcFullRes", vlcFullRes.toString())
            setProperty("vlcRender", vlcRender)
            setProperty("lightDecode", lightDecode)
    }

    companion object {
        const val DEFAULT_FTP_DIR = "/media/hdd/e2stream"
        const val EPG_UPDATE_DAILY = "daily"
        const val EPG_UPDATE_STARTUP = "startup"
        /** Głośność VLC ponad 100% to wzmocnienie programowe. */
        const val MAX_VOLUME = 150
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
            "auto" to "automatycznie (programowo)",
            "off" to "wyłączona",
            "d3d11va" to "Direct3D 11 (D3D11VA)",
            "dxva2" to "DirectX VA 2 (DXVA2)",
        )

        fun load(path: Path = configPath()): DesktopConfig {
            if (!Files.exists(path)) return DesktopConfig()
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            return fromProperties(properties)
        }

        fun fromProperties(properties: Properties): DesktopConfig {
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
                audioSync = bool("audioSync", defaults.audioSync),
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
                tsBannerSec = int("tsBannerSec", defaults.tsBannerSec, 1..30),
                actionDialogSec = int("actionDialogSec", defaults.actionDialogSec, 0..600),
                confirmDialogSec = int("confirmDialogSec", defaults.confirmDialogSec, 0..600),
                epgChannelSec = int("epgChannelSec", defaults.epgChannelSec, 0..600),
                epgMultiSec = int("epgMultiSec", defaults.epgMultiSec, 0..600),
                infoDialogSec = int("infoDialogSec", defaults.infoDialogSec, 1..600),
                hideInfoDialogs = bool("hideInfoDialogs", defaults.hideInfoDialogs),
                zapHistoryDays = int("zapHistoryDays", defaults.zapHistoryDays, 0..28)
                    .takeIf { it in ZapTimerStore.HISTORY_DAYS } ?: defaults.zapHistoryDays,
                menuOrder = string("menuOrder", "").split(',').map(String::trim).filter(String::isNotEmpty),
                menuHidden = parseSet(properties.getProperty("menuHidden")),
                appLanguage = string("appLanguage", defaults.appLanguage).takeIf(AppLanguage::isValid) ?: defaults.appLanguage,
                appVolume = int("appVolume", defaults.appVolume, 0..MAX_VOLUME),
                appMuted = bool("appMuted", defaults.appMuted),
                volumeKeysApp = bool("volumeKeysApp", defaults.volumeKeysApp),
                volumeStep = int("volumeStep", defaults.volumeStep, 1..25),
                lastEpgFetch = properties.getProperty("lastEpgFetch")?.toLongOrNull() ?: 0,
                epgPackIdentity = string("epgPackIdentity", ""),
                epgPackCheckAt = properties.getProperty("epgPackCheckAt")?.toLongOrNull() ?: 0,
                eitStream = bool("eitStream", defaults.eitStream),
                eitReplace = bool("eitReplace", defaults.eitReplace),
                // Starsze wersje: dekoder już skonfigurowany — bez pytania o kopię.
                backupDecision = bool("backupDecision", properties.getProperty("host").orEmpty().isNotBlank()),
                watchdog = bool("watchdog", defaults.watchdog),
                stallSec = int("stallSec", defaults.stallSec, 1..30),
                recLocalDir = string("recLocalDir", ""),
                autoUpdate = bool("autoUpdate", defaults.autoUpdate),
                updateChannel = string("updateChannel", defaults.updateChannel).takeIf { it == "stable" || it == "test" }
                    ?: defaults.updateChannel,
                lastUpdateCheck = properties.getProperty("lastUpdateCheck")?.toLongOrNull() ?: 0,
                recMaxH = int("recMaxH", defaults.recMaxH, 1..6),
                activateAnyRemoteKey = bool("activateAnyRemoteKey", defaults.activateAnyRemoteKey),
                channelSync = bool("channelSync", defaults.channelSync),
                deviceName = string("deviceName", ""),
                discoverySubnet = string("discoverySubnet", ""),
                blockOnDisconnect = bool("blockOnDisconnect", defaults.blockOnDisconnect),
                remoteTrace = bool("remoteTrace", defaults.remoteTrace),
                vlcInternal = bool("vlcInternal", defaults.vlcInternal),
                vlcFullRes = bool("vlcFullRes", defaults.vlcFullRes),
                vlcRender = properties.getProperty("vlcRender")?.takeIf { it in setOf("gpu", "cpu") } ?: defaults.vlcRender,
                lightDecode = properties.getProperty("lightDecode")?.takeIf { it in setOf("uhd", "always", "off") } ?: defaults.lightDecode,
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
