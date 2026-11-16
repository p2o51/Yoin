/// Home's Lyric Cards, the view model (Android ui/home/HomeLyricHighlight.kt
/// LyricHighlightViewModel): a few songs' lines at a time, playing one from
/// its line, the section's first-use intro, and keeping the user's own pick
/// from the lyrics page (LyricHighlightProcessor.keepUserPick).
///
/// The section is on every phone (owner 2026-11-08): Gemini Nano's picks where
/// it runs, and everywhere the lines the user shared or saved. HarmonyOS has no
/// Gemini Nano, so it shows the user's picks only, and its intro is Android's
/// "this phone can't run it" one (LyricHighlightIntro.Unsupported); Nano's
/// opt-in, its test log and Yoin Labs' switch are a platform limit.
library;

import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/highlight/lyric_highlight.dart';
import '../data/model/model.dart';
import '../playback/playback_controller.dart';
import 'home_section.dart';
import 'home_view_model.dart' show homeClockProvider;

/// The most lines the section can show (its largest setting; MAX_CARDS).
const int lyricHighlightMaxCards = 4;

/// The fewest cards the section's setting allows (LyricMinCount).
const int lyricHighlightMinCards = 2;

/// Coming back to Home after this long shows another set (ROTATE_AFTER_MS).
const Duration lyricHighlightRotateAfter = Duration(minutes: 20);

/// Land a breath before the line, so its first word isn't clipped (SEEK_LEAD_MS).
const int lyricHighlightSeekLeadMs = 600;

/// How long a pick or a play waits for the account's source (SOURCE_WAIT_MS).
const Duration _sourceWait = Duration(seconds: 4);

/// The section's setting as a number of cards (owner 2026-11-08: two to
/// four, four by default; HomeRowPreset.lyricCards). XL is never written; a
/// stored one reads as four.
int lyricCardsFor(HomeRowPreset preset) => switch (preset) {
  HomeRowPreset.s => 2,
  HomeRowPreset.m => 3,
  HomeRowPreset.l || HomeRowPreset.xl => 4,
};

/// The preset a count of cards is stored as (HomeLyricCountHandle presetFor).
HomeRowPreset lyricPresetFor(int count) => switch (count) {
  <= lyricHighlightMinCards => HomeRowPreset.s,
  3 => HomeRowPreset.m,
  _ => HomeRowPreset.l,
};

/// The highlight on Home: the picked lines, its song, and why it was chosen (HomeLyricHighlight).
@immutable
final class HomeLyricHighlight {
  const HomeLyricHighlight({
    required this.songId,
    required this.title,
    required this.artist,
    required this.album,
    required this.albumId,
    required this.coverKey,
    required this.durationMs,
    required this.text,
    required this.startMs,
    required this.kind,
  });

  factory HomeLyricHighlight.fromRow(String provider, LyricHighlightRow row, LyricHighlightBucket? kind) =>
      HomeLyricHighlight(
        songId: MediaId(provider, row.songId),
        title: row.title,
        artist: row.artist,
        album: row.album,
        albumId: row.albumId.trim().isEmpty ? null : MediaId(provider, row.albumId),
        coverKey: row.coverArtId,
        durationMs: row.durationMs,
        text: row.text ?? '',
        startMs: row.startMs,
        kind: kind,
      );

  final MediaId songId;
  final String title;
  final String artist;
  final String album;
  final MediaId? albumId;

  /// A CoverRef storage key.
  final String? coverKey;
  final int durationMs;

  /// The original lines, joined by "\n".
  final String text;
  final int? startMs;
  final LyricHighlightBucket? kind;

  CoverRef? get cover => CoverRef.fromStorageKey(coverKey);

  Track asTrack() => Track(
    id: songId,
    title: title,
    artist: artist,
    album: album.trim().isEmpty ? null : album,
    albumId: albumId,
    coverArt: cover,
    durationSec: durationMs ~/ 1000 > 0 ? durationMs ~/ 1000 : null,
  );

  /// Where playing it starts (LyricHighlightViewModel.play): [lyricHighlightSeekLeadMs]
  /// before the line; the top without a synced line or one past the song's end.
  int get playFromMs {
    final start = startMs;
    if (start == null || start <= lyricHighlightSeekLeadMs) return 0;
    if (durationMs > 0 && start >= durationMs) return 0;
    return start - lyricHighlightSeekLeadMs;
  }

  @override
  bool operator ==(Object other) =>
      other is HomeLyricHighlight &&
      other.songId == songId &&
      other.title == title &&
      other.artist == artist &&
      other.album == album &&
      other.albumId == albumId &&
      other.coverKey == coverKey &&
      other.durationMs == durationMs &&
      other.text == text &&
      other.startMs == startMs &&
      other.kind == kind;

