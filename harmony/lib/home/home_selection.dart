/// Home's pure rules: what Activities, Jump Back In and the shelves show, and
/// the feed geometry. No Flutter, no I/O — unit-tested directly.
library;

import 'dart:math' as math;

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../theme/tokens.dart';
import 'home_feed_density.dart';
import 'home_grid_pool_store.dart';
import 'home_metrics.dart';
import 'home_ui_state.dart';

// ---------------------------------------------------------------------------
// Greeting and time-ago.

enum HomeDayPart { morning, noon, afternoon, evening }

/// 5–10 morning, 11–12 noon, 13–17 afternoon, else evening (homeDayPartAt).
HomeDayPart homeDayPartAt(int hour) => switch (hour) {
      >= 5 && < 11 => HomeDayPart.morning,
      >= 11 && < 13 => HomeDayPart.noon,
      >= 13 && < 18 => HomeDayPart.afternoon,
      _ => HomeDayPart.evening,
    };

/// When the greeting next changes: the top of the next hour, plus a little slack.
Duration untilNextHour(DateTime now) {
  // Same zone as [now] (a UTC clock must not be read as local time).
  final next = now.isUtc ? DateTime.utc(now.year, now.month, now.day, now.hour + 1) : DateTime(now.year, now.month, now.day, now.hour + 1);
  final wait = next.difference(now);
  // Never a zero or negative timer (a DST jump, a frozen clock): that would spin.
  return (wait < const Duration(seconds: 1) ? const Duration(seconds: 1) : wait) + HomeTimings.greetingTickSlack;
}

sealed class TimeAgo {
  const TimeAgo();
}

final class TimeAgoJustNow extends TimeAgo {
  const TimeAgoJustNow();
  @override
  bool operator ==(Object other) => other is TimeAgoJustNow;
  @override
  int get hashCode => 0;
}

sealed class _CountedAgo extends TimeAgo {
  const _CountedAgo(this.count);
  final int count;
  @override
  bool operator ==(Object other) => other.runtimeType == runtimeType && other is _CountedAgo && other.count == count;
  @override
  int get hashCode => Object.hash(runtimeType, count);
  @override
  String toString() => '$runtimeType($count)';
}

final class TimeAgoMinutes extends _CountedAgo {
  const TimeAgoMinutes(super.count);
}

final class TimeAgoHours extends _CountedAgo {
  const TimeAgoHours(super.count);
}

final class TimeAgoDays extends _CountedAgo {
  const TimeAgoDays(super.count);
}

final class TimeAgoWeeks extends _CountedAgo {
  const TimeAgoWeeks(super.count);
}

/// formatTimeAgo: <1 min just now, <60 min m, <24 h h, <7 d d, else weeks (days ÷ 7).
TimeAgo timeAgoOf({required int nowMs, required int timestampMs}) {
  final diff = math.max(0, nowMs - timestampMs);
  final minutes = diff ~/ Duration.millisecondsPerMinute;
  if (minutes < 1) return const TimeAgoJustNow();
  if (minutes < 60) return TimeAgoMinutes(minutes);
  final hours = minutes ~/ 60;
  if (hours < 24) return TimeAgoHours(hours);
  final days = hours ~/ 24;
  if (days < 7) return TimeAgoDays(days);
  return TimeAgoWeeks(days ~/ 7);
}

// ---------------------------------------------------------------------------
// Activities.

/// Rows read from the log (Android limit 40 × 8) and the collapsed take.
const int homeActivityReadLimit = 320;
const int homeActivityLimit = 40;

/// Ids may be stored raw or `provider:raw` (legacy): strip a known provider prefix.
String stripProviderPrefix(String id) {
  final sep = id.indexOf(':');
  if (sep <= 0) return id;
  final prefix = id.substring(0, sep);
  const known = {MediaId.providerSubsonic, MediaId.providerSpotify, MediaId.providerAppleMusic, MediaId.providerLocal};
  return known.contains(prefix) && sep < id.length - 1 ? id.substring(sep + 1) : id;
}

