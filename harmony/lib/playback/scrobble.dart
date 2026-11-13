/// Play reporting ("scrobble"): a pure port of Android player/scrobble
/// (ScrobbleRule, ScrobbleTracker, PendingScrobbles) plus the reporter that
/// talks to the active source and keeps an offline queue.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/storage/stores.dart';

/// When a play counts (the Last.fm rule Subsonic servers follow): the song
/// is longer than [minTrackMs] and was listened to for half its length or
/// [maxListenMs], whichever comes first.
@immutable
final class ScrobbleRule {
  const ScrobbleRule({this.minTrackMs = 30000, this.maxListenMs = 240000});
  final int minTrackMs;
  final int maxListenMs;

  /// Listening needed for a play of a song [durationMs] long; null when too
  /// short to count. An unknown length (≤ 0) waits the full [maxListenMs].
  int? thresholdMs(int durationMs) {
    if (durationMs <= 0) return maxListenMs;
    if (durationMs <= minTrackMs) return null;
    final half = durationMs ~/ 2;
    return half < maxListenMs ? half : maxListenMs;
  }
}

/// What the player shows now, for one song of one account. [playKey] tells
/// two plays of the same song apart (queue entry + repeat count).
@immutable
final class ScrobbleSnapshot {
  const ScrobbleSnapshot({
    required this.profileId,
    required this.provider,
    required this.songId,
    required this.playKey,
    required this.isPlaying,
    required this.positionMs,
    required this.durationMs,
  });

  final String profileId;
  final String provider;

  /// Raw id.
  final String songId;
  final String playKey;
  final bool isPlaying;
  final int positionMs;
  final int durationMs;
}

sealed class ScrobbleEvent {
  const ScrobbleEvent(this.profileId, this.provider, this.songId);
  final String profileId;
  final String provider;
  final String songId;
}

/// The song started playing: "now playing" (scrobble submission=false).
final class ScrobbleStarted extends ScrobbleEvent {
  const ScrobbleStarted(super.profileId, super.provider, super.songId);
}

/// Listened long enough: one play, dated when it started (submission=true).
final class ScrobbleSubmit extends ScrobbleEvent {
  const ScrobbleSubmit(super.profileId, super.provider, super.songId, this.startedAtMs);
  final int startedAtMs;
}

/// Follows the player one snapshot at a time and says what to report.
/// Listened time accrues on [monotonicMs] only while playing, so a seek
/// forward earns nothing and a pause does not count.
final class ScrobbleTracker {
  ScrobbleTracker({this.rule = const ScrobbleRule(), required this.monotonicMs, required this.wallMs});

  final ScrobbleRule rule;
  final int Function() monotonicMs;
  final int Function() wallMs;
  _Session? _session;

  /// [snapshot] is the player now; null when nothing countable plays.
  List<ScrobbleEvent> update(ScrobbleSnapshot? snapshot) {
    final now = monotonicMs();
    final events = <ScrobbleEvent>[];
    final current = _session;
    if (current != null && (snapshot == null || !current.sameKey(snapshot))) {
      current.accrue(now);
      current.checkDue(rule, events);
      _session = null;
    }
    if (snapshot == null) return events;
    final active = _session ??= _Session(snapshot);
    active.accrue(now);
    if (snapshot.durationMs > 0) active.durationMs = snapshot.durationMs;
    if (snapshot.isPlaying) {
      active.playingSinceMs = now;
      if (!active.announced) {
        active.announced = true;
        active.startedAtWallMs = wallMs();
        events.add(ScrobbleStarted(snapshot.profileId, snapshot.provider, snapshot.songId));
      }
    } else {
      active.playingSinceMs = null;
    }
    active.checkDue(rule, events);
    return events;
  }

  /// Listening left before the current song counts; null when it has, can't, or isn't playing.
  int? msUntilSubmit() {
    final active = _session;
    if (active == null || active.submitted || active.playingSinceMs == null) return null;
    final threshold = rule.thresholdMs(active.durationMs);
    if (threshold == null) return null;
    final listened = active.listenedMs + (monotonicMs() - active.playingSinceMs!);
    final left = threshold - listened;
    return left < 0 ? 0 : left;
  }
}

