# v1 port spec: Library root + Album / Artist / Playlist detail pages (Subsonic only)

> Generated 2026-11-13 by reading the Android app (read-only agents). Android refs are relative to app/src/main/java/com/gpo/yoin. The "Corrections" section at the end overrides anything above it.

## Overview

ORDER OF WHAT THE USER SEES AND DOES (phone, Compact). 1) Tap Library in the bar: page cross-fades Loading (48dp loading indicator) -> Content (or Error with Retry / Settings). Cold start has already read the artist list (it doubles as the "server alive" probe); everything else loads when the page comes on screen. 2) Header: a full-width search pill ("Search library") with a settings gear at its end. 3) Under it a horizontally scrolling chip row: Playlists, Artists, Albums, Songs, Favorites (Subsonic shows all five). No chip on = the All view (default): artists (round), albums and playlists (square) mixed in ONE 3-column grid, never songs; its first grid item is a full-width sort row ("Recents v" + sort mark). Tapping a chip turns it on (an X unfolds in front of the row; X or tapping the same chip returns to All) and cross-fades to that view: Artists grid (round), Albums grid, Playlists rows, Songs rows (paged), Favorites (sectioned rows: Artists / Albums / Songs). Views keep their own scroll position. A fast-scroller handle slides in while a long grid scrolls; hold it for a letter/date tick column and a bubble. Pull down on any view re-reads it. 4) Tap the search pill: it morphs into a full-screen search surface (field + type chips All/Artists/Albums/Songs + results, 300 ms debounce); back collapses it. 5) Tap an artist/album/playlist cell: a PushPage detail route slides in (96dp + fade on the expressive default spatial spring; the page prefetches at tap). Back button and system back both pop. 6) ALBUM page: tinted page wash from the cover; header (back button 40dp, album title headlineSmall in the cover-scheme primary, meta line artist / "Album" / year); hero = big cover (min(0.74 x width, 300dp), 8dp corner) above the track count line and a flowing headline of all track titles (each title is a link that plays from that track). Pull the hero up (0.55 x page height travel) and it reshapes into the track list while the cover flies up into a 48dp stamp at the header end; pull down at the top of the list to go back. Rows: track number (or equalizer when current), title, artist (only if different from album artist), duration, heart toggle. Tap a row = play the whole album queue from that index. There is NO Play button on the page: Play and Shuffle live in the bottom bar's detail pose (Play + dropdown split button and the now-playing pill). 7) ARTIST page: header (back, artist name headlineSmall in cover primary, active span "2016 - 2025", favorite star), ringed circular portrait hero whose open ring turns with scroll and dissolves, then the Discography timeline (year column, 56dp cover, title + version tag, meta line "Latest / kind / N songs", first 8 rows then Show all). Play = every release in discography order (3 album reads in flight), Shuffle = all tracks shuffled. 8) PLAYLIST page: same header/hero/pull-up shell as Album, hero cover with up to two album-cover "sleeves" fanned behind it, meta block (Length | Owner [+ description]), flowing track titles; list rows lead with the 44dp album thumbnail. Tap row = play playlist queue from there; Play/Shuffle again in the bar.

## v1 must

### 1. L1. Library root state machine, page chrome, cold start

UiState sealed: Loading | Error(message) | Content. Switching between the three classes cross-fades (fade in + fade out on the Standard effects spring; Harmony: YoinSprings.defaultEffects 1.0/1600); Content->Content data updates recompose in place with NO fade (contentKey = state class). Page background = vertical gradient [surfaceContainer, background, surfaceContainerLow] (ExpressivePageBackground with no accent on Library). Loading: centred 48dp contained loading indicator (container secondaryContainer, indicator onSecondaryContainer). Error: centred column, horizontal padding 24, message in bodyLarge colour error, spacing 8; under it a Row spacing 12 with two TextButtons: Retry (reloads everything) and Settings (opens settings). Harmony's existing no-account prompt (Connect button -> SubsonicSetupScreen) stays for the LibraryNoAccount case (Android has no such state: it is set up in Landing). Cold start: wait up to 4000 ms for the active source, then call getArtists (flatten the index groups into one list, keep server order) - this is the 'server alive' probe; failure -> Error with SourceNetworkException -> setupUnreachable text, SourceServerException/Auth -> setupServerError / auth text, else library_error_load_library. On success state = Content(selectedTab = All, artists/albums/songs/playlists/favorites all null = 'not loaded yet'). All's albums + playlists are NOT read at cold start: they load when Library first comes on screen (ensureSelectedTabLoaded) so app launch costs no extra requests. null payload = view shows its loading indicator; empty list = view shows its empty state (never flash 'No X found' while a fetch runs). A chip whose fetch fails steps the selection back to the previous view and shows a snackbar (strings below); only All-with-nothing shows the page Error. Entrance animations are DISABLED in Android (EntranceMotion.kt returns progress 1 / no-op) - do not build staggered entrances.

**Motion:** Container: fadeIn+fadeOut, Standard effects spring. Chip view switch: same fades plus SizeTransform(clip=false) on the standard default spatial spring (0.9/700). List/grid item moves (sort change, items joining late): animateItem with placement = standard default spatial spring (0.9/700), fadeIn/fadeOut = effects spring (1.0/1600).

**Strings (key | en | zh):**
- library_title | Library | 资料库
- library_error_load_library | Failed to load library | 无法加载资料库
- library_error_retry | Retry | 重试
- library_error_settings | Settings | 设置
- library_error_load_tab_artists | Failed to load Artists | 无法加载艺术家
- library_error_load_tab_albums | Failed to load Albums | 无法加载专辑
- library_error_load_tab_songs | Failed to load Songs | 无法加载歌曲
- library_error_load_tab_playlists | Failed to load Playlists | 无法加载播放列表
- library_error_load_tab_favorites | Failed to load Favorites | 无法加载收藏
- library_empty_all | Your library is empty | 资料库为空
- library_empty_artists | No artists found | 未找到艺术家
- library_empty_albums | No albums found | 未找到专辑
- library_empty_songs | No songs found | 未找到歌曲
- library_empty_playlists | No playlists | 无播放列表
- library_empty_favorites | No favorites yet | 暂无收藏

**Android:** ui/library/LibraryViewModel.kt (loadInitialData 283, selectTab 469, loadAll 576, ensureSelectedTabLoaded 850); ui/library/LibraryScreen.kt (LibraryContent 449, error branch 497-536); ui/library/LibraryUiState.kt; ui/component/ExpressiveComponents.kt (ExpressivePageBackground 80-120); ui/component/EntranceMotion.kt; ui/component/YoinLoadingIndicator.kt

### 2. L2. Library header (Compact portrait)

Column(fill, no extra horizontal padding): [AppBarWithSearch band][chip row][view box]. Band is transparent over the page gradient (no tonal/scroll elevation colour); status-bar inset on top. It holds an M3 SearchBar pill (56dp high, full-radius, container = surfaceContainerHighest - a step brighter than the gradient so it reads as a control), leading search icon (YoinSymbols.search), placeholder library_search_placeholder_library, and an actions slot at the end with a settings IconButton (48dp touch, YoinSymbols.settings, cd library_cd_settings). Harmony v1: gear opens the Settings route (today SubsonicSetupScreen); the account-avatar switcher that replaces the gear on Android is deferred. The chip row and the view box sit in a Column with 4dp spacing: items dissolve into the seam rather than being cut at it, so the gap stays tight. Column is NOT padded: the chips, grid covers, list rows and the pill each carry their own 16dp so they share one left edge.

**Motion:** Pill -> search surface morph: see L13.

**Strings (key | en | zh):**
- library_search_placeholder_library | Search library | 搜索资料库
- library_cd_settings | Settings | 设置

**Android:** ui/library/LibraryScreen.kt (LibraryContentBody 614-1110: header branch ~838-880, browse column ~883-905)

### 3. L3. Filter chip row (the view switch)

Chips in order Playlists, Artists, Albums, Songs, Favorites (availableTabs: Playlists needs Capability.playlistsRead, Favorites needs Capability.favorites and is Subsonic-only, Songs shown for Subsonic) - All has no chip. Chip = FilledTonalButton look: min height 44, RoundedCornerShape(18) (RSE ok), content padding h16 v10, label labelLarge 1 line ellipsis; selected: container secondaryContainer / onSecondaryContainer, unselected: surfaceContainerHigh / onSurfaceVariant; both colours animate on the effects spring; press = scale 0.97 on the standard default spatial spring (elasticPress); semantics Role.Tab + selected. Row: horizontal scroll, gap 8, content padding start = 16 (8 when a chip is on) and end 16, with a scroll-aware edge fade 24dp wide on each edge ONLY while more content lies beyond that edge (alpha lerp animated on defaultEffects; eased 6-stop alpha ramp 0, .104, .352, .648, .896, 1). Tapping an off chip selects it; tapping the on chip, or the X, selects All. With a chip on, an X (FilledTonalIconButton 44x44, icon YoinSymbols.close 20dp, container surfaceContainerHigh, content onSurfaceVariant, start padding 16, cd library_cd_clear_filter) unfolds in FRONT of the row (expandHorizontally from Start + fade, Standard role) and the chips' start padding springs 16 -> 8 (spatial spring), so every chip slides over by the X's width. The chip just turned on is scrolled into view at least 24dp (ChipRevealMargin) clear of both row ends, following the row while the X unfolds, and once more when the unfold lands; after that the row belongs to the user. Chips are a tab group for accessibility. Library is a RootSection: system back never touches the chips (it leaves the app / goes to Home per shell rules). By You sub-chip: deferred (see defer).