/// collapseToLatestUnique: the newest per `action:entityType:entityId`, then [limit].
List<ActivityEvent> collapseToLatestUnique(List<ActivityEvent> events, {int limit = homeActivityLimit}) {
  final sorted = [...events]..sort((a, b) => b.timestampMs.compareTo(a.timestampMs));
  final seen = <String>{};
  final out = <ActivityEvent>[];
  for (final e in sorted) {
    if (!seen.add('${e.actionType.name}:${e.entityType.name}:${stripProviderPrefix(e.entityId)}')) continue;
    out.add(e);
    if (out.length >= limit) break;
  }
  return out;
}

/// homeActivityDedupKey: `entityType:id`, songs keyed by their song id.
String homeActivityDedupKey(ActivityEvent e) {
  final id = e.entityType == ActivityEntityType.song ? (e.songId ?? e.entityId) : e.entityId;
  return '${e.entityType.name}:${stripProviderPrefix(id)}';
}

/// selectHomeActivities: dedupe (newest wins, whatever the action), then drop
/// song events (single plays never earn a card).
List<ActivityEvent> selectHomeActivities(List<ActivityEvent> events) {
  final seen = <String>{};
  return [
    for (final e in events)
      if (seen.add(homeActivityDedupKey(e)) && e.entityType != ActivityEntityType.song) e,
  ];
}

MediaId? _id(String provider, String? raw) {
  if (raw == null || raw.isEmpty) return null;
  final stripped = stripProviderPrefix(raw);
  return stripped.isEmpty ? null : MediaId(provider, stripped);
}

/// buildActivityCoverArtUrl: the stored cover key, else (Subsonic) the album id.
CoverRef? activityCover(ActivityEvent e, String provider) {
  final stored = CoverRef.fromStorageKey(e.coverArtKey);
  if (stored != null) return stored;
  if (provider != MediaId.providerSubsonic) return null;
  final fallback = e.entityType == ActivityEntityType.album ? e.entityId : e.albumId;
  return fallback == null || fallback.isEmpty ? null : CoverSourceRelative(stripProviderPrefix(fallback));
}

/// ActivityEvent.asSong: a slim track (no album name; the album and artist ids the play
/// recorded, if any). Now Playing asks the service for links it lacks (song_links.dart).
Track activityAsTrack(ActivityEvent e, String provider) => Track(
      id: _id(provider, e.songId ?? e.entityId)!,
      title: e.title,
      artist: e.subtitle.isEmpty ? null : e.subtitle,
      albumId: _id(provider, e.albumId),
      artistId: _id(provider, e.artistId),
      coverArt: activityCover(e, provider),
    );

/// buildActivityEntries: the bento's cards, songs already removed.
List<HomeActivityEntry> buildActivityEntries(List<ActivityEvent> events, {required String provider}) => [
      for (final e in selectHomeActivities(events))
        if (_id(provider, e.entityId) case final id?)
          HomeActivityEntry(
            stableId: 'activity:${e.timestampMs}:${e.entityType.name}:${id.rawId}:${e.actionType.name}',
            entityType: e.entityType,
            entityKey: '${e.entityType.name}:${id.rawId}',
            title: e.title,
            subtitle: e.subtitle.isNotEmpty ? e.subtitle : null,
            timestampMs: e.timestampMs,
            cover: activityCover(e, provider),
            target: switch (e.entityType) {
              ActivityEntityType.album => HomeOpenAlbum(Album(
                  id: id,
                  name: e.title,
                  artist: e.subtitle.isEmpty ? null : e.subtitle,
                  artistId: _id(provider, e.artistId),
                  coverArt: activityCover(e, provider),
                )),
              ActivityEntityType.playlist => HomeOpenPlaylist(Playlist(
                  id: id,
                  name: e.title,
                  owner: e.subtitle.isEmpty ? null : e.subtitle,
                  coverArt: activityCover(e, provider),
                )),
              ActivityEntityType.artist => HomeOpenArtist(id, e.title),
              ActivityEntityType.song => HomePlaySong(activityAsTrack(e, provider)),
            },
          ),
    ];

