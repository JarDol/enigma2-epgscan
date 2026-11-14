# E2Stream for Windows

Windows desktop client for Enigma2 receivers. Current features include decoder
connection, bouquet/channel browsing with current EPG from OpenWebif, live
playback through an existing 64-bit VLC 3.x installation, pause/resume, and a
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

The app starts in a maximized window rather than exclusive full screen, so
switching to another app does not minimize it. The title bar and Windows
taskbar remain available. Use F11 or the player button to toggle between
maximized and regular window sizes.
The player opens first, including before a decoder is configured. Press M or
the mapped Menu remote key to open the menu and choose **Ustawienia**. Right-click
the player to show or hide the mouse controls; their **Menu** button opens the
same menu. The **Obraz** menu selects fit, fill with cropping, or stretch to fill.
The selection is saved.

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
hidden bouquets are available as on Android. Features not yet present in the
Windows player (full EPG, recordings, logs) are marked as in preparation.

A **Wróć** button closing the current window is shown only while the mouse is
being used: moving the mouse or clicking shows it, pressing a key or remote
button hides it. **Ogólne → Przycisk Wróć w oknach** can make it permanently visible.
**Pilot i klawiatura** contains the remote/keyboard button mapping, separate
OK/arrow action mappings for playback with and without the channel banner
(like Android), and **Test pilota**, which lists every received command with
its source, physical key and mapped action. Press Back twice to leave the test.

This is an early test version, not yet functionally equivalent to Android:
full EPG, recordings, true timeshift and most other Android features are not
yet available. Only channel switching, channel list, and pause/resume actions
currently work during playback.

## Build and run

Requires JDK 17 or 21 and Gradle:

```shell
gradle -p e2stream/desktop test
gradle -p e2stream/desktop run
```

Install 64-bit VLC 3.x separately. The player pauses/resumes from its controls
or from a key assigned to the timeshift action. On Windows, create installers
with the repository's Gradle wrapper:

```shell
e2stream\gradlew.bat -p e2stream\desktop packageReleaseMsi packageReleaseExe
```

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
