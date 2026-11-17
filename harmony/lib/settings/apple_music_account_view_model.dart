import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/applemusic/apple_music_name_region.dart';
import '../data/profile/profile.dart';
import '../detail/detail_cache.dart';
import '../platform/applemusic/apple_music_auth.dart';
import 'account_identity.dart';
import 'apple_music_setup_view_model.dart';
import 'settings_view_model.dart';

/// An Apple Music account's own page (Android ServiceSetupScreen managing an
/// Apple Music account + AppleMusicValidationViewModel + its name region):
/// the developer-token service it uses, Reconnect, and its song names.
final class AppleMusicAccountState {
  const AppleMusicAccountState({
    this.loaded = false,
    this.account,
    this.endpoint = '',
    this.connected = false,
    this.status = const AppleSetupIdle(),
    this.endpointError,
    this.nameRegion = AppleMusicNameRegion.defaultRegion,
    this.storefront,
  });

  final bool loaded;

  /// The account as Settings shows it; null once it was removed.
  final AccountCard? account;

  /// The stored developer-token service.
  final String endpoint;

  /// The account holds a Music User Token (Android `connected`): the button reads Reconnect.
  final bool connected;
  final AppleSetupStatus status;
  final AppleSetupError? endpointError;
  final AppleMusicNameRegion nameRegion;

  /// The storefront the last Reconnect signed in to (Android shows it under
  /// the status once connected); null until then.
  final String? storefront;

  bool get isBusy => status is AppleSetupAuthorizing || status is AppleSetupSaving;

  AppleMusicAccountState copyWith({
    bool? loaded,
    AccountCard? account,
    String? endpoint,
    bool? connected,
    AppleSetupStatus? status,
    AppleSetupError? endpointError,
    bool clearEndpointError = false,
    AppleMusicNameRegion? nameRegion,
    String? storefront,
  }) => AppleMusicAccountState(
    loaded: loaded ?? this.loaded,
    account: account ?? this.account,
    endpoint: endpoint ?? this.endpoint,
    connected: connected ?? this.connected,
    status: status ?? this.status,
    endpointError: clearEndpointError ? null : (endpointError ?? this.endpointError),
    nameRegion: nameRegion ?? this.nameRegion,
    storefront: storefront ?? this.storefront,
  );
}

class AppleMusicAccountViewModel extends Notifier<AppleMusicAccountState> {
  AppleMusicAccountViewModel(this.profileId);

  final String profileId;

  @override
  AppleMusicAccountState build() {
    ref.watch(accountsRevisionProvider);
    _load();
    return stateOrNull ?? const AppleMusicAccountState();
  }

  Future<void> _load() async {
    final repo = ref.read(profileRepositoryProvider);
    final profiles = await repo.profiles();
    final profile = profiles.where((p) => p.id == profileId).firstOrNull;
    if (!ref.mounted) return;
    if (profile == null) {
      state = state.copyWith(loaded: true);
      return;
    }
    ProfileCredentials? credentials;
    try {
      credentials = await repo.credentials(profileId);
    } catch (_) {
      credentials = null;
    }
    final active = await repo.activeProfile();
    final shapes = assignAvatarShapes([for (final p in profiles) p.id]);
    final region = await AppleMusicNameRegionStore(ref.read(keyValueStoreProvider)).read(profileId);
    if (!ref.mounted) return;
    final apple = credentials is AppleMusicCredentials ? credentials : null;
    state = state.copyWith(
      loaded: true,
      account: accountCardFor(
        profile,
        credentials,
        isActive: profile.id == active?.id,
        avatarShape: shapes[profile.id] ?? 0,
      ),
      // The stored service, else Yoin's own (an account whose credentials were lost).
      endpoint: apple?.endpoint ?? (state.endpoint.isEmpty ? yoinAppleMusicTokenServiceUrl : state.endpoint),
      connected: apple != null,
      nameRegion: region,
    );
  }

  /// The endpoint was edited: what was said about the old one no longer holds.
  void onEndpointChanged() {
    if (state.endpointError != null) state = state.copyWith(clearEndpointError: true);
    if (state.status is AppleSetupFailed) state = state.copyWith(status: const AppleSetupIdle());
  }

