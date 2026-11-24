# Open in Yoin & clipboard music links

Owner-approved 2026-11-24. Android ships first; iOS and HarmonyOS reuse the `:shared` parser (gap logged in [platforms.md](platforms.md)).

## What it does

- **Share target "Open in Yoin"** (`在 Yoin 中打开`): text shared from any app (Spotify's or Apple Music's share sheet, a chat, a browser). A music link in it opens in Yoin; any other text goes into Library search.
- **Clipboard prompt** (setting in Settings › Features and appearance › Music links, on by default): when Yoin comes to the foreground and the clipboard holds a Spotify or Apple Music link it hasn't offered before, a small card at the bottom of the shell offers to open it.
- Yoin does **not** register to VIEW `open.spotify.com` / `music.apple.com` links: tapping a link elsewhere never lands in Yoin unasked.

## Supported links (`:shared` `YoinMusicLinkParser`)

| Service | Recognised |
| :--- | :--- |
| Spotify | `open.spotify.com/{track,album,playlist,artist}/<22-char id>` (also `play.spotify.com`, `/intl-xx/`, `/embed/`, legacy `/user/<name>/playlist/<id>`, any query such as `si=`); `spotify:{track,album,playlist,artist}:<id>` and `spotify:user:<name>:playlist:<id>` URIs; `spotify.link`, `spoti.fi`, `spotify.app.link` short links (`YoinMusicLinkKind.ShortLink`, followed by the app) |
| Apple Music | `music.apple.com/<storefront>/{song,album,playlist,artist}/<slug>/<id>` (slug and storefront optional, raw-Unicode or percent-encoded slugs, `geo.` / `embed.` hosts); an album URL with `?i=<trackId>` is that song; legacy `itunes.apple.com/<storefront>/album/<slug>/id<id>[?i=<trackId>]` |

Not links Yoin opens: podcasts/episodes/shows, Apple stations and music videos, other hosts. In shared text the **first** supported link wins; links end at whitespace, quotes and CJK / full-width punctuation (`听这首：https://…，好听`).

Swift-facing API (flat names): `YoinMusicLink` (service, kind, id, storefront, url, `key`, `canonicalUrl`, `needsRedirectResolution`), `YoinMusicLinkService`, `YoinMusicLinkKind`, `YoinMusicLinkParser.findFirst/parse/containsLink`, `YoinSpotifyPageParser.parse` → `YoinSpotifyPageMeta`. None of them throw.

## Reading what a link names (no user login)

- **Apple Music**: public catalog API (`GET /v1/catalog/<storefront>/{songs,albums,artists,playlists}/<id>`) with Yoin's developer token (`YoinTokenService`); no Music User Token is sent. The link's storefront first, then `us`. Gives title, artist / curator, album, ISRC, duration, artwork.
- **Spotify**: with a Spotify account in use, its Web API (track, album, artist). Otherwise the public open.spotify.com page, read from its `<meta>` tags (`og:title`, `og:description`, `music:musician_description`, `music:duration`, `og:image`, falling back to `<title>`). The request carries no cookie, token or identifier: a generic browser user agent and `Accept-Language: en`. Timeout 6 s.
- **Short links**: redirects followed by hand (≤ 5 hops) until one names a supported link; a Branch landing page is scanned for its first open.spotify.com link.

Failures are silent: the card keeps its plain line ("Spotify link in clipboard").

## Where a link goes (owner 2026-11-24)

Routed by the user's **accounts**, not only the one in use:

- **Hit** — the user has an account on the link's service (Spotify link + Spotify account; Apple Music link + Apple Music account; with both, each link goes to its own service). Of several accounts on that service, the one in use, else the oldest. The card is rich: cover, title, artist, the service's name, and **Listen** (a song) or **Open** (album, artist, playlist). Tapping switches to that account first when another is in use (Home's account switcher path: `AccountSwitcherViewModel.switchTo`), then plays the song (`getTrack`, else by id alone) or opens the album / artist / playlist page.
- **Found** — no account on the link's service. The card is text only (`title · artist`, no cover) with **Search**. Tapping opens **Choose a match** (`OpenInResultsActivity`): the account in use is searched (ISRC first where the link's service named one, then `TrackMatcher.searchQuery`, then the bare title) and the results are ranked with the playlist-transfer `TrackMatcher` (albums / artists / playlists by name, then artist or owner). A found link never plays by itself, however close the match; when the best row is the same recording (ISRC or a confident `TrackMatcher` match) the page leads with it and leaves out the "couldn't confirm" line.

The share target follows the same routing without a card (the user already chose): a hit opens or plays at once, a found link opens Choose a match, other text opens Library search with that text (minus a short link that led nowhere).