final class _Session {
  _Session(this.snapshot) : durationMs = snapshot.durationMs;
  final ScrobbleSnapshot snapshot;
  int startedAtWallMs = 0;
  bool announced = false;
  bool submitted = false;
  int listenedMs = 0;
  int? playingSinceMs;
  int durationMs;

  bool sameKey(ScrobbleSnapshot s) => s.profileId == snapshot.profileId && s.playKey == snapshot.playKey;

  void accrue(int now) {
    final since = playingSinceMs;
    if (since == null) return;
    final delta = now - since;
    listenedMs += delta < 0 ? 0 : delta;
    playingSinceMs = now;
  }

  void checkDue(ScrobbleRule rule, List<ScrobbleEvent> into) {
    if (submitted || !announced) return;
    final threshold = rule.thresholdMs(durationMs);
    if (threshold == null || listenedMs < threshold) return;
    submitted = true;
    into.add(ScrobbleSubmit(snapshot.profileId, snapshot.provider, snapshot.songId, startedAtWallMs));
  }
}

/// One play the server hasn't heard yet. [timeMs] is when it started (epoch ms).
@immutable
final class PendingScrobble {
  const PendingScrobble({required this.profileId, required this.provider, required this.songId, required this.timeMs});
  final String profileId;
  final String provider;
  final String songId;
  final int timeMs;

  Map<String, Object?> toJson() => {'p': profileId, 'v': provider, 's': songId, 't': timeMs};

  static PendingScrobble? fromJson(Object? json) {
    if (json is! Map) return null;
    final p = json['p'], s = json['s'], t = json['t'];
    if (p is! String || s is! String || t is! int) return null;
    final v = json['v'];
    return PendingScrobble(profileId: p, provider: v is String ? v : MediaId.providerSubsonic, songId: s, timeMs: t);
  }

  @override
  bool operator ==(Object other) =>
      other is PendingScrobble &&
      other.profileId == profileId &&
      other.provider == provider &&
      other.songId == songId &&
      other.timeMs == timeMs;

  @override
  int get hashCode => Object.hash(profileId, provider, songId, timeMs);
}

/// The offline queue's rules (Android PendingScrobbles), pure.
abstract final class PendingScrobbles {
  /// Months of offline listening; past this the oldest plays go first.
  static const maxPending = 2000;

  /// Plays sent per flush round.
  static const batchSize = 50;

  static const storeKey = 'scrobble.queue';

  static List<PendingScrobble> add(List<PendingScrobble> queue, PendingScrobble play) {
    if (queue.contains(play)) return queue;
    final next = [...queue, play];
    return next.length > maxPending ? next.sublist(next.length - maxPending) : next;
  }

  /// The oldest plays for [profileId]: a play only ever goes to the account it was heard on.
  static List<PendingScrobble> batchFor(List<PendingScrobble> queue, String profileId, {int size = batchSize}) =>
      queue.where((p) => p.profileId == profileId).take(size).toList();

  static List<PendingScrobble> remove(List<PendingScrobble> queue, Iterable<PendingScrobble> sent) {
    final gone = sent.toSet();
    return gone.isEmpty ? queue : [for (final p in queue) if (!gone.contains(p)) p];
  }

  static String encode(List<PendingScrobble> queue) => jsonEncode([for (final p in queue) p.toJson()]);

  /// An unreadable value reads as empty (losing the queue beats crashing).
  static List<PendingScrobble> decode(String? raw) {
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = jsonDecode(raw);
      if (list is! List) return [];
      return [for (final e in list) ?PendingScrobble.fromJson(e)];
    } on FormatException {
      return [];
    }
  }

  /// Subsonic error 10 (missing parameter) or 70 (not found): the server
  /// will never take this play, drop it. Anything else is retried later.
  static bool isRejected(Object error) => error is SourceServerException && (error.code == 10 || error.code == 70);
}

/// Where plays go: the active source when it belongs to [profileId], else null.
typedef ScrobbleTargetResolver = MusicPlayback? Function(String profileId);