  /// Signs in again with [endpoint] and keeps the new Music User Token for
  /// this account (Android authorizationResult on an existing profile). The
  /// app follows when the account is the one in use.
  Future<void> reconnect(String endpoint) async {
    if (state.isBusy) return;
    final trimmed = endpoint.trim();
    if (trimmed.isEmpty) {
      state = state.copyWith(endpointError: AppleSetupError.endpointRequired);
      return;
    }
    if (!isAppleTokenEndpoint(trimmed)) {
      state = state.copyWith(endpointError: AppleSetupError.endpointInvalid);
      return;
    }
    final container = ref.container;
    final repo = ref.read(profileRepositoryProvider);
    final auth = ref.read(appleMusicAuthProvider);
    state = state.copyWith(status: const AppleSetupAuthorizing(), clearEndpointError: true);
    final AppleMusicAuthResult result;
    try {
      result = await auth.authorize(trimmed);
    } on AppleMusicAuthException catch (e) {
      if (ref.mounted) state = state.copyWith(status: AppleSetupFailed(e.failure));
      return;
    } catch (_) {
      if (ref.mounted) state = state.copyWith(status: const AppleSetupFailed(AppleMusicAuthFailure.unavailable));
      return;
    }
    if (!ref.mounted) return;
    state = state.copyWith(status: const AppleSetupSaving());
    try {
      final profile = (await repo.profiles()).where((p) => p.id == profileId).firstOrNull;
      // "This account was removed. Add it again." (Android): nothing to save to.
      if (profile == null) throw StateError('removed');
      await updateProfileCredentials(
        ref,
        profileId,
        displayName: profile.displayName,
        credentials: AppleMusicCredentials(endpoint: trimmed, musicUserToken: result.musicUserToken),
      );
      final active = await repo.activeProfile();
      if (active?.id == profileId) {
        container.invalidate(activeSourceProvider);
      } else {
        container.read(accountsRevisionProvider.notifier).bump();
      }
    } catch (_) {
      if (ref.mounted) state = state.copyWith(status: const AppleSetupFailed(AppleMusicAuthFailure.unavailable));
      return;
    }
    if (ref.mounted) {
      state = state.copyWith(
        status: const AppleSetupIdle(),
        endpoint: trimmed,
        connected: true,
        storefront: result.storefront,
      );
    }
  }

  /// Keeps [region] for this account. Pages already read carry the old names,
  /// so they are forgotten and every screen reads its content again (Android
  /// forgetDetails + notifyMusicConfigurationChanged).
  Future<void> setNameRegion(AppleMusicNameRegion region) async {
    if (state.nameRegion == region) return;
    final container = ref.container;
    final previous = state.nameRegion;
    state = state.copyWith(nameRegion: region);
    try {
      await AppleMusicNameRegionStore(ref.read(keyValueStoreProvider)).write(profileId, region);
    } catch (_) {
      // Not kept: the radio goes back to what is stored.
      if (ref.mounted && state.nameRegion == region) state = state.copyWith(nameRegion: previous);
      return;
    }
    final active = await container.read(profileRepositoryProvider).activeProfile();
    if (active?.id != profileId) {
      // Another account: what Memories and Home read from it is read again.
      container.read(accountsRevisionProvider.notifier).bump();
      return;
    }
    container.read(detailCacheProvider).clear();
    container.invalidate(activeSourceProvider);
  }
}

/// The token service's address: HTTPS, or HTTP on this device (Android requires https).
bool isAppleTokenEndpoint(String url) {
  final uri = Uri.tryParse(url);
  if (uri == null || uri.host.isEmpty || uri.userInfo.isNotEmpty) return false;
  if (uri.isScheme('https')) return true;
  return const {'localhost', '127.0.0.1', '::1'}.contains(uri.host) && uri.isScheme('http');
}

final appleMusicAccountProvider = NotifierProvider.autoDispose
    .family<AppleMusicAccountViewModel, AppleMusicAccountState, String>(AppleMusicAccountViewModel.new);
