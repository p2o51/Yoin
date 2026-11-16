/// Yoin's AI words on an album Memory (Android YoinRepository
/// getOrGenerateAlbumMemoryTitle / getCachedAlbumMemoryTitle,
/// albumMemoryAiTitle, and ui/memories/MemoryNarrationSource): the AI title
/// made from the words the user wrote on the album, and the AI narration made
/// from the listening facts only. Both are cached per account and album
/// ([MemoryCopyCache]); without an AI service, or when a call fails, the
/// cached title stands (else the album name) and the narration is the local
/// template.
///
/// Only the Memories deck generates. Home's memory card and the album page
/// read the cached title, never asking the model.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/ai/ai_service.dart';
import '../data/ai/ai_settings.dart';
import '../data/memory/memory_copy_cache.dart';
import '../data/model/model.dart';
import 'copy/memory_copy.dart';
import 'copy/memory_voice.dart';

/// `narration-v2:<hash>`: the prompt version and every input that shapes the prompt (narrationCacheKey).
String narrationCacheKey(MemoryNarrationBrief brief) =>
    'narration-v$memoryNarrationPromptVersion:${javaStringHash(brief.signal)}';

const _mechanismWords = ['memor', 'remember', '记忆', '回忆'];

/// The checks a model answer can fail on its own (followsVoiceRules): one or
/// two sentences of narration, a question of one or two sentences, and no
/// mechanism words. Anything else falls back to the local template.
bool narrationFollowsVoiceRules(MemoryNarrationText narration) {
  final sentences = MemorySentences.split(narration.narration);
  if (sentences.isEmpty || sentences.length > 2) return false;
  final question = MemorySentences.split(narration.question).length;
  if (question < 1 || question > 2) return false;
  final text = '${narration.narration} ${narration.question}'.toLowerCase();
  return !_mechanismWords.any(text.contains);
}

/// The words a Memory's AI title is made from: the review first, else the newest note.
@immutable
final class MemoryTitleWriting {
  const MemoryTitleWriting({required this.kind, required this.text});

  /// "album review" or "note", as the prompt names it.
  final String kind;
  final String text;
}

class MemoryAi {
  MemoryAi({
    required MemoryCopyCache cache,
    required Future<AiSettings> Function() settings,
    required AiService ai,
    int Function()? clock,
  }) : _cache = cache,
       _settings = settings,
       _ai = ai,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final MemoryCopyCache _cache;
  final Future<AiSettings> Function() _settings;
  final AiService _ai;
  final int Function() _clock;

  Future<AiSettings?> _readyAi() async {
    try {
      final ai = await _settings();
      return ai.isReady ? ai : null;
    } catch (_) {
      return null;
    }
  }

  /// The cached AI title only; null on a miss or a failed read.
  Future<String?> cachedTitle(MediaId albumId) async {
    try {
      return await _cache.title(albumId);
    } catch (_) {
      return null;
    }
  }

  /// The album's AI title, generated when the writing it comes from changed
  /// (getOrGenerateAlbumMemoryTitle). No writing: the cached title. No
  /// service, or a failed call: the cached title.
  Future<String?> title(Album album, MemoryTitleWriting? writing) async {
    MemoryCopyRow? cached;
    try {
      cached = await _cache.get(album.id);
    } catch (_) {
      return null;
    }
    final text = writing?.text;
    if (writing == null || text == null || text.trim().isEmpty) return _shown(cached?.title);
    final hash = '${javaStringHash('${album.id}|${album.name}|${writing.kind}|$text')}';
    if (cached?.title != null && cached?.titlePromptHash == hash) return _shown(cached?.title);
    final ai = await _readyAi();
    if (ai == null) return _shown(cached?.title);
    try {
      final generated = await _ai.generateAlbumMemoryTitle(
        ai,
        albumName: album.name,
        artist: album.artist,
        year: album.year,
        writingKind: writing.kind,
        writingText: text,
      );
      final now = _clock();
      await _cache.put(
        album.id,
        (row) => (row ?? MemoryCopyRow(generatedAt: now)).copyWith(title: generated, titlePromptHash: hash),
      );
      return generated;
    } catch (e) {
      debugPrint('YoinMemories: AI title failed: ${e.runtimeType}');
      return _shown(cached?.title);
    }
  }

  /// The AI narration for [albumId] from [brief], or null for the template
  /// (GeminiMemoryNarrationSource.narrate): a cached one written from the same
  /// brief by this prompt version, else a fresh one that keeps the voice rules.
  Future<MemoryNarrationText?> narrate(MediaId albumId, MemoryNarrationBrief brief) async {
    final key = narrationCacheKey(brief);
    MemoryCopyRow? cached;
    try {
      cached = await _cache.get(albumId);
    } catch (_) {
      cached = null;
    }
    if (cached != null && cached.promptHash == key) {
      final text = parseMemoryNarration(cached.copy);
      if (text != null) return text;
    }
    final ai = await _readyAi();
    if (ai == null) return null;
    final MemoryNarrationText generated;
    try {
      generated = await _ai.generateAlbumMemoryNarration(
        ai,
        languageName: brief.languageName,
        facts: brief.facts,
        alreadySaid: brief.alreadySaid,
      );
    } catch (e) {
      debugPrint('YoinMemories: AI narration failed: ${e.runtimeType}');
      return null;
    }
    if (!narrationFollowsVoiceRules(generated)) return null;
    final now = _clock();
    try {
      // The row's title stays: title and narration expire on their own keys.
      await _cache.put(
        albumId,
        (row) => (row ?? const MemoryCopyRow()).copyWith(
          copy: encodeMemoryNarration(generated),
          promptHash: key,
          generatedAt: now,
        ),
      );
    } catch (_) {
      // Shown this time; written again next time.
    }
    return generated;
  }

  static String? _shown(String? title) {
    final t = title?.trim();
    return t == null || t.isEmpty ? null : t;
  }
}

/// The active account's [MemoryAi]; null with no account.
final memoryAiProvider = FutureProvider<MemoryAi?>((ref) async {
  final store = await ref.watch(memoryStoreProvider.future);
  if (store == null) return null;
  return MemoryAi(
    cache: MemoryCopyCache.shared(ref.watch(keyValueStoreProvider), store.profileId),
    settings: () => ref.read(aiSettingsProvider.future),
    ai: ref.watch(aiServiceProvider),
  );
});
