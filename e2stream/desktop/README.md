# E2Stream for Windows

Windows desktop client for Enigma2 receivers. Current features include decoder
connection, bouquet/channel browsing with current EPG from OpenWebif, live
playback through an existing 64-bit VLC 3.x installation, pause/resume, and
configurable keyboard/remote key capture. The dark player shows channel and
programme information below the video for five seconds; the "Pasek" button
shows it again. The channel list opens in a separate window above the player
and supports mouse, arrow keys, Enter and Escape. EPG refreshes every minute
while a bouquet is selected; an EPG error does not prevent playback.

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
To map a remote button, select an action and press the button while the app is
focused. The receiver must expose the button to Windows as a keyboard key.