/// selectHomeHeroActivity: the first album or playlist among the first
/// [window] entries (artists are never hero). -1 = none.
int selectHeroIndex(List<HomeActivityEntry> entries, {int window = HomeBentoMetrics.heroWindow}) {
  for (var i = 0; i < math.min(window, entries.length); i++) {
    final t = entries[i].entityType;
    if (t == ActivityEntityType.album || t == ActivityEntityType.playlist) return i;
  }
  return -1;
}

/// The hero's identity (entityType:id), or null.
String? heroKeyOf(List<HomeActivityEntry> entries) {
  final i = selectHeroIndex(entries);
  return i < 0 ? null : entries[i].entityKey;
}

/// homeActivityAlbumCards: raw album ids among the first [limit] entries.
Set<String> activityAlbumIds(List<HomeActivityEntry> entries, {int limit = 20}) => {
      for (final e in entries.take(limit))
        if (e.target case HomeOpenAlbum(:final album)) album.id.rawId,
    };

/// notShownIn: Recently Played minus albums Activities already shows.
List<Album> notShownIn(List<Album> albums, Set<String> shownRawIds) =>
    shownRawIds.isEmpty ? albums : [for (final a in albums) if (!shownRawIds.contains(a.id.rawId)) a];

/// Card sizes in the phone bento.
enum BentoCardSize { hero, small, wide, strip }

/// One card in a bento row with its flex weight.
final class BentoCell {
  const BentoCell(this.size, this.entry, this.weight);
  final BentoCardSize size;
  final HomeActivityEntry entry;
  final double weight;
}

/// The phone bento's rows (ActivityBentoRows): [hero] / [small | wide] /
/// [strip] (narrow: two equal smalls). A row whose first card is past the
/// supply is dropped; otherwise its last card takes the missing weight.
List<List<BentoCell>> bentoRows(List<HomeActivityEntry> all, BentoRecipe recipe) {
  final entries = all.take(HomeBentoMetrics.phoneEntries).toList();
  if (entries.isEmpty) return const [];
  final heroIndex = selectHeroIndex(entries);
  final hero = heroIndex < 0 ? null : entries[heroIndex];
  final supporting = [
    for (var i = 0; i < entries.length; i++)
      if (i != heroIndex) entries[i],
  ].take(HomeBentoMetrics.phoneSupporting).toList();

  if (recipe == BentoRecipe.landscape) {
    final row = <(BentoCardSize, double)>[
      if (hero != null) (BentoCardSize.hero, HomeBentoMetrics.landscapeHeroWeight),
      (BentoCardSize.small, HomeBentoMetrics.landscapeSmallWeight),
      (BentoCardSize.wide, HomeBentoMetrics.landscapeWideWeight),
    ];
    final cards = [?hero, ...supporting];
    return [?_seat(row, cards, 0)];
  }

  final second = recipe == BentoRecipe.phoneNarrow
      ? const [(BentoCardSize.small, 1.0), (BentoCardSize.small, 1.0)]
      : const [(BentoCardSize.small, 1.0), (BentoCardSize.wide, 2.0)];
  final rows = <List<BentoCell>>[];
  if (hero != null) rows.add([BentoCell(BentoCardSize.hero, hero, 1)]);
  final r2 = _seat(second, supporting, 0);
  if (r2 != null) rows.add(r2);
  final r3 = _seat(const [(BentoCardSize.strip, 1.0)], supporting, second.length);
  if (r3 != null) rows.add(r3);
  return rows;
}

