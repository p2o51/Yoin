/// What the album page's About asks of the album About table and the AI
/// (Android YoinRepository: isAiReady / ensureAlbumAbout / regenerateAlbumAbout
/// / restoreAlbumAbout / askAboutAlbum; docs/design.md, album page › 专辑「关于」).
///
/// One searched AI call writes an album's credits and paragraph, cached per
/// album identity until regenerated or the AI language changes, like the song
/// About. An answer with nothing in it is still written (the page doesn't ask
/// again on every visit); a call that fails or a reply that isn't an answer
/// writes nothing (asked again next visit).
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../ai/ai_client.dart';
import '../ai/ai_service.dart';
import '../ai/ai_service_name.dart';
import '../ai/ai_settings.dart';
import '../albumabout/album_about_models.dart';
import '../albumabout/yoin_album_about.dart';
import '../model/model.dart';
import 'album_about_store.dart';
import 'song_about_repository.dart'
    show
        AboutLoadResult,
        AboutLoadSuccess,
        AboutLoadKeyMissing,
        AboutLoadError,
        AskResult,
        AskSuccess,
        AskKeyMissing,
        AskError;

/// How "Regenerate" on an album's About went (AlbumAboutRegenerateResult).
sealed class AlbumAboutRegenerateResult {
  const AlbumAboutRegenerateResult();
}

/// Written again; [before] is what was there (Undo puts it back, [AlbumAboutRepository.restore]).
final class AlbumAboutRegenerated extends AlbumAboutRegenerateResult {
  const AlbumAboutRegenerated(this.before);
  final List<AlbumAboutEntry> before;
}

/// The AI came back with nothing: the old About stays.
final class AlbumAboutNothingNew extends AlbumAboutRegenerateResult {
  const AlbumAboutNothingNew();
}

final class AlbumAboutKeyMissing extends AlbumAboutRegenerateResult {
  const AlbumAboutKeyMissing();
}

final class AlbumAboutRegenerateError extends AlbumAboutRegenerateResult {
  const AlbumAboutRegenerateError(this.message);
  final String message;
}

/// The service's facts about this release, for the prompt (Android Album.toAboutGrounding). HarmonyOS has
/// no service-written album notes, so [AlbumAboutGrounding.serviceNotes] stays null.
AlbumAboutGrounding albumAboutGrounding(Album album) {
  final tracks = [
    for (final t in album.tracks)
      if (t.title?.trim() ?? '' case final title when title.isNotEmpty) title,
  ];
  final summed = album.tracks.fold<int>(0, (sum, t) => sum + (t.durationSec ?? 0));
  return AlbumAboutGrounding(
    album: album.name,
    artist: album.artist ?? '',
    version: album.version,
    releaseDate: album.releaseDate,
    originalReleaseDate: album.originalReleaseDate,
    labels: album.recordLabels,
    // Android's ReleaseType names, as the prompt reads them.
    releaseType: switch (album.releaseType) {
      ReleaseType.album => 'Album',
      ReleaseType.ep => 'EP',
      ReleaseType.single => 'Single',
      ReleaseType.compilation => 'Compilation',
      null => null,
    },
    genre: album.genre,
    tracks: tracks,
    totalDurationSec: album.durationSec ?? (summed > 0 ? summed : null),
  );
}

class AlbumAboutRepository {
  AlbumAboutRepository({
    required AlbumAboutStore store,
    required Future<AiSettings> Function() settings,
    required AiService ai,
    int Function()? clock,
  }) : _store = store,
       _settings = settings,
       _ai = ai,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final AlbumAboutStore _store;
  final Future<AiSettings> Function() _settings;
  final AiService _ai;
  final int Function() _clock;

  AlbumAboutStore get store => _store;

  /// The ready AI settings, or null when no service is set up.
  Future<AiSettings?> _readyAi() async {
    try {
      final ai = await _settings();
      return ai.isReady ? ai : null;
    } catch (_) {
      return null;
    }
  }

  /// Whether the chosen AI service has its key: without one the album page never shows a writing card.
  Future<bool> isAiReady() async => await _readyAi() != null;

  /// One album's rows (canonical first, then the questions newest first).
  Future<List<AlbumAboutEntry>> rows(MediaId albumId) => _store.forAlbum(albumId.provider, albumId.rawId);

  /// Makes sure the album's credits and paragraph exist: one searched AI call when they don't (or when
  /// [retry]). Without a service, cached rows count as success.
  Future<AboutLoadResult> ensure(Album album, {bool retry = false}) async {
    final existing = await _store.canonical(album.id.provider, album.id.rawId);
    if (!retry && existing.isNotEmpty) return const AboutLoadSuccess();
    final ai = await _readyAi();
    if (ai == null) return existing.isNotEmpty ? const AboutLoadSuccess() : const AboutLoadKeyMissing();
    try {
      final reply = await _ai.runAlbumAbout(
        ai,
        albumAboutGrounding(album),
        targetLanguage: AiLanguages.normalize(ai.targetLanguage),
      );
      await _write(album, reply.credits, reply.blurb);
      return const AboutLoadSuccess();
    } catch (e) {
      return AboutLoadError(_message(e, 'Failed to load album about info'));
    }
  }

