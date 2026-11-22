# E2Stream for Windows

Windows desktop client for Enigma2 receivers. Current features include decoder
connection, bouquet/channel browsing with current EPG from OpenWebif, live
playback through VLC 3.x — bundled with the installer ("VLC wewnętrzny") or an
installed 64-bit VLC ("VLC zewnętrzny", Settings → Odtwarzanie → Biblioteki VLC), pause/resume, and a
universal remote-key mapping panel. Each logical E2Stream button can have
multiple keyboard or HID aliases. At startup, the interface selects a
light or dark palette based on the system app theme, with contrasting text.
Restart the app after changing the system theme. The player shows channel and
programme information below the video for five seconds; the "Pasek" button
shows it again. Settings and the channel list open in separate windows above
the player, so playback continues when settings are opened and closed. The
channel list lets you change bouquets with its selector or left/right buttons
(left/right arrow keys also work), and supports mouse, up/down arrow keys,
Enter and Escape. EPG refreshes every minute while a bouquet is selected; an
EPG error does not prevent playback.

**Odtwarzanie i obraz → Okno programu** (or F11 and the player button) selects
a regular window, a maximized window, or borderless full screen that covers the
Windows taskbar. Full screen restyles the existing window instead of using
exclusive full screen, so switching apps does not minimize the player.
The player opens first, including before a decoder is configured. Press M or
the mapped Menu remote key to open the menu and choose **Ustawienia**. Right-click
the player to show or hide the mouse controls; their **Menu** button opens the
same menu. The **Obraz** menu entry (and player button) opens a picker above the
video to select fit, fill with cropping, or stretch to fill. The selection is saved.

**Nagrania** appears as an extra bouquet (it can be hidden under
**Bukiety**). It lists the decoder's recording folders and files from OpenWebif
with service, date, length and description; playback resumes from the saved
position. During recording playback: ◀/▶ −10/+30 s, ▲/▼ ±5 min, OK pause,
Info shows the progress bar, Back/Stop returns to the list.

**Odtwarzanie i obraz → Timeshift** buffers the live stream on disk (default
`%LOCALAPPDATA%/E2Stream/timeshift`, 15–360 minutes, limited by free space) and
plays it through a local server. Pause, REW/FF, the configurable ◀▶/▲▼ steps,
Stop (back to live) and an optional Back-to-live action work as on Android; the
timeshift bar shows the buffered range and delay.
The beginning of a buffer that has not yet been trimmed is available with a
shorter safety margin. If the programme's PMT declares no PCR PID, buffering
accepts a PCR on a programme stream or starts without one after a short wait.
Automatic update checks are deferred while a local recording is active.

**Ogólne** also mirrors the Android options for menu entries (show/hide and
order; Ustawienia and Wyłącz stay last), timeouts of the timeshift/recording
bar, action and confirmation windows, channel EPG and Multi EPG, information
messages (or hide them), cleanup of
completed scheduled switches after 7/14/28 days, and the application language
(automatic, Polish, English — English covers section names, menu and hints, as
on Android). The menu has **Przełączenia wg harmonogramu** (local schedule with
upcoming and completed entries; 30 s before the start a countdown offers to
switch now or record on the receiver, Back skips) and **Harmonogramy
dekodera** (receiver timers with deletion). New scheduled switches are added
from the programme description in the EPG guide.

**EPG guide:** INFO shows the current programme description, the menu and
navigation actions open channel EPG, Multi EPG (GUIDE; ◀ ▶ move the time by
30 minutes) and EPG search (SEARCH). In these windows ◀ ▶ jump between days or
times, Menu picks another channel/bouquet or search, CH+/CH− page through the
list, and OK opens the programme description with switch, scheduled switch and
record-on-receiver actions (a scheduled switch at the same time asks whether to
replace it). In the channel list INFO/GUIDE opens the selected channel's EPG.
EPG is cached per channel in `%APPDATA%/E2Stream/epg`: imported from the EPG
Scan pack (daily or at every start) or, without the plugin, fetched from
OpenWebif at the configured hour; other channels are loaded on demand.
The channel History list shows the current programme from locally cached EPG
alongside the watched-channel and bouquet labels, without waiting for the receiver.

**Audio:** the audio track (AUDIO key, menu or player button) is remembered per
channel and receiver in `%APPDATA%/E2Stream/audio.properties` (matched by PID,
then language and codec). The choices use the Android format (`audio_<ref>` =
`id|language|codec`) and are synchronised through the EPG Scan plugin
(`device-win-<computer>.json`; channels missing locally come from the common
part), so a track picked on a TV is used on the PC and vice versa. Without a
saved choice the track in the preferred language is played. Application volume (0–150%) and mute are separate
from the Windows volume; volume up/down/mute keys of a keyboard or remote
control the application while E2Stream is in the foreground (the key is not
passed to Windows), and the system volume when it is in the background. This
can be switched off in **Odtwarzanie i obraz → Dźwięk**.

Settings follow the Android layout: a list of sections (Dekoder Enigma2,
Odtwarzanie i obraz, Wygląd, EPG i ikony kanałów, Ogólne, Pilot i klawiatura,
Informacje i aktualizacje, Logi i zrzuty), each opening a list
of options. The settings window has no title bar and is fully operable with a
remote or keyboard (arrows, OK, Back, digits in numeric fields) as well as the
mouse. Decoder settings include automatic receiver discovery on the local
network and an EPG Scan plugin check. Playback settings cover VLC network
buffering, deinterlacing, hardware decoding and preferred audio language.
Optional PIN protection for settings, start channel, resume of the last channel,
up/down inversion, banner and channel-list timeouts, list/banner font sizes and
hidden bouquets are available as on Android.

