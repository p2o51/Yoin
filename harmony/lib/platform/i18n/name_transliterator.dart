/// Han → Latin (pinyin) sort forms for names, the part of Android's ICU
/// (`Han-Latin/Names` + the zh collator, LibraryIndex.kt) Dart lacks.
///
/// Production goes through the `yoin/i18n` method channel served by the ArkTS
/// `YoinI18nPlugin` (ohos/entry/src/main/ets/yoin/i18n/): LocalizationKit's
/// `i18n.Transliterator`, or, where no Han transliterator exists, the zh
/// pinyin `intl.Collator`. Tests and hosts without the plugin get the pure-Dart
/// fallback ([IdentityNameTransliterator]): Han stays Han and the Library files
/// it in its trailing overflow section, as Android does on API 26–28.
library;

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Latin sort forms for display names.
abstract interface class NameTransliterator {
  /// One sort form per text, same order and length. Han is replaced by its
  /// pinyin with tone marks ("周杰伦" → "zhōu jié lún"); kana, Hangul and
  /// everything else pass through unchanged, so they keep their own buckets.
  Future<List<String>> latinKeys(List<String> texts);
}

/// The pure-Dart fallback: every text is its own sort form.
final class IdentityNameTransliterator implements NameTransliterator {
  const IdentityNameTransliterator();

  @override
  Future<List<String>> latinKeys(List<String> texts) => Future.value(List.of(texts));
}

const _i18nChannel = MethodChannel('yoin/i18n');

/// [NameTransliterator] over `yoin/i18n` (method `latinKeys`, `{texts: [..]}`
/// → `[..]`), with an in-memory cache: only texts that contain Han and were
/// never asked cross the channel, in batches of [batchSize]. When the plugin
/// is missing or fails, the texts come back unchanged (the fallback) and,
/// for a missing plugin, the channel is not asked again.
final class ChannelNameTransliterator implements NameTransliterator {
  ChannelNameTransliterator({MethodChannel channel = _i18nChannel, this.batchSize = 1000, this.cacheLimit = 20000})
    : _channel = channel;

  final MethodChannel _channel;
  final int batchSize;

  /// Past this many cached names the cache drops every name the current call
  /// does not ask for (a library's names fit).
  final int cacheLimit;

  final Map<String, String> _cache = {};
  bool _unavailable = false;

  @override
  Future<List<String>> latinKeys(List<String> texts) async {
    final wanted = <String>{
      for (final t in texts)
        if (containsHan(t)) t,
    };
    // Evict before working out what's missing, keeping this call's own names,
    // so every Han text here is either still cached or fetched below.
    if (_cache.length + wanted.length > cacheLimit) {
      _cache.removeWhere((k, _) => !wanted.contains(k));
    }
    final missing = <String>{
      for (final t in texts)
        if (!_cache.containsKey(t) && containsHan(t)) t,
    }.toList();
    if (missing.isNotEmpty && !_unavailable) await _fetch(missing);
    return [for (final t in texts) _cache[t] ?? t];
  }

  Future<void> _fetch(List<String> missing) async {
    for (var start = 0; start < missing.length; start += batchSize) {
      final batch = missing.sublist(start, start + batchSize > missing.length ? missing.length : start + batchSize);
      try {
        final keys = await _channel.invokeListMethod<Object?>('latinKeys', {'texts': batch});
        if (keys == null || keys.length != batch.length) return;
        for (final (i, text) in batch.indexed) {
          final key = keys[i];
          if (key is String && key.isNotEmpty) _cache[text] = key;
        }
      } on MissingPluginException {
        _unavailable = true;
        return;
      } catch (_) {
        // PlatformException or a host without bindings: these names keep the fallback this time.
        return;
      }
    }
  }
}

/// The app's transliterator. Overridden in tests that need pinyin.
final nameTransliteratorProvider = Provider<NameTransliterator>((ref) => ChannelNameTransliterator());

/// Sort forms for every distinct [names] entry that contains Han, as a map of
/// the names whose form differs. Texts without Han never cross the channel;
/// when none has Han nothing is awaited at all (returns null).
Future<Map<String, String>>? latinKeyMap(NameTransliterator transliterator, Iterable<String?> names) {
  final han = <String>{
    for (final n in names)
      if (n != null && containsHan(n)) n,
  }.toList();
  if (han.isEmpty) return null;
  return transliterator
      .latinKeys(han)
      .then(
        (keys) => {
          for (final (i, name) in han.indexed)
            if (keys[i] != name) name: keys[i],
        },
      );
}

/// Whether [text] has a Han ideograph (CJK Unified + extensions,
/// compatibility ideographs, 々 and 〇).
bool containsHan(String text) {
  for (final c in text.runes) {
    if (isHan(c)) return true;
  }
  return false;
}

/// Whether the code point [c] is Han script.
bool isHan(int c) =>
    (c >= 0x4E00 && c <= 0x9FFF) ||
    (c >= 0x3400 && c <= 0x4DBF) ||
    (c >= 0xF900 && c <= 0xFAFF) ||
    (c >= 0x20000 && c <= 0x323AF) ||
    c == 0x3005 ||
    c == 0x3007;