**Motion:** Colours: effects spring (1.0/1600). X expand/shrink: Standard role expandHorizontally/shrinkHorizontally + fade = standard default spatial (0.9/700) for size, effects for alpha. Chip start padding: spatial spring. Press: standard default spatial. Edge fade: defaultEffects. Chip reveal scroll runs alongside the unfold (asks for the chip's FINAL bounds).

**Strings (key | en | zh):**
- library_tab_playlists | Playlists | 播放列表
- library_tab_artists | Artists | 艺术家
- library_tab_albums | Albums | 专辑
- library_tab_songs | Songs | 歌曲
- library_tab_favorites | Favorites | 收藏
- library_cd_clear_filter | Clear filter | 清除筛选

**Android:** ui/library/LibraryFilterRow.kt (all); ui/component/ExpressiveComponents.kt (ExpressiveSegmentRow 419, ExpressiveSegmentChip 441); ui/component/PressFeedback.kt (elasticPress 55, horizontalEdgeFadeOnScroll 222); ui/library/LibraryScreen.kt (libraryTabLabels 1316); ui/library/LibraryViewModel.kt (visibleTabs 451)

### 4. L4. View container: per-view scroll state, cross-fade, empty/loading

The pull-to-refresh box (L12) holds an AnimatedContent keyed on selectedTab, unclipped (the fast-scroller handle reaches past the box). Hoist ONE scroll controller per view (All grid, Artists grid, Albums grid, Songs list, Playlists list, Favorites list) ABOVE the switcher: the outgoing view is disposed, and returning to a view must land where the user left it. Each view: payload null -> centred loading indicator (48dp); empty -> EmptyState = centred card (horizontal margin 16) of ExpressiveSectionPanel: Card shape (16 continuous), colour surfaceContainerHighest alpha 0.72, tonal elevation 1, text bodyLarge onSurfaceVariant, padding h20 v18; the empty state is vertically scrollable (no overscroll) so the pull-to-refresh still works over it. Content bottom padding = 108 + navigation-bar inset (LibraryTokens.bottomGroupPadding) so rows clear the floating bar; landscape handset (height < 480) uses 16 + inset.

**Motion:** fade in/out Standard effects + size spatial spring (above).

**Android:** ui/library/LibraryScreen.kt (view switch 906-1010, TabLoadingState 3008, EmptyState 3024, floatingBottomGroupContentPadding 171)

### 5. L5. Sort row, sort orders, persistence

A view has a sort row only if it offers 2+ orders. Offered orders for Subsonic (features.albumsHaveLibraryDates = playlistsHaveLibraryDates = true, i.e. album/playlist 'created'): All = Recents, Recently added, Alphabetical, Creator; Albums = same four; Artists = Recents, Alphabetical; Playlists = Recents, Recently added, Alphabetical, Creator; Songs and Favorites have no sort row. Default = first offered (Recents). Choice persists per profile per view in KeyValueStore: key '$profileId/${viewName}' (All, Artists, Albums, Playlists) -> enum name; an unknown/unoffered stored value reads as 'none chosen'. Row = first item of the grid (full span) or list (h16 padding), scrolls away with content: a tappable Row with the order's label (labelLarge, onSurface) + YoinSymbols.sort 18dp (onSurfaceVariant), 6dp gap, vertical padding 10, shape 12 clip, press area bleeds 8dp past the label each side while the label stays on the page edge (offset x -8, padding h8), min touch target 48. Tap opens a dropdown (YoinDropdownMenu) listing the offered orders, the active one with a trailing YoinSymbols.check; choosing a different one calls haptics tick, persists, re-sorts and every cell glides to its new place on the spatial spring. cd/onClickLabel library_sort_action (role dropdown). Menus must be YoinDropdownMenu, never a raw DropdownMenu.

**Motion:** Item placement on sort change: standard default spatial spring via animateItem. Menu: YoinDropdownMenu (owned by the shared component task).

**Strings (key | en | zh):**
- library_sort_action | Sort | 排序
- library_sort_recents | Recents | 最近
- library_sort_recently_added | Recently added | 最近添加
- library_sort_alphabetical | Alphabetical | 字母顺序
- library_sort_creator | Creator | 创建者

**Android:** ui/library/LibrarySortRow.kt; ui/library/LibrarySort.kt (librarySortOptions 66-95); ui/library/LibrarySortStore.kt; ui/library/LibraryViewModel.kt (selectSort 1114, sortSettings 1635); ui/library/LibraryScreen.kt (LibrarySortRowSpec 1778, librarySortRow 1790/1799)

### 6. L6. Sorting algorithm (client side, pure, off the UI isolate)

All lists are sorted on the client from the whole loaded collections (run in compute()/isolate; re-run only when the view's inputs change). distinctBy key first (a duplicate would crash grid keys). Entry = {position (provider order, last tie-break), sortName = name with ONE leading article stripped (trim, case-insensitive, only when followed by whitespace and a non-empty rest), creator, addedAtMs, lastSeenMs}. Articles for Subsonic: [The, El, La, Los, Las, Le, Les, Os, As, O, A]. Comparators: alphabetical = collator(sortName) then position; recentlyAdded = addedAtMs DESC (null last) then alphabetical; recents = lastSeenMs DESC (null last) then recentlyAdded; creator = collator(creator) nulls last then alphabetical. Creator: album -> album.artist (blank=null), playlist -> owner, artist -> its own name. Dates: album.addedAt (Subsonic 'created'); artists have NO date: in All and under Recently added an artist takes the newest addedAt of its albums (match by album.artistId, else by trimmed lower-case artist name). Date parse: ISO instant, offset date-time, local date-time (as UTC) or bare yyyy-MM-dd (UTC); unparsable = null. All = artists + albums + playlists in one sort, never songs. Fast-scroller sections are produced in the SAME pass: Alphabetical/Creator -> first-letter buckets (digits+symbols under '#', last; creator-less items trail under '#'); Recently added -> timeline (bubble label = localized 'Mar 2024', tick = year, or short month when the dates span under 2 years; the year replaces the month wherever a new year starts; NO sections if >20% undated or one segment holds >60% of dated items); Recents -> today / this week / this month / then months or years / 'Not opened' last (labels via keys, localized where drawn; no sections if no record at all). Collation + bucketing: Android uses ICU AlphabeticIndex (Latin, kana rows, Hangul initials; Han transliterated to pinyin on API 29+). Harmony needs an equivalent (see risks).

**Motion:** none (data only)

**Strings (key | en | zh):**
- library_recents_today | Today | 今天
- library_recents_this_week | This week | 本周
- library_recents_this_month | This month | 本月
- library_recents_not_opened | Not opened | 未打开过

**Android:** ui/library/LibrarySort.kt (LibrarySorter 117-215, stripLeadingArticle 262, parseLibraryDate 277); ui/library/LibraryIndex.kt (alphabetical, timeline, recents); ui/library/LibraryScrollIndex.kt (LibraryScrollIndexer); ui/library/LibraryViewModel.kt (observeSortedLists 1436, sortLists 1457, allOf 1548)

### 7. L7. Recents records (what Recents sorts by)

Every open of an artist/album/playlist FROM Library (not from search results unless scope = current library, which on Subsonic is always) writes lastSeenMs = now under key 'profileId/provider/Kind/rawId' (Kind = Artist|Album|Playlist) in a KeyValueStore file kept on device only (not synced): newest 300 per profile+provider (trim the oldest on write; one read-trim-write at a time). Android also merges Yoin's own visit/play rows (recordAlbumVisit, plays): Harmony v1 has no activity DB, so additionally write the same record when a play starts from an Album/Artist/Playlist page. Recents is re-read ONLY when Library comes into view (150 ms debounce), never while the user is looking at a list - a list on screen never re-sorts under the user. With no record at all Recents falls back to Recently added (then alphabetical). On a new local day the 'today/this week' cut moves even with no new record.

**Motion:** none

**Android:** ui/library/LibraryOpenStore.kt; data/repository/LibraryRecents.kt; ui/library/LibraryViewModel.kt (recordOpened 1097, onLibraryShown 1580, observeRecents 1599); ui/library/LibraryInView.kt

### 8. L8. Grid views: All, Artists, Albums

Compact = 3 fixed columns (also for <389dp windows), horizontal gap 12, vertical gap 16, content padding start16 top8 end16 bottom(108+nav inset). Cell = cover (aspect 1) + 5dp + text, no card, no border (covers never have outlines), press scale 0.97, no ripple, tap opens the detail. ALBUM/PLAYLIST cell: cover shape Cover (continuous 4dp corner), surfaceContainerHighest fill with a 24dp onSurfaceVariant fallback icon (album / playlist) while loading or on failure; title titleSmall onSurface 1 line (Android marquees on overflow - v1: ellipsis, see defer), album version drawn as a tag after the title (rounded 6dp, secondaryContainer / onSecondaryContainer, labelSmall, padding h6 v1; the ' (version)' suffix the server appended is stripped from the name); subtitle bodySmall onSurfaceVariant 1 line ellipsis = album.artist, or for a playlist its owner (non-blank) else the word 'Playlist' (library_playlist_fallback). ARTIST cell: circular cover, fallback icon YoinSymbols.artist, name titleSmall centred 1 line ellipsis, then (if albumCount != null) '%d albums' bodySmall at 12sp onSurfaceVariant centred. Covers: Subsonic coverArt id -> source.resolveCoverUrl(ref, size ~400 for grid; decode cacheWidth = cell px). Album with no coverArt on Subsonic falls back to the album's own raw id as cover id; playlist cover = playlist.coverArt else first track's coverArt else first track's albumId. Keys: artist 'artist:<id>', album 'album:<id>', playlist 'playlist:<id>' (All mixes the three). The sort row is the first full-span item. Items entering late (albums batches, playlists arriving after artists in All) animate in on the item springs; All shows whatever has loaded (artists from cold start are there at once) and shows the loading indicator only while nothing has; the empty state only when every list has settled empty. Medium/Wide change only the column count (see adaptive).

**Motion:** Placement spatial spring (standard default 0.9/700), fade effects spring; press 0.97 standard default spatial.

**Strings (key | en | zh):**
- library_artist_grid_album_count | one: %1$d album / other: %1$d albums | other: %1$d 张专辑
- library_playlist_fallback | Playlist | 播放列表

**Android:** ui/library/LibraryScreen.kt (libraryGridCells 208, ArtistsTabContent 1341, ArtistGridItem 1425, AlbumsTabContent 1531, AlbumGridItem 1618, PlaylistGridItem 1646, AllTabContent 1672, playlistBackdropArtUrl 2915, libraryCoverArtUrl 2929); ui/component/AlbumCard.kt; ui/component/AlbumVersionTitle.kt; ui/component/ExpressiveComponents.kt (ExpressiveMediaArtwork 184); ui/theme/Shape.kt (Cover = 4dp)

### 9. L9. List views: Playlists, Songs, Favorites (+ shared row spec)

All lists: edge-to-edge rows (no list padding; each row carries h16), top padding 8, bottom 108+inset. ROW (SongListItem): shape ListRow 12, transparent, ripple, padding h16 v10, spacing 12: [48dp thumb, Thumb shape 4dp, fallback musicNote][Column: title row + meta line][duration]. Title titleSmall onSurface, 1 line ellipsis, colour animates to primary on the effects spring when it is the current track; when current a 16dp equalizer symbol (primary; bars move while playing, sink to dots when paused) unfolds before the title (expandHorizontally from Start + fade, Standard role, 6dp end padding). Meta line (bodySmall, groups separated by a 12dp GAP, never a dot): artist, then album at 60% alpha ('muted'); when it does not fit, trailing groups are dropped whole. Duration labelLarge, tabular figures, onSurfaceVariant, 6dp before it, format m:ss. Long-press opens the track sheet (deferred). PLAYLISTS tab: rows with 48dp Thumb-shape cover, title titleSmall, PlaylistMeta = groups [owner plain (if non-blank), count stat '<n> <unit>', duration stat(s) 'h'/'min' (hours>0 -> hours stat; minutes shown if minutes>0 or hours==0)]; stat = value labelMedium-weight Medium tabular + unit at 60% alpha with 4dp inner gap; empty meta -> 'Playlist'. FAB 'New playlist' is NOT in v1. SONGS tab (Subsonic = songs of the newest albums, see L11): no header (Random mix header + reshuffle is off for Subsonic), rows as above; tap = play the list read so far from that index (queue = whole loaded list, no window cut on Subsonic; pages read later do not join the queue). A foot item after the last row while more can follow: 12dp vertical padding centred loading indicator (or a refresh IconButton when the last read failed). Next page is requested once the list is within about one screen of its end (items below the last visible <= visible count), once per page, never after a failure. FAVORITES tab (getStarred): sectioned list; section header = labelLarge onSurfaceVariant with padding start16 end16 top18 bottom6 ('Artists', 'Albums', 'Songs'; a section only if non-empty); artist rows (48dp circle avatar, name titleSmall, trailing meta pill '%d albums' labelMedium on surfaceContainerHighest@0.9, full-radius, padding h10 v6), album rows (48dp Thumb cover, title titleSmall + version tag, artist bodySmall), song rows as above; tap song = play favorites.tracks from that index. Empty = library_empty_favorites. Rows key by id (favorites prefixed 'fav-artist-', 'fav-album-', 'fav-song-').

**Motion:** Same item springs. Current-row title colour: effects spring. Equalizer reveal: Standard expand/fade.

**Strings (key | en | zh):**
- library_favorites_section_artists | Artists | 艺术家
- library_favorites_section_albums | Albums | 专辑
- library_favorites_section_songs | Songs | 歌曲
- library_artist_row_album_count | one %1$d album / other %1$d albums | other %1$d 张专辑
- library_playlist_track_unit | one track / other tracks | 首
- library_duration_hour_unit | h | 小时
- library_duration_minute_unit | min | 分钟
- detail_album_cd_now_playing | Now playing | 正在播放

**Android:** ui/component/SongListItem.kt (48-200); ui/component/MetaLine.kt (56-144); ui/library/LibraryScreen.kt (SongsTabContent 1813, PlaylistsTabContent 1950, PlaylistListItem 2121, FavoritesTabContent 2172, AlbumListItem 2312, PlaylistMeta 2377, ArtistListItem 1478, SectionHeader 2987); ui/library/LibrarySongsFoot.kt (LoadMoreSongsEffect 41-92, foot 104-137); ui/component/ExpressiveComponents.kt (ExpressiveMetaPill ~505)

### 10. L10. Albums read (batched) and list publication

Library needs the WHOLE album collection to sort it client-side. Read getAlbumList(type=newest, size=500, offset) batch after batch (500 = the API ceiling per request). First batch publishes at once; a full batch (size >= 500 AND at least one album not seen before) schedules the next, in the background, one read at a time. Guards: stop when a batch brings no new id (server ignores offset) and at a 100000-album ceiling. A failed batch stops the read where it is; the next time Library comes on screen (ensureSelectedTabLoaded) reads on from that offset. Each batch joins the list as it lands where it falls; while All/Albums is on screen and its sort is NOT Recently added (newest-first is the batch order, so only then is a joining batch guaranteed to land below what is shown) new albums are HELD and join together when the read ends, Library comes into view, or the view/sort changes - so the grid never shifts under the thumb. (Libraries under 500 albums never see any of this.) Playlists: getPlaylists once, cached; re-read on pull-to-refresh. Artists: cached from cold start. Favorites: getStarred, cached. Switching to a view reads its payload only if it is still null.

**Motion:** Joining items glide on the item spatial spring.

**Android:** ui/library/LibraryViewModel.kt (loadLibraryAlbums 649, libraryAlbumsQuery 674, readRemainingAlbums 699, readAlbumBatches 761, mergeAlbums 801, holdsAlbumBatches 825, releaseHeldAlbums 834; SUBSONIC_ALBUMS_BATCH 2398, ALBUMS_READ_CEILING 2415)

### 11. L11. Songs tab = newest albums' songs (Subsonic pager)

Subsonic has no song list, so Songs = the songs of albums, newest album first (getAlbumList2 newest), each album's songs in the album page's own order. Pager: each next() reads one page of 10 albums (offset starts 5 albums BEFORE where the last page ended, size = 10 + overlap, so albums deleted meanwhile never make it skip one; the new albums are those after the last album it already holds), opens them (getAlbum) at most 3 at a time through the album cache, and returns their songs in album order; a song id is listed once however many albums hold it, an album opens once; a page with no new songs reads on, so an empty result means the end; getAlbum null drops the album. A failed read leaves the pager where it was (retry re-reads the same albums); an album that fails twice in a row is left out. State publishes songsMore = None | Available | Loading | Failed. First visit reads page 1 (cachedSongs), later pages come from the near-end trigger (L9). Pull-to-refresh on Songs starts a fresh pager and swaps the list in place.

**Motion:** foot: Standard fade between spinner and retry icon.

**Android:** ui/library/NewestAlbumSongsPager.kt (all); ui/library/LibraryViewModel.kt (selectTab Songs branch 500-520, loadMoreSongs 1021, rereadSongs 982, songsFromNewestAlbums 1079); data/source/ServiceFeatures.kt (songsFromNewestAlbums 30)

### 12. L12. Pull to refresh

Pull on any view (including empty/loading-free empty states) re-reads exactly what that view lists and replaces rows in place: no view returns to its loading indicator, none loses its place, views not shown keep their data. All -> getArtists + whole albums (batches from offset 0, new albums join, an album gone from the server leaves only when the whole read is in) + getPlaylists; Albums -> albums; Artists -> getArtists; Playlists -> getPlaylists; Songs -> fresh pager page 1; Favorites -> getStarred. One pull at a time (isRefreshing keeps the indicator down). A list that cannot be read keeps its rows and a snackbar names which. The views never move for the pull: only the indicator comes down from under the view box's top edge. Indicator = loading indicator, container secondaryContainer, colour onSecondaryContainer; the pulled distance settles on the standard default spatial spring (not Material's default spec). Haptic tick on trigger.

**Motion:** Indicator pull/settle: spatialSpring (standard 0.9/700); refreshing state shown until the re-read ends.

**Strings (key | en | zh):**
- library_error_load_tab_* (see L1; the snackbar uses the same text)

**Android:** ui/library/LibraryViewModel.kt (pullRefresh 881, rereadView 909); ui/component/YoinPullToRefresh.kt; ui/library/LibraryScreen.kt (YoinPullToRefreshBox use ~890)

### 13. L13. Fast scroller (Artists, Albums, All grids)

Handle at the end edge of the grid; only for content longer than 3 screens (lines > 3 x lines-per-screen); slides in (translationX 32dp -> 0 + alpha) while the list scrolls or is held, sleeps 1600 ms after the list stops. Visual = filled tab 24 x 48 dp (touch 32 x 64), start corners 50% radius, secondaryContainer with two small chevrons (stroke 2dp, half-width 4, height 3, offset 5) in onSecondaryContainer; held = primary / onPrimary (effects spring). Press anywhere on it and drag: follows the finger 1:1 from the first pixel (no slop), the list jumps (scroll-to-item of the first item of the target LINE, once per frame max); release folds everything. Track = from the grid's first row (top inset 8) to bottomInset (= the bar clearance) above the grid bottom. While held, if sections exist: a tick column unfolds beside the handle (panel gap 4, side padding 8, ticks >= 18dp apart, tick labels = section tickLabel, merged runs share one tick) and a bubble (min 56dp, h-padding 16, 8dp left of the ticks, vertically centred on the thumb, shows the section label at the line round(f x lastLine); scale 0.6->1 + alpha on expand; pulses ~+3% on each section change). Line math: handle fraction = position/(lines-1) with a 'knee' so the handle reaches the track's bottom exactly when the list does (handleFraction/positionFor in FastScrollMath); a tick sits at line(start)/lastLine. Leading full-span items (the sort row) are counted: section item i maps to lazy index i + leading, index 0 maps to the very top. A list that changes length during a drag keeps measuring in the length it was grabbed at. It excludes the system back gesture over the handle only while visible. Skipped by the jump: no scroll velocity is faked (so the edge dissolve does nothing). If no sections (sort without sections) show the handle alone.

**Motion:** appear alpha: defaultEffects; slide: expressive default spatial (0.8/380); expandAlpha: fast effects (1.0/3800); expandMotion: expressive default spatial; thumb/glyph colours: defaultEffects; bubble pulse: expressive fast spatial (0.6/800) with initial velocity kick 3; handover of a pinned handle: fast spatial spring.

**Android:** ui/component/YoinFastScroller.kt (geometry 128-330, composition 608-1000); ui/library/LibraryFastScroller.kt (LibraryGridFastScroller 69-95, libraryJumpAnchor 38); ui/library/LibraryViewModel.kt (sections in sortLists 1520)

### 14. L14. Search (Subsonic = library scope only)

Pill tap expands the pill into a full-screen surface (M3 SearchBar -> ExpandedFullScreenSearchBar): leading icon becomes a back arrow (YoinSymbols.back, cd library_cd_close_search) that collapses; trailing: loading indicator 18dp while searching, else a clear X (YoinSymbols.close, cd library_cd_clear_search) when the field is non-empty; field text drives viewModel.search(query) which is debounced 300 ms (distinct, latest wins). Blank query = no results, search state cleared. Collapsing clears the query and the type filter. Surface keeps status-bar/cutout/IME insets but NOT the bottom (results run under the gesture bar with 24dp + nav inset bottom padding). Under the field: one filter row of type chips (Subsonic: All, Artists, Albums, Songs - Playlists only if Capability.searchPlaylists, which Subsonic lacks; no scope chips because no catalog scope), full-bleed row that scrolls under the surface edges with the same chip look; resting first chip aligns to the results' 16dp edge. Search call: search3 with default counts (about 20 per type). State: searchQuery, searchResults (null = none yet), isSearching, searchError, searchFilter (resets to All whenever the search is cleared/reopened). While isSearching = centred loading indicator; searchError (not 'no results') = centred message in error colour, bodyLarge, + TextButton 'Retry' (library_search_retry) re-running the search; results empty = EmptyState library_empty_search. Filter switch fetches nothing (a type shows what the mixed search returned) and cross-fades (Standard) with each (query, filter) keeping its own scroll position until the query is cleared. Layouts: All = sectioned list in order Artists (artist ROWS), Albums (grid-like rows: chunked into N columns, N = 3 on Compact, spacing 12, padding h16, same album cell), Playlists, Songs (each type capped at 40 in All); Artists/Albums filters = grid like the Library grid (3 cols); Songs = list. Tap result: artist/album opens detail (record Recents), song = play ALL songs found from that index (no activity context). Section headers as in Favorites ('Artists', 'Albums', 'Songs' via library_search_section_*).

**Motion:** Pill <-> surface container morph on a spatial spring with ONE progress value; the system 'back' collapses through the same controller (commit only on HarmonyOS - no gesture progress). Filter crossfade: Standard effects.

**Strings (key | en | zh):**
- library_search_filter_all | All | 全部
- library_search_section_artists | Artists | 艺术家
- library_search_section_albums | Albums | 专辑
- library_search_section_songs | Songs | 歌曲
- library_search_section_playlists | Playlists | 播放列表
- library_empty_search | No results found | 未找到结果
- library_search_retry | Retry | 重试
- library_error_search | Search failed. | 搜索失败。
- library_error_search_snackbar | Search failed | 搜索失败
- library_cd_close_search | Close search | 关闭搜索
- library_cd_clear_search | Clear search | 清除搜索

**Android:** ui/library/LibraryScreen.kt (inputField 757-802, ExpandedFullScreenSearchBar 1015-1100, LibrarySearchFilterRow 1144, SearchResultsContent 2419-2565, SearchAllResults 2566, SearchResultsGrid 2805, SearchResultsList 2869); ui/library/LibrarySearchFilter.kt (all); ui/library/LibraryViewModel.kt (search 1237, clearSearch 1257, observeSearch 1343 [SEARCH_DEBOUNCE_MS 2386])

### 15. L15. What a tap does (Library -> play / detail)

Cell/row tap on artist/album/playlist: record the open (L7) then push the detail route (YoinPushRoute) with the MediaId; start prefetching that id's load at tap time (single-flight, keyed profile+id; the page joins the in-flight load or reads the in-memory cache). Library stays mounted underneath (maintainState) so returning restores scroll and the sort. Song row in Songs: play(tracks = loaded songs, startIndex = tapped index, activityContext = none) - the whole list on Subsonic. Song in Favorites: play(favorites.tracks, index, activity context = Liked songs with cover = first favorite's cover). Song in search: play(results.tracks, index, none). Each play = build EngineItem per track from source.playback.handleFor(track) (DirectStream uri + headers), title/artist/album, artworkUri = cover url, durationMs = durationSec*1000; engine.setQueue(items, startIndex, playWhenReady: true). A tap that picks a song is an 'explicit start' (an unplayable track is not skipped); Play/Shuffle buttons are not (Subsonic has no unplayable tracks, so ignore).

