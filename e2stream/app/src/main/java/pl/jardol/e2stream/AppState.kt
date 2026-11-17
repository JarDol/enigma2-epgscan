package pl.jardol.e2stream

/** Stan procesu odczytywany przez [UpdateInfoProvider] (APK Updater) i automatyczną aktualizację. */
object AppState {
    /** Trwa nagrywanie lokalne albo zapis bufora — instalacja aktualizacji czeka. */
    @Volatile var recording = false

    /** Okno aplikacji jest otwarte (między onStart a onStop). */
    @Volatile var foreground = false
}