  @override
  int get hashCode => Object.hash(songId, title, artist, album, albumId, coverKey, durationMs, text, startMs, kind);
}

/// Why a row comes up. A pick of the user's own is [LyricHighlightBucket.user];
/// a Nano row would take its song's play-history kind on Android
/// (LyricHighlightSignals), but none is ever written on HarmonyOS.
LyricHighlightBucket? lyricHighlightBucketOf(LyricHighlightRow row) =>
    row.isUserPick ? LyricHighlightBucket.user : null;

/// [keep] plus new songs up to [lyricHighlightMaxCards] (LyricHighlightViewModel.pick),
/// with the songs newly put on show; null when nothing changed. A card of
/// another provider is dropped; the song playing now is passed over while
/// there are others.
({List<HomeLyricHighlight> cards, List<String> shown})? pickLyricHighlights({
  required List<LyricHighlightRow> rows,
  required String provider,
  required List<HomeLyricHighlight> keep,
  required int now,
  String? playingRawId,
  Random? random,
}) {
  final kept = [
    for (final c in keep)
      if (c.songId.provider == provider) c,
  ];
  final byId = {for (final r in rows) r.songId: r};
  final taken = {for (final c in kept) c.songId.rawId};
  final added = <HomeLyricHighlight>[];
  final shown = <String>[];
  while (kept.length + added.length < lyricHighlightMaxCards) {
    final pool = [
      for (final r in rows)
        if (!taken.contains(r.songId)) r,
    ];
    if (pool.isEmpty) break;
    final chosen = nextLyricHighlightToShow(
      [for (final r in pool) LyricHighlightShown(r.songId, r.lastShownAt)],
      bucketOf: (id) => byId[id] == null ? null : lyricHighlightBucketOf(byId[id]!),
      now: now,
      exclude: pool.length > 1 ? playingRawId : null,
      random: random,
    );
    if (chosen == null) break;
    final row = byId[chosen.songId]!;
    taken.add(row.songId);
    shown.add(row.songId);
    added.add(HomeLyricHighlight.fromRow(provider, row, lyricHighlightBucketOf(row)));
  }
  if (keep.isNotEmpty && added.isEmpty && kept.length == keep.length) return null;
  return (cards: [...kept, ...added], shown: shown);
}

/// The store the section and the lyrics page share.
final lyricHighlightStoreProvider = Provider<LyricHighlightStore>(
  (ref) => LyricHighlightStore(ref.watch(keyValueStoreProvider)),
);

/// The active account's id and provider, or null without one.
Future<({String profileId, String provider})?> _activeAccount(Ref ref) async {
  final source = await ref.read(activeSourceProvider.future).timeout(_sourceWait, onTimeout: () => null);
  if (source == null) return null;
  final profile = await ref.read(profileRepositoryProvider).activeProfile();
  if (profile == null) return null;
  return (profileId: profile.id, provider: source.provider);
}

/// Home's current highlights, a few songs at a time (LyricHighlightViewModel.cards):
/// up to [lyricHighlightMaxCards] different songs; Home shows as many as the
/// section's setting asks for.
class LyricHighlightCards extends Notifier<List<HomeLyricHighlight>> {
  int _generation = 0;
  int _drawnAt = 0;

  /// The display pick's dice; tests pin it.
  @visibleForTesting
  static Random? random;

  @override
  List<HomeLyricHighlight> build() {
    // A new set for each visit to Home (this provider's life) or a new
    // account; a highlight arriving only fills an empty place, so no line is
    // swapped out from under the reader.
    ref.watch(activeSourceProvider);
    final store = ref.watch(lyricHighlightStoreProvider);
    final sub = store.changes.listen((_) {
      if (state.length < lyricHighlightMaxCards) unawaited(_draw(keep: state));
    });
    ref.onDispose(sub.cancel);
    final generation = ++_generation;
    Future.microtask(() {
      if (ref.mounted && generation == _generation) unawaited(_draw(keep: const []));
    });
    return const [];
  }

  int get _nowMs => ref.read(homeClockProvider)().millisecondsSinceEpoch;

  /// Home came back into view (LyricHighlightViewModel.onHomeShown): a fresh
  /// set when the last one has been up a while.
  void onHomeShown() {
    if (_drawnAt == 0) return;
    final now = _nowMs;
    if (now - _drawnAt < lyricHighlightRotateAfter.inMilliseconds) return;
    // Taken now, so a second "shown" in the same return (app and page at once) draws no second set.
    _drawnAt = now;
    unawaited(_draw(keep: const []));
  }

