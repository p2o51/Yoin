/// The "别人怎么说" page's view model (Android NeoDbVoicesViewModel).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/neodb/neodb_voices.dart';
import 'neodb_providers.dart';

sealed class NeoDbVoicesUiState {
  const NeoDbVoicesUiState();
}

final class NeoDbVoicesLoading extends NeoDbVoicesUiState {
  const NeoDbVoicesLoading();
}

/// NeoDB has no item like this album: the page offers a search there.
final class NeoDbVoicesNotFound extends NeoDbVoicesUiState {
  const NeoDbVoicesNotFound(this.searchUrl);
  final String searchUrl;
}

final class NeoDbVoicesFailed extends NeoDbVoicesUiState {
  const NeoDbVoicesFailed();
}

@immutable
final class NeoDbVoicesContent extends NeoDbVoicesUiState {
  const NeoDbVoicesContent({
    required this.album,
    required this.voices,
    required this.count,
    this.references = const [],
    this.loadingMore = false,
    this.endReached = false,
    this.moreFailed = false,
  });

  final NeoDbAlbumVoices album;
  final List<NeoDbVoice> voices;

  /// Every comment and review on the item, not only the loaded ones.
  final int count;
  final List<NeoDbReferenceScore> references;
  final bool loadingMore;
  final bool endReached;

  /// The next page failed: the list ends in a retry.
  final bool moreFailed;

  NeoDbVoicesContent copyWith({
    List<NeoDbVoice>? voices,
    List<NeoDbReferenceScore>? references,
    bool? loadingMore,
    bool? endReached,
    bool? moreFailed,
  }) => NeoDbVoicesContent(
    album: album,
    voices: voices ?? this.voices,
    count: count,
    references: references ?? this.references,
    loadingMore: loadingMore ?? this.loadingMore,
    endReached: endReached ?? this.endReached,
    moreFailed: moreFailed ?? this.moreFailed,
  );
}

/// Which album the page is about.
typedef NeoDbVoicesQuery = ({String albumName, String? artistName});

class NeoDbVoicesViewModel extends Notifier<NeoDbVoicesUiState> {
  NeoDbVoicesViewModel(this.query);

  final NeoDbVoicesQuery query;
  int _nextPage = 1;
  int _loadGeneration = 0;
  bool _pageInFlight = false;

  @override
  NeoDbVoicesUiState build() {
    scheduleMicrotask(load);
    return const NeoDbVoicesLoading();
  }

  /// Loads from the start. One load at a time: Retry stays tappable while its
  /// message fades out, and a second tap replaces the first load.
  Future<void> load() async {
    final generation = ++_loadGeneration;
    _pageInFlight = false;
    _nextPage = 1;
    state = const NeoDbVoicesLoading();
    final service = ref.read(neoDbVoicesServiceProvider);
    bool current() => ref.mounted && generation == _loadGeneration;
    final NeoDbAlbumVoices? album;
    try {
      album = await service.findAlbum(query.albumName, query.artistName);
    } catch (_) {
      if (current()) state = const NeoDbVoicesFailed();
      return;
    }
    if (!current()) return;
    if (album == null) {
      final url = await service.searchUrl(query.albumName, query.artistName);
      if (current()) state = NeoDbVoicesNotFound(url);
      return;
    }
    final NeoDbVoicePage first;
    try {
      first = await service.voices(album.itemUuid, 1);
    } catch (_) {
      if (current()) state = const NeoDbVoicesFailed();
      return;
    }
    if (!current()) return;
    _nextPage = 2;
    state = NeoDbVoicesContent(
      album: album,
      voices: first.voices,
      count: first.count,
      endReached: first.page >= first.pages,
    );
    // The other catalogues come in after the words: they never hold the page back.
    final references = await service.referenceScores(album);
    if (!current()) return;
    final s = state;
    if (s is NeoDbVoicesContent) state = s.copyWith(references: references);
  }

  /// The next 20, when the list nears its end.
  Future<void> loadMore() async {
    final s = state;
    if (s is! NeoDbVoicesContent || s.loadingMore || s.endReached || _pageInFlight) return;
    _pageInFlight = true;
    final generation = _loadGeneration;
    final page = _nextPage;
    state = s.copyWith(loadingMore: true, moreFailed: false);
    try {
      final more = await ref.read(neoDbVoicesServiceProvider).voices(s.album.itemUuid, page);
      if (!ref.mounted || generation != _loadGeneration) return;
      final now = state;
      if (now is! NeoDbVoicesContent) return;
      _nextPage = page + 1;
      final known = {for (final v in now.voices) v.id};
      state = now.copyWith(
        voices: [...now.voices, ...more.voices.where((v) => !known.contains(v.id))],
        loadingMore: false,
        endReached: more.voices.isEmpty || more.page >= more.pages,
      );
    } catch (_) {
      if (!ref.mounted || generation != _loadGeneration) return;
      final now = state;
      if (now is NeoDbVoicesContent) state = now.copyWith(loadingMore: false, moreFailed: true);
    } finally {
      if (generation == _loadGeneration) _pageInFlight = false;
    }
  }
}

final neoDbVoicesViewModelProvider = NotifierProvider.autoDispose
    .family<NeoDbVoicesViewModel, NeoDbVoicesUiState, NeoDbVoicesQuery>(NeoDbVoicesViewModel.new);
