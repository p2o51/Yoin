/// Which album a song belongs to (Android data/local TrackAlbumLink +
/// TrackAlbumLinkDao, data/album TrackAlbumLinkEnricher and the ledger's
/// LedgerTrackResolver): a song note or score carries no album, and Yoin's
/// plays only know the songs played here. A score pulled from a Subsonic
/// server, or synced from another device, has nothing local to name its
/// album (owner 2026-11-12: 「歌曲评分 7.9 · 不知道是哪张专辑」 with a blank
/// cover). These links let such a song find its album for the Memory Ledger,
/// Home's Memories grid, Memories and Rediscover.
///
/// One JSON document per account on the key-value store (Android keys the
/// rows by (profileId, provider, trackId)), rows keyed by `provider:rawId`.
/// Unlike Android's ledger resolver, whose answers live for the process, every
/// answer is kept: a song is asked about once, ever. A link with no album is
/// a lookup that found none, and is not asked again.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';
import '../../playback/local_ratings.dart';
import '../model/model.dart';
import '../source/music_source.dart';
import 'memory_store.dart';

/// One song's album, learned from a lookup (Android TrackAlbumLink).
@immutable
final class TrackAlbumLink {
  const TrackAlbumLink({
    required this.trackId,
    this.albumId,
    this.source = sourceLookup,
    required this.updatedAt,
    this.title = '',
    this.albumName = '',
    this.artist = '',
    this.coverArtKey,
  });

  /// Android SOURCE_PLAY / SOURCE_DETAIL / SOURCE_IMPORT. HarmonyOS writes
  /// only [sourceLookup] (Android's "import": a lookup on the song's service);
  /// plays and album pages already name their songs' albums.
  static const sourcePlay = 'play';
  static const sourceDetail = 'detail';
  static const sourceLookup = 'import';

  final MediaId trackId;

  /// Null: the service doesn't know the song, or names no album.
  final MediaId? albumId;
  final String source;
  final int updatedAt;

  /// What the lookup said about the song and its album, so the ledger can
  /// name the album without reading it (Android LedgerTrack).
  final String title;
  final String albumName;
  final String artist;
  final String? coverArtKey;

  /// Whether the answer changes what a reader shows: an album, or a name
  /// alone (the song stops reading as unknown). Android LedgerTrack.isNews.
  bool get isNews => albumId != null || title.trim().isNotEmpty;

  /// [track] (null: the service doesn't know it) as a link for [trackId].
  factory TrackAlbumLink.answer(MediaId trackId, Track? track, {required int nowMs}) {
    final album = track?.albumId;
    return TrackAlbumLink(
      trackId: trackId,
      // An album of another service can't be this song's (a server's odd answer).
      albumId: album != null && album.provider == trackId.provider ? album : null,
      updatedAt: nowMs,
      title: track?.title?.trim() ?? '',
      albumName: track?.album?.trim() ?? '',
      artist: track?.artist?.trim() ?? '',
      coverArtKey: track?.coverArt?.storageKey,
    );
  }

  Map<String, Object?> toJson() => {
    'a': albumId?.rawId ?? '',
    's': source,
    'u': updatedAt,
    if (title.isNotEmpty) 't': title,
    if (albumName.isNotEmpty) 'al': albumName,
    if (artist.isNotEmpty) 'ar': artist,
    'c': ?coverArtKey,
  };

