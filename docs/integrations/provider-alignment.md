# Provider alignment research — 2026-10-01

Yoin keeps search, playback, favorites, library membership, playlists and local
ratings as distinct concepts. An Apple Music library addition is not an Apple
favorite. A Subsonic star and a Spotify liked song remain their existing heart
actions. Local ratings and notes remain profile scoped for every provider.

## Official API findings

| Area | Official contract | Yoin consequence |
| --- | --- | --- |
| Apple Music search | Apple exposes both catalog search and personal-library search. | Offer explicit library/catalog scopes and retain catalog-versus-library IDs. |
| Apple Music add to library | `POST /v1/me/library` accepts catalog IDs and requires a music user token. `202 Accepted` may precede the resource appearing in the library; ignored IDs do not prove membership. | An add request must distinguish acceptance from confirmed membership and refresh the library. Do not model this as a reversible favorite toggle. |
| Spotify catalog search | Current maximum `limit` is 10 per result type. | The former default 12 was invalid. Use 10 and clamp caller values to the documented range. |
| Spotify remove playlist | The February 2026 migration replaces `DELETE /playlists/{id}/followers` with `DELETE /me/library` and a playlist URI. | Remove/unfollow playlists through the shared library endpoint. |
| Subsonic playlist permissions | `getPlaylists` returns playlists the user may play, while updates are owner-only. OpenSubsonic adds an optional `readonly` flag. | Public foreign-owned playlists and explicitly read-only playlists cannot expose editing actions. Pass the authenticated username into mapping. |

Sources read on 2026-10-01:

- [Apple Music search](https://developer.apple.com/documentation/applemusicapi/search)
- [Add a resource to a library](https://developer.apple.com/documentation/applemusicapi/add-a-resource-to-a-library)
- [Spotify Search for Item](https://developer.spotify.com/documentation/web-api/reference/search)
- [Spotify February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)
- [Spotify Remove Items from Library](https://developer.spotify.com/documentation/web-api/reference/remove-library-items)
- [OpenSubsonic getPlaylists](https://opensubsonic.netlify.app/docs/endpoints/getplaylists/)
- [OpenSubsonic updatePlaylist](https://opensubsonic.netlify.app/docs/endpoints/updateplaylist/)
- [OpenSubsonic playlist response](https://opensubsonic.netlify.app/docs/responses/playlist/)

## Bounded correctness repairs

Spotify search now uses a supported page size. Playlist removal now sends
`spotify:playlist:{id}` to `DELETE /v1/me/library`. These preserve the existing
search and playlist UI while repairing the API calls behind them.

Subsonic maps the optional `readonly` response field and checks a named owner
against the configured username. Explicit `readonly: true` always blocks editing.
Legacy responses without an owner or read-only field remain editable, matching
the optional-field fallback; a named foreign owner remains read-only even if the
server sends `readonly: false`.

The Now Playing layouts expose Apple Music's separate library action beside
the local rating. `TrackLibraryButton` shows an add icon, a confirmation check,
or a read-back action while membership is pending. Repeated taps are blocked
only while a request is active or membership is confirmed. The state is scoped
to the current profile and song, and changing profiles triggers a fresh read.
Catalog/library search and search-result library actions share the same provider
membership contract.

## Validation boundary

The API findings establish service support and request contracts. Unit regression
coverage checks Spotify request routing/limits and Subsonic permission mapping.
The [2026-10-01 validation record](provider-alignment-validation-20261001.md)
separately records the 427 JVM tests, three native tests, actual Apple Music
additions/read-back, Spotify live search and Pixel Tablet responsive checks.
Subsonic live-server behavior, new authorization/reconnect and long-duration
native decoder stability remain unverified.