  Future<void> _draw({required List<HomeLyricHighlight> keep}) async {
    final generation = _generation;
    try {
      final account = await _activeAccount(ref);
      if (!ref.mounted || generation != _generation) return;
      if (account == null) {
        state = const [];
        return;
      }
      final store = ref.read(lyricHighlightStoreProvider);
      final rows = await store.usable(account.profileId, account.provider);
      if (!ref.mounted || generation != _generation) return;
      final now = _nowMs;
      final picked = pickLyricHighlights(
        rows: rows,
        provider: account.provider,
        keep: keep,
        now: now,
        playingRawId: _playingRawId(),
        random: random,
      );
      if (picked == null) return;
      await store.markShown(account.profileId, account.provider, picked.shown, now);
      if (!ref.mounted || generation != _generation) return;
      if (keep.isEmpty) _drawnAt = now;
      state = picked.cards;
    } catch (_) {
      // Unreadable: the set on show stays.
    }
  }

  String? _playingRawId() {
    try {
      return ref.read(playbackControllerProvider).current?.id.rawId;
    } catch (_) {
      return null;
    }
  }

  /// Plays the song alone from the picked line (LyricHighlightViewModel.play);
  /// without a synced line it plays from the top. A card of another account's
  /// provider does nothing.
  Future<void> play(HomeLyricHighlight card) async {
    final source = await ref.read(activeSourceProvider.future).timeout(_sourceWait, onTimeout: () => null);
    if (!ref.mounted || source == null || source.provider != card.songId.provider) return;
    // Android starts the song, then seeks once it plays; the HarmonyOS
    // controller starts it at the line directly (as a note's moment does).
    await ref.read(playbackControllerProvider.notifier).playTracks([card.asTrack()], startPositionMs: card.playFromMs);
  }
}

final lyricHighlightCardsProvider = NotifierProvider<LyricHighlightCards, List<HomeLyricHighlight>>(
  LyricHighlightCards.new,
);

/// What the section's first-use intro says (LyricHighlightIntro). Android
/// also has Supported(ready) for a phone Gemini Nano runs on; HarmonyOS is
/// never one, so only [unsupported] is shown here.
enum LyricHighlightIntro {
  /// No Nano here: the section shows the lines the user keeps.
  unsupported,
}

/// The intro to show in the section, or null once it's been answered (or
/// before the answer was read).
class LyricHighlightIntroNotifier extends Notifier<LyricHighlightIntro?> {
  bool _dismissed = false;

  @override
  LyricHighlightIntro? build() {
    final store = ref.watch(lyricHighlightStoreProvider);
    Future.microtask(() async {
      final seen = await store.introSeen();
      if (!ref.mounted || _dismissed) return;
      state = seen ? null : LyricHighlightIntro.unsupported;
    });
    return null;
  }

  /// "Got it" (dismissIntro): put away for good, on this device.
  void dismiss() {
    _dismissed = true;
    state = null;
    unawaited(ref.read(lyricHighlightStoreProvider).markIntroSeen().catchError((Object _) {}));
  }
}

final lyricHighlightIntroProvider = NotifierProvider<LyricHighlightIntroNotifier, LyricHighlightIntro?>(
  LyricHighlightIntroNotifier.new,
);

/// Keeps lines the user shared or saved from the lyrics page as the song's
/// highlight (LyricHighlightProcessor.keepUserPick, owner 2026-11-08: they
/// liked them, so they are candidates too).
class LyricHighlightKeeper {
  LyricHighlightKeeper(this._ref);

  final Ref _ref;

  /// Marks the pick over whatever was there, the first
  /// [lyricHighlightMaxUserPickLines] lines from [startMs] (the first line's
  /// time). Android takes the song playing now; the HarmonyOS share sheet
  /// freezes the song with the lines, so [track] is passed in. Only for the
  /// active account's provider.
  Future<void> keepUserPick({required Track track, required List<String> lines, int? startMs}) async {
    final text = lyricHighlightUserPickText(lines);
    if (text.isEmpty) return;
    try {
      final account = await _activeAccount(_ref);
      if (account == null || account.provider != track.id.provider) return;
      final store = _ref.read(lyricHighlightStoreProvider);
      final previous = await store.get(account.profileId, account.provider, track.id.rawId);
      await store.upsert(
        account.profileId,
        account.provider,
        LyricHighlightRow(
          songId: track.id.rawId,
          title: track.title ?? '',
          artist: track.artist ?? '',
          album: track.album ?? '',
          albumId: track.albumId?.rawId ?? '',
          coverArtId: track.coverArt?.storageKey ?? previous?.coverArtId,
          durationMs: (track.durationSec ?? 0) * 1000,
          text: text,
          startMs: startMs,
          model: lyricHighlightUserPick,
          attemptedAt: _ref.read(homeClockProvider)().millisecondsSinceEpoch,
          lastShownAt: previous?.lastShownAt,
        ),
      );
    } catch (_) {
      // Storage down: the share went out all the same.
    }
  }
}

final lyricHighlightKeeperProvider = Provider<LyricHighlightKeeper>(LyricHighlightKeeper.new);
