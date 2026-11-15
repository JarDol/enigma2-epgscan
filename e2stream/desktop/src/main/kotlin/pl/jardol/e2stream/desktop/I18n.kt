package pl.jardol.e2stream.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Język interfejsu: jak w Androidzie — automatycznie (język systemu), polski albo angielski. */
internal object AppLanguage {
    const val SYSTEM = "system"
    const val POLISH = "pl"
    const val ENGLISH = "en"

    val choices = listOf(SYSTEM, POLISH, ENGLISH)

    fun isValid(value: String) = value in choices

    /** Język systemu inny niż polski i angielski → angielski. */
    fun resolve(value: String, systemLanguage: String = Locale.getDefault().language): String = when (value) {
        POLISH, ENGLISH -> value
        else -> if (systemLanguage == POLISH) POLISH else ENGLISH
    }

    fun label(value: String): String = when (value) {
        POLISH -> tr("Polski")
        ENGLISH -> tr("Angielski")
        else -> tr("Automatycznie (język systemu)")
    }

    var current by mutableStateOf(resolve(SYSTEM))
        private set

    fun apply(value: String) {
        current = resolve(value)
    }
}

/**
 * Tłumaczenie tekstów interfejsu (na razie zakres jak w Androidzie: sekcje ustawień, menu, podpowiedzi).
 * Teksty bez tłumaczenia zostają po polsku.
 */
internal fun tr(polish: String): String =
    if (AppLanguage.current == AppLanguage.ENGLISH) English[polish] ?: polish else polish

private val English = mapOf(
    "Ustawienia" to "Settings",
    "Dekoder Enigma2" to "Enigma2 receiver",
    "adres, porty, logowanie OpenWebif, wtyczka EPG Scan, bukiety" to "address, ports, OpenWebif login, EPG Scan plugin, bouquets",
    "Odtwarzanie i obraz" to "Playback and video",
    "VLC: przeplot, akceleracja, bufor, obraz, okno, timeshift, dźwięk" to
        "VLC: deinterlacing, acceleration, buffer, video, window, timeshift, audio",
    "Wygląd" to "Appearance",
    "czcionki i ikony: lista kanałów, EPG, pasek kanału" to "font and picon sizes: channel list, EPG, channel banner",
    "EPG i ikony kanałów" to "EPG and channel picons",
    "pobieranie EPG, ikony z dekodera" to "EPG downloads, picons from receiver",
    "Ogólne" to "General",
    "bukiety, kanał startowy, menu, czasy okien, harmonogram, język, PIN" to
        "bouquets, startup channel, menu, timeouts, schedule, language, PIN",
    "Pilot i klawiatura" to "Remote and keyboard",
    "pilot HID / IR (WinLIRC), mapowanie klawiszy, OK i strzałki, test pilota" to
        "HID / IR remote (WinLIRC), key mapping, OK and arrows, remote test",
    "Informacje i aktualizacje" to "Information and updates",
    "wersja aplikacji i VLC, aktualizacje, opis aplikacji i wtyczki EPG Scan" to "app and VLC version, updates, application and EPG Scan plugin information",
    "Odtwarzacz VLC" to "VLC player",
    "Wersja VLC" to "VLC version",
    "Aktualizacja VLC" to "VLC update",
    "Biblioteki VLC" to "VLC libraries",
    "Aplikacja — więcej" to "Application — more",
    "Logi i zrzuty" to "Logs and dumps",
    "statystyki, log na dekoder, miejsce zapisu, diagnostyka pilota" to
        "statistics, log upload, storage location, remote diagnostics",
    "▲ ▼ wybór   •   OK zatwierdź   •   Wstecz poziom wyżej   •   Menu zamknij" to
        "▲ ▼ select   •   OK confirm   •   Back level up   •   Menu close",
    "Wstecz dwa razy — wyjście z testu" to "Back twice — leave the test",
    "Menu" to "Menu",
    "Wróć" to "Back",
    "Lista kanałów" to "Channel list",
    "Nagrania" to "Recordings",
    "Przełączenia wg harmonogramu" to "Scheduled switches",
    "Harmonogramy dekodera" to "Receiver timers",
    "Test pilota" to "Remote test",
    "Obraz — skalowanie" to "Picture — scaling",
    "Okno / pełny ekran" to "Window / full screen",
    "Wyłącz E2Stream" to "Exit E2Stream",
    "Język aplikacji" to "Application language",
    "Automatycznie (język systemu)" to "Automatic (system language)",
    "Polski" to "Polish",
    "Angielski" to "English",
)
