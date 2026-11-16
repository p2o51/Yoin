/// Now Playing's About and Ask bar state (Android NowPlayingViewModel's About
/// part: aboutUiState, askState, onAboutOpened, retryFetchSongInfo,
/// askQuestion, regenerateAbout / undoAboutRegenerate). The rows themselves
/// live in the About table; this holds only what a table can't say — the
/// first fetch running, its error, the Ask bar, the rows being written again.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/about/song_about_repository.dart';
import '../data/about/song_about_store.dart';
import '../data/model/model.dart';
import '../playback/playback_controller.dart';
import 'about_logic.dart';

/// The song About is about: the metadata its rows are keyed by.
@immutable
final class AboutSong {
  const AboutSong(this.title, this.artist, this.album);

  final String title;
  final String artist;
  final String album;

  static AboutSong? of(Track? track) =>
      track == null ? null : AboutSong(track.title ?? '', track.artist ?? '', track.album ?? '');

  bool owns(SongAboutEntry row) =>
      row.titleKey == normalizeAboutKey(title) &&
      row.artistKey == normalizeAboutKey(artist) &&
      row.albumKey == normalizeAboutKey(album);

  @override
  bool operator ==(Object other) =>
      other is AboutSong && other.title == title && other.artist == artist && other.album == album;

  @override
  int get hashCode => Object.hash(title, artist, album);
}

@immutable
final class NowPlayingAbout {
  const NowPlayingAbout({
    this.song,
    this.about = const AboutIdle(),
    this.ask = const AskIdle(),
    this.regenerating = const {},
  });

  final AboutSong? song;
  final AboutUiState about;
  final AskBarState ask;

  /// Rows the AI is writing again (their dots).
  final Set<AboutRef> regenerating;

  /// The AI's work shows (the Now Playing wash): a question, or About's first fetch.
  bool get thinking => ask is AskLoading || about is AboutLoading;

  NowPlayingAbout copyWith({AboutUiState? about, AskBarState? ask, Set<AboutRef>? regenerating}) => NowPlayingAbout(
    song: song,
    about: about ?? this.about,
    ask: ask ?? this.ask,
    regenerating: regenerating ?? this.regenerating,
  );

  @override
  bool operator ==(Object other) =>
      other is NowPlayingAbout &&
      other.song == song &&
      other.about == about &&
      other.ask == ask &&
      setEquals(other.regenerating, regenerating);

  @override
  int get hashCode => Object.hash(song, about, ask, Object.hashAll(regenerating));
}

/// What a regenerate came to; the screen says it (with Undo after a success).
sealed class AboutRegenerateOutcome {
  const AboutRegenerateOutcome();
}

/// The row says something new; [undo] puts the old text back.
final class AboutRegenerated extends AboutRegenerateOutcome {
  const AboutRegenerated(this.ref, this.beforeTitle, this.beforeAnswer);
  final AboutRef ref;
  final String? beforeTitle;
  final String beforeAnswer;
}

final class AboutRegenerateNothing extends AboutRegenerateOutcome {
  const AboutRegenerateNothing();
}

final class AboutRegenerateKeyMissing extends AboutRegenerateOutcome {
  const AboutRegenerateKeyMissing();
}

final class AboutRegenerateFailed extends AboutRegenerateOutcome {
  const AboutRegenerateFailed();
}

class NowPlayingAboutNotifier extends Notifier<NowPlayingAbout> {
  StreamSubscription<List<SongAboutEntry>>? _rows;
  List<SongAboutEntry> _entries = const [];
  bool _loading = false;
  AboutUiState? _error;
  Future<void>? _canonicalFetch;
  int _canonicalGeneration = 0;
  int _askRequest = 0;

  @override
  NowPlayingAbout build() {
    final song = ref.watch(playbackControllerProvider.select((s) => AboutSong.of(s.current)));
    _rows?.cancel();
    _entries = const [];
    _loading = false;
    _error = null;
    // A new song: the transient overlays go (Android resets aboutError / askState on a song change).
    _canonicalGeneration++;
    _canonicalFetch = null;
    ref.onDispose(() => _rows?.cancel());
    final repo = _repoOrNull();
    if (song != null && repo != null) {
      _rows = repo.watch(song.title, song.artist, song.album).listen((rows) {
        _entries = rows;
        _publish();
      });
    }
    return NowPlayingAbout(song: song);
  }

  SongAboutRepository get _repo => ref.read(songAboutRepositoryProvider);

  /// The repository, or null where the app's storage isn't there (previews, harnesses): About stays idle.
  SongAboutRepository? _repoOrNull() {
    try {
      return _repo;
    } catch (_) {
      return null;
    }
  }

