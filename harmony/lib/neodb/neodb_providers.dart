/// NeoDB's dependency graph and its shared state (Android AppContainer's
/// NeoDB services, SettingsViewModel's NeoDB half, and the push state the
/// album page's rate sheet and the Memories diary both read).
///
/// One process here (Android runs Settings, the album page and Memories in
/// different Activities and re-reads the token on resume): a sign-in in
/// Settings shows on every line at once through [neoDbAccountProvider].
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../data/neodb/neodb_api.dart';
import '../data/neodb/neodb_config.dart';
import '../data/neodb/neodb_oauth.dart';
import '../data/neodb/neodb_sync_service.dart';
import '../data/neodb/neodb_voices_service.dart';
import '../data/sync/cloud_sync_providers.dart';
import '../detail/detail_cache.dart';
import '../platform/storage/stores.dart';
import '../playback/playback_controller.dart';
import 'album_neodb_sync.dart';

final neoDbHttpClientProvider = Provider<http.Client>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return client;
});

final neoDbApiProvider = Provider<NeoDbApi>((ref) => NeoDbApi(ref.watch(neoDbHttpClientProvider)));

/// The secret store, or none where the platform gives none (previews, tests):
/// then NeoDB is simply signed out.
SecretStore? _secretsOrNull(Ref ref) {
  try {
    return ref.watch(secretStoreProvider);
  } catch (_) {
    return null;
  }
}

final neoDbConfigProvider = Provider<NeoDbConfigStore>(
  (ref) => NeoDbConfigStore(ref.watch(keyValueStoreProvider), _secretsOrNull(ref)),
);

final neoDbMappingsProvider = Provider<NeoDbMappings>((ref) => NeoDbMappings(ref.watch(keyValueStoreProvider)));

final neoDbSyncServiceProvider = Provider<NeoDbSyncService>((ref) {
  final store = ref.watch(keyValueStoreProvider);
  return NeoDbSyncService(
    api: ref.watch(neoDbApiProvider),
    config: ref.watch(neoDbConfigProvider),
    mappings: ref.watch(neoDbMappingsProvider),
    // The same per-account store the screens and cloud sync write through.
    memory: (profileId) => MemoryStore.shared(store, profileId),
  );
});

final neoDbVoicesServiceProvider = Provider<NeoDbVoicesService>(
  (ref) => NeoDbVoicesService(
    api: ref.watch(neoDbApiProvider),
    config: ref.watch(neoDbConfigProvider),
    client: ref.watch(neoDbHttpClientProvider),
  ),
);

final neoDbOAuthProvider = Provider<NeoDbOAuth>(
  (ref) => NeoDbOAuth(api: ref.watch(neoDbApiProvider), host: ref.watch(cloudSyncHostProvider)),
);

/// Opens a web page in the system browser (the Voices page's ways out).
/// Only http(s) is ever opened (NeoDbVoicesParsing.isWebLink).
final neoDbOpenLinkProvider = Provider<Future<bool> Function(Uri url)>((ref) {
  final host = ref.watch(cloudSyncHostProvider);
  return (url) async {
    try {
      return await host.openBrowser(url);
    } catch (_) {
      return false;
    }
  };
});

/// Shows a one-line message on the window's snackbar (with an optional action).
typedef NeoDbMessenger = void Function(String message, {String? actionLabel, VoidCallback? onAction});

final neoDbMessengerProvider = Provider<NeoDbMessenger>(
  (ref) =>
      (message, {actionLabel, onAction}) => ref
          .read(playbackControllerProvider.notifier)
          .showMessage(message, actionLabel: actionLabel, onAction: onAction, long: actionLabel != null),
);

// ---------------------------------------------------------------------------
// The account (Settings › NeoDB).
// ---------------------------------------------------------------------------

@immutable
final class NeoDbAccount {
  const NeoDbAccount({required this.instance, required this.token, required this.hasInstance});

  /// The instance the page shows (neodb.social until one is saved).
  final String instance;

  /// The access token, '' when signed out. Only ever in memory here.
  final String token;

  /// An instance was saved (Android's neodb_config row).
  final bool hasInstance;

  /// Settings' summary reads the token alone (Android NeoDbItem).
  bool get signedIn => token.trim().isNotEmpty;

  /// Sync needs both an instance and a token (NeoDBSyncService.currentSession).
  bool get configured => signedIn && hasInstance;
}

class NeoDbAccountNotifier extends AsyncNotifier<NeoDbAccount> {
  bool _signingIn = false;

