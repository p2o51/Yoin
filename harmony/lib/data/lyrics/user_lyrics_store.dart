import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../platform/storage/stores.dart';
import 'lyrics_search.dart';

/// Lyrics the user chose for a song (Android `lyrics_cache` rows with
/// `userChosen`: typed or pasted in the LRC editor — provider `manual` — or a
/// search result applied). They never expire and win over the server's lyrics
/// when the song loads. Stored per song key ([MediaId.toString], provider
/// included) in the app's key-value store.
///
/// Android also caps the whole cache at 10 MiB (evicting search picks, never
/// manual rows); Harmony keeps only the user's own rows, so no budget yet.
@immutable
final class UserLyrics {
  const UserLyrics({required this.lrc, required this.provider, this.providerSongId});

  /// Typed or pasted (LyricsCachePolicy.MANUAL_PROVIDER).
  static const manualProvider = 'manual';

  final String lrc;
  final String provider;
  final String? providerSongId;

  Map<String, Object?> toJson() => {'lrc': lrc, 'provider': provider, 'providerSongId': providerSongId};

  static UserLyrics? fromJson(Object? json) {
    if (json is! Map || json['lrc'] is! String) return null;
    return UserLyrics(
      lrc: json['lrc'] as String,
      provider: (json['provider'] as String?) ?? manualProvider,
      providerSongId: json['providerSongId'] as String?,
    );
  }
}

class UserLyricsStore {
  UserLyricsStore(this._store);

  final KeyValueStore _store;

  static String keyFor(String songKey) => 'yoin.lyrics.user.$songKey';

  Future<UserLyrics?> read(String songKey) async {
    final raw = await _store.read(keyFor(songKey));
    if (raw == null) return null;
    try {
      return UserLyrics.fromJson(jsonDecode(raw));
    } catch (_) {
      return null;
    }
  }

  Future<void> write(String songKey, UserLyrics lyrics) => _store.write(keyFor(songKey), jsonEncode(lyrics.toJson()));
}

/// The user's chosen lyrics; tests without a key-value store get none.
final userLyricsStoreProvider = Provider<UserLyricsStore?>((ref) {
  try {
    return UserLyricsStore(ref.watch(keyValueStoreProvider));
  } catch (_) {
    return null;
  }
});

/// The Search key's providers (tests override with fakes).
final lyricsSearchProvider = Provider<LyricsSearch>((ref) => LyricsSearch());
