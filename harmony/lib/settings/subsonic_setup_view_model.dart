import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/media_id.dart';
import '../data/profile/profile.dart';
import '../data/source/music_source.dart';

/// What the form can say went wrong (keys of strings_settings.xml).
enum SetupError {
  addressRequired, // settings_setup_address_required
  checkAddress, // settings_setup_check_address
  usernameRequired, // settings_setup_username_required
  passwordRequired, // settings_setup_password_required
  serverError, // settings_setup_server_error
  unreachable, // settings_setup_unreachable
  saveFailed, // settings_setup_save_failed
}

/// The status line under the fields (ServiceSetupUiState.kt SubsonicStatus).
sealed class SetupStatus {
  const SetupStatus();
}

final class SetupIdle extends SetupStatus {
  const SetupIdle();
}

/// Test pressed: reaching the server.
final class SetupTesting extends SetupStatus {
  const SetupTesting();
}

/// Connect pressed: reaching the server, then saving the account.
final class SetupSaving extends SetupStatus {
  const SetupSaving();
}

/// The server answered the test.
final class SetupReachable extends SetupStatus {
  const SetupReachable();
}

/// [error], or — when the server said what was wrong ("Wrong username or
/// password") — its own words in [serverMessage], as Android shows them.
final class SetupFailed extends SetupStatus {
  const SetupFailed(this.error, {this.serverMessage});
  final SetupError error;
  final String? serverMessage;
}

/// Adding a Subsonic account (Android ServiceSetupViewModel, Subsonic half).
final class SubsonicSetupState {
  const SubsonicSetupState({this.status = const SetupIdle(), this.addressError, this.connected = false});

  final SetupStatus status;

  /// Said under the address field, not in the status line; cleared as the address is edited.
  final SetupError? addressError;

  /// The account is saved and active: the page closes.
  final bool connected;

  bool get isBusy => status is SetupTesting || status is SetupSaving;

  SubsonicSetupState copyWith({SetupStatus? status, SetupError? addressError, bool clearAddressError = false, bool? connected}) =>
      SubsonicSetupState(
        status: status ?? this.status,
        addressError: clearAddressError ? null : (addressError ?? this.addressError),
        connected: connected ?? this.connected,
      );
}

class SubsonicSetupViewModel extends Notifier<SubsonicSetupState> {
  @override
  SubsonicSetupState build() => const SubsonicSetupState();

  /// Test: reaches the server with these credentials and says whether it answered.
  Future<void> test({required String address, required String username, required String password}) async {
    if (state.isBusy) return;
    final credentials = _validate(address, username, password);
    if (credentials == null) return;
    state = state.copyWith(status: const SetupTesting());
    final failure = await _probe(credentials);
    if (!ref.mounted) return;
    state = state.copyWith(status: failure ?? const SetupReachable());
  }

  /// Connect: reaches the server, then saves the account and makes it the one in use.
  Future<void> connect({required String address, required String username, required String password}) async {
    if (state.isBusy) return;
    final credentials = _validate(address, username, password);
    if (credentials == null) return;
    // Held across the awaits: once the save has started, the app has to
    // follow the new active account even if this page was closed meanwhile.
    final container = ref.container;
    final profiles = ref.read(profileRepositoryProvider);
    state = state.copyWith(status: const SetupSaving());
    final failure = await _probe(credentials);
    if (!ref.mounted) return;
    if (failure != null) {
      state = state.copyWith(status: failure);
      return;
    }
    try {
      await profiles.add(
            provider: MediaId.providerSubsonic,
            displayName: displayNameFor(credentials),
            credentials: credentials,
          );
    } catch (_) {
      if (ref.mounted) state = state.copyWith(status: const SetupFailed(SetupError.saveFailed));
      return;
    }
    // ProfileRepository.add made it active in storage: the running app follows.
    container.invalidate(activeSourceProvider);
    if (ref.mounted) state = state.copyWith(connected: true);
  }

  /// The address was edited: what was said about the old one no longer holds.
  void onAddressChanged() {
    if (state.addressError != null) state = state.copyWith(clearAddressError: true);
  }

  SubsonicCredentials? _validate(String address, String username, String password) {
    final url = address.trim().isEmpty ? null : normalizeAddress(address);
    final addressError = switch (url) {
      null => SetupError.addressRequired,
      _ when !_isHttpUrl(url) => SetupError.checkAddress,
      _ => null,
    };
    if (addressError != null) {
      state = state.copyWith(status: const SetupIdle(), addressError: addressError);
      return null;
    }
    final error = username.trim().isEmpty
        ? SetupError.usernameRequired
        : password.trim().isEmpty
            ? SetupError.passwordRequired
            : null;
    if (error != null) {
      state = state.copyWith(status: SetupFailed(error), clearAddressError: true);
      return null;
    }
    state = state.copyWith(clearAddressError: true);
    return SubsonicCredentials(baseUrl: url!, username: username.trim(), password: password);
  }

  /// null when the server answered, else what to say.
  Future<SetupFailed?> _probe(SubsonicCredentials credentials) async {
    final source = ref.read(musicSourceFactoryProvider)(credentials);
    try {
      return await source.library.ping() ? null : const SetupFailed(SetupError.serverError);
    } on SourceNetworkException {
      return const SetupFailed(SetupError.unreachable);
    } on SourceException catch (e) {
      final message = e.message.trim();
      return SetupFailed(SetupError.serverError, serverMessage: message.isEmpty ? null : message);
    } catch (_) {
      return const SetupFailed(SetupError.unreachable);
    } finally {
      source.dispose();
    }
  }

  static bool _isHttpUrl(String url) {
    final uri = Uri.tryParse(url);
    return uri != null && (uri.scheme == 'http' || uri.scheme == 'https') && uri.host.isNotEmpty;
  }

  /// Trims, adds https:// when no scheme was typed, drops trailing slashes.
  static String normalizeAddress(String raw) {
    var value = raw.trim();
    if (!value.contains('://')) value = 'https://$value';
    while (value.endsWith('/')) {
      value = value.substring(0, value.length - 1);
    }
    return value;
  }

  /// The stored account name, as Android builds it: "user @ host".
  static String displayNameFor(SubsonicCredentials credentials) {
    final host = Uri.tryParse(credentials.baseUrl)?.host ?? '';
    final username = credentials.username;
    if (host.isNotEmpty && username.isNotEmpty) return '$username @ $host';
    if (host.isNotEmpty) return host;
    if (username.isNotEmpty) return username;
    return credentials.baseUrl;
  }
}

final subsonicSetupViewModelProvider =
    NotifierProvider.autoDispose<SubsonicSetupViewModel, SubsonicSetupState>(SubsonicSetupViewModel.new);