  /// "Regenerate" from the album's About sheet: asked again; an empty answer keeps what was there.
  Future<AlbumAboutRegenerateResult> regenerate(Album album) async {
    final before = await _store.canonical(album.id.provider, album.id.rawId);
    final ai = await _readyAi();
    if (ai == null) return const AlbumAboutKeyMissing();
    try {
      final reply = await _ai.runAlbumAbout(
        ai,
        albumAboutGrounding(album),
        targetLanguage: AiLanguages.normalize(ai.targetLanguage),
      );
      if (reply.isEmpty) return const AlbumAboutNothingNew();
      final first = before.isEmpty ? null : before.map((r) => r.createdAt).reduce((a, b) => a < b ? a : b);
      await _write(album, reply.credits, reply.blurb, createdAt: first);
      return AlbumAboutRegenerated(before);
    } catch (e) {
      return AlbumAboutRegenerateError(_message(e, 'Failed to ask ${aiServiceName(ai.provider, zh: false)}'));
    }
  }

  /// Undo of [regenerate]: the rows as they were, written back now (so they sync as the newest).
  Future<void> restore(List<AlbumAboutEntry> rows) async {
    if (rows.isEmpty) return;
    final now = _clock();
    await _store.upsertAll([for (final r in rows) r.copyWith(updatedAt: now)]);
  }

  Future<void> _write(Album album, AlbumAiCredits credits, String? blurb, {int? createdAt}) {
    final now = _clock();
    AlbumAboutEntry row(String entryKey, String answer) => AlbumAboutEntry(
      provider: album.id.provider,
      albumId: album.id.rawId,
      albumDisplay: album.name,
      artistDisplay: album.artist ?? '',
      kind: YoinAlbumAbout.kindCanonical,
      entryKey: entryKey,
      answerText: answer,
      createdAt: createdAt ?? now,
      updatedAt: now,
    );
    return _store.upsertAll([
      row(YoinAlbumAbout.canonCredits, credits.encode()),
      row(YoinAlbumAbout.canonBlurb, blurb ?? ''),
    ]);
  }

  /// Asks the AI about the album (the About sheet's "Ask …"): the answer is kept as an ask row keyed by
  /// the question's key; asking again refreshes it and keeps its place (its createdAt).
  Future<AskResult> ask(Album album, String question) async {
    final q = question.trim();
    if (q.isEmpty) return const AskError('Question is empty');
    final ai = await _readyAi();
    if (ai == null) return const AskKeyMissing();
    final key = YoinAlbumAbout.questionKey(q);
    try {
      final reply = await _ai.askAboutAlbum(
        ai,
        albumAboutGrounding(album),
        question: q,
        targetLanguage: AiLanguages.normalize(ai.targetLanguage),
      );
      final now = _clock();
      final existing = await _store.get(
        AlbumAboutRef(
          provider: album.id.provider,
          albumId: album.id.rawId,
          kind: YoinAlbumAbout.kindAsk,
          entryKey: key,
        ),
      );
      await _store.upsert(
        AlbumAboutEntry(
          provider: album.id.provider,
          albumId: album.id.rawId,
          albumDisplay: album.name,
          artistDisplay: album.artist ?? '',
          kind: YoinAlbumAbout.kindAsk,
          entryKey: key,
          promptText: q,
          titleText: reply.title,
          answerText: reply.answer,
          createdAt: existing?.createdAt ?? now,
          updatedAt: now,
        ),
      );
      return AskSuccess(reply.answer);
    } catch (e) {
      return AskError(_message(e, 'Failed to ask ${aiServiceName(ai.provider, zh: false)}'));
    }
  }

  static String _message(Object e, String fallback) {
    if (e is AiException && e.message.isNotEmpty) return e.message;
    final text = e.toString();
    return text.isEmpty ? fallback : text;
  }
}

/// The album About table on the app's key-value store (shared with cloud sync).
final albumAboutStoreProvider = Provider<AlbumAboutStore>(
  (ref) => AlbumAboutStore.shared(ref.watch(keyValueStoreProvider)),
);

final albumAboutRepositoryProvider = Provider<AlbumAboutRepository>(
  (ref) => AlbumAboutRepository(
    store: ref.watch(albumAboutStoreProvider),
    settings: () => ref.read(aiSettingsProvider.future),
    ai: ref.watch(aiServiceProvider),
  ),
);
