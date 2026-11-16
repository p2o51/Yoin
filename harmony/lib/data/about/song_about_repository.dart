/// What every About surface asks of the About table and the AI (Android
/// YoinRepository observeAbout / ensureCanonicalAbout / askAboutSong /
/// generateAskTitle / regenerateAboutEntry / editAboutEntry /
/// writeOwnAboutEntry): Now Playing's About, its Ask bar, the album page's
/// scrapbook and its track sheet all go through here.
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../ai/ai_client.dart';
import '../ai/ai_service.dart';
import '../ai/ai_settings.dart';
import 'song_about_store.dart';

/// How a canonical About load went (AboutLoadResult).
sealed class AboutLoadResult {
  const AboutLoadResult();
}

final class AboutLoadSuccess extends AboutLoadResult {
  const AboutLoadSuccess();
}

/// No AI service is set up (and nothing is cached).
final class AboutLoadKeyMissing extends AboutLoadResult {
  const AboutLoadKeyMissing();
}

final class AboutLoadError extends AboutLoadResult {
  const AboutLoadError(this.message);
  final String message;
}

/// How an Ask went (AskAboutResult).
sealed class AskResult {
  const AskResult();
}

final class AskSuccess extends AskResult {
  const AskSuccess(this.answer);
  final String answer;
}

final class AskKeyMissing extends AskResult {
  const AskKeyMissing();
}

final class AskError extends AskResult {
  const AskError(this.message);
  final String message;
}

/// How "Regenerate" on one row went (AboutRegenerateResult).
sealed class RegenerateResult {
  const RegenerateResult();
}

/// The row says something new; [beforeTitle] / [beforeAnswer] put it back (Undo).
final class RegenerateSuccess extends RegenerateResult {
  const RegenerateSuccess({required this.beforeTitle, required this.beforeAnswer});
  final String? beforeTitle;
  final String beforeAnswer;
}

/// The AI had nothing for the row (or the row is gone, or it is the listener's own).
final class RegenerateNothingNew extends RegenerateResult {
  const RegenerateNothingNew();
}

final class RegenerateKeyMissing extends RegenerateResult {
  const RegenerateKeyMissing();
}

final class RegenerateError extends RegenerateResult {
  const RegenerateError(this.message);
  final String message;
}

/// One song's About rows as every reader shows them (observeAbout): blank
/// asks / own entries left out; canonical rows first (in [aboutCanonicalOrder]),
/// then the asks and own entries in the order they were first written, newest
/// first, so an edit or a regenerate leaves a row where it was.
List<SongAboutEntry> aboutRowsForDisplay(List<SongAboutEntry> rows) {
  final live = [
    for (final r in rows)
      if (r.isCanonical || r.answerText.trim().isNotEmpty) r,
  ];
  int order(SongAboutEntry r) {
    final i = aboutCanonicalOrder.indexOf(r.entryKey);
    return i < 0 ? aboutCanonicalOrder.length : i;
  }

  final canonical = live.where((r) => r.isCanonical).toList()..sort((a, b) => order(a).compareTo(order(b)));
  final rest = live.where((r) => !r.isCanonical).toList()
    ..sort((a, b) {
      final c = b.createdAt.compareTo(a.createdAt);
      return c != 0 ? c : b.updatedAt.compareTo(a.updatedAt);
    });
  return [...canonical, ...rest];
}

class SongAboutRepository {
  SongAboutRepository({
    required SongAboutStore store,
    required Future<AiSettings> Function() settings,
    required AiService ai,
    int Function()? clock,
  }) : _store = store,
       _settings = settings,
       _ai = ai,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final SongAboutStore _store;
  final Future<AiSettings> Function() _settings;
  final AiService _ai;
  final int Function() _clock;

  SongAboutStore get store => _store;

  /// The ready AI settings, or null when no service is set up.
  Future<AiSettings?> _readyAi() async {
    try {
      final ai = await _settings();
      return ai.isReady ? ai : null;
    } catch (_) {
      return null;
    }
  }

  /// One song's rows, live ([aboutRowsForDisplay]); emits again after every write.
  Stream<List<SongAboutEntry>> watch(String title, String artist, String album) async* {
    final t = normalizeAboutKey(title), a = normalizeAboutKey(artist), l = normalizeAboutKey(album);
    Future<List<SongAboutEntry>> read() async => aboutRowsForDisplay(await _store.forSong(t, a, l));
    final changes = StreamController<void>();
    final sub = _store.changes.listen((_) => changes.add(null));
    try {
      yield await read();
      await for (final _ in changes.stream) {
        yield await read();
      }
    } finally {
      await sub.cancel();
      await changes.close();
    }
  }

