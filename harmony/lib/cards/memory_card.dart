/// The Memory service card (Android widget/MemoryWidget.kt, the "Memory"
/// family; Figma decided Play + Memory): your album memories on the home
/// screen — the score and how long since you last heard it lead, album and
/// artist follow. Dart picks and words the memories and fetches the covers;
/// the ArkTS host (yoin/cards) stores them and refreshes every placed card.
/// A tap opens Memories on that memory (handled by [PlayCardPublisher], the
/// one listener of card taps).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/album_memory_candidate.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../memories/copy/memory_copy.dart';
import '../memories/home_memory_pill.dart';
import '../platform/cards/cards_channel.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/artwork_retry.dart';

/// Memories the card holds (Android memoryItems: the timeline shows up to a dozen; the card's widest row fits 4).
const memoryCardLimit = 8;

/// Cover edge requested for the card (Android loads 128dp).
const memoryCardCoverPx = 320;

/// The card's memories: one per album, the most recently heard first
/// (Android sorts by the last real play, then the first). [candidates] come
/// strength-ranked; ties keep that order.
List<AlbumMemoryCandidate> selectMemoryCandidates(List<AlbumMemoryCandidate> candidates, {int limit = memoryCardLimit}) {
  final seen = <String>{};
  final unique = [
    for (final c in candidates)
      if (seen.add(c.albumId.toString())) c,
  ];
  int heard(AlbumMemoryCandidate c) => c.plays?.lastPlayedAt ?? c.lastPlayedAt ?? c.firstPlayedAt ?? 0;
  final order = {for (var i = 0; i < unique.length; i++) unique[i]: i};
  unique.sort((a, b) {
    final newer = heard(b).compareTo(heard(a));
    return newer != 0 ? newer : order[a]!.compareTo(order[b]!);
  });
  return unique.take(limit).toList();
}

/// The memory's score: the album's own, else the tracks' average; null when unrated.
String? memoryCardScore(AlbumMemoryCandidate c) {
  final score = c.albumRating ?? c.averageSongRating;
  return score == null || score <= 0 ? null : MemoryScores.text(score);
}

/// "Today", "3 Days Ago", "2 Months Ago", "1 Year Ago" (Android
/// WidgetData.lastHeardText; strings_widget.xml widget_last_heard_*). Null
/// when it was never played in Yoin.
String? lastHeardText(int? lastHeardAtMs, int nowMs, {required bool zh}) {
  if (lastHeardAtMs == null) return null;
  final days = ((nowMs - lastHeardAtMs) < 0 ? 0 : nowMs - lastHeardAtMs) ~/ Duration.millisecondsPerDay;
  String unit(int n, String one, String other, String zhUnit) => zh ? '$n $zhUnit' : '$n ${n == 1 ? one : other}';
  if (days < 1) return zh ? '今天' : 'Today'; // widget_last_heard_today
  if (days < 31) return unit(days, 'Day Ago', 'Days Ago', '天前'); // widget_last_heard_days
  if (days < 365) {
    final months = days ~/ 30 < 1 ? 1 : days ~/ 30;
    return unit(months, 'Month Ago', 'Months Ago', '个月前'); // widget_last_heard_months
  }
  return unit(days ~/ 365, 'Year Ago', 'Years Ago', '年前'); // widget_last_heard_years
}

/// Keeps the Memory card in step with the active account's memories (the
/// same pool the Home bubble reads).
final class MemoryCardPublisher {
  MemoryCardPublisher(
    this._ref,
    this._host, {
    Future<Uint8List?> Function(String url)? coverBytes,
    int Function()? nowMs,
  }) : _coverBytes = coverBytes ?? loadArtworkBytes,
       _nowMs = nowMs ?? (() => DateTime.now().millisecondsSinceEpoch);

  final Ref _ref;
  final CardsHost _host;

  /// A cover's bytes through the app's cover cache, so the card keeps them offline.
  final Future<Uint8List?> Function(String url) _coverBytes;
  final int Function() _nowMs;

  Timer? _debounce;
  String? _lastSignature;
  int _generation = 0;

  /// The state last scheduled, and whether a cover was missing from the last
  /// publish: a failed cover asks again when failed artwork does.
  HomeMemoryPillState? _lastState;
  bool _coversMissing = false;

  void _retryCovers() {
    final state = _lastState;
    if (!_coversMissing || state == null) return;
    _lastSignature = null;
    _schedule(state);
  }

  void start() {
    ArtworkRetrySignal.generation.addListener(_retryCovers);
    _ref.listen<HomeMemoryPillState>(homeMemoryPillProvider, (_, next) => _schedule(next), fireImmediately: true);
  }

  void dispose() {
    ArtworkRetrySignal.generation.removeListener(_retryCovers);
    _debounce?.cancel();
  }

  void _schedule(HomeMemoryPillState state) {
    if (!state.resolved) return;
    _lastState = state;
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 800), () => _publish(state));
  }

  Future<void> _publish(HomeMemoryPillState state) async {
    final generation = ++_generation;
    MusicSource? source;
    try {
      source = await _ref.read(activeSourceProvider.future);
    } catch (_) {
      source = null;
    }
    final pool = [?state.latest, ...state.recall];
    final chosen = source == null ? const <AlbumMemoryCandidate>[] : selectMemoryCandidates(pool);
    final now = _nowMs();
    final zh = PlatformDispatcher.instance.locale.languageCode == 'zh';
    final worded = [
      for (final c in chosen)
        (
          candidate: c,
          score: memoryCardScore(c),
          lastHeard: lastHeardText(c.plays?.lastPlayedAt, now, zh: zh),
        ),
    ];
    final signature = [
      source?.provider,
      for (final w in worded)
        '${w.candidate.sessionId}|${w.candidate.albumName}|${w.candidate.artistName}|${w.score}|${w.lastHeard}|'
            '${w.candidate.coverArt?.storageKey}',
    ].join('\n');
    if (signature == _lastSignature) return;

    final covers = await Future.wait([for (final w in worded) _cover(source!, w.candidate.coverArt)]);
    if (generation != _generation) return; // a newer change is on its way
    final items = [
      for (var i = 0; i < worded.length; i++)
        MemoryCardItem(
          sessionId: worded[i].candidate.sessionId,
          album: worded[i].candidate.albumName,
          artist: worded[i].candidate.artistName,
          score: worded[i].score,
          lastHeard: worded[i].lastHeard,
          cover: covers[i],
        ),
    ];
    _coversMissing = [
      for (var i = 0; i < worded.length; i++) worded[i].candidate.coverArt != null && covers[i] == null,
    ].any((m) => m);
    try {
      await _host.publishMemory(items);
      _lastSignature = signature;
    } on MissingPluginException {
      _lastSignature = signature;
    } catch (e) {
      debugPrint('cards: memory publish failed: $e');
    }
  }

  Future<Uint8List?> _cover(MusicSource source, CoverRef? cover) async {
    if (cover == null) return null;
    final url = source.resolveCoverUrl(cover, size: memoryCardCoverPx);
    if (url == null) return null;
    return _coverBytes(url);
  }
}

/// The one Memory card publisher; started with the Play card's
/// ([playCardPublisherProvider] reads it).
final memoryCardPublisherProvider = Provider<MemoryCardPublisher>((ref) {
  final publisher = MemoryCardPublisher(ref, ref.read(cardsHostProvider));
  publisher.start();
  ref.onDispose(publisher.dispose);
  return publisher;
});