**Motion:** Detail route: YoinPushRoute (already built: 96dp shared-axis X + fade on the expressive default spatial spring, same controller for pop).

**Android:** ui/navigation/YoinNavHost.kt (onSongClick/onFavoriteSongClick/onSongsListClick/onSearchSongClick 1379-1405; AppContainer.playSongsList 1840; playSearchResults 1857; startQueueFor 1870); ui/library/LibraryScreen.kt (openArtist/openAlbum/openPlaylist 331-356); ui/detail/DetailPrefetch.kt

### 16. D1. Detail pages common shell (all three)

PushPage only: opened via YoinPushRoute, explicit back button + system back both -> Navigator.pop (one path). No raw PopScope. Header = Row (status-bar inset, padding start 8 end 4 [Artist/Playlist] / end 16 [Album] top 4 bottom 6): DetailBackButton = FilledTonalIconButton 40dp (touch target 48), container surfaceContainer, content onSurface, icon YoinSymbols.back 22dp, cd detail_cd_back, haptic click; then a 14dp spacer; then the title column. Title style headlineSmall in the PAGE ACCENT = cover colour scheme's primary (animated on the effects spring; falls back to the app theme until the cover resolves), meta line under it bodyMedium (see per page). Page background = ExpressivePageBackground with accentColor = cover accent: top stop = lerp(surfaceContainer, accent, 0.18) (effects spring), then background, then surfaceContainerLow. Cover colour scheme = SPEC_2025 Expressive scheme seeded from the cover (decode ~ small size, Score/Quantizer seed, dark/light follows system, animate to it; never snap). The header persists across Loading/Error/Content. States cross-fade (Standard, keyed by state class so Content->Content updates do not re-fade). Loading = centred 48dp loading indicator. Error = centred column padding 24, message bodyLarge error colour, spacing 10, TextButton Retry (cmp_detail_retry) re-runs the load. All three are loaded through source.library.getAlbum/getArtist/getPlaylist (null -> not-found error text; throw -> load error text). Bar: detail pages need the detail-pose bottom bar (D8).