  /// Every row under one album's key, live, newest first (the scrapbook matches them to its tracks).
  Stream<List<SongAboutEntry>> watchAlbum(String albumName) async* {
    final key = normalizeAboutKey(albumName);
    final changes = StreamController<void>();
    final sub = _store.changes.listen((_) => changes.add(null));
    try {
      yield await _store.byAlbum(key);
      await for (final _ in changes.stream) {
        yield await _store.byAlbum(key);
      }
    } finally {
      await sub.cancel();
      await changes.close();
    }
  }

  /// Makes sure the six canonical rows exist (ensureCanonicalAbout). A no-op
  /// when they are cached, unless [retry]: then they are asked for again and
  /// written over. Without a service, cached rows count as success.
  Future<AboutLoadResult> ensureCanonicalAbout({
    required String title,
    required String artist,
    required String album,
    bool retry = false,
  }) async {
    final t = normalizeAboutKey(title), a = normalizeAboutKey(artist), l = normalizeAboutKey(album);
    final existing = retry
        ? const <SongAboutEntry>[]
        : [
            for (final r in await _store.forSong(t, a, l))
              if (r.isCanonical) r,
          ];
    if (!retry && aboutCanonicalOrder.every((k) => existing.any((r) => r.entryKey == k))) {
      return const AboutLoadSuccess();
    }
    final ai = await _readyAi();
    if (ai == null) return existing.isNotEmpty ? const AboutLoadSuccess() : const AboutLoadKeyMissing();
    try {
      final values = await _ai.generateAbout(
        ai,
        title: title,
        artist: artist,
        album: album,
        targetLanguage: AiLanguages.normalize(ai.targetLanguage),
      );
      final byKey = {for (final v in values) v.key: v.answer};
      final now = _clock();
      await _store.upsertAll([
        for (final key in aboutCanonicalOrder)
          SongAboutEntry(
            titleKey: t,
            artistKey: a,
            albumKey: l,
            titleDisplay: title,
            artistDisplay: artist,
            albumDisplay: album,
            kind: SongAboutKinds.canonical,
            entryKey: key,
            answerText: byKey[key] ?? '',
            createdAt: now,
            updatedAt: now,
          ),
      ]);
      return const AboutLoadSuccess();
    } catch (e) {
      return AboutLoadError(_message(e, 'Failed to load song about info'));
    }
  }

  /// A short heading for an Ask while its answer is on the way; never stored.
  Future<String?> generateAskTitle({
    required String title,
    required String artist,
    required String album,
    required String question,
  }) async {
    final q = question.trim();
    if (q.isEmpty) return null;
    final ai = await _readyAi();
    if (ai == null) return null;
    try {
      return await _ai.generateAskTitle(
        ai,
        title: title,
        artist: artist,
        album: album,
        question: q,
        targetLanguage: AiLanguages.normalize(ai.targetLanguage),
      );
    } catch (_) {
      return null;
    }
  }

  /// Asks the AI [question] about the song and files the answer under the
  /// normalised question: asking it again replaces the answer and keeps the
  /// row's place (its createdAt).
  Future<AskResult> askAboutSong({
    required String title,
    required String artist,
    required String album,
    required String question,
  }) async {
    final q = question.trim();
    if (q.isEmpty) return const AskError('Question is empty');
    final ai = await _readyAi();
    if (ai == null) return const AskKeyMissing();
    final t = normalizeAboutKey(title), a = normalizeAboutKey(artist), l = normalizeAboutKey(album);
    final questionKey = normalizeAboutKey(q);
    try {
      final reply = await _ai.askAboutSong(
        ai,
        title: title,
        artist: artist,
        album: album,
        question: q,
        targetLanguage: AiLanguages.normalize(ai.targetLanguage),
      );
      final now = _clock();
      final existing = await _store.get(
        AboutRef(titleKey: t, artistKey: a, albumKey: l, kind: SongAboutKinds.ask, entryKey: questionKey),
      );
      await _store.upsert(
        SongAboutEntry(
          titleKey: t,
          artistKey: a,
          albumKey: l,
          titleDisplay: title,
          artistDisplay: artist,
          albumDisplay: album,
          kind: SongAboutKinds.ask,
          entryKey: questionKey,
          promptText: q,
          titleText: reply.title,
          answerText: reply.answer,
          createdAt: existing?.createdAt ?? now,
          updatedAt: now,
        ),
      );
      return AskSuccess(reply.answer);
    } catch (e) {
      return AskError(_message(e, 'Failed to ask Gemini'));
    }
  }

