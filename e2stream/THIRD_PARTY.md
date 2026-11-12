# E2Stream – składniki zewnętrzne i licencje

| Składnik | Do czego | Licencja | Źródła |
|---|---|---|---|
| AndroidX Media3 (ExoPlayer) 1.5.1 | odtwarzacz (silnik „ExoPlayer”) | Apache-2.0 | https://github.com/androidx/media |
| libVLC (org.videolan.android:libvlc-all) 3.6.0 | odtwarzacz (silnik „VLC”), w tym wbudowane FFmpeg | LGPL-2.1-or-later | https://code.videolan.org/videolan/vlc-android, https://code.videolan.org/videolan/vlc |
| vlcj 4.8.4 oraz vlcj-natives 4.8.3 | osadzenie odtwarzacza VLC w aplikacji Windows | GPL-3.0 | https://github.com/caprica/vlcj |
| JLayer 1.0.1.4 (com.googlecode.soundlibs) | programowy dekoder dźwięku MPEG Layer I/II (MP2) | LGPL-2.1 | https://github.com/pdudits/soundlibs |
| AndroidX Core | biblioteka pomocnicza Androida | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |

## Zgodność

* E2Stream jest na licencji **GPL-2.0-or-later**. Apache-2.0 jest zgodna z GPL w wersji 3 (nie z samą wersją 2),
  dlatego aplikacja zbudowana razem z Media3 jest rozpowszechniana jako całość na warunkach **GPL-3.0**
  – pozwala na to zapis „or later”.
* Biblioteki LGPL (libVLC, JLayer) są używane jako osobne biblioteki bez zmian; ich licencja dopuszcza łączenie
  z programem na GPL. Wymagania LGPL – dostęp do źródeł biblioteki i możliwość podmiany jej na inną wersję –
  spełnia otwarty kod aplikacji (można ją zbudować z inną wersją biblioteki) i powyższe odnośniki do źródeł.
* Wersja Windows używa vlcj na GPL-3.0, dlatego również jest rozpowszechniana jako całość na warunkach GPL-3.0.
  VLC nie jest dołączany do instalatora; użytkownik instaluje go osobno.
* Pełne teksty licencji: GPL-2.0 – `LICENSE` w repozytorium; Apache-2.0 – https://www.apache.org/licenses/LICENSE-2.0;
  LGPL-2.1 – https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html; GPL-3.0 – https://www.gnu.org/licenses/gpl-3.0.html.
* Licencje oprogramowania nie dotyczą patentów na formaty (MPEG-2, H.264, AC-3 itd.) – to osobna sprawa,
  istotna przy sprzedaży urządzeń, nie przy prywatnym użytku.
