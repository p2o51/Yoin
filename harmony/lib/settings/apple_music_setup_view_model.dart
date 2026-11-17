import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/media_id.dart';
import '../data/profile/profile.dart';
import '../data/profile/profile_repository.dart';
import '../platform/applemusic/apple_music_auth.dart';

/// The MusicKit developer-token service Yoin runs (Android YoinTokenService.URL).
/// Public on purpose: it hands out short-lived developer tokens, nothing
/// account-specific. The setup page offers it as the default endpoint.
const yoinAppleMusicTokenServiceUrl = 'https://yoin-apple-music-token.p2o51willam.workers.dev/token';

/// What the Apple Music form can say is wrong before sign-in even starts.
enum AppleSetupError {
  endpointRequired, // settings_apple_endpoint_required (mirror)
  endpointInvalid, // settings_apple_endpoint check
}

/// The status line under the field (Android AppleMusicValidationUiState.status).
sealed class AppleSetupStatus {
  const AppleSetupStatus();
}

/// Nothing happening yet: the page shows "Connect your Apple Music account".
final class AppleSetupIdle extends AppleSetupStatus {
  const AppleSetupIdle();
}

/// The ArkWeb sign-in is open: "Complete authorization in Apple Music."
final class AppleSetupAuthorizing extends AppleSetupStatus {
  const AppleSetupAuthorizing();
}

/// Signed in; saving the account.
final class AppleSetupSaving extends AppleSetupStatus {
  const AppleSetupSaving();
}

/// Sign-in did not finish ([failure] decides the message; Android collapses
/// every Apple failure to "Couldn't connect"), or [accountLimit]: five
/// accounts are kept already, so none was added (settings_setup_account_limit).
final class AppleSetupFailed extends AppleSetupStatus {
  const AppleSetupFailed(this.failure, {this.accountLimit = false});
  final AppleMusicAuthFailure failure;
  final bool accountLimit;
}

final class AppleMusicSetupState {
  const AppleMusicSetupState({
    this.status = const AppleSetupIdle(),
    this.endpointError,
    this.connected = false,
    this.canAddProfile = true,
  });

  final AppleSetupStatus status;

  /// Fewer than [ProfileRepository.maxProfiles] accounts are kept: Connect may add one.
  final bool canAddProfile;

  /// Said under the endpoint field, cleared as it is edited.
  final AppleSetupError? endpointError;

  /// The account is saved and active: the page closes.
  final bool connected;

  bool get isBusy => status is AppleSetupAuthorizing || status is AppleSetupSaving;

  AppleMusicSetupState copyWith({
    AppleSetupStatus? status,
    AppleSetupError? endpointError,
    bool clearEndpointError = false,
    bool? connected,
    bool? canAddProfile,
  }) =>
      AppleMusicSetupState(
        status: status ?? this.status,
        endpointError: clearEndpointError ? null : (endpointError ?? this.endpointError),
        connected: connected ?? this.connected,
        canAddProfile: canAddProfile ?? this.canAddProfile,
      );
}

/// Adding an Apple Music account (Android AppleMusicValidationViewModel, the
/// connect half). The ArkWeb sign-in does the Apple ID step; this only starts
/// it, then saves the Music User Token it returns.
class AppleMusicSetupViewModel extends Notifier<AppleMusicSetupState> {
  /// The account limit's refusal (no sign-in is started for an account that can't be kept).
  static const limitReached = AppleSetupFailed(AppleMusicAuthFailure.unavailable, accountLimit: true);

  @override
  AppleMusicSetupState build() {
    _readLimit();
    return const AppleMusicSetupState();
  }

  /// Whether another account fits (MAX_PROFILES).
  Future<void> _readLimit() async {
    final List<Profile> profiles;
    try {
      profiles = await ref.read(profileRepositoryProvider).profiles();
    } catch (_) {
      return;
    }
    if (!ref.mounted) return;
    final fits = profiles.length < ProfileRepository.maxProfiles;
    if (fits != state.canAddProfile) state = state.copyWith(canAddProfile: fits);
  }

  /// Starts sign-in for [endpoint], then saves the account and makes it active.
  Future<void> connect(String endpoint) async {
    if (state.isBusy) return;
    final checked = _validate(endpoint);
    if (checked == null) return;
    if (!state.canAddProfile) {
      state = state.copyWith(status: limitReached);
      return;
    }
    // Held across the awaits: once the save starts, the app must follow the
    // new active account even if this page was closed meanwhile.
    final container = ref.container;
    final profiles = ref.read(profileRepositoryProvider);
    final auth = ref.read(appleMusicAuthProvider);

    state = state.copyWith(status: const AppleSetupAuthorizing(), clearEndpointError: true);
    final AppleMusicAuthResult result;
    try {
      result = await auth.authorize(checked);
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
      await profiles.add(
        provider: MediaId.providerAppleMusic,
        displayName: 'Apple Music',
        credentials: AppleMusicCredentials(endpoint: checked, musicUserToken: result.musicUserToken),
      );
    } on ProfileLimitReachedException {
      if (ref.mounted) state = state.copyWith(status: limitReached, canAddProfile: false);
      return;
    } catch (_) {
      if (ref.mounted) state = state.copyWith(status: const AppleSetupFailed(AppleMusicAuthFailure.unavailable));
      return;
    }
    // ProfileRepository.add made it active in storage: the running app follows.
    container.invalidate(activeSourceProvider);
    if (ref.mounted) state = state.copyWith(connected: true);
  }

  /// The endpoint was edited: what was said about the old one no longer holds.
  void onEndpointChanged() {
    if (state.endpointError != null) state = state.copyWith(clearEndpointError: true);
    if (state.status is AppleSetupFailed) state = state.copyWith(status: const AppleSetupIdle());
  }

  /// The trimmed endpoint, or null (and an error set) when it isn't usable.
  String? _validate(String endpoint) {
    final trimmed = endpoint.trim();
    if (trimmed.isEmpty) {
      state = state.copyWith(status: const AppleSetupIdle(), endpointError: AppleSetupError.endpointRequired);
      return null;
    }
    if (!_isHttpsOrLoopback(trimmed)) {
      state = state.copyWith(status: const AppleSetupIdle(), endpointError: AppleSetupError.endpointInvalid);
      return null;
    }
    state = state.copyWith(clearEndpointError: true);
    return trimmed;
  }

  static bool _isHttpsOrLoopback(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null || uri.host.isEmpty || uri.userInfo.isNotEmpty) return false;
    if (uri.isScheme('https')) return true;
    final loopback = const {'localhost', '127.0.0.1', '::1'}.contains(uri.host);
    return loopback && uri.isScheme('http');
  }
}

final appleMusicSetupViewModelProvider =
    NotifierProvider.autoDispose<AppleMusicSetupViewModel, AppleMusicSetupState>(AppleMusicSetupViewModel.new);