/// Feeds a [ScrobbleTracker] and sends what it says: now playing (dropped on
/// failure) and plays (queued on disk, flushed in order; offline-safe).
/// Plain Dart, owned by PlaybackController; never held by a widget.
final class ScrobbleReporter {
  ScrobbleReporter({
    required this.tracker,
    required this.targetFor,
    this.store,
    this.log = _noLog,
  });

  final ScrobbleTracker tracker;
  final ScrobbleTargetResolver targetFor;
  final KeyValueStore? store;
  final void Function(String message) log;

  ScrobbleSnapshot? _last;
  Timer? _due;
  List<PendingScrobble>? _queue;
  Future<void> _tail = Future.value();
  bool _disposed = false;

  /// Call with every player change (null when nothing countable plays).
  void update(ScrobbleSnapshot? snapshot) {
    if (_disposed) return;
    _last = snapshot;
    _handle(tracker.update(snapshot));
    _schedule();
  }

  /// Sends queued plays of [profileId] (after a submit, on source change, on resume).
  Future<void> flush(String profileId) => _serial(() => _flush(profileId));

  /// The queue as stored (tests and diagnostics).
  Future<List<PendingScrobble>> pending() => _serial(() async => List.unmodifiable(await _load()));

  void dispose() {
    _disposed = true;
    _due?.cancel();
  }

  void _schedule() {
    _due?.cancel();
    final ms = tracker.msUntilSubmit();
    if (ms == null) return;
    // A little late beats a little early: the next update then counts it.
    _due = Timer(Duration(milliseconds: ms + 50), () => update(_last));
  }

  void _handle(List<ScrobbleEvent> events) {
    for (final event in events) {
      switch (event) {
        case ScrobbleStarted():
          final target = targetFor(event.profileId);
          if (target == null) continue;
          unawaited(target.reportNowPlaying(MediaId(event.provider, event.songId)).catchError((Object e) {
            log('now playing dropped: ${e.runtimeType}');
          }));
        case ScrobbleSubmit():
          final play = PendingScrobble(
            profileId: event.profileId,
            provider: event.provider,
            songId: event.songId,
            timeMs: event.startedAtMs,
          );
          _serial(() async {
            _queue = PendingScrobbles.add(await _load(), play);
            await _save();
            await _flush(event.profileId);
          }).ignore();
      }
    }
  }

  Future<T> _serial<T>(Future<T> Function() op) {
    final done = _tail.then((_) => op());
    _tail = done.then((_) {}, onError: (Object e) => log('scrobble op failed: ${e.runtimeType}'));
    return done;
  }

  Future<List<PendingScrobble>> _load() async {
    final cached = _queue;
    if (cached != null) return cached;
    String? raw;
    try {
      raw = await store?.read(PendingScrobbles.storeKey);
    } catch (e) {
      log('scrobble queue unreadable: ${e.runtimeType}');
    }
    return _queue = PendingScrobbles.decode(raw);
  }

  Future<void> _save() async {
    final queue = _queue ?? const [];
    try {
      await store?.write(PendingScrobbles.storeKey, queue.isEmpty ? null : PendingScrobbles.encode(queue));
    } catch (e) {
      log('scrobble queue not saved: ${e.runtimeType}');
    }
  }

  Future<void> _flush(String profileId) async {
    var queue = await _load();
    while (true) {
      final target = targetFor(profileId);
      if (target == null) return;
      final batch = PendingScrobbles.batchFor(queue, profileId);
      if (batch.isEmpty) return;
      final done = <PendingScrobble>[];
      Object? failure;
      for (final play in batch) {
        try {
          await target.submitPlay(MediaId(play.provider, play.songId), DateTime.fromMillisecondsSinceEpoch(play.timeMs));
          done.add(play);
        } catch (e) {
          if (PendingScrobbles.isRejected(e)) {
            log('play rejected by the server, dropped');
            done.add(play);
          } else {
            failure = e;
            break;
          }
        }
      }
      queue = _queue = PendingScrobbles.remove(await _load(), done);
      await _save();
      if (failure != null) {
        log('scrobble flush paused: ${failure.runtimeType}');
        return;
      }
    }
  }
}

void _noLog(String _) {}