**Motion:** Page push/pop = YoinPushRoute. Accent colours: effects spring (1.0/1600). State fade: Standard effects.

**Strings (key | en | zh):**
- detail_cd_back | Back | 返回
- cmp_detail_retry | Retry | 重试
- detail_album_error_not_found | Album not found | 未找到专辑
- detail_album_error_load | Couldn't load this album. | 无法加载此专辑。
- detail_artist_error_not_found | Artist not found | 未找到艺术家
- detail_artist_error_load | Couldn't load this artist. | 无法加载此艺术家。
- detail_playlist_error_not_found | Playlist not found | 未找到播放列表
- detail_playlist_error_load | Couldn't load this playlist. | 无法加载此播放列表。

**Android:** ui/detail/DetailBackButton.kt; ui/detail/AlbumDetailScreen.kt (AlbumTopHeader 998, AlbumDetailContent 380); ui/detail/ArtistDetailScreen.kt (ArtistDetailScreen 113, ArtistTopHeader 362); ui/detail/PlaylistDetailScreen.kt (PlaylistTopHeader 698); ui/component/ExpressiveComponents.kt (80-120); ui/theme/CoverColorScheme.kt (rememberCoverColorScheme 35); ui/detail/DetailPageColors.kt; ui/component/DetailErrorState.kt; ui/navigation/back/ (PushPage doctrine) .claude/skills/predictive-back/SKILL.md

### 17. D2. Album page - header, hero, flowing titles (Compact)

Header meta line (bodyMedium, groups 12dp apart): artist (plain, if non-blank), 'Album' as a Kind group in primary colour (detail_album_kind), year (plain, if non-null). Title = albumName with the ' (version)' suffix stripped, headlineSmall 1 line ellipsis, followed by an AlbumVersionTag when album.version != null (labelMedium, 8dp gap). Hero (resting state): cover square side = min(0.74 x pageWidth, 300dp), centred horizontally, its slot is side + 56dp tall (the 56dp band is where Android draws two brand blocks behind the cover - deferred - so keep the slot height even without them); cover corner 8dp (Hero shape, continuous) at rest, no border, no shadow, tonalElevation 3, decode at 640px. Under the slot (padding horizontal 16, top 8): [track-count line] then 6dp then the FLOWING TITLES. Track-count line = labelMedium stats: '<n> tracks' (n = songs.size when loaded else songCount) + total duration stats ('h' and 'min'; hours>0 shows hours then minutes only if >0; no hours shows minutes even if 0), only if songs non-empty. Flowing titles: ONE Text, headlineMedium (28/36), unbounded height, overflow visible (it deliberately runs down behind the bar and past the bottom safe area; no ellipsis), titles joined by a single plain space (never a bullet), tone alternating onSurface (even index) / onSurfaceVariant (odd); a title up to 16 'width units' (CJK glyph = 2, others 1) is glued into one unit (spaces -> NBSP, WORD JOINER U+2060 between wide glyphs and at wide/narrow boundaries) so it wraps as a whole; longer titles stay breakable; each title is its OWN tappable span: tap = play the album from that track (same as tapping the list row); a pressed title underlines; links only live while the hero is the dominant layer (expand < 0.5). A song whose artist differs from the album artist shows ' (feat. X)' (detail_album_feat, NBSP-led, 0.6em, onSurfaceVariant) after its title. Empty album (no songs) shows the album name in that Text. Hero Last Play / rating emblem / comment blocks are deferred (they need Room data Harmony does not have).

**Motion:** Hero cover entrance 'staged beat' (rise 20dp, scale 0.94 -> 1, once per album per page instance) is nice-to-have; meta rises 14dp. Hero layer handoff: see D3.

**Strings (key | en | zh):**
- detail_album_kind | Album | 专辑
- detail_unit_track | one: track / other: tracks | 首
- detail_unit_hour | h | 小时
- detail_unit_minute | min | 分钟
- detail_album_feat | (feat. %1$s) | (feat. %1$s)

**Android:** ui/detail/AlbumDetailScreen.kt (AlbumTopHeader 998-1129, AlbumOverviewPage 1199-1339, AlbumHeroDetails 1341-1413); ui/detail/AlbumDetailComponents.kt (AlbumTrackCountLabel 373, totalDurationGroups 409, flowingTitle 456, buildAlbumTrackTitles 478, DetailMetaLine 352); ui/detail/AlbumDetailUiState.kt; ui/detail/AlbumDetailViewModel.kt (loadAlbum 220, toAlbumSong 930); ui/component/MetaLine.kt

### 18. D3. Album + Playlist pull-up reshape (hero <-> track list)

One RevealState per page: fraction 1 = hero, 0 = list. A durable `expanded` bool is the source of truth; ONE effect (reconcile) animates fraction to match it; gestures only (a) drag live and (b) on release set `expanded` AND launch the settle to the chosen end - never a second animator. Drag: finger delta d (px, positive = down) -> fraction -= d/travel, travel = 0.55 x pageHeight (DetailPullUpTravelFraction); past 1 a rubber band caps overshoot (c = 0.30: 1 + c*(1 - 1/(1 + over/c))); below 0 hard clamp. Release decision: velocity (px/s, up negative) / travel <= -1.6 -> expanded; >= +1.6 -> hero; else fraction < 0.5 -> expanded, else hero. Hero drag is enabled only while !expanded; the track list uses a nested-scroll rule: when the list is at its top and the user pulls down (and fraction < 1), or pulls up while fraction > 0, the delta drives the fraction instead of scrolling (user input only); on fling release with fraction in (0,1) commit with the fling velocity. System back is NOT involved (the pulled-up list is not a back stop: one back leaves the page). Layers (expand = 1 - fraction): hero slot height = lerp(cover+56, 0, expand) so the list docks straight under the header; hero content alpha = 1 - smoothStep(0, .55, expand) and translateY = -28dp * smoothStep(0, .6, expand); list alpha = smoothStep(.3, .8, expand), translateY = +28dp * (1 - smoothStep(.25, 1, expand)); hero stops being interactive at expand >= .5, list interactive from >= .5; list is composed only while expand > 0.001, hero while expand < 0.999. Cover flight: the cover is drawn in an overlay ABOVE the header band but below touches: rect(progress) lerps (smoothStep 0..1 of progress = expand) from its resting rect to the 48dp stamp at the header's end (stamp.left = width - 16 - 48; stamp vertically level with the title row, lifted 6dp), corner 8dp -> 4dp, 'only moves and scales'. The header title cluster lifts 6dp and settles to 0.9 scale (top-start pivot) with smoothStep(0,1,progress); header text/back-button hand over to white over smoothStep(.25,.7,progress); title gives up (48 + 12)dp at its end as the stamp forms; the bar counts as docked past 0.5 (stamp becomes a button: tap = collapse back to the hero). v1 SIMPLIFICATION of the 'spectrum bar': while docked, fill the header band with the cover scheme's primary (alpha = textProgress) and draw header content in onPrimary; the cover-strip spectrum, the brand 'butterfly' blocks and their dot dissolve are deferred. Pulled-up list footer (liner notes) deferred.

**Motion:** Settle spring = predictiveBackSettleSpring: damping 0.75 (LowBouncy), stiffness 400 x 1.3 = 520, starting from the finger velocity. Handoff curves are smoothStep, read per frame in layout/paint only (never rebuild the page per frame). Reduced motion: no flight/lift - fade in place.

**Android:** ui/detail/DetailPullUpReshape.kt (all); ui/experience/RevealState.kt (dragBy 62, settle 78, applyRubberBand 244, rememberRevealState 254); ui/detail/AlbumSpectrumBar.kt (geometry 134-300, albumBarCoverRect 322, AlbumBarStampSize 131); ui/detail/AlbumDetailScreen.kt (reveal wiring 410-430, cover layer 706-760, header 800-880); ui/detail/PlaylistDetailScreen.kt (PlaylistPullUpOverview 1079-1200)

### 19. D4. Album track list rows

List top padding 4, bottom 112 + nav inset, page padding h16. First item: the track-count line (labelMedium, padding start 8 top 2 bottom 10). Multi-disc (more than one distinct discNumber): a heading item before each disc's first row ('Disc %d', labelLarge onSurfaceVariant, padding start 8, top 16 (0 for the very first) bottom 6). Row (AlbumTrackRow): clip ListRow 12, padding h8 v10, spacing 12: [index slot][title col][duration][heart]. Index slot: min width 18, end-aligned, labelMedium tabular onSurfaceVariant = trackNumber ?: (index+1); when this track is the current one it becomes the 16dp equalizer in the page accent. Title titleMedium (16/24 w500) 1 line ellipsis, colour = accent when current else onSurface; artist line bodySmall onSurfaceVariant ONLY when track.artist is non-blank and != album artist (else the row collapses to one 56dp line); spacing 2. Duration labelLarge tabular onSurfaceVariant. Heart (Subsonic supports favorites): 36dp touch (48 min target) IconButton wrapping a 28dp circle with a 1.5dp ring (outline @0.5 alpha when off, transparent when on), container = accent when on / transparent off, heart glyph 16dp (onPrimary on, onSurfaceVariant off), YoinSymbols.favorite / favoriteFilled; ring/container/ink animate on the effects spring; tap pops: scale to 1.25 (on) or 1.15 (off) in 90 ms (linear tween is allowed here) then settles on the standard default spatial spring; haptic confirm/tick. Toggle semantics: row shows the target at once (pending tap), writeActions.setFavorite(id, target); if the write fails the old heart returns. Keys: '<songId>#<index>' (a provider can list a track twice). Tap row = play whole album queue from this row; long-press (track sheet) deferred. cd detail_album_cd_add_favorite / detail_album_cd_remove_favorite.

**Motion:** Heart: colours effects spring; pop = 90 ms tween up then standard default spatial settle. Equalizer reveal as L9.

**Strings (key | en | zh):**
- detail_disc_heading | Disc %1$d | 第 %1$d 碟
- detail_album_cd_add_favorite | Add to favorites | 加入收藏
- detail_album_cd_remove_favorite | Remove from favorites | 取消收藏

**Android:** ui/detail/AlbumDetailScreen.kt (AlbumTrackList 1589-1682); ui/detail/AlbumDetailComponents.kt (AlbumTrackRow 527-656, AlbumCircleToggle 659-750, AlbumDiscHeading 398); ui/detail/AlbumDetailViewModel.kt (toggleStar 352, toAlbumSong 930)

### 20. D5. Album play semantics

All play entries build the queue from the album's loaded tracks in album order (the order getAlbum returned). playSong(songId): startIndex = index of that id (0 if missing), shuffle false, explicitStart true. Play (bar): startIndex 0, in order. Shuffle (bar menu / button): tracks = shuffled copy of ALL tracks, startIndex 0, shuffled flag true. All three set the play context to Album (id, name, artist name/id, cover id) so Now Playing's 'Playing from' can open this page again. No-op while the album is not loaded. Visit recording (Home activity) is deferred; Recents record is L7.

