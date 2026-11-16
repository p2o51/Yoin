/// Home's "Made For You" (Android ui/home/HomeMadeForYou.kt, owner
/// 2026-11-15,「为你推荐」): the playlists the service makes for this
/// account. Apple Music's personal mixes come from its recommendations
/// ([MusicMadeForYou]); other services have none here (Android reads
/// Spotify's over App Remote, which HarmonyOS lacks). The last list is kept
/// per account so the shelf shows at once, and read again each time Home
/// comes up after a while. A tap opens the playlist's page.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import 'home_view_model.dart' show homeClockProvider;

/// A read this recent stands; Home showing again reads anew after it (Android REFRESH_AFTER_MS).
const Duration madeForYouRefreshAfter = Duration(minutes: 10);

/// Waits before each try of a read (Android RETRY_DELAYS_MS): a slow first
/// connect gets a few more chances rather than leaving the shelf empty until
/// Home's next start. Tests override it.
final madeForYouRetryDelaysProvider = Provider<List<Duration>>(
  (ref) => const [
    Duration.zero,
    Duration(seconds: 5),
    Duration(seconds: 10),
    Duration(seconds: 20),
    Duration(seconds: 30),
  ],
);

/// The card's second line (Android cardSubtitle): whose it is; a mix's long
/// description doesn't fit a card.
String madeForYouCardSubtitle(MadeForYouPlaylist playlist) => switch (playlist.id.provider) {
  MediaId.providerAppleMusic => 'Apple Music',
  _ => playlist.subtitle,
};

/// The shelf for the active account, in the service's order; empty for a
/// service that makes none (the section then hides).
class MadeForYouNotifier extends Notifier<List<MadeForYouPlaylist>> {
  /// Where an account's last shelf is kept (the cache store: it can be read again).
  static String keyFor(String profileId) => 'home_made_for_you.v1.$profileId';

  int _generation = 0;
  String? _profileId;
  MusicMadeForYou? _reader;
  DateTime? _readAt;
  Future<void>? _refresh;

  @override
  List<MadeForYouPlaylist> build() {
    // Only a settled source: while an account switch loads, the previous account's source is
    // still the provider's value, and its mixes would be read and kept under the new account's id.
    final active = ref.watch(activeSourceProvider);
    final source = active.isLoading || active.hasError ? null : active.value;
    final generation = ++_generation;
    _profileId = null;
    _reader = null;
    _readAt = null;
    _refresh = null;
    final reader = source?.madeForYou;
    if (reader == null) return const [];
    Future.microtask(() async {
      String? profileId;
      try {
        profileId = (await ref.read(profileRepositoryProvider).activeProfile())?.id;
      } catch (_) {
        profileId = null;
      }
      if (!ref.mounted || generation != _generation || profileId == null) return;
      _profileId = profileId;
      _reader = reader;
      final saved = await _load(profileId);
      if (!ref.mounted || generation != _generation) return;
      if (saved.isNotEmpty) state = saved;
      onHomeShown();
    });
    return const [];
  }

  /// Home came up: read the list again when the last read is old enough.
  void onHomeShown() {
    final profileId = _profileId;
    final reader = _reader;
    if (profileId == null || reader == null || _refresh != null) return;
    final now = ref.read(homeClockProvider)();
    final readAt = _readAt;
    if (readAt != null && now.difference(readAt) < madeForYouRefreshAfter) return;
    final generation = _generation;
    late final Future<void> run;
    run = _read(generation, profileId, reader).whenComplete(() {
      if (identical(_refresh, run)) _refresh = null;
    });
    _refresh = run;
  }

  Future<void> _read(int generation, String profileId, MusicMadeForYou reader) async {
    for (final wait in ref.read(madeForYouRetryDelaysProvider)) {
      if (wait > Duration.zero) await Future<void>.delayed(wait);
      if (!ref.mounted || generation != _generation) return;
      List<MadeForYouPlaylist> shelf;
      try {
        shelf = await reader.madeForYou();
      } catch (_) {
        continue;
      }
      if (!ref.mounted || generation != _generation) return;
      _readAt = ref.read(homeClockProvider)();
      state = shelf;
      await _save(profileId, shelf);
      return;
    }
  }

  Future<List<MadeForYouPlaylist>> _load(String profileId) async {
    try {
      final raw = await ref.read(cacheStoreProvider).read(keyFor(profileId));
      if (raw == null) return const [];
      final list = jsonDecode(raw);
      if (list is! List) return const [];
      return [for (final item in list) ?MadeForYouPlaylist.fromJson(item)];
    } catch (_) {
      return const [];
    }
  }

  Future<void> _save(String profileId, List<MadeForYouPlaylist> shelf) async {
    try {
      await ref.read(cacheStoreProvider).write(keyFor(profileId), jsonEncode([for (final p in shelf) p.toJson()]));
    } catch (_) {
      // Unsaved: the next start reads it again.
    }
  }
}

/// The active account's Made For You shelf.
final madeForYouProvider = NotifierProvider<MadeForYouNotifier, List<MadeForYouPlaylist>>(MadeForYouNotifier.new);