  void _publish({AskBarState? ask, Set<AboutRef>? regenerating}) {
    final AboutUiState about = switch (_error) {
      final e? => e,
      _ when _entries.isNotEmpty => AboutReady(_entries),
      _ when _loading => const AboutLoading(),
      _ => const AboutIdle(),
    };
    state = state.copyWith(about: about, ask: ask, regenerating: regenerating);
  }

  /// The user landed on About (compact or expanded): one fetch, unless the
  /// rows are cached or a fetch is already running (onAboutOpened).
  void onAboutOpened() {
    if (_canonicalFetch != null) return;
    _fetchCanonical(retry: false);
  }

  /// Retry / Load song info: asks again and writes over the cached rows (retryFetchSongInfo).
  void retry() => _fetchCanonical(retry: true);

  void _fetchCanonical({required bool retry}) {
    final song = state.song;
    if (_repoOrNull() == null) return;
    if (song == null || song.title.trim().isEmpty || song.artist.trim().isEmpty) return;
    final generation = ++_canonicalGeneration;
    _loading = true;
    _error = null;
    _publish();
    final run = () async {
      try {
        final result = await _repo.ensureCanonicalAbout(
          title: song.title,
          artist: song.artist,
          album: song.album,
          retry: retry,
        );
        if (!ref.mounted || generation != _canonicalGeneration) return;
        _error = switch (result) {
          AboutLoadSuccess() => null,
          AboutLoadKeyMissing() => const AboutKeyMissing(),
          AboutLoadError(:final message) => AboutError(message),
        };
      } finally {
        if (ref.mounted && generation == _canonicalGeneration) {
          _loading = false;
          _canonicalFetch = null;
          _publish();
        }
      }
    }();
    _canonicalFetch = run;
  }

  /// The Ask bar took focus.
  void onAskFocused() {
    if (state.ask is! AskLoading) state = state.copyWith(ask: const AskFocused());
  }

  /// The keyboard went away without a question.
  void onAskCollapse() {
    if (state.ask is AskFocused) state = state.copyWith(ask: const AskIdle());
  }

  void dismissAskError() {
    if (state.ask is AskFailed) state = state.copyWith(ask: const AskIdle());
  }

  /// Asks [question] about the song on show; the answer lands in the table
  /// and the rows show it. A short heading replaces the question on the bar
  /// as soon as the AI has one (askQuestion).
  void ask(String question) {
    final q = question.trim();
    final song = state.song;
    if (q.isEmpty || song == null || _repoOrNull() == null) return;
    final id = ++_askRequest;
    state = state.copyWith(ask: AskLoading(q, requestId: id));
    unawaited(() async {
      final title = await _repo.generateAskTitle(
        title: song.title,
        artist: song.artist,
        album: song.album,
        question: q,
      );
      final current = state.ask;
      if (title != null && ref.mounted && current is AskLoading && current.requestId == id) {
        state = state.copyWith(ask: AskLoading(title, requestId: id));
      }
    }());
    unawaited(() async {
      final result = await _repo.askAboutSong(title: song.title, artist: song.artist, album: song.album, question: q);
      if (!ref.mounted || state.song != song) return;
      state = state.copyWith(
        ask: switch (result) {
          AskSuccess() => const AskIdle(),
          AskKeyMissing() => const AskFailed(keyMissing: true),
          AskError(:final message) => AskFailed(message: message),
        },
      );
    }());
  }

  /// The AI writes [entry] again (a long press's Regenerate); the row keeps
  /// its text until the new one lands. The listener's own entries are theirs.
  Future<AboutRegenerateOutcome?> regenerate(SongAboutEntry entry) async {
    if (entry.isOwn) return null;
    final key = entry.ref;
    if (state.regenerating.contains(key)) return null;
    state = state.copyWith(regenerating: {...state.regenerating, key});
    try {
      return switch (await _repo.regenerate(key)) {
        RegenerateSuccess(:final beforeTitle, :final beforeAnswer) => AboutRegenerated(key, beforeTitle, beforeAnswer),
        RegenerateNothingNew() => const AboutRegenerateNothing(),
        RegenerateKeyMissing() => const AboutRegenerateKeyMissing(),
        RegenerateError() => const AboutRegenerateFailed(),
      };
    } finally {
      if (ref.mounted) state = state.copyWith(regenerating: {...state.regenerating}..remove(key));
    }
  }

  /// The snackbar's Undo after a regenerate: the row says what it said before.
  Future<void> undoRegenerate(AboutRegenerated done) =>
      _repo.edit(done.ref, title: done.beforeTitle, answer: done.beforeAnswer);
}

final nowPlayingAboutProvider = NotifierProvider<NowPlayingAboutNotifier, NowPlayingAbout>(NowPlayingAboutNotifier.new);