**Wygląd** sets list, banner and EPG font sizes and channel-icon sizes.
**EPG i ikony kanałów** stores the EPG update mode, daily refresh hour and
number of days, refreshes the current EPG, downloads the EPG on demand, clears
the stored EPG, and downloads channel picons into `%APPDATA%/E2Stream/picons`. Picons are
taken from the EPG Scan pack (`picons.zip`), then from the decoder over FTP
(configured directory or the one from `/etc/enigma2/settings`), then from
OpenWebif. They are refreshed automatically once a day or at every start
(**Aktualizacja z EPG Scan**) and can be redownloaded manually. Missing picons can
show the "E2" placeholder. Changed EPG Scan picon packs replace existing icons
after their advertised SHA-256 has been verified. **Dekoder Enigma2** also holds the FTP user,
password and target directory (default `/media/hdd/e2stream`).

**Logi i zrzuty** shows VLC playback statistics (resolution, codec, bitrate,
frames/s, lost frames, corrupted packets, discontinuities) in the bottom panel,
sends `e2stream-<COMPUTER>.log` to the decoder (EPG Scan plugin upload with FTP
fallback) on demand or every 2 minutes when it changed, and lets you choose
and open the log directory (default `%APPDATA%/E2Stream/logs`, rotated at
2 MB). TS dumps from the Android ExoPlayer pipeline do not apply under VLC.

A **Wróć** button closing the current window is shown only while the mouse is
being used: moving the mouse or clicking shows it, pressing a key or remote
button hides it. **Ogólne → Przycisk Wróć w oknach** can make it permanently visible.
**Pilot i klawiatura** contains the remote/keyboard button mapping, separate
OK/arrow action mappings for playback with and without the channel banner
(like Android), and **Test pilota**, which lists every received command with
its source, physical key and mapped action. Press Back twice to leave the test.

This is an early test version, not yet functionally equivalent to Android.

## Build and run

Requires JDK 17 or 21 and Gradle:

```shell
gradle -p e2stream/desktop test
gradle -p e2stream/desktop run
```

Installers built by CI bundle VLC 3.x win64 (downloaded from videolan.org into
`vlc-bundle/windows/vlc`, not stored in the repository). Local builds without
that directory use an installed 64-bit VLC 3.x. The player pauses/resumes from its controls
or from the Play/Pause key. On Windows, create installers
with the repository's Gradle wrapper:

```shell
e2stream\gradlew.bat -p e2stream\desktop packageReleaseMsi packageReleaseExe
```

Test releases are built by `.github/workflows/e2stream-windows.yml` on every push to
`windows/0.1.0-test` that touches `e2stream/desktop/` (or the workflow). To publish a new
one, raise `packageVersion` in `build.gradle.kts` and add a `## X.Y.Z` section to
[`CHANGELOG.md`](CHANGELOG.md); it becomes the release notes of `e2stream-windows-X.Y.Z-test`.
The build stops right away if that release already exists or the section is missing.
Put `[stable]` in the commit message (or run the workflow manually with *stable*) to publish a
stable release `e2stream-windows-X.Y.Z` instead of a test one. Pushes to other `windows/*` branches
only build and test (the installer is kept as a workflow artifact). Add `[skip ci]` to commits that
should not produce a release.

The app tries the EPG Scan client API first and falls back to OpenWebif. Decoder
settings and key bindings are stored in `%APPDATA%/E2Stream/settings.properties`.
The OpenWebif password and EPG Scan token are stored in this file as plain text.
To map a button, open **Ustawienia → Pilot i klawiatura → Klawisze pilota**,
select a logical button, choose **Dodaj klawisz**, then press a keyboard
key or a button on a connected HID remote. HID reports are handled only while
an E2Stream window is focused; they are not translated into system-wide
keystrokes. Test with the specific remote because HID report formats vary by
device. Power can be mapped like any other button. While E2Stream is running,
the assigned Power key is monitored globally: it activates the app if another
window is active, or asks to close E2Stream if it is already active. Mapping a
keyboard key as Power makes that key global, even in other applications. No
separate background service is installed, so Power cannot start E2Stream after
the app has closed. In the confirmation window, remote OK closes the app and
Back cancels; Enter and Escape work on a keyboard. A second launch also signals
the existing instance.

IR remotes that are not HID/keyboard devices (serial or USB IR receivers) are
supported through WinLIRC or a LIRC server: enable **Pilot i klawiatura →
Odbieraj klawisze z WinLIRC / LIRC** and set the host and port (default
`127.0.0.1:8765`, the WinLIRC default). WinLIRC must be running. Received
buttons appear in **Test pilota** and can be mapped like other keys. A button
mapped to Power works even when E2Stream is in the background; other buttons
are handled only while an E2Stream window is active. The client reconnects
every 5 seconds if WinLIRC is unavailable.
Newly connected HID devices are registered as needed without repeatedly
re-registering usages already monitored by the receiver.

The remote page displays the last nonzero HID report and receiver errors to
help diagnose unsupported devices. While mapping a key or after a HID binding
is present, Windows browser Home and Back keystrokes are suppressed only while
an E2Stream window has focus; other applications retain their normal behavior.
This suppression applies to those browser keys from any input device while
E2Stream is active, not just the remote.

For local remote diagnostics on Windows, close any running E2Stream instance,
then run the following from the repository root in PowerShell:

```powershell
$env:E2STREAM_HID_TRACE = '1'
.\e2stream\gradlew.bat -p e2stream\desktop run 2>&1 | Tee-Object -FilePath "$env:TEMP\E2Stream-HID.log"
Remove-Item Env:E2STREAM_HID_TRACE
```

The opt-in trace records mapped remote actions, HID report contents and queue
delay while the application is focused. It may include input device identifiers;
review the log before sharing it. Remove the environment variable after stopping
the application to disable tracing in that PowerShell session.
