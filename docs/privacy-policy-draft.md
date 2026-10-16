# Yoin Privacy Policy Release Notes

**Published:** [Yoin Privacy Policy](https://yoin.gojyuplus.com/privacy)

Developer: GojyuPlusOne. Privacy contact: [lastnatsu51@gmail.com](mailto:lastnatsu51@gmail.com).

Release review: 2026-10-13. The notes in this section are internal follow-up items, not part of the public policy below.

- Confirm actual logging, access and retention settings for the Yoin Apple Music token service and the Spotoolfy lyrics gateway. The token Worker's repository configuration enables sampled observability; client code and Worker code do not establish production log contents or retention. Do not promise zero logs or a deletion period without this evidence.
- Confirm the final APK/AAB matches the statements below, including microphone behavior and the Gemini API key backup caveat. The current key is stored in the Room database included in Android backup; Google Drive sync excludes that key. A change to credential storage requires corresponding policy review.
- Reconcile Google Play Data safety answers with these flows and the providers' applicable terms. These notes do not establish the platform's collection/sharing classifications or certify compliance.
- Verify that the published policy is accessible from the app and correctly linked in the Google Play listing. Website publication alone does not verify those entry points.

---

# Yoin Privacy Policy

Last updated: 2026-10-13

Yoin is an Android music client. It stores your music profiles, settings, listening history, ratings, reviews, notes and cached content on your device. It also connects to music providers and optional services to browse and play music, find lyrics, generate text and synchronize selected data. Yoin does not provide a hosted music catalog.

## Music Providers

When you connect a Subsonic/OpenSubsonic or Navidrome server, Spotify, or Apple Music, Yoin exchanges the authentication information and requests needed for that service. These can include searches, library and catalog identifiers, artwork requests, playback commands, and supported library or rating changes. The provider receives requests made while you use its features, including automatic content loading. Music streams and artwork may be delivered by the provider's content servers. Spotify playback uses the Spotify app; Apple Music playback uses MusicKit.

Apple Music also needs a developer token. Yoin offers a default token service hosted on Cloudflare Workers at `yoin-apple-music-token.p2o51willam.workers.dev`; you can supply another token service. The default service provides an app developer token. Yoin's token request does not include your Music User Token, listening history, notes or reviews. The service and its hosting provider receive the network request and may process connection and operational information such as IP address and request timing. Apple account authorization is handled separately with Apple. Hosting logs and retention are subject to the service's confirmed operating settings and the host's policies.

## Google Cast

Yoin initializes Google's Cast SDK as part of its playback infrastructure, before you choose to cast. The SDK sends information about Cast discovery and session events, the mobile device, and the app and SDK to Google for Cast usage and performance analysis. According to [Google's Cast SDK data disclosure](https://developers.google.com/cast/docs/android_sender/data_disclosure), this data is collected anonymously, is encrypted in transit, and is not used to model a specific user's behavior. Google states that neither app developers nor users can opt out of this SDK collection or delete its data.

## Lyrics

Yoin can send song titles, artist names and selected song identifiers to QQ Music, the Spotoolfy gateway for NetEase lyrics at `api.spotoolfy.gojyuplus.com`, Huawei Music and LRCLIB. Search terms you enter in the lyrics search panel are also sent to the queried services.

For non-Subsonic music, missing or expired cached lyrics can trigger automatic lookups through QQ Music, NetEase and LRCLIB, including when the song changes or the next song's lyrics are prefetched. Subsonic playback normally uses the connected server's lyrics unless you select another source. Manual lyrics search queries all four sources; Huawei is used only in manual search. Retrieved lyrics and available translations are cached locally.

## Gemini

Gemini features require an API key that you provide. Requests go directly to Google's Gemini API and include your key for authentication. The information sent depends on the feature:

- Opening About can generate missing song information using the song title, artist, album and language. Ask Gemini sends that metadata and your question, including a separate request to generate a question heading. About and question answers use Google Search grounding.
- Requesting lyric translation can send the lyrics text, song title, artist and target language when a suitable provider translation or cached result is unavailable. This includes lyrics you have entered yourself.
- Loading Memories can automatically generate narration from listening facts, such as play counts, date spans, seasons, track names, rating signals and note counts. Narration does not send the text of your notes or reviews.
- Loading Memories can also automatically generate an album title from the selected review or note, together with album metadata. **This sends that selected writing's text to Gemini**, which may be the full note or review. Other surfaces reuse the cached title without generating it.

These automatic requests can occur after you have configured a key; they do not require a separate generation tap each time. Cached results reduce repeat requests. Removing your Gemini key stops new Gemini generation requests from Yoin, but does not delete previous requests from Google's systems. Google's applicable terms and privacy policies govern its processing and retention.

## NeoDB

After you connect NeoDB, saving an album review in Yoin automatically sends album lookup information, your rating and review text to the selected instance. This includes closing the album's rating panel or saving a review in Memories; a separate sync tap is not required. Yoin can also read your own records back.

Long reviews are sent with public visibility (`visibility=0`). Short reviews keep the shelf record's existing visibility, or use public visibility for a new record. Yoin does not offer a visibility selector. Yoin does not request a separate Fediverse broadcast, but that does not make these records private. Synchronized reviews should not be treated as private backups or local-only notes. The selected NeoDB instance's access and retention policies also apply.

## Optional Google Drive Sync

Cloud sync requires you to turn it on and authorize Google Drive's app-data permission. Yoin reads and writes its own hidden app-data files rather than your general Drive documents. While enabled, synchronization can run automatically when the app enters the foreground or background and after local changes, as well as when you request it.

Synced data includes song notes and their text, song and album ratings, album reviews, Home layouts, selected settings, generated Memory and album titles, Memory narration, and About/Ask Gemini questions and answers. Cached lyric translations can include their source lyrics. Records include music identifiers, timestamps and synchronization metadata. Account descriptors include provider, display name, creation time, a Subsonic username/server-host hint when applicable, and an Apple storefront when known. Files also identify the writing device and app version.

Selected synced settings are translation language, Spotify Client ID and scroll-edge style. Drive sync excludes provider passwords and access/refresh tokens, the Gemini API key, NeoDB token, Apple token-service endpoint, audio files and raw playback history. Album notes currently stay outside Drive sync. Generated text may still reflect listening activity or writing. Hand-entered source lyrics are excluded from source-lyrics snapshots, although their translations can sync.

Files are compressed JSON sent over HTTPS. Yoin does not add end-to-end encryption to these Drive files. Turning sync off stops synchronization on that device and keeps the existing cloud copy. The app's Delete cloud data action deletes Yoin's synchronized content and tells other devices to stop syncing; it leaves a small reset marker containing device identification and timing to prevent old content from being uploaded again. Local data remains. Google's storage and retention policies also apply.

## Local Storage And Android Backup

Profile credentials and NeoDB tokens are encrypted in Android app-private storage excluded from Android backup. Other app data is stored in local databases and app storage.

Android cloud backup and device-to-device transfer are separate from optional Google Drive sync. Yoin's main database is eligible for Android backup, subject to device and system settings. This can include profile metadata, listening history, ratings, reviews, notes, cached lyrics and generated text. **The current Gemini API key is stored in this database and may be included in Android backup.** The Drive sync exclusions above do not change Android backup behavior.

## Audio And Network Security

The current app does not request microphone access at runtime, record microphone audio or upload microphone recordings. Playing music still involves the selected music provider and its playback services.

Yoin supports user-configured HTTP music servers. HTTP does not protect requests or content in transit as HTTPS does. Use HTTPS server addresses where available.

## Deletion And Contact

You can clear Yoin's local data through Android's app-storage controls or uninstall the app. This does not automatically delete Google Drive copies, Android backups, content synchronized to a music provider or NeoDB, or requests processed by Gemini and lyrics services. Use the relevant cloud-deletion action, Android/Google account controls or the service's own controls for those copies. Yoin cannot specify or enforce third-party server retention from the app.

Developer: GojyuPlusOne

Privacy contact: [lastnatsu51@gmail.com](mailto:lastnatsu51@gmail.com)
