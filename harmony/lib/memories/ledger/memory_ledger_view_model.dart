/// The memory ledger, kept fresh (Android ledger/MemoryLedgerViewModel.kt):
/// every write to a note, review, score or title reloads it, as does an
/// account switch. Reads are local (the account's MemoryStore, its track
/// ratings, its cached album details); nothing here writes.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/activity/activity.dart';
import '../../data/memory/memory_grid.dart';
import '../../data/memory/memory_ledger.dart';
import '../../data/memory/memory_store.dart';
import '../../data/model/model.dart';
import '../../detail/detail_cache.dart';
import '../../playback/local_ratings.dart';
import '../memories_view_model.dart';

/// Everything All Memories (and Home's Memories grid) show.
@immutable
final class MemoryLedgerState {
  const MemoryLedgerState({
    required this.entries,
    required this.stats,
    required this.grid,
    required this.today,
    required this.dayOf,
  });

  /// Newest first.
  final List<MemoryLedgerEntry> entries;
  final MemoryLedgerStats stats;
  final MemoryGrid grid;

  /// Today's calendar day (UTC midnight), and an instant's.
  final DateTime today;
  final DateTime Function(int epochMs) dayOf;

  bool get isEmpty => entries.isEmpty;

  static MemoryLedgerState of(
    List<MemoryLedgerEntry> entries, {
    required DateTime today,
    required DateTime Function(int epochMs) dayOf,
    int firstDayOfWeek = DateTime.monday,
  }) => MemoryLedgerState(
    entries: entries,
    stats: memoryLedgerStats(entries),
    grid: buildMemoryGrid(entries, today: today, dayOf: dayOf, firstDayOfWeek: firstDayOfWeek),
    today: today,
    dayOf: dayOf,
  );
}

/// The first day of the week: Sunday where the locale's calendar starts on
/// it (Android WeekFields.of(locale)), Monday elsewhere (zh-CN included).
final memoryLedgerFirstDayProvider = Provider<int>((ref) {
  const sundayFirst = {'US', 'CA', 'JP', 'TW', 'HK', 'IL', 'BR', 'MX', 'PH', 'KR', 'IN', 'ZA', 'AU'};
  final country = PlatformDispatcher.instance.locale.countryCode;
  return country != null && sundayFirst.contains(country) ? DateTime.sunday : DateTime.monday;
});

/// Albums looked up beyond the ones written about, to place a scored song
/// on its album (Android MAX_CANDIDATE_SCAN_SIZE): the most recently played.
const int memoryLedgerPlayedScan = 48;

class MemoryLedgerViewModel extends AsyncNotifier<MemoryLedgerState> {
  StreamSubscription<MemoryStoreChange>? _changes;
  Timer? _debounce;

  @override
  Future<MemoryLedgerState> build() async {
    ref.watch(activeSourceProvider);
    final store = await ref.watch(memoryStoreProvider.future);
    _changes?.cancel();
    _changes = store?.changes.listen((_) => _scheduleReload());
    ref.listen(localRatingsProvider, (previous, next) {
      if (previous != null && !identical(previous, next)) _scheduleReload();
    });
    ref.onDispose(() {
      _changes?.cancel();
      _debounce?.cancel();
    });
    return _load(store);
  }

  void _scheduleReload() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 150), () async {
      if (!ref.mounted) return;
      final store = await ref.read(memoryStoreProvider.future);
      final next = await AsyncValue.guard(() => _load(store));
      if (ref.mounted) state = next;
    });
  }

  Future<MemoryLedgerState> _load(MemoryStore? store) async {
    final now = ref.read(memoriesClockProvider)();
    final dayOf = ref.read(memoriesDayOfProvider);
    final firstDay = ref.read(memoryLedgerFirstDayProvider);
    if (store == null) return MemoryLedgerState.of(const [], today: dayOf(now), dayOf: dayOf, firstDayOfWeek: firstDay);
    final kv = ref.read(keyValueStoreProvider);
    final ratings = {...await LocalRatingStore(kv).read(store.profileId), ...ref.read(localRatingsProvider)};
    final rows = MemoryLedgerRows(
      albumRatings: await store.albumRatings(),
      songNotes: await store.songNotes(),
      titles: await store.titles(),
      trackRatings: ratings,
    );
    final plays = await store.albumPlays();
    final log = await ref.read(activityLogProvider.future);
    final activity = await log?.recent() ?? const <ActivityEvent>[];
    final provider = (await ref.read(activeSourceProvider.future))?.provider;
    final albums = await _albums(rows, plays, provider == null ? const [] : activity, provider ?? '');
    final tracks = <MediaId, LedgerKnownTrack>{};
    for (final (album, detail) in albums.values) {
      for (final t in detail?.tracks ?? const <Track>[]) {
        tracks.putIfAbsent(t.id, () => (album: album, title: t.title));
      }
    }
    final entries = buildMemoryLedger(rows, album: (id) => albums[id]?.$1, track: (id) => tracks[id]);
    return MemoryLedgerState.of(entries, today: dayOf(now), dayOf: dayOf, firstDayOfWeek: firstDay);
  }

  /// Every album the rows name, and the most recently played ones, with their
  /// cached details: names, artists and covers from the detail first, then
  /// from Yoin's own plays and visits.
  Future<Map<MediaId, (MemoryLedgerAlbum, Album?)>> _albums(
    MemoryLedgerRows rows,
    List<AlbumPlays> plays,
    List<ActivityEvent> activity,
    String provider,
  ) async {
    final ids = <MediaId>{
      ...rows.albumRatings.keys,
      ...rows.titles.keys,
      for (final n in rows.songNotes) ?n.albumId,
      for (final p in plays.take(memoryLedgerPlayedScan)) p.albumId,
    }.toList();
    final names = <MediaId, ({String name, String artist, String? cover})>{};
    for (final p in plays) {
      if (p.albumName.trim().isEmpty) continue;
      names.putIfAbsent(p.albumId, () => (name: p.albumName, artist: p.artistName, cover: p.coverArtKey));
    }
    for (final e in activity) {
      if (e.entityType != ActivityEntityType.album || e.title.trim().isEmpty) continue;
      final id = MediaId(provider, e.entityId);
      names.putIfAbsent(id, () => (name: e.title, artist: e.subtitle, cover: e.coverArtKey));
    }
    final cache = ref.read(detailCacheProvider);
    final details = List<Album?>.filled(ids.length, null);
    var next = 0;
    Future<void> worker() async {
      while (next < ids.length) {
        final i = next++;
        try {
          details[i] = cache.cachedAlbum(ids[i]) ?? await cache.album(ids[i]);
        } catch (_) {
          details[i] = null;
        }
      }
    }

    await Future.wait([for (var w = 0; w < 4; w++) worker()]);
    final out = <MediaId, (MemoryLedgerAlbum, Album?)>{};
    for (var i = 0; i < ids.length; i++) {
      final detail = details[i];
      final known = names[ids[i]];
      final name = detail?.name ?? known?.name;
      if (name == null || name.trim().isEmpty) continue;
      out[ids[i]] = (
        MemoryLedgerAlbum(
          albumId: ids[i],
          name: name,
          artist: detail?.artist ?? known?.artist ?? '',
          cover: detail?.coverArt ?? CoverRef.fromStorageKey(known?.cover),
        ),
        detail,
      );
    }
    return out;
  }
}

final memoryLedgerProvider = AsyncNotifierProvider<MemoryLedgerViewModel, MemoryLedgerState>(MemoryLedgerViewModel.new);
