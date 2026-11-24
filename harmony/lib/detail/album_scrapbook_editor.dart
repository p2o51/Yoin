/// What page 2's track sheet writes and reads beyond the page (Android
/// AlbumDetailViewModel.editScrapbook, scrapFeeds): a track's score, the
/// listener's version of an About row, a new entry (asked or written), the
/// AI writing a row again or a track's About from nothing, the track's notes,
/// the album's own notes (its stickies), and its timed lyrics for the Note
/// tab. One per album page.
library;

import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/about/song_about_repository.dart';
import '../data/lyrics/lyrics_repository.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../playback/local_ratings.dart';
import 'album_scrapbook_sheet_model.dart';
import 'detail_cache.dart';

class AlbumScrapbookEditor extends Notifier<ScrapGemini> {
  AlbumScrapbookEditor(this.albumId);

  final MediaId albumId;

  final _events = StreamController<ScrapEvent>.broadcast();
  final Map<MediaId, ScrapLyrics> _lyrics = {};

  /// Notes deleted from the sheet, as they were, for Undo.
  final Map<String, SongNote> _deletedNotes = {};

  /// Album notes deleted from a sticky, as they were, for Undo.
  final Map<String, AlbumNote> _deletedAlbumNotes = {};

  static final Random _random = Random.secure();

  /// What the AI's work came to; the sheet (or the page) says it.
  Stream<ScrapEvent> get events => _events.stream;

  @override
  ScrapGemini build() {
    ref.onDispose(_events.close);
    return const ScrapGemini();
  }

  Album? get _album => ref.read(detailCacheProvider).cachedAlbum(albumId);

  Track? _track(MediaId id) => _album?.tracks.where((t) => t.id == id).firstOrNull;

  /// The keys a track's About rows are filed under, as page 2 reads them: the
  /// track's title and artist, and this album's name (so an entry written here shows here).
  ({String title, String artist, String album})? _keys(MediaId trackId) {
    final track = _track(trackId);
    if (track == null) return null;
    final name = _album?.name.trim();
    return (
      title: track.title ?? '',
      artist: track.artist ?? '',
      album: name == null || name.isEmpty ? (track.album ?? '') : name,
    );
  }

  SongAboutRepository get _about => ref.read(songAboutRepositoryProvider);

  int _now() => DateTime.now().millisecondsSinceEpoch;

  void _emit(ScrapEvent event) {
    if (!_events.isClosed) _events.add(event);
  }

  /// Runs one write; a failure is logged (the page re-reads the stores, so nothing to roll back).
  Future<void> _write(Future<void> Function() body) async {
    try {
      await body();
    } catch (e) {
      debugPrint("YoinScrapbook: couldn't save the edit: ${e.runtimeType}");
    }
  }

  void edit(ScrapEdit edit) {
    switch (edit) {
      case ScrapScoreEdit(:final trackId, :final score):
        final ratings = ref.read(localRatingsProvider.notifier);
        unawaited(_write(() => score == null ? ratings.clearRating(trackId) : ratings.setRating(trackId, score)));
      case ScrapAboutEdit(:final ref, :final title, :final answer):
        unawaited(_write(() => _about.edit(ref, title: title, answer: answer)));
      case ScrapAskEdit():
        unawaited(_ask(edit));
      case ScrapWriteEdit(:final trackId, :final heading, :final text):
        final keys = _keys(trackId);
        if (keys == null) return;
        unawaited(
          _write(
            () => _about.writeOwn(
              title: keys.title,
              artist: keys.artist,
              album: keys.album,
              heading: heading,
              text: text,
            ),
          ),
        );
      case ScrapGenerateEdit():
        unawaited(_generate(edit));
      case ScrapRegenerateEdit():
        unawaited(_regenerate(edit));
      case ScrapNoteTextEdit(:final noteId, :final text):
        unawaited(_write(() => _editNote(noteId, text)));
      case ScrapNewNoteEdit(:final trackId, :final positionMs, :final text):
        unawaited(_write(() => _newNote(trackId, positionMs, text)));
      case ScrapRestoreNoteEdit(:final noteId):
        final note = _deletedNotes.remove(noteId);
        if (note == null) return;
        unawaited(_write(() async => (await ref.read(memoryStoreProvider.future))?.putSongNote(note)));
      case ScrapNewAlbumNoteEdit(:final text):
        unawaited(_write(() => _newAlbumNote(text)));
      case ScrapAlbumNoteTextEdit(:final noteId, :final text):
        unawaited(_write(() => _editAlbumNote(noteId, text)));
      case ScrapRestoreAlbumNoteEdit(:final noteId):
        final note = _deletedAlbumNotes.remove(noteId);
        if (note == null) return;
        unawaited(_write(() => _restoreAlbumNote(note)));
    }
  }

