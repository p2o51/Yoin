# NeoDB on HarmonyOS

The Android integration (`app/.../data/integration/neodb/`, `NeoDbVoices*`,
Settings' NeoDB item, the rate sheet's and the Memories diary's NeoDB line)
ported to the Flutter app.

## What it does

- **Sync**: an album's score and its one text go to the user's NeoDB Mark and
  Review when the rate sheet closes or a Memories review is saved (signed in
  only). Up to 360 characters the text is the Mark's short comment and the
  remote Review is deleted; longer, it is the Review and the short comment is
  cleared. The Mark is read first and its tags / visibility / shelf kept.
- **Dirty flags**: `AlbumRatingRow.ratingNeedsSync` / `reviewNeedsSync` /
  `neoDbReviewUuid` (Android `album_ratings`). A local write sets them; a push
  clears only what is still what it sent. Cloud sync carries them untouched.
- **Lines**: the rate sheet ends in "NeoDB · Sign in to sync / Syncing… / Synced /
  Couldn't sync + Retry" and "What others say"; the diary foot shows the state
  (signed in) and "What others say".
- **What others say** (`lib/neodb/neodb_voices_screen.dart`): a PushPage over the
  root navigator (Android: its own Activity). Works signed out (neodb.social,
  anonymous); a revoked token falls back to anonymous reads.
- **Settings › Sync and storage › NeoDB**: instance, Sign in (again), Sign out,
  and the pasted-token escape hatch.

## How it is built

| Piece | Where |
| --- | --- |
| REST client, models | `lib/data/neodb/neodb_api.dart`, `neodb_models.dart` |
| Sync (push, pull) | `lib/data/neodb/neodb_sync_service.dart` |
| Voices parsing and reads | `lib/data/neodb/neodb_voices.dart`, `neodb_voices_service.dart` |
| Instance, token, album ↔ item map | `lib/data/neodb/neodb_config.dart` |
| Browser sign-in | `lib/data/neodb/neodb_oauth.dart` |
| Providers, push state, line state | `lib/neodb/neodb_providers.dart` |
| UI | `lib/neodb/` (lines, Voices page, Settings page, strings) |

### Sign-in

NeoDB registers OAuth clients per instance (`POST /api/v1/apps`) and matches the
redirect URI in full. Android uses `yoin://auth/neodb/callback`; HarmonyOS uses
cloud sync's road instead (RFC 8252): Dart listens on `http://127.0.0.1:<port>/neodb/callback`,
registers a client for exactly that address, opens `/oauth/authorize` in the
system browser through `CloudSyncHost.openBrowser`, holds the app awake
(`holdWhileAway`), and exchanges the code. Coming back to Yoin without finishing
cancels after 3 s. No new ArkTS, no `module.json5` change, and Windows works
through `DesktopCloudSyncHost`.

Storage: the instance is `neodb.instance` in the key-value store; the token is
`neodb.access_token` in the `SecretStore` (Asset Store); the map is
`neodb.external_mappings.v1`. One NeoDB account for the app, as on Android.

## Differences from Android

- Loopback redirect instead of the `yoin://` scheme (above).
- The restore guard runs after a Phone Clone or cloud restore (`docs/backup.md`); a restore
  that put no values back leaves no marker (Android marks every restore).
- The token field has no show/hide eye (Harmony's `ExpressiveTextField` has no
  trailing slot; the AI key field is the same).
- `pullAlbum` is ported and tested but, as on Android, nothing calls it yet.
- The Memories "Synced to NeoDB" / "NeoDB ready" card chips aren't shown on
  Android either and are not ported.
- No Tip yet: Android's "Settings regrouped" tip has no HarmonyOS counterpart and
  new tip copy must come from the owner's copy model.
