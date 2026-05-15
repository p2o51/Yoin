# Yoin - Android Music Client

Yoin is an animation-first Android Compose music client. The shipped backend is
Subsonic/OpenSubsonic; Spotify is being added through profile-based source
switching, read-only catalog access, and Android App Remote playback.

The current product focus is a local-first music memory system, expressive album
surfaces, Spotify search/playback hardening, lyrics tooling, and preparing the
0.5 closed test track.

## Build & Run

Use Android Studio's bundled JBR for command-line builds on this machine:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew assembleDebug    # build debug APK
./gradlew :app:bundleRelease # build signed release AAB for Play Console
./gradlew test             # run unit tests
./gradlew ktlintCheck      # lint check
./gradlew ktlintFormat     # auto-fix lint
```

See [docs/design.md](docs/design.md) for the full design specification.
See [docs/release-0.5-closed-test.md](docs/release-0.5-closed-test.md) for the
current Play Console closed-test checklist and upload-key setup.

## Local Tooling

The Spotify track-match playground is wired as a Gradle subproject for local
research only. It can sample exported Spotify liked-song rows, query Spotify for
ISRCs, then check MusicBrainz candidates:

```bash
./gradlew :playground:track-match:test
./gradlew :playground:track-match:run --args="--input .context/attachments/liked_songs.csv"
```

Debug token export is available only in debug builds through the debug source
set. It exists to support local playground research and should not be treated as
release behavior.