**Motion:** none

**Android:** ui/detail/AlbumDetailActivity.kt (playFrom 80-110, playSong 112-118); ui/detail/AlbumDetailViewModel.kt (getAlbumSongs 200)

### 21. D6. Artist page

Loading: getArtist(id) -> ArtistDetail (albums are summaries without tracks). releases = albums ordered NEWEST FIRST: if the first dated album's year < the last dated album's year the list came oldest-first (Navidrome) so reverse it, then stable-sort by year DESC, albums without a year last. Prefetch the first 6 releases' getAlbum in the background. heroUrl = artist.coverArt url else the first release's cover url (older Subsonic has no artist image). Page colours from heroUrl: ring = scheme.primary, ring dot = scheme.tertiary, accent/title = scheme.primary. Header Row: [back][14dp][name headlineSmall in accent, 1 line][active-span line bodyMedium: 'minYear - maxYear' (en dash with spaces) or a single year, none if no year][favorite star at the end]. Star (Subsonic supports favorites; label 'Favorite'/'Favorited' for non-Spotify): IconButton, YoinSymbols.star / starFilled, tint accent when on else onSurfaceVariant (effects spring), same pop as the heart (90 ms up to 1.25 on / 1.15 off, then standard default spatial); optimistic, writeActions.setFavorite(artistId, target), revert on failure (observe overrides). BODY: vertical scroll, bottom padding 120 + nav inset, centred children. Hero = open-ring portrait: portrait circle size = min(0.52 x width, 216dp), band height = portrait + 150dp, ring is drawn centred on the portrait, reach 0.84 x portrait (outer radius) : room = portrait*(reach-0.5), stroke = room*0.59, radius = portrait/2 + room*0.41 + stroke/2, round caps, OPEN at the upper right (opening centred at -45 degrees, 0.9 rad wide), a dot of radius stroke*0.45 sitting in the opening at that angle in ring-dot colour; ring rotates with scroll: degrees = scrollPx * 0.06 and breaks into fine dots as it leaves: leaving = smoothStep(0, 220dp, scrollPx) (v1 may drop the dots and just fade the ring by `leaving`); portrait: circle, fallback YoinSymbols.artist, no border, tonalElevation 3, decode 720. Spacer 32. Most Played section is deferred (local play history). DISCOGRAPHY (padding h16, page content width): section header = underlined section label 'Discography' (AlbumSectionLabel) with trailing '%d releases' labelMedium onSurfaceVariant (padding h8); if the releases carry >= 2 distinct kinds (releaseType Album / Single+EP / Compilation) show filter segment chips All / Albums / Singles & EPs / Compilations (hidden when fewer than two kinds, which is the usual Subsonic case); then 8dp spacer; empty -> 'No releases' bodyMedium. Rows (first 8 of the filtered list; the rest in an AnimatedVisibility expand-vertically on the spatial spring + fade, toggled by a TextButton 'Show all %d' / 'Show fewer' in accent, padding start 48): row = clip ListRow 12, padding end8 top8 bottom8, [year column 48dp wide with start padding 8, labelLarge tabular onSurfaceVariant, shown only where the year changes from the previous row ('-' when unknown)][56dp cover, Cover shape 4dp, fallback album, decode 168][12dp][title titleMedium 1 line + version tag; meta line bodySmall: 'Latest' (Kind, primary; only the newest release overall), kind (Album/EP/Single/Compilation as Kind groups, onSurfaceVariant), '<n> songs' stat]. Tap = push Album page. PLAY / SHUFFLE (bar): Subsonic queues the whole discography: read each release's tracks with getAlbum, at most 3 album reads in flight (sliding window: a finished read frees a slot for the next album immediately, albums start in order); Play = tracks in discography order (newest release first, each album in its own order); Shuffle = all tracks then shuffled; an album that cannot be read contributes nothing (never fails the whole play); the read runs in the playback layer's 'play when loaded' so a page rebuild does not drop the tap and a newer start cancels it; context = Artist (id, name, cover id).

**Motion:** Ring turn + dissolve read at paint time from the scroll offset (no per-frame rebuild). Show-all: Expressive role fade + expandVertically on the spatial spring. Star pop as the heart. State fade Standard.

**Strings (key | en | zh):**
- detail_artist_favorite | Favorite | 收藏
- detail_artist_favorited | Favorited | 已收藏
- detail_artist_discography | Discography | 作品列表
- detail_artist_releases | one %1$d release / other %1$d releases | other %1$d 张作品
- detail_artist_no_releases | No releases | 无发行作品
- detail_artist_show_all | Show all %1$d | 显示全部 %1$d
- detail_artist_show_fewer | Show fewer | 收起
- detail_artist_latest | Latest | 最新
- detail_artist_kind_album | Album | 专辑
- detail_artist_kind_ep | EP | EP
- detail_artist_kind_single | Single | 单曲
- detail_artist_kind_compilation | Compilation | 合辑
- detail_artist_filter_all | All | 全部
- detail_artist_filter_albums | Albums | 专辑
- detail_artist_filter_singles | Singles & EPs | 单曲与 EP
- detail_artist_filter_compilations | Compilations | 合辑
- detail_unit_song | one song / other songs | 首

**Android:** ui/detail/ArtistDetailScreen.kt (header 362-480, ArtistBody 490-640, ArtistRingHero 707-735, ArtistPortrait 736, ArtistDiscography 1011-1104, ArtistReleaseRow 1112-1183, ReleaseMetaLine 1185, artistActiveSpan 478); ui/detail/DetailBackdrops.kt (ArtistOpenRing 38-99); ui/detail/ArtistDetailViewModel.kt (loadArtist 69, toggleFollow 186, getPlayTracks 232, playArtist 270, newestReleasesFirst 293); ui/detail/ArtistPlayTracks.kt (loadArtistTracks / loadArtistPlayTracks / loadAlbumsInOrder, ARTIST_ALBUM_LOADS_IN_FLIGHT = 3); ui/detail/ArtistDetailUiState.kt

### 22. D7. Playlist page

getPlaylist(id) -> Playlist with tracks (entries keep a stable per-entry rowKey 'row-<n>' so the same song twice is two rows and a removed row fades out). Header = Album's shape but a Row: title playlistName headlineSmall (accent), meta line bodyMedium: owner (plain, if non-blank) + 'Playlist' as an accent Kind group (detail_playlist_kind); Rename/Delete/overflow menu is deferred (needs playlist writes). Hero cover = playlist.coverArt resolved, else the FIRST track's coverArt (or its albumId as a cover id); the page palette follows whichever wins; same cover side rule as Album (min(0.74w, 300)), 8dp corner, 56dp band. SLEEVES: up to 2 other covers fanned behind the hero cover: take the rows' own album cover urls (resolved at the rows' size 320 so the image cache is shared) in playlist order, distinct, not equal to the hero cover (and not the first song's when the hero only stands in with the first track's cover); sleeve side 0.86 x cover, centred on the cover, offset +-0.20 x cover side horizontally (start sleeve leans toward the start, end sleeve toward the end), 0.07 x side lower, tilted -+6.3 degrees, cover's corner radius, flat, drawn whole (never clipped; shrink the spread with playlistSleeveSpread when the box is narrow: spread = clamp(sideRoom/cover + 0.5 - halfExtent, 0, 0.20), halfExtent = 0.86/2*(cos 6.3 + sin 6.3)); knock-out ring of page surface max(3dp, 2.5% of side) around the cover and the front sleeve only where it overlaps a sleeve behind; sleeves leave on the pull-up by breaking into dots (leaving = smoothStep(0, 0.7, progress); v1: just fade them by that value); entrance: each sleeve opens from behind the cover (scale 0.6 -> 1, fade rate 1.4, 50 ms stagger, a beat after the cover). Hero meta block (width = cover side): Row space-between: [Length: caption 'Length' (AlbumSectionLabel), '<n> tracks' bodyLarge tabular onSurface, then total duration 'Xh Ym'/'Ym' bodyMedium tabular onSurfaceVariant or '-'] [Owner (only if non-blank): caption 'Owner', owner bodyLarge 1 line, optional 'Public'/'Private' only when isPublic != null - Subsonic mapper gives null in Harmony today]; then, if comment non-blank: caption 'Description' + comment bodyMedium max 3 lines; column spacing 14. Under it (28dp gap): flowing titles (same rules as Album, no feat credits, each title plays the playlist from that song). Empty: 'This playlist is empty.' (bodyMedium, onSurfaceVariant, centred in hero; also as the first list item with padding start 8 top 8). Same pull-up as D3. LIST: rows = clip ListRow 12, padding h8 v8, spacing 12: [44dp album thumbnail, Thumb shape 4dp, fallback musicNote, decode 120][title titleMedium 1 line + meta line bodySmall = artist, album (muted, 12dp gap)][duration labelLarge tabular]; first item = track-count line (count = songCount ?: songs.size); no heart on playlist rows; tap = play playlist queue from this index (shuffle false, explicitStart true); Play (bar) = index 0 in order; Shuffle = shuffled copy; context = Playlist (id, name, owner, cover = playlist own cover id else first track cover id).

**Motion:** Same as D3; sleeves entrance described above on the expressive springs.

**Strings (key | en | zh):**
- detail_playlist_kind | Playlist | 播放列表
- detail_playlist_length | Length | 时长
- detail_playlist_owner | Owner | 创建者
- detail_playlist_description | Description | 简介
- detail_playlist_public | Public | 公开
- detail_playlist_private | Private | 私密
- detail_playlist_empty_hero | This playlist is empty. | 此播放列表为空。
- detail_playlist_empty_list | This playlist is empty. | 此播放列表为空。
- detail_playlist_tracks | one %1$d track / other %1$d tracks | other %1$d 首曲目
- cmp_duration_hours_minutes | %1$dh %2$dm | %1$d 小时 %2$d 分钟
- cmp_duration_minutes | %1$dm | %1$d 分钟

**Android:** ui/detail/PlaylistDetailScreen.kt (header 698-790, PlaylistPullUpOverview 1079, PlaylistHeroDetails 1309, PlaylistTrackList 1360, PlaylistTrackRow 1491, PlaylistHeroMeta 1688, buildPlaylistTrackTitles 1759); ui/detail/PlaylistSleeves.kt (constants 28-60, playlistSleeveCovers 86, playlistSleeveSpread ~104); ui/detail/PlaylistDetailViewModel.kt (loadPlaylist 318, show 343, publish 356); ui/detail/PlaylistDetailActivity.kt (playFrom 82-105, onSongClick 244-249); ui/detail/PlaylistDetailUiState.kt

### 23. D8. Bottom bar detail pose (Play split button + pill) - v1 NEEDS IT