  /// "Regenerate" on one row: a question is asked again in its own words; a
  /// canonical field is fetched again with the rest of the song's About and
  /// only that field written. The row keeps its place; the listener's own
  /// entries are never regenerated.
  Future<RegenerateResult> regenerate(AboutRef ref) async {
    final row = await _store.get(ref);
    if (row == null || row.isOwn) return const RegenerateNothingNew();
    final ai = await _readyAi();
    if (ai == null) return const RegenerateKeyMissing();
    final language = AiLanguages.normalize(ai.targetLanguage);
    try {
      String? title;
      String answer;
      if (row.kind == SongAboutKinds.ask) {
        final prompt = row.promptText?.trim();
        final reply = await _ai.askAboutSong(
          ai,
          title: row.titleDisplay,
          artist: row.artistDisplay,
          album: row.albumDisplay,
          question: prompt == null || prompt.isEmpty ? row.entryKey : prompt,
          targetLanguage: language,
        );
        title = reply.title;
        answer = reply.answer;
      } else {
        final values = await _ai.generateAbout(
          ai,
          title: row.titleDisplay,
          artist: row.artistDisplay,
          album: row.albumDisplay,
          targetLanguage: language,
        );
        title = row.titleText;
        answer = values.where((v) => v.key == row.entryKey).firstOrNull?.answer ?? '';
      }
      if (answer.trim().isEmpty) return const RegenerateNothingNew();
      await _store.upsert(
        row.copyWith(titleText: title, clearTitle: title == null, answerText: answer.trim(), updatedAt: _clock()),
      );
      return RegenerateSuccess(beforeTitle: row.titleText, beforeAnswer: row.answerText);
    } catch (e) {
      return RegenerateError(_message(e, 'Failed to ask Gemini'));
    }
  }

  /// The listener's own version of one row (editAboutEntry): [answer] (and,
  /// on an ask or own row, [title]) replace the text. A blank [answer]
  /// removes the row from every view; it stays as a blank row so the removal
  /// syncs (About deletes never do).
  Future<void> edit(AboutRef ref, {String? title, required String answer}) async {
    final row = await _store.get(ref);
    if (row == null) return;
    final text = answer.trim();
    final heading = title?.trim();
    final String? nextTitle;
    if (row.isCanonical || text.isEmpty) {
      nextTitle = row.titleText;
    } else if (row.isOwn) {
      // The listener's own entry always keeps a heading.
      nextTitle = heading == null || heading.isEmpty ? row.titleText : heading;
    } else {
      nextTitle = heading == null || heading.isEmpty ? null : heading;
    }
    await _store.upsert(
      row.copyWith(titleText: nextTitle, clearTitle: nextTitle == null, answerText: text, updatedAt: _clock()),
    );
  }

  /// The listener's own entry for a song (the scrapbook's Write): writing
  /// the same heading again replaces its text. A heading is matched against
  /// the live entries' headings (an entry keeps its key after a rename); a
  /// removed entry's key is free again. Blank input writes nothing.
  Future<void> writeOwn({
    required String title,
    required String artist,
    required String album,
    required String heading,
    required String text,
  }) async {
    final head = heading.trim();
    final body = text.trim();
    if (head.isEmpty || body.isEmpty) return;
    final t = normalizeAboutKey(title), a = normalizeAboutKey(artist), l = normalizeAboutKey(album);
    final headKey = normalizeAboutKey(head);
    final now = _clock();
    final live = [
      for (final r in await _store.forSong(t, a, l))
        if (r.isOwn && r.answerText.trim().isNotEmpty) r,
    ];
    final existing = live.where((r) => normalizeAboutKey(r.titleText ?? '') == headKey).firstOrNull;
    var entryKey = existing?.entryKey;
    if (entryKey == null) {
      for (var n = 1; ; n++) {
        final candidate = n == 1 ? headKey : '$headKey #$n';
        if (live.every((r) => r.entryKey != candidate)) {
          entryKey = candidate;
          break;
        }
      }
    }
    await _store.upsert(
      SongAboutEntry(
        titleKey: t,
        artistKey: a,
        albumKey: l,
        titleDisplay: title,
        artistDisplay: artist,
        albumDisplay: album,
        kind: SongAboutKinds.own,
        entryKey: entryKey,
        titleText: head,
        answerText: body,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
      ),
    );
  }

  static String _message(Object e, String fallback) {
    if (e is AiException && e.message.isNotEmpty) return e.message;
    final text = e.toString();
    return text.isEmpty ? fallback : text;
  }
}

/// The About table on the app's key-value store (shared with cloud sync).
final songAboutStoreProvider = Provider<SongAboutStore>(
  (ref) => SongAboutStore.shared(ref.watch(keyValueStoreProvider)),
);

final songAboutRepositoryProvider = Provider<SongAboutRepository>(
  (ref) => SongAboutRepository(
    store: ref.watch(songAboutStoreProvider),
    settings: () => ref.read(aiSettingsProvider.future),
    ai: ref.watch(aiServiceProvider),
  ),
);
