# Yoin Privacy Policy Draft

Last updated: 2026-05-15

Yoin is a local-first Android music client. Yoin does not operate its own music catalog, analytics service, advertising network, or cloud account system. You connect Yoin to services that you choose, such as your own Subsonic/OpenSubsonic or Navidrome server, Spotify, Gemini, or NeoDB.

This draft is provided so the Play Console privacy policy can be reviewed and published to a public web URL before closed testing.

## Information You Provide

Yoin may store the following information on your device:

- Music server profile details, such as server URL and username.
- Provider credentials or tokens for services you connect.
- Spotify Client ID and OAuth information when you configure Spotify.
- Gemini API key when you configure AI-powered features.
- NeoDB configuration and OAuth information when you configure NeoDB.
- Local ratings, album reviews, notes, playback history, cached lyrics, translated lyrics, and generated song or album information.

## How Information Is Used

Yoin uses this information to:

- Connect to your selected music server or provider.
- Browse your library, stream music, and control playback.
- Save local ratings, notes, album reviews, and music memories.
- Search Spotify or your current library when you request it.
- Generate or cache song information, lyric translations, and memory copy when you configure a Gemini API key.
- Sync album reviews to NeoDB when you configure NeoDB and choose to sync.

## Data Shared With Other Services

Yoin does not send data to a Yoin-operated backend. Data can be transmitted to services you configure or use:

- Your Subsonic/OpenSubsonic or Navidrome server receives authentication, library, search, playback, artwork, and lyrics requests.
- Spotify receives OAuth, catalog, search, saved-item, and playback-control requests when Spotify support is configured.
- Gemini receives prompts for About, Ask Gemini, lyrics translation, and Memory copy only when you save a Gemini API key and use those features.
- NeoDB receives album metadata, ratings, and review content only when you configure NeoDB and choose to sync album reviews.

Yoin does not sell personal data and does not include ads or analytics SDKs in the current app.

## Audio Permission

Yoin may request Android's audio recording permission for the playback visualizer. This permission is used to analyze the active playback session locally for visual effects. Yoin does not use this permission to record microphone audio or upload audio recordings.

## Storage And Backup

Yoin stores app data in private Android app storage. Credentials and tokens are intended to be excluded from Android backup. The main local database may be included in Android cloud backup or device-to-device transfer, so ratings, reviews, notes, playback history, cached lyrics, translations, and generated metadata may restore on a new device.

## Security

Use HTTPS server URLs where possible. Yoin currently supports user-provided HTTP music servers for self-hosted setups, but HTTPS is recommended for protecting data in transit.

## Data Deletion

You can remove local Yoin data by deleting profiles, clearing app storage, or uninstalling the app. Data already stored on third-party services, such as your music server, Spotify, Gemini, or NeoDB, must be managed through those services.

## Contact

Before publication, replace this section with the developer contact email shown on the Google Play store listing.