ANSWER: YES, v1 needs the detail pose, because on Android Play and Shuffle exist ONLY there (the pages have no Play button) and the pill is how Now Playing opens from a detail page. It is present in ALL page states (Loading/Error too; Play simply no-ops until tracks arrive). Pose (Compact, chromeProgress 1): [Play split button (fixed width FloatingBarSplitWidth = 156dp)] [8dp gap] [now-playing pill (fills the rest)] inside the same bar surface as the shell (height 68 = 48 button + 10x2, corner 28, surfaceContainerHigh, margins h16 / v12, inner item gap 8). Play split = TWO parts in a plain Row (2dp apart, SplitButtonDefaults.Spacing): leading 'Play' button (height 48, fillPlay, padding h16, filled with the PAGE COLOURS: container = cover scheme primary, content = onPrimary; YoinSymbols.playFilled icon 20 + 8dp + label cmp_play_label in titleMedium, 1 line clip) and a trailing dropdown button (padding h14, same colours, ▾ = YoinSymbols.chevronDown rotating 180 degrees on a spring while the menu is open; cd cmp_play_cd_more). The menu (YoinDropdownMenu, rows titleMedium, padding h20 v14, 22dp leading icons) holds 'Shuffle play' (YoinSymbols.shuffle) first; per-page extra rows deferred except none needed for v1. Play = onPlay (index 0, in order), menu Shuffle = onShuffle; haptic click on Play. Android DRIVES the pose from the shell (nav -> split morph on a Standard default spatial spring (0.9/700), the detail window then shows a pixel-identical bar so the bar reads as one fixed element while only page content slides). Harmony: a pushed Flutter route would slide its own bar with the page, so make the bar a SHELL-LEVEL overlay slot: the shell bar exposes a `barPose` (0 = nav idle/playing pose, 1 = detail pose) plus a `BarPlaySplitActions` provider (playContainer, playContent, onPlay, onShuffle, menu rows); a detail route publishes its actions on mount (colours follow the cover scheme as it resolves, animated) and clears them on dispose; the bar morphs on the standard default spatial spring (dp-lerp of slot widths: Home slot -> Play split, Library slot (with its leading gap) -> 0, pill gets the rest). The pill is the bar owner's component (tap opens Now Playing); this area only needs the slot + actions contract. Medium/Wide: centred bar detail pose = [Play split (stretches)] [promoted extras: Share, 48dp round buttons] [pill 200dp]; in Wide with the detail column the shell's single bar carries the page's Play (merged pose) - both deferred.

**Motion:** Bar pose morph: standard default spatial spring (0.9/700), one driver. Play-half and dropdown colours: effects spring as the cover scheme resolves. Dropdown hinge: spatial spring on rotation.

**Strings (key | en | zh):**
- cmp_play_label | Play | 播放
- cmp_play_cd_more | More play options | 更多播放选项
- cmp_play_menu_shuffle | Shuffle play | 随机播放

**Android:** ui/detail/DetailBottomBar.kt (DetailBottomBar 1-150); ui/component/YoinButtonGroup.kt (BarPlaySplitActions 1165, resolveBarGeometry 652-725, PlaySplitButton use ~455-480); ui/component/PlaySplitButton.kt (PlaySplitButton 64-150, PlayMenuContent); ui/component/FloatingBottomBar.kt (FloatingBarSplitWidth 147, FloatingBarDetailPillWidth 150, FloatingBarItemGap 139); ui/navigation/back/DetailBackEntering.kt (rememberShellBarChromeMorph 201); theme tokens BarTokens in Harmony lib/theme/tokens.dart

### 24. D9. Data freshness / cache for details

A detail load is single-flight keyed (profileId, entity id): tap-time prefetch and the page's own load join the same Future; a landed result lives in an in-memory cache (v1: unbounded LRU of a few dozen entries is fine) so reopening is instant; Artist prefetches the albums of its first 6 releases. Pages re-read nothing on resume except favorite states. Hearts: a favorites override map (MediaId -> bool) is applied over every list that shows hearts (album rows, Favorites tab, search) so a toggle made anywhere (Now Playing) shows everywhere without a reload.

**Motion:** none

**Android:** ui/detail/DetailPrefetch.kt; ui/detail/ArtistDetailViewModel.kt (prefetchAlbum 81); ui/detail/AlbumDetailViewModel.kt (applyFavoriteOverrides 528)

## Data calls
- MusicLibrary.getArtists() -> List<ArtistIndex> (flatten to Artists; at cold start, probe + first list). Cache for the profile; re-read on pull.
- MusicLibrary.getAlbumList(AlbumListType.newest, size: 500, offset: n) in a loop (L10) for All/Albums; Library does NOT use alphabeticalByName paging any more (the existing Harmony LibraryViewModel with pageSize 60 alphabeticalByName is a framework proof to be replaced).
- MusicLibrary.getPlaylists() (Playlists + All). Playlist cover for a row: playlist.coverArt else first track coverArt / albumId (the list call gives no tracks on Subsonic list responses: if tracks are empty use playlist.coverArt = coverArt id the server sends, Navidrome returns it).
- MusicLibrary.getStarred() (Favorites): tracks, albums, artists.
- Songs tab: MusicLibrary.getAlbumList(newest, size: 10 + overlap, offset: from) then MusicLibrary.getAlbum(id) for each (3 in flight) - NewestAlbumSongsPager (L11).
- MusicLibrary.search(query, trackCount: 20, albumCount: 20, artistCount: 20) after 300 ms debounce (Android leaves the server default count).
- MusicLibrary.getAlbum(id) / getArtist(id) / getPlaylist(id) for the three detail pages (single-flight + memory cache). Artist Play: getAlbum for every release (3 in flight, sliding window).
- MusicWriteActions.setFavorite(id, bool) for song hearts and the artist star (optimistic, revert on failure). Album heart on Subsonic is NOT offered (only tracks and artist).
- MusicPlayback.handleFor(track) per queued track (Subsonic = DirectStream uri + headers) -> PlaybackEngine.setQueue(items, startIndex, playWhenReady: true). Build the items with Future.wait; queues are the whole list (Subsonic never window-cuts).
- MusicSource.resolveCoverUrl(ref, size:) for covers: grid 400, album-page hero 640, artist portrait 720, rows 120-320 (decode cacheWidth = display px x dpr).
- KeyValueStore: library sort ('$profileId/$view' -> enum name), library opens ('profile/provider/Kind/rawId' -> epoch ms, newest 300 per profile+provider).

## State model
ANDROID: LibraryViewModel exposes ONE StateFlow<LibraryUiState> (Loading | Content | Error). Content carries per-view nullable payloads (artists, albums, songs, playlists, favorites: null = not loaded, empty = loaded-empty), selectedTab (All + five chips), allItems (sorted mix), sorts + sortOptions per view, scrollSections per view (fast scroller), searchQuery/searchResults/isSearching/searchError/searchFilter/availableSearchFilters, songsMore, isRefreshing, availableTabs. Sorting+indexing happens in a separate observer on a background dispatcher fed by a LibraryListInputs (lists, sorts, recents, day, ignoredArticles, allSettled); the UI state is only ever updated from its output. A generation counter + profile id guards every async load so a profile switch or reload discards stale results. Detail VMs: AlbumDetail/ArtistDetail/PlaylistDetail each expose Loading | Content | Error and keep non-UI collections (album songs, playlist entries with row keys, loaded artist) in the VM; plays are issued by the host from those lists.