List<BentoCell>? _seat(List<(BentoCardSize, double)> slots, List<HomeActivityEntry> supply, int from) {
  if (from >= supply.length) return null;
  final available = math.min(slots.length, supply.length - from);
  final cells = <BentoCell>[
    for (var i = 0; i < available; i++) BentoCell(slots[i].$1, supply[from + i], slots[i].$2),
  ];
  if (available < slots.length) {
    // The row's last card takes the missing weight (a small that grows reads as a wide).
    final missing = slots.skip(available).fold<double>(0, (s, e) => s + e.$2);
    final last = cells.removeLast();
    final grownSize = last.size == BentoCardSize.small ? BentoCardSize.wide : last.size;
    cells.add(BentoCell(grownSize, last.entry, last.weight + missing));
  }
  return cells;
}

// ---------------------------------------------------------------------------
// Jump Back In.

const int homeGridMaxCompacts = 28;

int _artlessLast<T>(T a, T b, CoverRef? Function(T) cover) => (cover(a) == null ? 1 : 0) - (cover(b) == null ? 1 : 0);

/// A stable sort (Dart's List.sort is not stable).
List<T> stableSortedArtlessLast<T>(List<T> items, CoverRef? Function(T) cover) {
  final indexed = [for (var i = 0; i < items.length; i++) (i, items[i])];
  indexed.sort((a, b) {
    final c = _artlessLast(a.$2, b.$2, cover);
    return c != 0 ? c : a.$1.compareTo(b.$1);
  });
  return [for (final e in indexed) e.$2];
}

/// Round-robin: one from each list in turn until all are used.
List<T> interleave<T>(List<List<T>> lists) {
  final out = <T>[];
  final longest = lists.fold<int>(0, (m, l) => math.max(m, l.length));
  for (var i = 0; i < longest; i++) {
    for (final l in lists) {
      if (i < l.length) out.add(l[i]);
    }
  }
  return out;
}

CoverRef? _subsonicAlbumFallback(MediaId? albumId) =>
    albumId != null && albumId.provider == MediaId.providerSubsonic ? CoverSourceRelative(albumId.rawId) : null;

HomeWidgetCard albumCard(Album a) => HomeWidgetCard(
      stableId: 'grid-album:${a.id.rawId}',
      kind: HomeCardKind.album,
      title: a.name,
      subtitle: a.artist ?? '',
      cover: a.coverArt ?? _subsonicAlbumFallback(a.id),
      version: a.version,
      target: HomeOpenAlbum(a),
    );

HomeWidgetCard songCard(Track t) => HomeWidgetCard(
      stableId: 'grid-song:${t.id.rawId}',
      kind: HomeCardKind.song,
      title: t.title ?? '',
      subtitle: t.artist ?? '',
      cover: trackCover(t),
      target: HomePlaySong(t),
    );

/// The placeholder Android shows as a playlist owner when there is none.
const String _placeholderOwner = 'Playlist';

HomeWidgetCard playlistCard(Playlist p) => HomeWidgetCard(
      stableId: 'grid-playlist:${p.id.rawId}',
      kind: HomeCardKind.playlist,
      title: p.name,
      subtitle: p.owner == null || p.owner == _placeholderOwner ? '' : p.owner!,
      // An empty playlist's server art is a black square: the placeholder instead.
      cover: p.songCount == 0 ? null : p.coverArt,
      target: HomeOpenPlaylist(p),
    );

/// A track's cover, else (Subsonic) its album's.
CoverRef? trackCover(Track t) => t.coverArt ?? _subsonicAlbumFallback(t.albumId);

/// buildWidgetGrid: primary = interleave(albums[0..3), tracks[0..2),
/// playlists[0..3)), extras = the rest interleaved; artless last; ≤ [limit]
/// (28). Home keeps the whole pool (`limit: null`) and caps the shelf after
/// the cards hidden for a while are left out (jbiShelfCards), so a hidden
/// cover makes room for the next one in line.
List<HomeWidgetCard> buildWidgetGrid(HomeGridPools pools, {int? limit = homeGridMaxCompacts}) {
  final albums = [for (final a in stableSortedArtlessLast(pools.albums, (a) => a.coverArt ?? _subsonicAlbumFallback(a.id))) albumCard(a)];
  final tracks = [for (final t in stableSortedArtlessLast(pools.tracks, trackCover)) songCard(t)];
  final playlists = [for (final p in stableSortedArtlessLast(pools.playlists, (p) => p.coverArt)) playlistCard(p)];
  final primary = interleave([albums.take(3).toList(), tracks.take(2).toList(), playlists.take(3).toList()]);
  final extras = interleave([albums.skip(3).toList(), tracks.skip(2).toList(), playlists.skip(3).toList()]);
  final seen = <String>{};
  final cards = [for (final c in [...primary, ...extras]) if (seen.add(c.stableId)) c];
  final sorted = stableSortedArtlessLast(cards, (c) => c.cover);
  return limit == null ? sorted : sorted.take(limit).toList();
}