  NeoDbConfigStore get _config => ref.read(neoDbConfigProvider);

  @override
  Future<NeoDbAccount> build() async {
    final config = ref.watch(neoDbConfigProvider);
    final stored = await config.storedInstance();
    final token = await config.readToken() ?? '';
    return NeoDbAccount(
      instance: stored ?? NeoDbConfigStore.defaultInstance,
      token: token,
      hasInstance: stored != null,
    );
  }

  /// Saves the instance and a pasted token (Android saveNeoDbConfig). A blank
  /// instance is neodb.social; false when the instance has no readable host.
  Future<bool> saveConfig(String instance, String token) async {
    final normalized = normalizeNeoDbInstance(instance);
    if (normalized == null) return false;
    final trimmed = token.trim();
    await _config.setInstance(normalized);
    await _config.writeToken(trimmed);
    state = AsyncData(NeoDbAccount(instance: normalized, token: trimmed, hasInstance: true));
    return true;
  }

  /// Signs out: the token goes, the instance stays for the next sign-in.
  Future<void> clearToken() async {
    await _config.clearToken();
    final current = state.value;
    state = AsyncData(
      NeoDbAccount(
        instance: current?.instance ?? await _config.instance(),
        token: '',
        hasInstance: current?.hasInstance ?? (await _config.storedInstance()) != null,
      ),
    );
  }

  /// Remembers [instance], then signs in on its web page. Null: the instance
  /// has no readable host (nothing opened). A success saves the token.
  Future<NeoDbOAuthResult?> signIn(String instance) async {
    final normalized = normalizeNeoDbInstance(instance);
    if (normalized == null) return null;
    if (_signingIn) return const NeoDbOAuthCancelled();
    _signingIn = true;
    try {
      await _config.setInstance(normalized);
      final current = state.value;
      state = AsyncData(NeoDbAccount(instance: normalized, token: current?.token ?? '', hasInstance: true));
      final result = await ref.read(neoDbOAuthProvider).signIn(normalized);
      if (result is NeoDbOAuthSuccess && ref.mounted) await saveConfig(result.instance, result.accessToken);
      return result;
    } finally {
      _signingIn = false;
    }
  }

  /// A sign-in is waiting for the browser to come back.
  bool get waitingForBrowser => ref.read(neoDbOAuthProvider).isWaitingForBrowser;

  /// The user came back without finishing on NeoDB's page.
  void cancelSignIn() => ref.read(neoDbOAuthProvider).cancelWaiting();
}

final neoDbAccountProvider = AsyncNotifierProvider<NeoDbAccountNotifier, NeoDbAccount>(NeoDbAccountNotifier.new);

// ---------------------------------------------------------------------------
// Pushes: which albums are on their way and which failed.
// ---------------------------------------------------------------------------

@immutable
final class NeoDbPushState {
  const NeoDbPushState({this.syncing = const {}, this.failed = const {}});

  final Set<MediaId> syncing;

  /// The last push failed; shown while its change still waits.
  final Set<MediaId> failed;

  NeoDbPushState copyWith({Set<MediaId>? syncing, Set<MediaId>? failed}) =>
      NeoDbPushState(syncing: syncing ?? this.syncing, failed: failed ?? this.failed);
}

enum NeoDbPushOutcome { pushed, nothingToPush, notConfigured, failed, busy }

/// Hands an album to NeoDB when something changed since the last push
/// (AlbumDetailViewModel.syncToNeoDb, MemoriesViewModel.pushToNeoDb). Lives
/// with the app, not with the sheet that asked: closing the sheet starts a
/// push that may land after it is gone.
class NeoDbPushController extends Notifier<NeoDbPushState> {
  /// Albums with a push under way, claimed before the first await: the sheet's
  /// close and the Memories save can ask at once, and only one may send.
  final _inFlight = <MediaId>{};

  /// Asked again while busy: the push that runs reads the row before the
  /// newer change landed, so one more follows it (a no-op when nothing waits).
  final _again = <MediaId>{};

  @override
  NeoDbPushState build() => const NeoDbPushState();

