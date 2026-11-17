# AmpTV 0.1 — Classic Winamp-inspired Android TV starter

Native Android TV Kotlin / Jetpack Compose prototype for Onn 4K Plus.

## Implemented
- Leanback launcher entry point
- Landscape-oriented retro player and playlist editor
- D-pad-focusable demo playlist items
- Disabled transport buttons (no misleading simulated Spotify playback)
- Spotify client ID stored as public build configuration (not a secret)

## Not implemented yet
- OAuth PKCE sign-in / token handling
- Spotify playlist retrieval
- Spotify Connect device discovery and playback
- Background playback / MediaSession
- Real audio-reactive visualizer

## Build
Open this directory in Android Studio (JDK 17, Android SDK 35). Let Android Studio sync Gradle, then Build > Build APK(s). This archive does not include the Gradle wrapper or a compiled APK; Gradle and Android SDK are unavailable in the generation environment.

## Install on Onn Google TV
Enable developer options and wireless/USB debugging. From your development machine:

    adb connect <TV_IP>:<ADB_PORT>
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Do not use a guessed ADB port; read it from the TV's debugging screen. Launch AmpTV from the Apps list.

## Spotify integration plan
1. Confirm the available Spotify Web API endpoints for the registered development app.
2. Implement PKCE using a TV-appropriate authorization flow, preferably companion-device/browser sign-in with a secure callback.
3. Read the user's playlists and device list; handle empty device lists.
4. Test playback commands with the official Spotify Google TV app active.
5. Add MediaSession and remote transport support once playback works.

Never embed a Spotify client secret in an APK. Spotify Connect control is not guaranteed by Premium alone. Do not claim the current prototype plays music.