  static TrackAlbumLink? fromJson(MediaId trackId, Object? json) {
    if (json is! Map) return null;
    final a = json['a'];
    final u = json['u'];
    return TrackAlbumLink(
      trackId: trackId,
      albumId: a is String && a.isNotEmpty ? MediaId(trackId.provider, a) : null,
      source: (json['s'] as String?) ?? sourceLookup,
      updatedAt: u is num ? u.toInt() : 0,
      title: (json['t'] as String?) ?? '',
      albumName: (json['al'] as String?) ?? '',
      artist: (json['ar'] as String?) ?? '',
      coverArtKey: json['c'] as String?,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is TrackAlbumLink &&
      other.trackId == trackId &&
      other.albumId == albumId &&
      other.source == source &&
      other.updatedAt == updatedAt &&
      other.title == title &&
      other.albumName == albumName &&
      other.artist == artist &&
      other.coverArtKey == coverArtKey;

  @override
  int get hashCode => Object.hash(trackId, albumId, source, updatedAt, title, albumName, artist, coverArtKey);
}

/// One account's links on a [KeyValueStore]. Writes are serialised; [changes]
/// fires after a write that changed something.
final class TrackAlbumLinkStore {
  TrackAlbumLinkStore(this._store, {required this.profileId});

  static final _shared = Expando<Map<String, TrackAlbumLinkStore>>('TrackAlbumLinkStore.shared');

  /// [profileId]'s links on [store], the same instance every time (one cache,
  /// one write queue for every reader).
  static TrackAlbumLinkStore shared(KeyValueStore store, String profileId) =>
      (_shared[store] ??= {})[profileId] ??= TrackAlbumLinkStore(store, profileId: profileId);

  static String keyFor(String profileId) => 'track_album_links.v1.$profileId';

  final KeyValueStore _store;
  final String profileId;

  Map<MediaId, TrackAlbumLink>? _links;
  Future<void> _tail = Future.value();
  final _changes = StreamController<void>.broadcast();

  Stream<void> get changes => _changes.stream;

  /// Every link, answered or not, by song.
  Future<Map<MediaId, TrackAlbumLink>> links() async {
    await _tail;
    return Map.unmodifiable(await _load());
  }

  /// Adds or replaces [links] (Android upsertAll). Nothing new: no write.
  Future<void> putAll(Iterable<TrackAlbumLink> links) {
    final rows = links.toList();
    final done = _tail.then((_) async {
      final current = await _load();
      var changed = false;
      for (final link in rows) {
        if (current[link.trackId] == link) continue;
        current[link.trackId] = link;
        changed = true;
      }
      if (!changed) return;
      try {
        await _store.write(
          keyFor(profileId),
          jsonEncode({for (final e in current.entries) e.key.toString(): e.value.toJson()}),
        );
      } catch (_) {
        // Stored as it was: read it again next time.
        _links = null;
        rethrow;
      }
      if (!_changes.isClosed) _changes.add(null);
    });
    _tail = done.catchError((Object _) {});
    return done;
  }

  Future<Map<MediaId, TrackAlbumLink>> _load() async {
    if (_links != null) return _links!;
    final out = <MediaId, TrackAlbumLink>{};
    final raw = await _store.read(keyFor(profileId));
    if (raw != null && raw.isNotEmpty) {
      Object? decoded;
      try {
        decoded = jsonDecode(raw);
      } on FormatException {
        decoded = null;
      }
      if (decoded is Map) {
        decoded.forEach((key, value) {
          final id = MediaId.tryParse(key is String ? key : null);
          final link = id == null ? null : TrackAlbumLink.fromJson(id, value);
          if (link != null) out[id!] = link;
        });
      }
    }
    return _links = out;
  }
}

/// What one account knows of a song's album: the album, and the names a
/// lookup or a play gave with it.
@immutable
final class TrackAlbumFacts {
  const TrackAlbumFacts({
    required this.albumId,
    this.title = '',
    this.albumName = '',
    this.artist = '',
    this.coverArtKey,
  });

  final MediaId albumId;
  final String title;
  final String albumName;
  final String artist;
  final String? coverArtKey;
}

/// Every song of one account whose album is known, by song: a lookup's link
/// first, then a play in Yoin, then the album a note was written on (Android
/// MemoryLedgerDao: COALESCE(link, play)). [notes] are HarmonyOS's own:
/// its notes carry the album they were written on.
Map<MediaId, TrackAlbumFacts> trackAlbumIndex({
  Map<MediaId, TrackAlbumLink> links = const {},
  Iterable<TrackPlays> plays = const [],
  Iterable<SongNote> notes = const [],
}) {
  final out = <MediaId, TrackAlbumFacts>{};
  for (final link in links.values) {
    final album = link.albumId;
    if (album == null) continue;
    out[link.trackId] = TrackAlbumFacts(
      albumId: album,
      title: link.title,
      albumName: link.albumName,
      artist: link.artist,
      coverArtKey: link.coverArtKey,
    );
  }
  for (final play in plays) {
    final album = play.albumId;
    if (album == null || out.containsKey(play.trackId)) continue;
    out[play.trackId] = TrackAlbumFacts(
      albumId: album,
      title: play.title,
      albumName: play.album,
      artist: play.artist,
      coverArtKey: play.coverArtKey,
    );
  }
  for (final note in notes) {
    final album = note.albumId;
    if (album == null || out.containsKey(note.trackId)) continue;
    out[note.trackId] = TrackAlbumFacts(albumId: album, title: note.title, artist: note.artist);
  }
  return out;
}

/// One account's [trackAlbumIndex] as song → album, read from its stores.
Future<Map<MediaId, MediaId>> loadTrackAlbums(MemoryStore store, TrackAlbumLinkStore links) async {
  final index = trackAlbumIndex(
    links: await links.links(),
    plays: await store.trackPlays(),
    notes: await store.songNotes(),
  );
  return {for (final e in index.entries) e.key: e.value.albumId};
}

/// The songs of [provider] that were noted or scored (a cleared score is no
/// score) and whose album nothing knows: no link (a link with no album is a
/// lookup that found none, never retried), no play with an album, no note
/// written on one. The oldest note or score first, at most [limit]. Android
/// TrackAlbumLinkDao.getUnlinkedTracks.
List<MediaId> unlinkedTracks({
  required String provider,
  Iterable<SongNote> notes = const [],
  Map<MediaId, LocalRating> ratings = const {},
  Map<MediaId, TrackAlbumLink> links = const {},
  Iterable<TrackPlays> plays = const [],
  int limit = trackAlbumLinkBatch,
}) {
  final known = <MediaId>{
    ...links.keys,
    for (final p in plays)
      if (p.albumId != null) p.trackId,
    for (final n in notes)
      if (n.albumId != null) n.trackId,
  };
  final since = <MediaId, int>{};
  void consider(MediaId id, int at) {
    if (id.provider != provider || known.contains(id)) return;
    final was = since[id];
    if (was == null || at < was) since[id] = at;
  }

  for (final n in notes) {
    consider(n.trackId, n.createdAt);
  }
  for (final r in ratings.values) {
    if (r.rating != null) consider(r.trackId, r.updatedAt);
  }
  final ordered = since.entries.toList()
    ..sort((a, b) {
      final c = a.value.compareTo(b.value);
      return c != 0 ? c : a.key.toString().compareTo(b.key.toString());
    });
  return [for (final e in ordered.take(limit)) e.key];
}

/// Songs one enricher pass reads at a time (Android BATCH).
const int trackAlbumLinkBatch = 50;

/// A breath between a pass's lookups (Android SPACING_MS): a big batch
/// shouldn't crowd out the requests the user is waiting on.
const Duration trackAlbumLinkSpacing = Duration(milliseconds: 250);

/// Lookups in flight on a Subsonic server (LedgerTrackResolver Parallel).
const int trackAlbumServerParallel = 4;

/// A server's answers kept in one write (Android writes each row; one JSON
/// document per account here, so a big pass writes it a batch at a time).
const int trackAlbumServerBatch = 25;

/// A streaming service's songs one ledger pass asks about, newest first
/// (LedgerTrackResolver ServicePerPass), and the most a streaming account
/// answers for in one process (ServiceBudget).
const int trackAlbumServicePerPass = 20;
const int trackAlbumServiceBudget = 20;

/// One account as the linker reads and writes it.
final class TrackAlbumAccount {
  const TrackAlbumAccount({
    required this.profileId,
    required this.provider,
    required this.lookup,
    required this.memory,
    required this.links,
    required this.ratings,
  });

  final String profileId;

  /// [MediaId] provider token of the account's service.
  final String provider;
  final MusicTrackLookup lookup;
  final MemoryStore memory;
  final TrackAlbumLinkStore links;

  /// The account's track scores as they stand.
  final Future<Map<MediaId, LocalRating>> Function() ratings;
}

/// A song a ledger reader wants named, in its own account.
@immutable
final class TrackAlbumWanted {
  const TrackAlbumWanted(this.profileId, this.trackId);

  final String profileId;
  final MediaId trackId;

  String get key => '$profileId|$trackId';

  @override
  bool operator ==(Object other) =>
      other is TrackAlbumWanted && other.profileId == profileId && other.trackId == trackId;

  @override
  int get hashCode => Object.hash(profileId, trackId);
}

/// What a ledger pass brought: [answered] songs have an answer now, and
/// [found] says one of them named an album or a song.
typedef TrackAlbumPass = ({int answered, bool found});

/// Finds the albums of noted and scored songs, lazily and throttled
/// (Android TrackAlbumLinkEnricher + LedgerTrackResolver, one service).
///
/// The enricher ([kick]) works through the account in use's unlinked songs,
/// one at a time with a breath between, each answer stored as it comes, so a
/// pass that fails (offline, rate limited, signed out) ends and the next one
/// picks up where it stopped. Subsonic and Spotify only, as on Android;
/// Apple Music's songs reach their albums through plays and album pages.
///
/// The ledger ([resolve]) asks for the songs it still can't place: every song
/// on a Subsonic server (any account's own, a few at a time), and the first
/// few of a streaming service's (the account in use only), one at a time,
/// each account at most [serviceBudget] songs a process. A streaming
/// service's ledger lookups wait for the enricher's pass, so it hears one of
/// the two at a time.
class TrackAlbumLinker {
  TrackAlbumLinker({
    required this.activeAccount,
    required this.isActive,
    this.clock = _now,
    this.spacing = trackAlbumLinkSpacing,
    this.serviceBudget = trackAlbumServiceBudget,
  });

  /// The account in use, or null (none, or its service has no song lookup).
  final Future<TrackAlbumAccount?> Function() activeAccount;

  /// Whether [profileId] is still the account in use.
  final bool Function(String profileId) isActive;
  final int Function() clock;
  final Duration spacing;
  final int serviceBudget;

  static int _now() => DateTime.now().millisecondsSinceEpoch;

  Future<void>? _pass;
  bool _again = false;
  bool _disposed = false;

  /// One streaming-service ledger pass at a time, whoever asks.
  Future<void> _serviceLane = Future.value();

  /// How many songs each streaming account answered for in this process.
  final Map<String, int> _serviceAnswered = {};

  /// Asks for an enricher pass for the account in use: on foreground, on an
  /// account switch. A kick during a pass runs one more after it, so a switch
  /// mid-pass still gets the new account its own.
  void kick() {
    if (_disposed) return;
    _again = true;
    if (_pass != null) return;
    _pass = () async {
      try {
        while (_again && !_disposed) {
          _again = false;
          try {
            await runPass();
          } catch (e) {
            // A storage failure ends this pass like a network one.
            debugPrint('YoinLinks: pass failed: ${e.runtimeType}');
            return;
          }
        }
      } finally {
        _pass = null;
      }
    }();
  }

  /// Returns once no enricher pass is running.
  Future<void> awaitIdle() async {
    while (true) {
      final running = _pass;
      if (running == null) return;
      await running;
    }
  }

  /// Links as many of the account in use's songs as it can; returns how many
  /// it looked up.
  @visibleForTesting
  Future<int> runPass() async {
    final account = await activeAccount();
    if (account == null) return 0;
    if (account.provider != MediaId.providerSubsonic && account.provider != MediaId.providerSpotify) return 0;
    var looked = 0;
    while (!_disposed) {
      final batch = unlinkedTracks(
        provider: account.provider,
        notes: await account.memory.songNotes(),
        ratings: await account.ratings(),
        links: await account.links.links(),
        plays: await account.memory.trackPlays(),
      );
      if (batch.isEmpty) return looked;
      for (final trackId in batch) {
        // The account changed under the pass: the next pass belongs to the new one.
        if (_disposed || !isActive(account.profileId)) return looked;
        final Track? track;
        try {
          track = await account.lookup.getTrack(trackId);
        } catch (_) {
          return looked;
        }
        await _store(account, [TrackAlbumLink.answer(trackId, track, nowMs: clock())]);
        looked++;
        if (spacing > Duration.zero) await Future<void>.delayed(spacing);
      }
    }
    return looked;
  }

  /// Asks the songs' services about [wanted] ([accountFor] gives each
  /// account, null when it can't be asked). Every answer is stored.
  Future<TrackAlbumPass> resolve(
    List<TrackAlbumWanted> wanted, {
    required Future<TrackAlbumAccount?> Function(String profileId) accountFor,
  }) async {
    if (wanted.isEmpty || _disposed) return (answered: 0, found: false);
    final accounts = <String, TrackAlbumAccount?>{};
    for (final id in {for (final w in wanted) w.profileId}) {
      try {
        accounts[id] = await accountFor(id);
      } catch (_) {
        accounts[id] = null;
      }
    }
    final failed = <String>{};

    // The answer, or null: none (the account failed).
    Future<TrackAlbumLink?> lookUp(TrackAlbumWanted want) async {
      final account = accounts[want.profileId];
      if (account == null || failed.contains(want.profileId) || _disposed) return null;
      final Track? track;
      try {
        track = await account.lookup.getTrack(want.trackId);
      } catch (_) {
        // Offline, rate limited or signed out: the account's other songs would fail the same way.
        failed.add(want.profileId);
        return null;
      }
      return TrackAlbumLink.answer(want.trackId, track, nowMs: clock());
    }

    // Null: no answer (the account failed, or it couldn't be kept); else whether it was news.
    Future<bool?> ask(TrackAlbumWanted want) async {
      final link = await lookUp(want);
      if (link == null) return null;
      try {
        await _store(accounts[want.profileId]!, [link]);
      } catch (_) {
        return null;
      }
      return link.isNews;
    }

    final usable = [
      for (final w in {...wanted})
        if (accounts[w.profileId] case final a? when a.provider == w.trackId.provider) w,
    ];
    final servers = [
      for (final w in usable)
        if (w.trackId.provider == MediaId.providerSubsonic) w,
    ];
    final services = [
      for (final w in usable)
        if (w.trackId.provider != MediaId.providerSubsonic && isActive(w.profileId)) w,
    ];

    final results = <bool?>[];
    // A server of one's own: a few songs at a time. Its answers come fast, so
    // they are kept a batch at a time: one write of the account's links (and
    // one round of reloads) per batch, not per song.
    final answers = <(String, TrackAlbumLink)>[];
    var kept = 0;
    Future<void> keep() async {
      final batch = answers.sublist(kept);
      kept = answers.length;
      final byAccount = <String, List<TrackAlbumLink>>{};
      for (final (id, link) in batch) {
        (byAccount[id] ??= []).add(link);
      }
      for (final MapEntry(key: id, value: links) in byAccount.entries) {
        try {
          await _store(accounts[id]!, links);
          results.addAll([for (final l in links) l.isNews]);
        } catch (_) {
          // Not kept: asked again on a later pass.
          results.addAll(List<bool?>.filled(links.length, null));
        }
      }
    }

    var next = 0;
    Future<void> worker() async {
      while (next < servers.length) {
        final want = servers[next++];
        final link = await lookUp(want);
        if (link == null) {
          results.add(null);
          continue;
        }
        answers.add((want.profileId, link));
        if (answers.length - kept >= trackAlbumServerBatch) await keep();
      }
    }

    await Future.wait([for (var i = 0; i < trackAlbumServerParallel; i++) worker()]);
    await keep();

    if (services.isNotEmpty) {
      final lane = Completer<void>();
      final previous = _serviceLane;
      _serviceLane = lane.future;
      try {
        await previous;
        var requests = 0;
        for (final want in services.take(trackAlbumServicePerPass)) {
          if (failed.contains(want.profileId) || _disposed) break;
          // Answered by another reader while this one waited its turn.
          final already = (await accounts[want.profileId]!.links.links())[want.trackId];
          if (already != null) {
            results.add(already.isNews);
            continue;
          }
          if ((_serviceAnswered[want.profileId] ?? 0) >= serviceBudget) continue;
          await awaitIdle();
          if (requests++ > 0 && spacing > Duration.zero) await Future<void>.delayed(spacing);
          final answer = await ask(want);
          // A failed lookup taught nothing and isn't counted: the song is asked again.
          if (answer != null) _serviceAnswered.update(want.profileId, (n) => n + 1, ifAbsent: () => 1);
          results.add(answer);
        }
      } finally {
        lane.complete();
      }
    }
    return (answered: results.where((r) => r != null).length, found: results.any((r) => r == true));
  }

  Future<void> _store(TrackAlbumAccount account, List<TrackAlbumLink> links) async {
    await account.links.putAll(links);
    // A note that came without its song's name (synced, imported) gets it.
    for (final link in links) {
      if (link.title.isNotEmpty) {
        await account.memory.fillMissingSong(link.trackId, title: link.title, artist: link.artist);
      }
    }
  }

  void dispose() => _disposed = true;
}
