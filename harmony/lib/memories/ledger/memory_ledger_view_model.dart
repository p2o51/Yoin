/// The memory ledger, kept fresh (Android ledger/MemoryLedgerViewModel.kt):
/// every account's memories (owner 2026-11-08: every account counts as the
/// listener), the one in use first. Every write to a note, review, score or
/// title of any account reloads it, as does an account switch or an account
/// coming, going or being renamed. Reads are local (each account's
/// MemoryStore, its track ratings, its cached album details, the account in
/// use's detail cache); nothing here writes.
///
/// Another account's albums are named from its plays and visits and from
/// the details cached on disk while it was in use (Android getCachedAlbumFor:
/// never asked over the network here), and its covers come from its own
/// server (the deck's [OtherProfileSource.coverFor]).
///
/// A song note or score whose song Yoin never played, nor saw on an album it
/// loaded, is placed through the song's album link (track_album_links.dart).
/// Songs nothing places yet are asked about on their service once the ledger
/// shows (Android LedgerTrackResolver, runs while Home's grid reads the
/// ledger): every answer is kept, reloads the ledger, and the next pass asks
/// for what is still unknown, until a pass brings nothing.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/activity/activity.dart';
import '../../data/memory/memory_grid.dart';
import '../../data/memory/memory_ledger.dart';
import '../../data/memory/memory_store.dart';
import '../../data/memory/track_album_links.dart';
import '../../data/model/model.dart';
import '../../data/profile/profile.dart';
import '../../detail/detail_cache.dart';
import '../../platform/storage/observed_store.dart';
import '../../playback/local_ratings.dart';
import '../../settings/account_identity.dart';
import '../../settings/settings_view_model.dart';
import '../memories_ui_state.dart';
import '../memories_view_model.dart';
import '../other_profiles.dart';
import '../track_album_linker.dart';

/// An account the ledger has memories from (Android LedgerProfile): what an
/// entry's "From …" line shows, and whether its albums open from here.
@immutable
final class LedgerProfile {
  const LedgerProfile({
    required this.id,
    required this.name,
    required this.provider,
    this.shape = 0,
    this.isActive = false,
    this.canSwitch = false,
  });

  final String id;

  /// Its card's name (the username, else the stored name).
  final String name;

  /// [MediaId] provider token: the mark's service.
  final String provider;

  /// Its avatar shape, for the mark before its name.
  final int shape;
  final bool isActive;

  /// Another account Yoin could switch to (the deck's and Home's shelf's
  /// rule): its albums open after the switch notice. False for the account
  /// in use and for one that has to be fixed on its page first.
  final bool canSwitch;

  /// As the deck names another account.
  MemoryOtherProfile get asOther => MemoryOtherProfile(id: id, name: name, provider: provider, shape: shape);

  @override
  bool operator ==(Object other) =>
      other is LedgerProfile &&
      other.id == id &&
      other.name == name &&
      other.provider == provider &&
      other.shape == shape &&
      other.isActive == isActive &&
      other.canSwitch == canSwitch;

  @override
  int get hashCode => Object.hash(id, name, provider, shape, isActive, canSwitch);
}

/// Everything All Memories (and Home's Memories grid) show.
@immutable
final class MemoryLedgerState {
  const MemoryLedgerState({
    required this.entries,
    required this.stats,
    required this.grid,
    required this.today,
    required this.dayOf,
    this.profiles = const [],
    this.activeProfileId,
  });

  /// Newest first.
  final List<MemoryLedgerEntry> entries;
  final MemoryLedgerStats stats;
  final MemoryGrid grid;

  /// Today's calendar day (UTC midnight), and an instant's.
  final DateTime today;
  final DateTime Function(int epochMs) dayOf;

  /// The accounts with any memory, the one in use first.
  final List<LedgerProfile> profiles;

  /// The account in use; null with none.
  final String? activeProfileId;

  bool get isEmpty => entries.isEmpty;

  LedgerProfile? profile(String id) => profiles.where((p) => p.id == id).firstOrNull;

  /// The account an entry of [profileId] says it is from (Android FromLine):
  /// only another account's, and only while more than one account has memories.
  LedgerProfile? fromProfile(String profileId) {
    final owner = profile(profileId);
    return owner == null || profiles.length < 2 || owner.isActive ? null : owner;
  }