List<T> distinctBy<T>(Iterable<T> items, Object Function(T) key) {
  final seen = <Object>{};
  return [for (final i in items) if (seen.add(key(i))) i];
}

/// Pool sizes (fetchAndPersistGridPools).
const int gridAlbumFetch = 24;
const int gridAlbumPool = 14;
const int gridSongFetch = 16;
const int gridSongPool = 10;
const int gridPlaylistPool = 10;

List<Album> albumPoolFrom(List<Album> random, math.Random rng) =>
    (distinctBy(random, (a) => a.id)..shuffle(rng)).take(gridAlbumPool).toList();

List<Track> trackPoolFrom(List<Track> tracks, math.Random rng, {required bool shuffle}) {
  final distinct = distinctBy(tracks, (t) => t.id);
  if (shuffle) distinct.shuffle(rng);
  return distinct.take(gridSongPool).toList();
}

List<Playlist> playlistPoolFrom(List<Playlist> playlists, math.Random rng) =>
    stableSortedArtlessLast(distinctBy(playlists, (p) => p.id)..shuffle(rng), (p) => p.coverArt).take(gridPlaylistPool).toList();

// ---------------------------------------------------------------------------
// Recently Added / Played / playlists.

const int recentlyAddedMaxTracks = 16;
const int recentlyAddedMaxAlbums = 20;
const Duration recentlyAddedWindow = Duration(days: 30);
const int homeRecentlyPlayedLimit = 20;
const int homePlaylistLimit = 20;

final RegExp _offsetSuffix = RegExp(r'([+-]\d{2}:?\d{2}|Z)$', caseSensitive: false);

/// parseAddedAtMillis: ISO instant with Z, offset date-time, zone-less
/// date-time (read as UTC) and date-only; null when unparseable.
int? parseAddedAtMs(String? raw) {
  final value = raw?.trim();
  if (value == null || value.isEmpty) return null;
  String normalized;
  if (!value.contains('T') && !value.contains(' ')) {
    normalized = '${value}T00:00:00Z';
  } else {
    final timePart = value.substring(value.indexOf(RegExp('[T ]')) + 1);
    normalized = _offsetSuffix.hasMatch(timePart) ? value : '${value}Z';
  }
  return DateTime.tryParse(normalized)?.millisecondsSinceEpoch;
}

/// Starred items within the last 30 days, newest first, distinct, capped.
/// [starredAt] reads the star timestamp (see the model note in the report).
List<T> recentlyAddedFrom<T>(
  List<T> items, {
  required int nowMs,
  required String? Function(T) starredAt,
  required Object Function(T) id,
  required int cap,
}) {
  final windowStart = nowMs - recentlyAddedWindow.inMilliseconds;
  final dated = <(int, T)>[
    for (final i in items)
      if (parseAddedAtMs(starredAt(i)) case final ms? when ms >= windowStart) (ms, i),
  ]..sort((a, b) => b.$1.compareTo(a.$1));
  return distinctBy([for (final d in dated) d.$2], id).take(cap).toList();
}

// ---------------------------------------------------------------------------
// Geometry (the density rules themselves: home_feed_density.dart).

/// The feed frame for a container: its live margins and content column, and
/// the discrete [density] read from its resting width.
final class HomeFeedFrame {
  const HomeFeedFrame({required this.start, required this.content, required this.end, required this.density});