  /// Appends an album note (Android YoinRepository.addAlbumNote): a fresh UUID,
  /// the album's name and artist kept with it.
  Future<void> _newAlbumNote(String text) async {
    final album = _album;
    final store = await ref.read(memoryStoreProvider.future);
    final words = text.trim();
    if (album == null || store == null || words.isEmpty) return;
    final now = _now();
    await store.putAlbumNote(
      AlbumNote(
        id: newAlbumNoteId(_random),
        albumId: albumId,
        content: words,
        createdAt: now,
        updatedAt: now,
        albumName: album.name,
        artist: album.artist ?? '',
      ),
    );
  }

  /// New words for an album note; blank deletes it and keeps it for Undo
  /// (Android editAlbumNoteById). The same words write nothing.
  Future<void> _editAlbumNote(String noteId, String text) async {
    final store = await ref.read(memoryStoreProvider.future);
    if (store == null) return;
    final note = (await store.albumNotes(albumId)).where((n) => n.id == noteId).firstOrNull;
    if (note == null) return;
    final words = text.trim();
    if (words.isEmpty) {
      _deletedAlbumNotes[noteId] = note;
      await store.deleteAlbumNote(noteId);
      return;
    }
    if (words == note.content.trim()) return;
    await store.putAlbumNote(
      AlbumNote(
        id: note.id,
        albumId: note.albumId,
        content: words,
        createdAt: note.createdAt,
        updatedAt: _now(),
        albumName: note.albumName,
        artist: note.artist,
      ),
    );
  }

  /// A deleted album note back (Undo): same id and creation time, stamped now
  /// (never at or before its last version, so the restore wins over the delete
  /// it undoes everywhere it synced).
  Future<void> _restoreAlbumNote(AlbumNote note) async {
    final store = await ref.read(memoryStoreProvider.future);
    if (store == null) return;
    final now = _now();
    await store.putAlbumNote(
      AlbumNote(
        id: note.id,
        albumId: note.albumId,
        content: note.content,
        createdAt: note.createdAt,
        updatedAt: now > note.updatedAt ? now : note.updatedAt + 1,
        albumName: note.albumName,
        artist: note.artist,
      ),
    );
  }

  Future<void> _ask(ScrapAskEdit edit) async {
    final question = edit.question.trim();
    final keys = _keys(edit.trackId);
    if (question.isEmpty || keys == null) return;
    final asking = ScrapAsking(edit.trackId, question);
    state = state.copyWith(asking: [...state.asking, asking]);
    try {
      final result = await _about.askAboutSong(
        title: keys.title,
        artist: keys.artist,
        album: keys.album,
        question: question,
      );
      switch (result) {
        case AskSuccess():
          break;
        case AskKeyMissing():
          _emit(const ScrapFailed(ScrapFailure.keyMissing));
        case AskError(:final message):
          debugPrint('YoinScrapbook: ask failed: $message');
          _emit(const ScrapFailed(ScrapFailure.failed));
      }
    } finally {
      if (ref.mounted) state = state.copyWith(asking: [...state.asking]..remove(asking));
    }
  }

