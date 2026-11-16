/// Home's Lyric Cards, the stored side (Android data/highlight/ and
/// data/local/LyricHighlight*.kt): one row per song and account, the lines
/// it shows and where they start, plus enough of the track to show and play
/// it without a network call.
///
/// On Android the rows come from two writers: Gemini Nano, which picks a
/// passage from songs the user plays (LyricHighlightProcessor), and the user,
/// whose lines shared or saved from the lyrics page are kept as a pick of
/// their own ([lyricHighlightUserPick], LyricHighlightProcessor.keepUserPick).
/// HarmonyOS has no Gemini Nano (nor AICore), so only the user's picks are
/// written here: the processing queue, the Nano budget, the model download
/// and Yoin Labs' switch are a platform limit, not ported. Every row
/// therefore carries [lyricHighlightUserPick], and the display pick
/// ([nextLyricHighlightToShow]) is Android's whole selector all the same.
///
/// Local only, as on Android: not synced, not backed up (a pick can always
/// be made again). One JSON object per account and provider on the
/// key-value store.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../../platform/storage/stores.dart';

/// [LyricHighlightRow.model] of a line the user picked themselves (LyricHighlightProcessor.USER_PICK).
const String lyricHighlightUserPick = 'user';

/// A pick keeps at most this many lines (MAX_USER_PICK_LINES).
const int lyricHighlightMaxUserPickLines = 4;

const int _dayMs = 24 * 60 * 60 * 1000;

/// One stored highlight (Android LyricHighlight): [songId] is the raw id.
@immutable
final class LyricHighlightRow {
  const LyricHighlightRow({
    required this.songId,
    required this.title,
    required this.artist,
    required this.album,
    required this.albumId,
    required this.coverArtId,
    required this.durationMs,
    required this.text,
    required this.startMs,
    required this.model,
    required this.attemptedAt,
    this.lyricsFingerprint,
    this.lastShownAt,
    this.artistId = '',
  });

  final String songId;
  final String title;
  final String artist;
  final String album;

  /// Blank when unknown.
  final String albumId;

  /// The song's artist (raw id), so the card's song opens its artist in Now
  /// Playing; HarmonyOS's own column (Android asks the service). Blank when
  /// unknown, as in rows kept before it.
  final String artistId;

  /// A CoverRef storage key.
  final String? coverArtId;
  final int durationMs;

  /// The lines joined with "\n"; null = tried, nothing usable (a Nano row; never written here).
  final String? text;

  /// Where the first line starts; null for unsynced lyrics (play from the top).
  final int? startMs;
  final String? lyricsFingerprint;

  /// [lyricHighlightUserPick] for the user's own pick; Nano's model name otherwise.
  final String? model;
  final int attemptedAt;
  final int? lastShownAt;

  bool get isUserPick => model == lyricHighlightUserPick;

  LyricHighlightRow shownAt(int at) => LyricHighlightRow(
    songId: songId,
    title: title,
    artist: artist,
    album: album,
    albumId: albumId,
    coverArtId: coverArtId,
    durationMs: durationMs,
    text: text,
    startMs: startMs,
    lyricsFingerprint: lyricsFingerprint,
    model: model,
    attemptedAt: attemptedAt,
    lastShownAt: at,
    artistId: artistId,
  );

  Map<String, Object?> toJson() => {
    't': title,
    'ar': artist,
    'al': album,
    'ai': albumId,
    if (artistId.isNotEmpty) 'ri': artistId,
    'c': coverArtId,
    'd': durationMs,
    'x': text,
    's': startMs,
    'f': lyricsFingerprint,
    'm': model,
    'a': attemptedAt,
    'ls': lastShownAt,
  };

