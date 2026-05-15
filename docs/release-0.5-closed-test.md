# Yoin 0.5 Closed Test Prep

Last updated: 2026-05-15

## Release Target

- Track: Google Play closed testing
- Package name: `com.gpo.yoin`
- Version: `0.5.0` / `versionCode 5`
- Primary audience: personal Subsonic/OpenSubsonic and Navidrome users who also want Spotify profile/search playback experiments
- Current product focus: profile-local Album Memory, Now Playing lyrics/About/notes, Spotify search/playback hardening, NeoDB album review sync
- Release artifact: `app/build/outputs/bundle/release/app-release.aab`

## Local Release Commands

Use Android Studio's bundled JBR on this machine:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

git diff --check
./gradlew :app:ktlintCheck
./gradlew :app:testDebugUnitTest
./gradlew :app:bundleRelease
```

## Play Console Checklist

- Create the app in Play Console with package `com.gpo.yoin`.
- Turn on Play App Signing and configure the upload key before uploading the first `.aab`.
- Complete the main store listing: app name, short description, full description, screenshots, app icon, feature graphic, contact email, website/privacy URL.
- Complete App content:
  - Privacy policy URL
  - Data safety
  - Ads: no ads
  - App access: provide demo account details or clear test instructions
  - Content rating questionnaire
  - Target audience and content
  - News apps / government apps / financial features: not applicable unless Play asks for more detail
- Create a closed testing track, add tester emails or a Google Group, upload the AAB, and share the opt-in link.
- If this is a personal developer account created after 2023-11-13, keep at least 12 testers opted in continuously for 14 days before applying for production access.

## Tester Setup Notes

Yoin does not ship a hosted music service. Testers need at least one of:

- A Subsonic/OpenSubsonic or Navidrome server URL, username, and password.
- A Spotify Premium account plus a configured Spotify Client ID for App Remote playback.
- Optional: a Gemini API key for About, Ask Gemini, lyrics translation, and Memory copy.
- Optional: a NeoDB account for album review sync.

For Google review, prepare a demo Subsonic/Navidrome profile if possible. If no public demo server is available, put exact setup steps in the App access field and explain that the app is a client for user-provided music servers.

## Smoke Test Script

- Fresh install launches to the shell without crash.
- Create or switch a Subsonic profile, then load Home and Library.
- Play a track, background the app, and verify media notification controls.
- Open Now Playing, switch through Lyrics / About / Note, then collapse with gesture and system back.
- Lyrics: search, apply a candidate, translate, verify no `[1]` / `[2]` line markers leak into translated text, and recenter to the active line.
- About: first open triggers Gemini only when configured; repeated open should read cached rows.
- Memory: pull down from Home, verify six-card deck behavior, reason chips, score semantics, and gesture return upward.
- Album detail: save album rating and review, reopen, then verify Memory entry and NeoDB gating.
- Library: long-press Library on a Spotify profile and confirm Spotify Global search mode; normal tap stays in Library.
- Settings: profile switch, Spotify Client ID entry, Spotify OAuth bootstrap, NeoDB configuration, Gemini key save.
- Permission surfaces: notification prompt on Android 13+, playback visualizer behavior with and without audio permission.

## Data Safety Draft

Yoin is local-first and does not operate its own cloud backend. Data can still leave the device when users connect third-party or self-hosted services.

Data likely collected or transmitted:

- Account/authentication data: Subsonic credentials, Spotify OAuth tokens, Spotify Client ID, NeoDB OAuth token, Gemini API key. Credentials are stored locally in app-private or no-backup storage; they are transmitted only to the corresponding user-configured service.
- App activity and preferences: active profile, playback history, ratings, album reviews, notes, Memory cache, lyrics cache, Gemini About/Ask rows. These are stored in the local Room database. Some database rows can be included in Android cloud/device backup because `yoin-database` is included by `data_extraction_rules.xml`.
- Audio data: the app requests `RECORD_AUDIO` for Android's playback visualizer path. The intent is to analyze the active playback session for local visual effects, not to record microphone audio.
- Network content: metadata, artwork, lyrics, streams, search requests, and playback commands are exchanged with the user's selected music provider or integrations.

Third-party/user-configured destinations:

- Subsonic/OpenSubsonic/Navidrome server selected by the user.
- Spotify Web API and Spotify Android App Remote when a Spotify profile is configured.
- Gemini API when the user saves a Gemini API key and triggers AI-powered features.
- NeoDB instance when the user configures NeoDB and syncs album reviews.

Security notes:

- App credentials and tokens should not be included in Android backup.
- The main local database is included in backup for continuity, so local ratings, notes, reviews, history, and generated/cached AI or lyrics data may restore across devices.
- `usesCleartextTraffic` is currently enabled to support self-hosted HTTP music servers. Prefer HTTPS server URLs for closed test instructions.
- There are no ads and no analytics SDKs in the current app.

## Store Listing Draft

Short description:

> A fluid Android music client for Subsonic, Navidrome, Spotify experiments, and local music memories.

Full description:

> Yoin is an Android music client built around smooth motion, immersive Now Playing surfaces, and a local-first memory layer for albums you spend time with. Connect your own Subsonic/OpenSubsonic or Navidrome server, browse your library, play music with Media3, save ratings and notes, translate lyrics with your own Gemini API key, and build private album memories from your listening history.
>
> Yoin does not provide a hosted music catalog. You bring your own music server or connected provider account. Spotify support is experimental and requires the Spotify app, a Premium account for App Remote playback, and a configured Client ID.

0.5 release notes:

> 0.5 focuses on the first closed-test-ready Yoin loop: profile-local Album Memories, album rating/review polish, Spotify search/playback hardening, cached lyrics translation, Gemini About/Ask rows, and NeoDB album review sync.

## Upload Blockers

- Confirm Play upload signing key setup.
- Publish the privacy policy draft to a public, non-PDF URL and add that URL inside the app or Settings if required for review.
- Prepare screenshots from a real device.
- Prepare a tester email list or Google Group.
- Prepare Play review access instructions and, ideally, a demo Subsonic/Navidrome account.
- Decide whether global cleartext traffic should remain for 0.5 or be narrowed after the first closed test.
- Re-check `RECORD_AUDIO` disclosure and runtime behavior on a fresh install.