  Future<void> _generate(ScrapGenerateEdit edit) async {
    final trackId = edit.trackId;
    if (state.generating.contains(trackId)) return;
    final keys = _keys(trackId);
    if (keys == null) return;
    state = state.copyWith(generating: {...state.generating, trackId});
    try {
      final result = await _about.ensureCanonicalAbout(
        title: keys.title,
        artist: keys.artist,
        album: keys.album,
        retry: true,
      );
      switch (result) {
        case AboutLoadSuccess():
          break;
        case AboutLoadKeyMissing():
          _emit(const ScrapFailed(ScrapFailure.keyMissing));
        case AboutLoadError(:final message):
          debugPrint('YoinScrapbook: generate failed: $message');
          _emit(const ScrapFailed(ScrapFailure.failed));
      }
    } finally {
      if (ref.mounted) state = state.copyWith(generating: {...state.generating}..remove(trackId));
    }
  }

  Future<void> _regenerate(ScrapRegenerateEdit edit) async {
    final key = edit.ref;
    if (state.regenerating.contains(key)) return;
    state = state.copyWith(regenerating: {...state.regenerating, key});
    try {
      _emit(switch (await _about.regenerate(key)) {
        RegenerateSuccess(:final beforeTitle, :final beforeAnswer) => ScrapRegenerated(
          ScrapAboutEdit(key, title: beforeTitle, answer: beforeAnswer),
        ),
        RegenerateNothingNew() => const ScrapFailed(ScrapFailure.nothingNew),
        RegenerateKeyMissing() => const ScrapFailed(ScrapFailure.keyMissing),
        RegenerateError() => const ScrapFailed(ScrapFailure.failed),
      });
    } finally {
      if (ref.mounted) state = state.copyWith(regenerating: {...state.regenerating}..remove(key));
    }
  }

  Future<void> _editNote(String noteId, String text) async {
    final store = await ref.read(memoryStoreProvider.future);
    if (store == null) return;
    final note = (await store.songNotes()).where((n) => n.id == noteId).firstOrNull;
    if (note == null) return;
    if (text.trim().isEmpty) {
      _deletedNotes[noteId] = note;
      await store.deleteSongNote(noteId);
      return;
    }
    await store.putSongNote(
      SongNote(
        id: note.id,
        trackId: note.trackId,
        albumId: note.albumId,
        content: text.trim(),
        createdAt: note.createdAt,
        updatedAt: _now(),
        title: note.title,
        artist: note.artist,
        positionMs: note.positionMs,
      ),
    );
  }

  Future<void> _newNote(MediaId trackId, int? positionMs, String text) async {
    final track = _track(trackId);
    final store = await ref.read(memoryStoreProvider.future);
    if (track == null || store == null || text.trim().isEmpty) return;
    final now = _now();
    await store.putSongNote(
      SongNote(
        id: '${now.toRadixString(36)}-${trackId.rawId.hashCode.toRadixString(36)}',
        trackId: trackId,
        albumId: track.albumId ?? albumId,
        content: text.trim(),
        createdAt: now,
        updatedAt: now,
        title: track.title ?? '',
        artist: track.artist ?? '',
        positionMs: positionMs,
      ),
    );
  }

  /// [trackId]'s timed lyrics for the Note tab, read once per page visit.
  Future<ScrapLyrics> lyrics(MediaId trackId) async {
    final cached = _lyrics[trackId];
    if (cached != null) return cached;
    final track = _track(trackId);
    if (track == null) return ScrapLyrics.none;
    try {
      final source = await ref.read(activeSourceProvider.future);
      if (source == null) return ScrapLyrics.none;
      final loaded = await ref.read(lyricsRepositoryProvider).getLoadedLyrics(track, source);
      return _lyrics[trackId] = ScrapLyrics.of(loaded?.sheet);
    } catch (e) {
      debugPrint("YoinScrapbook: couldn't read lyrics: ${e.runtimeType}");
      return ScrapLyrics.none;
    }
  }
}

/// A random (version 4) UUID, the shape Android gives an album note's id.
String newAlbumNoteId(Random random) {
  final b = List<int>.generate(16, (_) => random.nextInt(256));
  b[6] = (b[6] & 0x0F) | 0x40;
  b[8] = (b[8] & 0x3F) | 0x80;
  final hex = [for (final x in b) x.toRadixString(16).padLeft(2, '0')].join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}

final albumScrapbookEditorProvider = NotifierProvider.autoDispose.family<AlbumScrapbookEditor, ScrapGemini, MediaId>(
  AlbumScrapbookEditor.new,
);