  static LyricHighlightRow? fromJson(String songId, Object? json) {
    if (json is! Map || songId.isEmpty) return null;
    final a = json['a'];
    final d = json['d'];
    final s = json['s'];
    final ls = json['ls'];
    return LyricHighlightRow(
      songId: songId,
      title: (json['t'] as String?) ?? '',
      artist: (json['ar'] as String?) ?? '',
      album: (json['al'] as String?) ?? '',
      albumId: (json['ai'] as String?) ?? '',
      artistId: (json['ri'] as String?) ?? '',
      coverArtId: json['c'] as String?,
      durationMs: d is num ? d.toInt() : 0,
      text: json['x'] as String?,
      startMs: s is num ? s.toInt() : null,
      lyricsFingerprint: json['f'] as String?,
      model: json['m'] as String?,
      attemptedAt: a is num ? a.toInt() : 0,
      lastShownAt: ls is num ? ls.toInt() : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is LyricHighlightRow &&
      other.songId == songId &&
      other.title == title &&
      other.artist == artist &&
      other.album == album &&
      other.albumId == albumId &&
      other.artistId == artistId &&
      other.coverArtId == coverArtId &&
      other.durationMs == durationMs &&
      other.text == text &&
      other.startMs == startMs &&
      other.lyricsFingerprint == lyricsFingerprint &&
      other.model == model &&
      other.attemptedAt == attemptedAt &&
      other.lastShownAt == lastShownAt;

  @override
  int get hashCode => Object.hash(
    songId,
    title,
    artist,
    album,
    albumId,
    artistId,
    coverArtId,
    durationMs,
    text,
    startMs,
    lyricsFingerprint,
    model,
    attemptedAt,
    lastShownAt,
  );
}

/// The text a pick keeps (keepUserPick): the first [lyricHighlightMaxUserPickLines]
/// non-blank lines, trimmed, joined by "\n"; empty when none.
String lyricHighlightUserPickText(Iterable<String> lines) =>
    lines.map((l) => l.trim()).where((l) => l.isNotEmpty).take(lyricHighlightMaxUserPickLines).join('\n');

/// Why a highlight comes up (LyricHighlightSelector.Bucket); [user] is a line the user shared or saved.
enum LyricHighlightBucket { heavy, rediscover, fresh, user }

/// The display mix, in parts (LyricHighlightSelector.MIX).
const Map<LyricHighlightBucket, int> lyricHighlightMix = {
  LyricHighlightBucket.heavy: 5,
  LyricHighlightBucket.rediscover: 3,
  LyricHighlightBucket.fresh: 2,
  LyricHighlightBucket.user: 3,
};

/// A highlight shown within this window waits while others are available (RESHOW_COOLDOWN_MS).
const int lyricHighlightReshowCooldownMs = 7 * _dayMs;

/// A stored highlight as the display pick sees it (LyricHighlightSelector.Shown).
@immutable
final class LyricHighlightShown {
  const LyricHighlightShown(this.songId, this.lastShownAt);
  final String songId;
  final int? lastShownAt;
}

/// The highlight to show next (LyricHighlightSelector.nextToShow): draws a
/// kind by [lyricHighlightMix] among the kinds that have something to show,
/// then the least recently shown highlight of that kind; anything shown in the
/// last [lyricHighlightReshowCooldownMs] waits while something else is
/// available. [exclude] is the song playing right now.
LyricHighlightShown? nextLyricHighlightToShow(
  List<LyricHighlightShown> shown, {
  required LyricHighlightBucket? Function(String songId) bucketOf,
  required int now,
  String? exclude,
  Random? random,
}) {
  final rng = random ?? Random();
  final pool = [
    for (final s in shown)
      if (s.songId != exclude) s,
  ];
  if (pool.isEmpty) return null;
  final rested = [
    for (final s in pool)
      if (s.lastShownAt == null || now - s.lastShownAt! >= lyricHighlightReshowCooldownMs) s,
  ];
  final eligible = rested.isEmpty ? pool : rested;
  final byBucket = <LyricHighlightBucket?, List<LyricHighlightShown>>{};
  for (final s in eligible) {
    byBucket.putIfAbsent(bucketOf(s.songId), () => []).add(s);
  }
  final kinds = [
    for (final b in LyricHighlightBucket.values)
      if (byBucket[b]?.isNotEmpty ?? false) b,
  ];
  final List<LyricHighlightShown> group;
  if (kinds.isEmpty) {
    group = eligible;
  } else {
    final total = kinds.fold<int>(0, (sum, k) => sum + lyricHighlightMix[k]!);
    var roll = rng.nextInt(total);
    var kind = kinds.last;
    for (final k in kinds) {
      roll -= lyricHighlightMix[k]!;
      if (roll < 0) {
        kind = k;
        break;
      }
    }
    group = byBucket[kind]!;
  }
  // Never shown first, then the longest ago; a random pick among equals.
  final never = [
    for (final s in group)
      if (s.lastShownAt == null) s,
  ];
  if (never.isNotEmpty) return never[rng.nextInt(never.length)];
  final oldest = group.map((s) => s.lastShownAt!).reduce(min);
  final ties = [
    for (final s in group)
      if (s.lastShownAt == oldest) s,
  ];
  return ties[rng.nextInt(ties.length)];
}

/// The highlights per account (LyricHighlightDao), and whether the section's
/// first-use intro was answered (LyricHighlightProcessor's intro_seen, one
/// per device). Writes go one after another, so a pick and a "shown" mark
/// never overwrite each other.
class LyricHighlightStore {
  LyricHighlightStore(this._store);

  final KeyValueStore _store;
  Future<void> _queue = Future.value();
  final _changes = StreamController<void>.broadcast();

  static String keyFor(String provider, String profileId) => 'lyric_highlights.v1.$provider|$profileId';

  /// The intro was answered ("Got it"), on this device.
  static const String introSeenKey = 'lyric_highlights.intro_seen';

  /// A new highlight was kept (LyricHighlightProcessor.revision): a display can refresh.
  Stream<void> get changes => _changes.stream;

  Future<Map<String, LyricHighlightRow>> _read(String provider, String profileId) async {
    try {
      final raw = await _store.read(keyFor(provider, profileId));
      if (raw == null) return {};
      final json = jsonDecode(raw);
      if (json is! Map) return {};
      return {
        for (final row in json.entries.map((e) => LyricHighlightRow.fromJson('${e.key}', e.value)).nonNulls)
          row.songId: row,
      };
    } catch (_) {
      return {};
    }
  }

  Future<void> _write(String provider, String profileId, Map<String, LyricHighlightRow> rows) => _store.write(
    keyFor(provider, profileId),
    rows.isEmpty ? null : jsonEncode({for (final r in rows.values) r.songId: r.toJson()}),
  );

  Future<T> _serial<T>(Future<T> Function() task) {
    final run = _queue.then((_) => task());
    _queue = run.then<void>((_) {}, onError: (Object _) {});
    return run;
  }

  /// Usable highlights (text present) of [profileId]'s library on [provider] (LyricHighlightDao.usable).
  Future<List<LyricHighlightRow>> usable(String profileId, String provider) => _serial(() async {
    final rows = await _read(provider, profileId);
    return [
      for (final r in rows.values)
        if (r.text != null) r,
    ];
  });

  Future<LyricHighlightRow?> get(String profileId, String provider, String songId) =>
      _serial(() async => (await _read(provider, profileId))[songId]);

  /// Writes [row] over the song's earlier one; [notify] tells a display (a new highlight).
  Future<void> upsert(String profileId, String provider, LyricHighlightRow row, {bool notify = true}) async {
    await _serial(() async {
      final rows = await _read(provider, profileId);
      rows[row.songId] = row;
      await _write(provider, profileId, rows);
    });
    if (notify) _changes.add(null);
  }

  /// [songIds] were put on show at [at] (LyricHighlightDao.markShown).
  Future<void> markShown(String profileId, String provider, Iterable<String> songIds, int at) {
    final ids = songIds.toSet();
    if (ids.isEmpty) return Future.value();
    return _serial(() async {
      final rows = await _read(provider, profileId);
      var changed = false;
      for (final id in ids) {
        final row = rows[id];
        if (row == null) continue;
        rows[id] = row.shownAt(at);
        changed = true;
      }
      if (changed) await _write(provider, profileId, rows);
    });
  }

  Future<bool> introSeen() async {
    try {
      return await _store.read(introSeenKey) != null;
    } catch (_) {
      return false;
    }
  }

  Future<void> markIntroSeen() => _store.write(introSeenKey, '1');
}