  /// Pushes [albumId] of the account in use. A failure shows
  /// [failureMessage] once and leaves the album failed until a push lands.
  Future<NeoDbPushOutcome> push(MediaId albumId, {required String failureMessage, Album? album}) async {
    if (!_inFlight.add(albumId)) {
      _again.add(albumId);
      return NeoDbPushOutcome.busy;
    }
    var registered = false;
    var failed = false;
    try {
      final account = await ref.read(neoDbAccountProvider.future);
      if (!account.configured) return NeoDbPushOutcome.notConfigured;
      final store = await ref.read(memoryStoreProvider.future);
      if (store == null) return NeoDbPushOutcome.nothingToPush;
      final row = await store.albumRatingRow(albumId);
      if (albumNeoDbSync(configured: true, row: row) != AlbumNeoDbSync.pending) {
        _setFailed(albumId, false);
        return NeoDbPushOutcome.nothingToPush;
      }
      state = state.copyWith(syncing: {...state.syncing, albumId});
      registered = true;
      final cache = ref.read(detailCacheProvider);
      final meta = album ?? cache.cachedAlbum(albumId) ?? await cache.album(albumId);
      if (meta == null) throw StateError('Album metadata unavailable');
      await ref.read(neoDbSyncServiceProvider).pushAlbum(store.profileId, meta);
      _setFailed(albumId, false);
      return NeoDbPushOutcome.pushed;
    } catch (e) {
      // Offline with no cached detail, the album read throws too: a failed sync, never a crash.
      debugPrint('NeoDb: push failed for $albumId: ${e.runtimeType}');
      failed = true;
      if (!ref.mounted) return NeoDbPushOutcome.failed;
      _setFailed(albumId, true);
      ref.read(neoDbMessengerProvider)(failureMessage);
      return NeoDbPushOutcome.failed;
    } finally {
      _inFlight.remove(albumId);
      if (registered && ref.mounted) {
        state = state.copyWith(syncing: {...state.syncing}..remove(albumId));
      }
      // A failure already said so and waits for Retry; a landed push takes the newer change too.
      if (_again.remove(albumId) && !failed && ref.mounted) {
        unawaited(push(albumId, failureMessage: failureMessage, album: album));
      }
    }
  }

  void _setFailed(MediaId albumId, bool failed) {
    if (!ref.mounted || state.failed.contains(albumId) == failed) return;
    state = state.copyWith(failed: failed ? {...state.failed, albumId} : ({...state.failed}..remove(albumId)));
  }
}

final neoDbPushProvider = NotifierProvider<NeoDbPushController, NeoDbPushState>(NeoDbPushController.new);

// ---------------------------------------------------------------------------
// Where NeoDB stands for one album (the sheet's and the diary's line).
// ---------------------------------------------------------------------------

class AlbumNeoDbNotifier extends Notifier<AlbumNeoDbSync> {
  AlbumNeoDbNotifier(this.albumId);

  final MediaId albumId;
  AlbumRatingRow? _row;
  bool _loaded = false;
  int _generation = 0;
  StreamSubscription<MemoryStoreChange>? _changes;

  @override
  AlbumNeoDbSync build() {
    final account = ref.watch(neoDbAccountProvider);
    final pushes = ref.watch(neoDbPushProvider);
    final storeValue = ref.watch(memoryStoreProvider);
    final store = storeValue.value;
    _changes?.cancel();
    _changes = store?.changes.listen((c) {
      if (c == MemoryStoreChange.albumRatings) _reload(store);
    });
    ref.onDispose(() => _changes?.cancel());
    if (!storeValue.isLoading) scheduleMicrotask(() => _reload(store));
    return _compute(account, pushes);
  }

  Future<void> _reload(MemoryStore? store) async {
    final generation = ++_generation;
    AlbumRatingRow? row;
    try {
      row = await store?.albumRatingRow(albumId);
    } catch (_) {
      row = null;
    }
    if (!ref.mounted || generation != _generation) return;
    _row = row;
    _loaded = true;
    state = _compute(ref.read(neoDbAccountProvider), ref.read(neoDbPushProvider));
  }

  AlbumNeoDbSync _compute(AsyncValue<NeoDbAccount> account, NeoDbPushState pushes) {
    if (!_loaded || (account.isLoading && !account.hasValue)) return AlbumNeoDbSync.unknown;
    final configured = account.value?.configured ?? false;
    if (configured && pushes.syncing.contains(albumId)) return AlbumNeoDbSync.syncing;
    final base = albumNeoDbSync(configured: configured, row: _row);
    // A failure stands only while its change still waits: synced elsewhere, it's gone.
    if (base == AlbumNeoDbSync.pending && pushes.failed.contains(albumId)) return AlbumNeoDbSync.failed;
    return base;
  }
}

final albumNeoDbProvider = NotifierProvider.autoDispose.family<AlbumNeoDbNotifier, AlbumNeoDbSync, MediaId>(
  AlbumNeoDbNotifier.new,
);