  /// Whether [album] is the account in use's ('' = no account named: one-account previews).
  bool isOwn(MemoryLedgerAlbum album) => album.profileId.isEmpty || album.profileId == activeProfileId;

  /// Whether tapping [album] does anything: the account in use's opens its
  /// page; another account's switches there first, when Yoin can switch.
  bool opens(MemoryLedgerAlbum? album) =>
      album != null && (isOwn(album) || (profile(album.profileId)?.canSwitch ?? false));

  static MemoryLedgerState of(
    List<MemoryLedgerEntry> entries, {
    required DateTime today,
    required DateTime Function(int epochMs) dayOf,
    int firstDayOfWeek = DateTime.monday,
    List<LedgerProfile> profiles = const [],
    String? activeProfileId,
  }) => MemoryLedgerState(
    entries: entries,
    stats: memoryLedgerStats(entries),
    grid: buildMemoryGrid(entries, today: today, dayOf: dayOf, firstDayOfWeek: firstDayOfWeek),
    today: today,
    dayOf: dayOf,
    profiles: profiles,
    activeProfileId: activeProfileId,
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
  final Map<String, StreamSubscription<MemoryStoreChange>> _changes = {};
  final Map<String, StreamSubscription<void>> _linkChanges = {};
  Timer? _debounce;
  late TrackAlbumLinker _linker;

  /// A pass of song lookups is out; the reload after it starts the next.
  bool _resolving = false;

  /// Another account's cached album details, read from disk once while this
  /// ledger lives (they don't change while that account isn't in use).
  final Map<String, Future<Album?>> _otherDetails = {};

  @override
  Future<MemoryLedgerState> build() async {
    ref.watch(activeSourceProvider);
    _linker = ref.read(trackAlbumLinkerProvider);
    final store = await ref.watch(memoryStoreProvider.future);
    ref.listen(localRatingsProvider, (previous, next) {
      if (previous != null && !identical(previous, next)) _scheduleReload();
    });
    // An account added, removed or renamed: its memories come, go or say a new name.
    ref.listen(otherProfileSourcesProvider, (previous, next) {
      if (previous?.value != null && !identical(previous!.value, next.value)) _scheduleReload();
    });
    ref.listen(accountsRevisionProvider, (_, _) => _scheduleReload());
    // Another account's song scores, written by cloud sync straight to its
    // store (the account in use's come through localRatingsProvider).
    final kv = ref.read(keyValueStoreProvider);
    final ownRatings = store == null ? null : LocalRatingStore.keyFor(store.profileId);
    final ratingWrites = kv is ObservedKeyValueStore
        ? kv.writes.listen((key) {
            if (key != ownRatings && _changes.keys.any((id) => key == LocalRatingStore.keyFor(id))) _scheduleReload();
          })
        : null;
    ref.onDispose(() {
      ratingWrites?.cancel();
      for (final s in _changes.values) {
        s.cancel();
      }
      _changes.clear();
      for (final s in _linkChanges.values) {
        s.cancel();
      }
      _linkChanges.clear();
      _debounce?.cancel();
      _otherDetails.clear();
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

  /// Every listed account's rows reload the ledger when written (a note on
  /// this device, or another device's through cloud sync).
  /// A song's album learned (here, by the enricher, or another reader) too.
  void _watchStores(Iterable<MemoryStore> stores) {
    final wanted = {for (final s in stores) s.profileId: s};
    for (final id in [..._changes.keys]) {
      if (!wanted.containsKey(id)) unawaited(_changes.remove(id)!.cancel());
    }
    for (final id in [..._linkChanges.keys]) {
      if (!wanted.containsKey(id)) unawaited(_linkChanges.remove(id)!.cancel());
    }
    final kv = ref.read(keyValueStoreProvider);
    for (final MapEntry(key: id, value: store) in wanted.entries) {
      _changes.putIfAbsent(id, () => store.changes.listen((_) => _scheduleReload()));
      _linkChanges.putIfAbsent(id, () => TrackAlbumLinkStore.shared(kv, id).changes.listen((_) => _scheduleReload()));
    }
  }

  /// Asks the songs' services about [wanted], one pass at a time; a pass that
  /// answered any reloads the ledger, which asks for what is left.
  void _resolve(List<TrackAlbumWanted> wanted, Map<String, TrackAlbumAccount? Function()> accounts) {
    if (_resolving || wanted.isEmpty) return;
    _resolving = true;
    unawaited(() async {
      TrackAlbumPass? pass;
      try {
        pass = await _linker.resolve(wanted, accountFor: (id) async => accounts[id]?.call());
      } catch (e) {
        debugPrint('YoinLedger: song lookups failed: ${e.runtimeType}');
      } finally {
        _resolving = false;
      }
      if (ref.mounted && pass != null && pass.answered > 0) _scheduleReload();
    }());
  }

  Future<MemoryLedgerState> _load(MemoryStore? store) async {
    final now = ref.read(memoriesClockProvider)();
    final dayOf = ref.read(memoriesDayOfProvider);
    final firstDay = ref.read(memoryLedgerFirstDayProvider);
    if (store == null) {
      _watchStores(const []);
      return MemoryLedgerState.of(const [], today: dayOf(now), dayOf: dayOf, firstDayOfWeek: firstDay);
    }
    final kv = ref.read(keyValueStoreProvider);
    final accounts = await _accounts(store.profileId);
    final stores = [store, for (final a in accounts.others) MemoryStore.shared(kv, a.profile.id)];
    _watchStores(stores);

    // The account in use: its live ratings over the stored ones, its log, its detail cache.
    final ratings = {...await LocalRatingStore(kv).read(store.profileId), ...ref.read(localRatingsProvider)};
    final log = await ref.read(activityLogProvider.future);
    final source = await ref.read(activeSourceProvider.future);
    final provider = source?.provider;
    final cache = ref.read(detailCacheProvider);
    final own = await _accountEntries(
      store,
      ratings: ratings,
      activity: provider == null ? const [] : await log?.recent() ?? const <ActivityEvent>[],
      provider: provider ?? '',
      detail: (id) async => cache.cachedAlbum(id) ?? await cache.album(id),
      // An album only a song's link names: what this device already holds of it, never a read.
      cachedDetail: (id) async => cache.cachedAlbum(id),
      cover: (ref) => ref,
    );
    final entries = [...own.entries];
    final wanted = [...own.wanted];
    final lookups = <String, TrackAlbumAccount? Function()>{
      if (source != null)
        store.profileId: () =>
            trackAlbumAccount(kv: kv, store: store, source: source, liveRatings: () => ref.read(localRatingsProvider)),
    };
    // The others: what this device kept of them, read only.
    final disk = ref.read(detailCacheStoreProvider);
    for (final other in accounts.others) {
      final id = other.profile.id;
      try {
        Future<Album?> detail(MediaId albumId) => _otherDetails.putIfAbsent('$id|$albumId', () async {
          if (disk == null) return null;
          return (await disk.readAlbum(id, albumId.toString()))?.value();
        });
        final read = await _accountEntries(
          MemoryStore.shared(kv, id),
          ratings: await LocalRatingStore(kv).read(id),
          activity: await StoredActivityLog(kv, profileId: id).recent(),
          provider: other.profile.provider,
          detail: detail,
          cachedDetail: detail,
          // A cover id needs that account's own server; none to ask: no cover.
          cover: (ref) => ref is CoverUrl ? ref : other.source?.coverFor(ref),
        );
        entries.addAll(read.entries);
        wanted.addAll(read.wanted);
        // Its own server answers for its songs (a streaming service only for the account in use).
        final lookup = other.source?.trackLookup;
        if (lookup != null) {
          lookups[id] = () => TrackAlbumAccount(
            profileId: id,
            provider: other.profile.provider,
            lookup: lookup,
            memory: MemoryStore.shared(kv, id),
            links: TrackAlbumLinkStore.shared(kv, id),
            ratings: () => LocalRatingStore(kv).read(id),
          );
        }
      } catch (e) {
        // That account adds nothing this time; the rest still show.
        debugPrint('YoinLedger: another account unreadable: ${e.runtimeType}');
      }
    }
    entries.sort(compareLedgerNewestFirst);
    _resolve(wanted, lookups);
    final withMemories = {for (final e in entries) e.profileId};
    final profiles = [
      if (withMemories.contains(store.profileId) && accounts.own != null) accounts.own!,
      for (final a in accounts.others)
        if (withMemories.contains(a.profile.id)) a.profile,
    ];
    return MemoryLedgerState.of(
      entries,
      today: dayOf(now),
      dayOf: dayOf,
      firstDayOfWeek: firstDay,
      profiles: profiles,
      activeProfileId: store.profileId,
    );
  }

  /// Every account still there (Android: only accounts that exist; a removed
  /// account's rows aren't anyone's memories now): the one in use, then the
  /// others oldest first, each with its card's name and mark, and its source
  /// when Yoin could switch to it.
  Future<({LedgerProfile? own, List<_OtherAccount> others})> _accounts(String activeId) async {
    final repo = ref.read(profileRepositoryProvider);
    List<Profile> profiles;
    try {
      profiles = await repo.profiles();
    } catch (_) {
      profiles = const [];
    }
    List<OtherProfileSource> sources;
    try {
      sources = await ref.read(otherProfileSourcesProvider.future);
    } catch (_) {
      sources = const [];
    }
    final byId = {for (final s in sources) s.id: s};
    final shapes = assignAvatarShapes([for (final p in profiles) p.id]);
    LedgerProfile? own;
    final others = <_OtherAccount>[];
    for (final p in profiles) {
      ProfileCredentials? credentials;
      try {
        credentials = await repo.credentials(p.id);
      } catch (_) {
        credentials = null;
      }
      final active = p.id == activeId;
      final card = accountCardFor(p, credentials, isActive: active, avatarShape: shapes[p.id] ?? 0);
      final source = active ? null : byId[p.id];
      final profile = LedgerProfile(
        id: p.id,
        name: card.title,
        provider: p.provider,
        shape: card.avatarShape,
        isActive: active,
        canSwitch: source != null && usableOtherAccounts([card]).isNotEmpty,
      );
      if (active) {
        own = profile;
      } else {
        others.add(_OtherAccount(profile, source));
      }
    }
    return (own: own, others: others);
  }

  /// One account's entries: its rows named against [detail] (an album's
  /// cached detail) and its own plays and visits, with covers through [cover],
  /// and the songs of its entries nothing places yet ([TrackAlbumWanted]: no
  /// album, never asked about). An album only a song's link names is read
  /// through [cachedDetail] (what is on this device) and else named by the
  /// link itself.
  Future<({List<MemoryLedgerEntry> entries, List<TrackAlbumWanted> wanted})> _accountEntries(
    MemoryStore store, {
    required Map<MediaId, LocalRating> ratings,
    required List<ActivityEvent> activity,
    required String provider,
    required Future<Album?> Function(MediaId id) detail,
    required Future<Album?> Function(MediaId id) cachedDetail,
    required CoverRef? Function(CoverRef ref) cover,
  }) async {
    final rows = MemoryLedgerRows(
      albumRatings: await store.albumRatings(),
      songNotes: await store.songNotes(),
      titles: await store.titles(),
      trackRatings: ratings,
    );
    final plays = await store.albumPlays();
    final links = await TrackAlbumLinkStore.shared(ref.read(keyValueStoreProvider), store.profileId).links();
    final index = trackAlbumIndex(links: links, plays: await store.trackPlays());
    // The albums of the noted and scored songs, as their links and plays name them.
    final linked = <MediaId, TrackAlbumFacts>{};
    void link(MediaId song) {
      final facts = index[song];
      if (facts == null) return;
      final was = linked[facts.albumId];
      if (was == null || (was.albumName.trim().isEmpty && facts.albumName.trim().isNotEmpty)) {
        linked[facts.albumId] = facts;
      }
    }

    for (final n in rows.songNotes) {
      if (!n.isBlank) link(n.trackId);
    }
    for (final r in rows.trackRatings.values) {
      if (r.rating != null) link(r.trackId);
    }
    final albums = await _albums(
      rows,
      plays,
      provider.isEmpty ? const [] : activity,
      provider,
      profileId: store.profileId,
      detail: detail,
      cachedDetail: cachedDetail,
      linked: linked,
      cover: cover,
    );
    final tracks = <MediaId, LedgerKnownTrack>{};
    for (final (album, d) in albums.values) {
      for (final t in d?.tracks ?? const <Track>[]) {
        tracks.putIfAbsent(t.id, () => (album: album, title: t.title));
      }
    }
    LedgerKnownTrack? track(MediaId id) {
      final known = tracks[id];
      if (known != null) return known;
      final facts = index[id];
      final named = facts == null ? null : albums[facts.albumId]?.$1;
      if (named == null) return null;
      return (album: named, title: facts!.title.trim().isEmpty ? null : facts.title);
    }

    final entries = buildMemoryLedger(rows, album: (id) => albums[id]?.$1, track: track, profileId: store.profileId);
    final wanted = <TrackAlbumWanted>[
      for (final e in entries)
        if (e.album == null &&
            (e.kind == MemoryLedgerKind.songNote || e.kind == MemoryLedgerKind.trackRating) &&
            e.songId != null &&
            !links.containsKey(e.songId))
          TrackAlbumWanted(store.profileId, e.songId!),
    ];
    return (entries: entries, wanted: wanted);
  }

  /// Every album the rows name, and the most recently played ones, with their
  /// cached details: names, artists and covers from the detail first, then
  /// from Yoin's own plays and visits, then from the songs' [linked] facts.
  /// The albums only [linked] names are read through [cachedDetail].
  Future<Map<MediaId, (MemoryLedgerAlbum, Album?)>> _albums(
    MemoryLedgerRows rows,
    List<AlbumPlays> plays,
    List<ActivityEvent> activity,
    String provider, {
    required String profileId,
    required Future<Album?> Function(MediaId id) detail,
    required Future<Album?> Function(MediaId id) cachedDetail,
    Map<MediaId, TrackAlbumFacts> linked = const {},
    required CoverRef? Function(CoverRef ref) cover,
  }) async {
    final scanned = <MediaId>{
      ...rows.albumRatings.keys,
      ...rows.titles.keys,
      for (final n in rows.songNotes) ?n.albumId,
      for (final p in plays.take(memoryLedgerPlayedScan)) p.albumId,
    };
    final ids = [
      ...scanned,
      for (final id in linked.keys)
        if (!scanned.contains(id)) id,
    ];
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
    for (final MapEntry(key: id, value: f) in linked.entries) {
      if (f.albumName.trim().isEmpty) continue;
      names.putIfAbsent(id, () => (name: f.albumName, artist: f.artist, cover: f.coverArtKey));
    }
    final details = List<Album?>.filled(ids.length, null);
    var next = 0;
    Future<void> worker() async {
      while (next < ids.length) {
        final i = next++;
        try {
          details[i] = await (scanned.contains(ids[i]) ? detail : cachedDetail)(ids[i]);
        } catch (_) {
          details[i] = null;
        }
      }
    }

    await Future.wait([for (var w = 0; w < 4; w++) worker()]);
    final out = <MediaId, (MemoryLedgerAlbum, Album?)>{};
    for (var i = 0; i < ids.length; i++) {
      final d = details[i];
      final known = names[ids[i]];
      final name = d?.name ?? known?.name;
      if (name == null || name.trim().isEmpty) continue;
      final ref = d?.coverArt ?? CoverRef.fromStorageKey(known?.cover);
      out[ids[i]] = (
        MemoryLedgerAlbum(
          albumId: ids[i],
          name: name,
          artist: d?.artist ?? known?.artist ?? '',
          cover: ref == null ? null : cover(ref),
          profileId: profileId,
        ),
        d,
      );
    }
    return out;
  }
}

/// Another account as the ledger reads it: who it is, and its source (covers,
/// switching) when Yoin could switch to it.
final class _OtherAccount {
  const _OtherAccount(this.profile, this.source);

  final LedgerProfile profile;
  final OtherProfileSource? source;
}

final memoryLedgerProvider = AsyncNotifierProvider<MemoryLedgerViewModel, MemoryLedgerState>(MemoryLedgerViewModel.new);