  final double start;
  final double content;
  final double end;
  final HomeFeedDensity density;

  /// A landscape handset (height < 480).
  bool get landscape => density.compactHeight;

  /// Feed units N and phone-sized cover columns K (resting).
  int get units => density.units;
  int get coverColumns => density.coverColumns;

  double get itemSpacing => landscape ? HomeFeedMetrics.itemSpacingLandscape : HomeFeedMetrics.itemSpacing;

  @override
  bool operator ==(Object other) =>
      other is HomeFeedFrame && other.start == start && other.content == content && other.end == end && other.density == density;

  @override
  int get hashCode => Object.hash(start, content, end, density);

  @override
  String toString() => 'HomeFeedFrame($start, $content, $end, $density)';
}

/// The frame of a [width]-wide container (the LIVE width) whose resting
/// shape is [density]. [insets] overrides the class's margins while a frame
/// class change blends between two formulas.
HomeFeedFrame homeFeedFrame(double width, HomeFeedDensity density, {FeedFrameInsets? insets}) {
  final m = insets ?? feedFrameInsets(width, density.frameClass);
  final content = math.max(0.0, width - m.start - m.end);
  return HomeFeedFrame(start: m.start, content: content, end: math.max(0.0, width - m.start - content), density: density);
}

/// The bento recipe for a frame (units ≥ 3 → the units grid).
BentoRecipe bentoRecipeFor(HomeFeedFrame frame) => frame.landscape
    ? BentoRecipe.landscape
    : frame.units <= 1
        ? BentoRecipe.phoneNarrow
        : frame.units == 2
            ? BentoRecipe.phone
            : BentoRecipe.units;

/// Jump Back In's grid: columns, rows and the cover's side. [templated] =
/// the seeded lanes (home_jbi_template.dart); otherwise a packed grid.
final class JbiGeometry {
  const JbiGeometry({
    required this.columns,
    required this.rows,
    required this.columnWidth,
    required this.coverSide,
    this.templated = false,
    this.landscape = false,
  });
  final int columns;
  final int rows;
  final double columnWidth;
  final double coverSide;
  final bool templated;

  /// The landscape handset's six-across shelf (its own row presets: 1 / 1 / 2 / 3).
  final bool landscape;
  int get cells => columns * rows;
}

/// jbiGridSpec: compact height min(K, 6) columns at ≤ 100 — the landscape
/// handset's six across × 2 rows, a short window too narrow for six the
/// phone's 4 rows (and the phone's row presets, jbiPresetRows); N ≤ 2 the
/// phone's 3 × 4 at ≤ 100; N ≥ 3 K templated lanes × [jbiTemplateRows],
/// covers 100–128 following the live column.
JbiGeometry jbiGeometry(HomeFeedFrame frame) {
  double columnWidth(int k) => (frame.content - HomeTokens.jbiColumnGap * (k - 1)) / k;
  if (frame.landscape) {
    final k = math.min(frame.coverColumns, HomeJbiMetrics.landscapeMaxColumns);
    final w = columnWidth(k);
    final sixAcross = k >= HomeJbiMetrics.landscapeMaxColumns;
    return JbiGeometry(
      columns: k,
      rows: sixAcross ? HomeJbiMetrics.landscapeRows : HomeJbiMetrics.phoneRows,
      columnWidth: w,
      coverSide: math.min(w, HomeTokens.jbiCover),
      landscape: sixAcross,
    );
  }
  if (frame.units <= 2) {
    final w = columnWidth(HomeJbiMetrics.phoneColumns);
    return JbiGeometry(
      columns: HomeJbiMetrics.phoneColumns,
      rows: HomeJbiMetrics.phoneRows,
      columnWidth: w,
      coverSide: math.min(w, HomeTokens.jbiCover),
    );
  }
  final k = frame.coverColumns;
  final w = columnWidth(k);
  return JbiGeometry(columns: k, rows: jbiTemplateRows(k), columnWidth: w, coverSide: jbiTemplateCoverSide(w), templated: true);
}