## Clipboard rules (`ClipboardLinkGate`, `ClipboardLinkWatcher`)

1. Once per foreground (process `ON_START`), when the shell window gets focus (Android only lets the focused app read the clipboard), never under the landing.
2. First only `ClipboardManager.primaryClipDescription` — reading it shows no "pasted from your clipboard" notice. The content is read only if: the setting is on; the clip isn't marked sensitive (`EXTRA_IS_SENSITIVE`); it is text (`text/plain`, `text/html`, `text/uri-list`); its timestamp differs from the last clip looked at; and, on API 31+ when classification is complete, `getConfidenceScore(TextClassifier.TYPE_URL)` ≥ 0.5.
3. Only the item's own text or URI is read (never `coerceToText`). The first supported link is offered unless its key hash (SHA-256 of `service:kind:id`) equals the last one offered. Nothing is offered before the first account exists.
4. Kept on the device (`OpenInSettingsStore`, prefs `yoin_open_in`): the toggle, the last clip timestamp, the last link hash. Never the clip text; nothing is uploaded except the link's own id to its service for the metadata read above. Not part of cloud sync.

The card auto-dismisses after 12 s (longer under TalkBack via `calculateRecommendedTimeoutMillis`), has a close button, and hides while Now Playing covers the window. Motion: `YoinMotion` expressive slide + scale + fade in, standard out; text cross-fades when the title arrives. Colours: the Continue On card's inverse surface (`continueOnCardScheme`).

## Settings › Features and appearance › Music links (Carbon + Rebecca, owner-approved 2026-11-24)

The third row of the 「功能与外观 / Features and appearance」 group (Settings IA v4), after AI features and Appearance: icon `YoinSymbols.Link`, title 「音乐链接 / Music links」, summary 「剪贴板识别已开启 / Clipboard detection on」 or 「剪贴板识别已关闭 / Clipboard detection off」 (live from `OpenInSettingsStore`). Like every v4 row it opens its own page, `SettingsFeature.MusicLinks` in `SettingsFeatureActivity` (on windows ≥ 840dp the `settings-setup` split rule puts it in the right pane and the row shows as the open one), titled 「音乐链接 / Music links」:

1. **Detect music links in clipboard** (`ClipboardLinksRow`), the switch. The whole row toggles and is a switch to TalkBack (`Role.Switch` + on/off state, via `SettingsItem(checked = …)`).
2. Two read-only rows explaining how links reach Yoin: **Open in Yoin** (`YoinSymbols.Share`: pick it from another app's share menu) and **Open or search across services** (`YoinSymbols.Search`: links from a signed-in service open directly, others search in the service in use).

The toggle no longer sits directly in the Features group.

## Strings (copy: Rebecca, 2026-11-24)

`open_in_share_label`, `open_in_listen`, `open_in_open`, `open_in_link_in_clipboard`, `open_in_search`, `open_in_search_on` (TalkBack label of a found card's Search), `open_in_results_title`, `open_in_results_description`, `open_in_results_empty`, `settings_music_links_title`, `settings_music_links_summary_on` / `_off`, `settings_clipboard_links_title`, `settings_clipboard_links_summary`, `settings_music_links_share_title` / `_body`, `settings_music_links_cross_title` / `_body` in `res/values{,-zh-rCN}/strings_open_in.xml`. No Tip yet (owner: skip for now).

## Code

- `shared/.../openin/YoinMusicLink.kt`, `YoinSpotifyPageMeta.kt` (+ commonTest with saved Spotify page fixtures)
- `app/.../data/openin/`: `OpenInRouting` (hit / found, hit action), `OpenInMatcher` (ranking), `MusicLinkMetadata`, `MusicLinkResolver` (network), `OpenInCoordinator` (prompt + requests), `ClipboardLinkGate` / `ClipboardLinkWatcher`, `OpenInSettingsStore`
- `app/.../ui/openin/`: `OpenInCard`, `OpenInShareActivity` (target of the exported `.OpenInYoin` alias), `OpenInResultsActivity` / `Screen` / `ViewModel`
- Shell: `YoinNavHost` collects `OpenInCoordinator.requests` while resumed and draws the card above the snackbar host; `LibraryViewModel.openSearchShortcut(scope, query)`.
- Settings › Features and appearance › Music links: `MusicLinksItem` (row, `SettingsScreen.kt`), `MusicLinksPage.kt` (page + `ClipboardLinksRow`).

Back surfaces: Choose a match is a PushPage-style Activity with Pattern A native cross-Activity back (top-bar back button finishes); the share target has no window.