FLUTTER (Riverpod, no codegen, one Notifier per screen; widgets stateless except SpringValues): sealed LibraryUiState = LibraryLoading | LibraryNoAccount | LibraryError(error) | LibraryContent. Replace the proof-of-concept LibraryContent(albums, canLoadMore) with: LibraryContent { LibraryTab selected; List<LibraryTab> availableTabs; List<Artist>? artists; List<Album>? albums; List<Playlist>? playlists; List<Track>? songs; Starred? favorites; List<LibraryItem>? allItems; Map<LibraryTab,LibrarySort> sorts; Map<LibraryTab,List<LibrarySort>> sortOptions; Map<LibraryTab,List<FastScrollSection>> sections; SongsMore songsMore (none/available/loading/failed); bool isRefreshing; } with an internal generation int and the active profile id captured per async load (use ref.mounted checks as the existing code does). Keep the LIVE lists and the SORTED lists separate: a Notifier holds LibraryListInputs and a compute() isolate produces SortedLibraryLists {artists, albums, playlists, all, sections}; publish only the output. A SEPARATE LibrarySearchNotifier (query, results?, isSearching, error, filter) with a 300 ms debounce timer + latest-wins token, so typing never rebuilds the Library lists. Providers to add in lib/app/providers.dart (shared file, list in report): libraryOpenStoreProvider and librarySortStoreProvider (over keyValueStoreProvider), favoriteOverridesProvider (Map<MediaId,bool>, written by every setFavorite, read by Album rows / Favorites / search; Now Playing shares it), playingTrackIdProvider + isPlayingProvider (select()ed projections of the engine state stream: currentId and isPlaying only - NEVER put positionMs in anything list rows watch), detailCacheProvider (single-flight Map<(profileId,id), Future<...>> + memory LRU). Detail: AlbumDetailNotifier.family(MediaId) -> sealed Loading | Content(album meta + List<AlbumSong> with isStarred) | Error; ArtistDetailNotifier.family(MediaId) -> Content(name, heroUrl, releases newest-first, isStarred, activeSpan); PlaylistDetailNotifier.family(MediaId) -> Content(name, owner, comment, songCount, totalDuration, coverUrl, songs with rowKey, sleeveCoverUrls). Pull-up reveal fraction, scroll offsets and fast-scroller drag are widget-local SpringValue/ScrollController state (visual only), NOT in the Notifier. Route args are MediaId (Home will push the same three pages: expose `AlbumDetailScreen(albumId)`, `ArtistDetailScreen(artistId)`, `PlaylistDetailScreen(playlistId)` constructors and a `pushAlbum(context, id)` helper that does the tap-time prefetch + Recents record.

## Adaptive
PHONE (Compact, width < 600) is specified above. HEIGHT FIRST, then width (YoinWindowInfo.of(context); columns use forPaneWidth): isCompactHeight = height < 480 (landscape phone) wins over width. LIBRARY. Compact: 3 fixed columns (even <389dp). Medium (600-839) and Wide (>= 840) and Tabletop: page fills the column with an extra 16dp (32dp total gutter; no 720dp clamp), grid = adaptive count floor((available + 12)/(96 + 12)) with available = width - 64 (600 -> 5, 800 -> 6, 1280 -> 11; cells stay phone-sized, the COUNT follows width), and a shelf too short to fill the page grows its cells (LibrarySparseGrid, owner 2026-11-12). Landscape handset: ONE header row [search pill fixed 208dp][chips scrolling][settings], no title, header horizontal 16 top 8, cells min 100dp (about 6 columns at 844), bottom padding 16 + nav inset. Medium header: [Library title headlineLarge][search pill takes the row][gear] with the chip row on its own line below. Wide header (and a shell column >= 840): one row [title headlineMedium][search pill max 360dp][chips (weight 1, edge-fade scroll)][gear], gaps 16, status-bar inset, top 16, h16, bottom 6; the fast-scroller handle reaches across the 16dp canvas gutter (endOverhang) to hug the page edge; the Wide shell also opens detail pages in a right column instead of pushing (deferred). Search results use the same cells (Compact 3, else floor((w - 32 + 12)/(96 + 12)), landscape min 100). DETAIL. Album: Compact = pull-up (above); height breakpoint on Compact width: if (height - (min(0.74w,300)+56)) < 220 + 250 x fontScale the portrait hero would push every title under the bar -> 'Short': cover on the 16dp start line (side = min(0.62w, w - 160, 260)) with the meta stacked beside it; Landscape handset: cover 256dp (min(256, height - header - 24)) at start inset 44 on the left, list/meta on the right, same pull-up; Medium (>= 600, not Wide): NO reveal state - a plain scrolling list whose first item is a hero ROW (cover 240dp beside the meta), the cover forms the stamp as the page scrolls (progress = scrollOffset / coverSide); Wide full window: identity column 400dp (cover 360) beside the full track list (max width 800), no spectrum bar. Artist: Medium/Wide hero = ringed portrait on the left (180 Medium, 220 Wide) with an identity column on the right (the ONLY place the name appears; header keeps just Back) holding name, '<n> releases' + span, and the Follow/Favorite star with its label; landscape handset: portrait 220 at start 40 + Most Played beside it (48 gap); Wide desktop: Most Played (480) beside the discography. Playlist: same family as Album (Medium hero row with 240 cover + 40dp sleeve room, Wide identity column 400 / cover 360 / inset 20 / margin 40 / list max 800, landscape cover 256 at start 44 with 40 gutter). Bar: Compact = full-width bottom bar; Medium/Wide = centred bar (height 60, button 44, max width 600, bottom margin 24) whose detail pose is [Play split stretches][promoted extras (Share)][pill 200]; Edge-split capsules in landscape handsets. All branching reads YoinWindowInfo only (never the device model); test portrait, landscape, the MateBook window and the narrow Pura X outer window.

## Deferred
- Seam dissolve (tide line at the top seam, halftone field around the floating bar, text fade; SeamDissolve.kt/SeamHalftone.kt AGSL): v1 stand-in = a 16dp alpha fade (smoothStep) on the scroll viewport's top edge under the chips and a ~28dp fade-to-page-colour behind the bar; reason: AGSL + per-item pixel reading has no Flutter/OHOS equivalent yet and is its own task.
- Marquee titles in album/playlist grid cells (AlbumCard uses MarqueeText): v1 uses ellipsis; reason: owner's own wave/marquee cost review (project_wave_marquee_cost) is unresolved and continuous animation on a 3x N grid is expensive on OHOS.
- Entrance animations: none exist on Android either (EntranceMotion.kt is a no-op) - do not build.
- By You sub-chip + Playlist.ownedByMe: appears only when the loaded playlists mix owners; a single-user Navidrome never shows it; needs owner field mapping and the ✕/By You reveal choreography.
- Playlist create FAB / dialog, rename, delete, remove-track, playlist backup/offline toggle: need Capability.playlistsWrite and write actions Harmony's MusicWriteActions lacks; not in the owner's v1 list.
- Track long-press sheet (TrackMenuSheet): Play next, Add to queue, Add to playlist, Go to artist/album, share, notes - needs engine queue append/insert (PlaybackEngine only has setQueue) and playlist writes.
- Detail ▾ menu extras (Play next, Add to queue, Add to playlist, Open in..., Share/promoted Share button, offline): same reason; v1 menu = Shuffle play only.
- Album page: rating emblem, Last Play, comment/review + rate sheet, NeoDB, page 2 Scrapbook (HorizontalPager + AlbumPageDots), 'Based on X/N' score, cover spectrum bar (AlbumSpectrum* strip sorting), brand 'butterfly' blocks behind the cover, song notes marker, Memory/Memories: need Room data + AI + NeoDB, owner excluded Memories/notes/AI/cloud from v1.
- Artist page: Most Played (local play history), cover-strip ring dot dissolve (v1 can fade the ring), Wide desktop Most Played column, artist Share.
- Library: Spotify/Apple Music scopes + catalog search, Random mix header/reshuffle, Songs by title sort, favoritesAreLibrary, Spotify cache paths, Apple pending state: not Subsonic.
- Home account switcher/avatar in the header (HomeAccountSwitcherHost, avatar hold): Home/settings area; v1 shows the settings gear.
- Library cold-start 'copy diagnostics' button, library action feedback/add-to-library buttons on search rows (Capability.libraryAdd is Spotify/Apple only).
- Keep-grid-anchor across width changes (LibraryGridAnchor / KeepGridAnchorAcrossWidthChanges) and search scroll memory per (query, scope, filter): v1 keeps a plain per-view ScrollController; rotate/fold may jump - add the anchor in the adaptive pass.
- Detail predictive-back card collapse (DetailPredictiveBackCollapse), bar nav<->split scrub, covered-window animation freeze, shell->detail hand-off intro (DetailEnterIntro): HarmonyOS back delivers no progress; commit through YoinPushRoute pop is the contract.
- Sparse-grid growth (LibrarySparseGrid: cells grow past Compact when a shelf is too short), landscape-handset one-row header, Medium/Wide header rows, Wide detail column (ui/navigation/pane): Compact phone first, adaptive pass after (see adaptive). The detail pages' Short / Landscape / Medium / Wide layouts are ported (lib/detail/detail_layout.dart, ReshapeOverviews in reshape_scaffold.dart, the artist heroes): the butterfly stays on the Compact hero only (hugging the cover in the detail column and in the landscape hero), never on Short or Medium (Android cabd5e434, a3edca486); the centred bar promotes Shuffle + Go to artist / Share.
- Haptics (rememberYoinHaptics: tick, confirm, click, segment tick on fast scroller): no Harmony haptics channel yet; hook points are listed in each item.
- Artwork retry-on-network-return (ArtworkRetry.kt) and palette backdrops (extractBackdropColors is false in Library anyway).
- Fast-scroller extras: pinned-handle handover spring, per-section haptic tick, system-gesture-exclusion region (see risks).

## Gaps in the HarmonyOS framework
- MODEL: Playlist has no addedAt ('created'), no isPublic/changed; mapper must read Subsonic playlist.created (and .public) so Recently added works for playlists (Android Playlist.libraryAddedAt). Add Playlist.addedAt now; ownedByMe can wait. Album.addedAt, Album.version, Album.releaseType, Track.discNumber already exist - good.
- SOURCE: getArtists drops the server's `ignoredArticles`; use the fixed 11-word list above (Android does the same). MusicLibrary has no getLibrarySongs (not needed on Subsonic). Capability enum lacks playlistsWrite / libraryAdd / searchPlaylists (needed only as 'absent' today).
- PLAYBACK: PlaybackEngine only has setQueue/skipTo/next/previous - no append/insert/remove (Play next, Add to queue deferred). setQueue with 500-2000 items (Songs list) + EngineItem needs headers per item: confirm the ArkTS side accepts a queue that large without building every AVPlayer source up front. Play context ('Playing from' Album/Artist/Playlist/Liked songs) has no home: Now Playing needs {kind, id, name, cover id} next to the queue - define a PlayContext value passed to the play call.
- COVER COLOUR: lib/theme/mcu2025 has the SPEC_2025 scheme but no image->seed glue (Android CoverSeedExtractor + ExpressiveColorSchemeFactory.fromSeed). Add rememberCoverScheme(url) = decode a small bitmap, Celebi quantize + Score (material_color_utilities has both) -> seed -> existing DynamicScheme; cache by url; animate to it (a new ColorScheme instance rebuilds the whole subtree, so scope a Theme to the page and lerp colours on the effects spring instead of swapping the page Theme per frame - 'theme wash invariant').
- COMPONENTS MISSING in lib/ui/component (only ExpressiveTextField exists): ExpressiveSegmentChip/Row (+ horizontal edge fade), elasticPress (press scale 0.97), ExpressiveMediaArtwork (Image.network with cacheWidth, surfaceContainerHighest fill, fallback icon 24dp, crossfade reveal; ClipRSuperellipse for the continuous 4/8dp corners), MetaLine (group gap 12, drop trailing groups that do not fit, Stat/Plain/Kind), AlbumVersionTag, YoinLoadingIndicator (M3 Expressive contained loading indicator - Flutter has none: a morphing-polygon painter or the OHOS Flutter M3E package), YoinPullToRefresh (spring-settled), YoinDropdownMenu, SearchBar expand/collapse morph, fast scroller, SongListItem row, equalizer symbol animated (yoin_symbols has a static `equalizer`; Android uses an animated painter that moves when playing and sinks to dots when paused), animated expand hinge (use chevronDown + rotation), SplitButton/PlaySplitButton, edge-fade, empty-state panel. All icons exist in yoin_symbols (search, close, back, sort, check, shuffle, playFilled, favorite/favoriteFilled, star/starFilled, musicNote, playlist, artist, album, settings, refresh, moreVertical, chevronDown, equalizer).
- TOKENS: LibraryTokens has gridGutterH/V, coverToTitle, bottomGroupPadding, row dims - missing: chip height 44 / corner 18 / gap 8, fast-scroller dims (24x48 / 32x64 / slide 32 / hide 1600 ms), detail tokens (header paddings, hero 0.74/300/56, stamp 48, pull-up travel 0.55, handoff shift 28, thresholds 1.6 / 0.5 / rubber 0.30, settle spring 0.75 / 520), sleeve constants, artist ring constants, search debounce 300. Add them to lib/theme/tokens.dart and tell the Kotlin side. Standard-role effects springs equal the expressive effects ones (1.0/3800, 1600, 800) so YoinSprings.defaultEffects serves both; the standard spatial pair already exists.
- BAR: lib/shell/yoin_bottom_bar.dart has only the idle pose; no detail pose, no pill, no `barPose`/actions provider (see D8). Whoever owns the bar must add the slot contract before detail pages can host Play.
- SHELL/ROUTING: no pushDetail helper, no Settings route (gear), no snackbar host for the load-failure messages (library_error_load_tab_*, search failed), no in-memory detail cache / single-flight, no favorite-overrides provider, no playing-track-id/isPlaying projections.
- TEXT: Dart has no ICU AlphabeticIndex / Collator / Han->pinyin. Needs either an ArkTS channel (OHOS ohos.i18n Collator + Transliterator 'Han-Latin/Names' + AlphabeticIndex if exposed) or a Dart fallback (diacritic-folded Latin A-Z, a pinyin package for Han, kana/Hangul bucketed as their own labels or '#', digits/symbols '#'). Also plural/format helpers in strings.dart (one/other for en, other for zh; stat groups).
- STRINGS: lib/l10n/strings.dart has 15 strings; add all keys listed per item (Library, detail, cmp_play_*, duration formats) as getters with the Android key in a comment.
- FONT/TYPE: tabular figures for durations/years/stat values (FontFeature.tabularFigures) and 'withTabularFigures' for track numbers; make sure Google Sans Flex's tnum feature survives the variable-font path.
- INPUT: no system-gesture exclusion API wired for the fast-scroller handle (HarmonyOS edge back).

## Risks
- HarmonyOS side-swipe back fires from BOTH screen edges: the fast-scroller handle sits on the right edge and the user's drag will compete with it. Inset the handle track from the very edge (the handle is 24dp wide at the end edge; consider a 16dp touch inset) and/or ask the ArkTS host to disable gesture-back over that region while the handle is visible; verify on the Mate 70 Air release HAP.
- Sort + index of thousands of albums must not run on the UI isolate (Android uses Dispatchers.Default); re-sorting only the view whose inputs changed. A first-paint of a 3-column grid with network covers needs cacheWidth set (grid 400px source, decode to cell px) or release builds will stutter; use the release HAP only.
- Held album batches (L10) are easy to get wrong: a batch must NEVER land among rows the user is looking at; implement with an explicit `held` set released on read-end / Library-shown / view-or-sort change.
- Chip row reveal + X unfold interplay (animate padding, scroll-into-view of the FINAL bounds, one-shot after arrival) is subtle; build it with a single controller and test with 5 chips on a narrow window (Pura X outer ~1:1 window).
- Cover colour seed extraction on-device for every detail page open can jank the push transition: decode small (e.g. 64-128px), do it off-thread, cache by url, and let the page animate colours in after the route settles.
- Pull-up reshape fights nested scrolling and the HarmonyOS back gesture; reproduce the exact nested-scroll rule (pull down at list top drives the fraction; pull up while fraction > 0 drives it) and keep ONE settle owner or the list/hero will fight.
- Flowing-titles Text with per-title tap spans (TextSpan + TapGestureRecognizer) across 40+ titles in a 28sp headline is heavy: mark it RepaintBoundary, build the span list once, and make sure the recogniser lifecycle is disposed.
- Search surface morph + system back: a PopScope-free design is required (AGENTS: no raw PopScope). Recommended: implement search as its own route (PopupRoute/PageRoute with a spring controller morphing from the pill's rect) so system back = Navigator.pop through the same controller as the back arrow.
- Detail bar contract (D8): if the bar stays inside each route it will slide with the page and the 'bar reads as fixed' behaviour is lost; agree the shell-level slot with the bar owner before building pages.
- Subsonic servers differ: Navidrome returns artist `coverArt` and playlist `coverArt`; Gonic/others may not - fall back to first album cover (artist) and first track cover (playlist) exactly as above. Album list `created` may be absent on old servers (Recently added then collapses to alphabetical; section index must return none when >20% undated).
- Songs tab reads albums 3 at a time; on a slow server the first page can take a while - show the loading indicator (songs null) and never block the other views. Failed album twice in a row is silently dropped from Songs (by design).

## Corrections (from a second reader — these win)
The spec is largely accurate. I checked the string keys and en/zh values, the pull-up numbers (0.55 travel, 1.6/0.5 thresholds, settle spring damping 0.75 and stiffness 400 x 1.3), the hero, artist ring and fast-scroller constants, the sort options per view, the search filters, the paging constants (500 / 100000 / 10 / 3 / 300), and the artist release ordering and Play loader. All of those match the Android code. The items below are the errors and omissions I found. Paths are relative to app/src/main/java/com/gpo/yoin.

CORRECTIONS (wrong in the spec)

1. Artist star is not `setFavorite`. D6 and dataCalls say `writeActions.setFavorite(artistId, target)`.
   - Android calls `repository.setArtistFollowed(id, followed, artist)` (ui/detail/ArtistDetailViewModel.kt toggleFollow 186). Subsonic implements it as `star?artistId=<rawId>` / `unstar?artistId=<rawId>` (data/source/subsonic/SubsonicMusicSource.kt 158-169).
   - A comment there says the song-style `id=` param is not accepted by spec-strict servers for artists.
   - Harmony needs a separate `setArtistFollowed` (or `setFavorite` branching on the kind of id) that sends `artistId=`.
   - Song hearts use `star?id=`.
   - The artist star also writes the same favorites-override map, and the page observes it (observeFollow 202).

2. Playlist cover is not "the coverArt the server sends".
   - `SubsonicPlaylist.toPlaylist` always sets `coverArt = CoverRef.SourceRelative(playlist.id)`, so the cover id is the playlist's own id (data/source/subsonic/SubsonicMappers.kt 167). The server's own `coverArt` field is ignored.
   - So the Library cell, row and Playlist hero all request `getCoverArt?id=<playlistId>`.
   - The first-track fallback only applies when the cover resolves to null. On Subsonic `heroIsFirstTrackCover` is effectively false.
   - If the playlist-id cover fails to load, Android shows the fallback icon, not the first track's cover.
   - Strike the spec's remark that "list calls give no tracks so use first track coverArt". With this mapper it never happens.

3. Cover URL sizes are wrong.
   - Library cells and rows (grid and list, all views) all use `resolveSubsonicCoverUrl(id, size = 256)` (ui/library/LibraryViewModel.kt 2236), not "~400 grid".
   - Detail hero, artist portrait and release rows (Album, Artist and Playlist hero) call `repository.resolveCoverUrl(ref)` with NO size. The request is the server's original, decoded down client-side to 640 / 720 / 168 px.
   - Only the Playlist rows and the sleeve covers use `size = 320` (ui/detail/PlaylistDetailViewModel.kt 446).
   - Flutter should therefore pass `cacheWidth` and keep the URL identical across screens, so the image cache is shared. For example the Library 256 URL and the detail unsized URL are different cache keys. A sized detail URL (about 640) would be a deliberate improvement, not parity.
   - The Playlist sleeve rule "same url as the rows, size 320" holds.

4. `Album.addedAt` is not Subsonic `created`.
   - Android's Album has two fields (data/source/subsonic/SubsonicMappers.kt 118-127). `addedAt = starred` (the star timestamp, used by Home shelves). `libraryAddedAt = created` (used by Library Recently added).
   - L6 says "album.addedAt (Subsonic 'created')". Harmony must map `created` into whichever field the sorter reads, and not conflate it with `starred`.
   - The same applies to Playlist: `libraryAddedAt = created`. The spec's `Playlist.addedAt` gap is right, but name it the library date.

5. Album track order is not "the order getAlbum returned".
   - `toAlbum()` runs `discTrackOrder(...)` (data/model/Track.kt 92).
   - If any track has a `discNumber`, the tracks are sorted by (discNumber nulls last, trackNumber nulls last).
   - Otherwise the server order is kept.
   - This governs the track rows, the disc headings, the flowing titles, the play queue, and Shuffle's source order. D5 and D4 must say so; the Dart mapper needs the same stable sort.
   - `discNumber` is mapped as `takeIf { it > 0 }`.

6. Track artist text.
   - `Track.artist = displayArtist?.takeIf(nonBlank) ?: artist` (OpenSubsonic `displayArtist`).
   - Rows and the feat credit use it. `featArtist` is that string whenever it is non-blank and not equal, case-insensitively, to `album.artist` (ui/detail/AlbumDetailViewModel.kt toAlbumSong).
   - D2/D4 compare "artist differs from album artist"; make it case-insensitive and use `displayArtist`.
   - OpenSubsonic multi-artist data is carried in `extras`. It is not needed for v1.

OMISSIONS (missing for v1)

7. Detail page load waits for the active source.
   - Album, Artist and Playlist loads call `awaitActiveSource(DETAIL_SOURCE_WAIT_MS = 4000)` before the first read. This matters for a cold start from a widget or notification (ui/detail/AlbumDetailViewModel.kt 202, 4000 ms at ~945).
   - Without it a detail opened right after launch fails with "no profile".
   - Add this to D1/D9.
   - The pages are not seeded from the tapped Library cell. Header text shows only after `getAlbum`, `getArtist` or `getPlaylist` returns (loading indicator until then).

8. Visits feed Home, not just Library Recents.
   - Album and Artist pages call `repository.recordAlbumVisit(album)` and `recordArtistVisit(artist)` on load, off the critical path (AlbumDetailViewModel 241, ArtistDetailViewModel 81).
   - Home's Activities / Jump-back-in shelves read these rows. The spec defers "visit recording (Home activity)", but the Home area depends on it.
   - Either Harmony adds a minimal local activity store now, or the Home spec must state that Home runs without it.
   - Playlist pages record no visit.

9. Library error and snackbar host.
   - The Library page has a snackbar host. A failed chip fetch or search shows `library_error_load_tab_*` / `library_error_search_snackbar`.
   - The spec lists the strings, but the Harmony gap list only says there is no snackbar host. Treat the snackbar host as a v1 dependency of L1, L12 and L14.

10. Favorites on Subsonic.
    - Only song rows (Album page and Now Playing) get hearts, and the artist page gets the star. SongListItem has no heart at all (ui/component/SongListItem.kt), so Library Songs, Favorites and search song rows have no heart. The spec is silent here and a reader could add one.
    - The favorites override map is applied to Album rows and to the artist star state (`overrides[id]` in observeFollow). Library Favorites is a plain `getStarred2` list. It does not update live after an unstar on another page unless the Library re-reads. Nothing in Android refreshes it on a resume either.

11. Album heart write.
    - Album rows use "pending tap" state (`PendingFavoriteTap`). The tap shows the target at once, the write runs, and the finally block clears the pending state so the repository state takes over.
    - A failed write therefore flips the heart back.
    - Also, on each page open a favorite catch-up check runs (`favoriteOpeningCheck`). During it a heart corrected by fresh state flips quietly, with no pop animation.
    - Subsonic's `refreshFavoriteStates` is minor, so v1 can skip it. Implement the pending-tap-then-revert behaviour as described.

12. Search service counts.
    - `search3` is called with only `query`; `artistCount`, `albumCount` and `songCount` are null (server defaults, 20 on Subsonic). The dataCalls line "trackCount: 20, albumCount: 20, artistCount: 20" sends explicit counts. That is fine functionally, but not parity. Harmony's `search` signature should allow null counts.

13. Chip view icons and the `LibraryTab.Chips` order.
    - Chip order is the list `LibraryTab.Chips` filtered by `visibleTabs`. The Songs chip appears for Subsonic only through `features.songsFromNewestAlbums` (it has no `LIBRARY_SONGS` capability).
    - Harmony must expose an equivalent flag (`songsFromNewestAlbums = true`, `albumsHaveLibraryDates = true`, `playlistsHaveLibraryDates = true`). The spec mentions the date flags only in passing, and `songsFromNewestAlbums` is needed for the Songs chip.

14. `ArtistDetail` album summaries.
    - `ArtistDetail.albums` come from `getArtist` as summaries. The mapper gives `coverArt = coverArt ?: id` (album id fallback), `releaseType` from OpenSubsonic `releaseTypes` (first recognised of album / ep / single / compilation), `version` trimmed or null, `songCount`, `year`.
    - The release filter chips are hidden when there are fewer than 2 distinct kinds, which is the usual Navidrome case. Harmony's Album model must carry `releaseType` and `version`; the spec notes they exist.

SMALL NOTES

- Fast-scroller track top inset is 8dp (`LibraryScrollerTopInset`). Hide delay is `HIDE_DELAY_MILLIS = 1600`. Thumb 24x48 / touch 32x64 / slide 32. All match the spec.
- Library cold start waits `ACTIVE_SOURCE_WAIT_MS` (the spec's 4000 ms was not re-verified here).
- Nothing else materially wrong. The deferrals (seam dissolve, marquee, By You, track sheet, scrapbook, spectrum bar) are all justified by the owner's v1 scope.

## Since parity p3 step C (2026-11-25): search finds Memories and notes

This replaces L14's "All = sectioned list" and its chip order. Android 0.6.3's search (docs/design.md 「搜索的「全部」页」, docs/search.md) on HarmonyOS:

- **Core.** `lib/data/search/` is a pure-Dart port of `:shared` `search/` (`search_match.dart` fold / match / fragment with UTF-16 highlight ranges and the surrogate-safe cut; `memory_search.dart` the ledger search across accounts, attachments, cover marks; `search_page.dart` sections, relevance folding, the best match, the Memories list, 1×2 packing; `search_filter.dart`; `note_open_target.dart`). It must keep passing `test/data/search/memory_search_golden_test.dart` (every vector of `shared/src/commonTest/resources/golden/memory-search.json`). The per-code-point NFKD is `search_nfkd.dart`, generated by `scripts/gen_search_nfkd.py`; `NoteMarkup.plainText` gives the words.
- **Ledger.** `memorySearchPortProvider` (`library/library_search_memories.dart`) reads `memoryLedgerProvider` (every counted account) and each account's AI titles (`MemoryCopyCache`). The search notifier asks it beside the service, so its hits land first; `LibrarySearchState.searched / memories / ledger` carry them.
- **Chips.** All, Songs, Albums, Memories, Artists, Playlists (Playlists as before, by capability). Type chips still list the service's rows; Memories lists every note and memory hit as full-width 1×2s ("Nothing you've written matches." when none).
- **All.** `library/library_search_tiles.dart`: the best-match card, then sections ordered by hit strength, at most `SearchTokens.allMaxRows` rows and `allMaxWide` 1×2s each, Show all → that section's chip, "N less relevant from Spotify / Apple Music" for folded rows. Tiles are Home's backdrop covers (album Bun, song Circle, playlist Ghostish, artist circle); a song met through a note or an album through its memory's words is a 1×2 with "Your note" + time / score + memory title and the fragment, the hit inked like a marker pen; album covers wear the memory mark (Spotify: beside the title); another account's tile carries a small badge. Columns: 3 on a phone and Pura X's outer screen, else the Library grid's count (min cell 96, 100 on a landscape handset) between 3 and 8.
- **Taps.** A note on a returned song plays that song alone and seeks to the note's second once the song is current and prepared (`search_note_playback.dart`: engine phase `ready`, within `noteSeekTimeout` = 4 s); any other note opens its album's scrapbook at the song. An album with a memory opens on its scrapbook; a memory known only from the ledger opens its album's scrapbook. Another account's switches first, behind the Memories deck's one-off notice (`requestAccountSwitch` + `switchAccountThen`).
- **Strings** are Android's `library_search_filter_memories`, `_show_all`, `_folded`, `_your_note`, `_your_memory`, `_no_memories` (EN + zh, verbatim).
- **Not yet:** the `MemorySearch` tip; device QA (phone, foldable, MateBook window).
