import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/media_id.dart';
import '../data/profile/profile.dart';
import '../data/profile/profile_repository.dart';
import '../data/source/music_source.dart';
import '../ui/component/user_message.dart';
import 'account_identity.dart';
import 'settings_view_model.dart';

/// What the form can say went wrong (keys of strings_settings.xml).
enum SetupError {
  addressRequired, // settings_setup_address_required
  checkAddress, // settings_setup_check_address
  usernameRequired, // settings_setup_username_required
  passwordRequired, // settings_setup_password_required
  serverError, // settings_setup_server_error
  unreachable, // settings_setup_unreachable
  saveFailed, // settings_setup_save_failed
  accountLimit, // settings_setup_account_limit
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

/// The server answered the test. [server]: what it calls itself ("Navidrome
/// 0.64.2", the ping's OpenSubsonic type and serverVersion); null when it
/// doesn't say (Android main SubsonicStatus.Reachable).
final class SetupReachable extends SetupStatus {
  const SetupReachable({this.server});
  final String? server;

  @override
  bool operator ==(Object other) => other is SetupReachable && other.server == server;

  @override
  int get hashCode => server.hashCode;
}

/// [error], or — when the server said what was wrong ("Wrong username or
/// password") — its own words in [serverMessage], as Android shows them; a
/// server that didn't answer says which way in [line] (unreachable, taking
/// too long, or the secure connection failed: UserMessage.kt).
final class SetupFailed extends SetupStatus {
  const SetupFailed(this.error, {this.serverMessage, this.line});
  final SetupError error;
  final String? serverMessage;
  final UserLine? line;
}

/// Adding or managing a Subsonic account (Android ServiceSetupViewModel,
/// Subsonic half). Managing, the form starts from the stored credentials.
final class SubsonicSetupState {
  const SubsonicSetupState({
    this.status = const SetupIdle(),
    this.addressError,
    this.connected = false,
    this.loaded = true,
    this.stored,
    this.account,
    this.canAddProfile = true,
  });

  final SetupStatus status;

  /// Adding: fewer than [ProfileRepository.maxProfiles] accounts are kept, so
  /// Connect may save one (ServiceSetupUiState.canAddProfile). Always true
  /// while managing.
  final bool canAddProfile;

  /// The stored values have been read (always true while adding).
  final bool loaded;

  /// Managing: what the fields start from; null while adding, or when the
  /// account's credentials are missing.
  final SubsonicCredentials? stored;

  /// Managing: the account's face, as its Settings card shows it.
  final AccountCard? account;

  bool get isManaging => account != null;

  /// Managing an account whose credentials couldn't be read.
  bool get credentialsMissing => account?.credentialsMissing ?? false;

  /// Said under the address field, not in the status line; cleared as the address is edited.
  final SetupError? addressError;

  /// The account is saved and active: the page closes.
  final bool connected;

  bool get isBusy => status is SetupTesting || status is SetupSaving;

  SubsonicSetupState copyWith({
    SetupStatus? status,
    SetupError? addressError,
    bool clearAddressError = false,
    bool? connected,
    bool? canAddProfile,
  }) =>
      SubsonicSetupState(
        status: status ?? this.status,
        addressError: clearAddressError ? null : (addressError ?? this.addressError),
        connected: connected ?? this.connected,
        loaded: loaded,
        stored: stored,
        account: account,
        canAddProfile: canAddProfile ?? this.canAddProfile,
      );
}

class SubsonicSetupViewModel extends Notifier<SubsonicSetupState> {
  /// [profileId] null = adding; else the account being managed.
  SubsonicSetupViewModel(this.profileId);

  final String? profileId;

  @override
  SubsonicSetupState build() {
    final id = profileId;
    if (id == null) {
      _readLimit();
      return const SubsonicSetupState();
    }
    _loadExisting(id);
    return const SubsonicSetupState(loaded: false);
  }

  /// Adding: whether another account fits (MAX_PROFILES).
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

  Future<void> _loadExisting(String id) async {
    final repo = ref.read(profileRepositoryProvider);
    final profiles = await repo.profiles();
    final profile = profiles.where((p) => p.id == id).firstOrNull;
    if (profile == null) return;
    final active = await repo.activeProfile();
    ProfileCredentials? credentials;
    try {
      credentials = await repo.credentials(id);
    } catch (_) {
      credentials = null;
    }
    final shapes = assignAvatarShapes([for (final p in profiles) p.id]);
    if (!ref.mounted) return;
    state = SubsonicSetupState(
      status: state.status,
      addressError: state.addressError,
      stored: credentials is SubsonicCredentials ? credentials : null,
      account: accountCardFor(profile, credentials, isActive: profile.id == active?.id, avatarShape: shapes[id] ?? 0),
    );
  }

  /// Test: reaches the server with these credentials and says whether it answered.
  Future<void> test({required String address, required String username, required String password}) async {
    if (state.isBusy) return;
    final credentials = _validate(address, username, password);
    if (credentials == null) return;
    state = state.copyWith(status: const SetupTesting());
    String? server;
    final failure = await _probe(credentials, named: (name) => server = name);
    if (!ref.mounted) return;
    state = state.copyWith(status: failure ?? SetupReachable(server: server));
  }

  /// Connect (adding): reaches the server, then saves the account and makes
  /// it the one in use. Save (managing): reaches the server, then rewrites
  /// the account's credentials; the account in use reconnects with them.
  Future<void> connect({required String address, required String username, required String password}) async {
    if (state.isBusy || !state.loaded) return;
    final editing = profileId;
    if (editing != null) return _save(editing, address: address, username: username, password: password);
    final credentials = _validate(address, username, password);
    if (credentials == null) return;
    if (!state.canAddProfile) {
      state = state.copyWith(status: const SetupFailed(SetupError.accountLimit));
      return;
    }
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
    } on ProfileLimitReachedException {
      // Another account was added meanwhile (another page, cloud sync's Add).
      if (ref.mounted) {
        state = state.copyWith(status: const SetupFailed(SetupError.accountLimit), canAddProfile: false);
      }
      return;
    } catch (_) {
      if (ref.mounted) state = state.copyWith(status: const SetupFailed(SetupError.saveFailed));
      return;
    }
    // ProfileRepository.add made it active in storage: the running app follows.
    container.invalidate(activeSourceProvider);
    if (ref.mounted) state = state.copyWith(connected: true);
  }

  Future<void> _save(String id, {required String address, required String username, required String password}) async {
    final credentials = _validate(address, username, password);
    if (credentials == null) return;
    final container = ref.container;
    final wasActive = state.account?.isActive ?? false;
    state = state.copyWith(status: const SetupSaving());
    final failure = await _probe(credentials);
    if (!ref.mounted) return;
    if (failure != null) {
      state = state.copyWith(status: failure);
      return;
    }
    try {
      await updateProfileCredentials(ref, id, displayName: displayNameFor(credentials), credentials: credentials);
    } catch (_) {
      if (ref.mounted) state = state.copyWith(status: const SetupFailed(SetupError.saveFailed));
      return;
    }
    // The account in use reconnects with the new credentials; Settings re-reads either way.
    if (wasActive) container.invalidate(activeSourceProvider);
    container.read(accountsRevisionProvider.notifier).bump();
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

  /// null when the server answered, else what to say. With [named] (Test),
  /// one ping that also reads what the server calls itself, handed to [named].
  Future<SetupFailed?> _probe(SubsonicCredentials credentials, {void Function(String? name)? named}) async {
    final source = ref.read(musicSourceFactoryProvider)(credentials);
    try {
      final identity = named == null ? null : source.serverIdentity;
      if (identity != null) {
        named!(await identity.pingServerName());
        return null;
      }
      return await source.library.ping() ? null : const SetupFailed(SetupError.serverError);
    } on SourceNetworkException catch (e) {
      return SetupFailed(SetupError.unreachable, line: UserLine.forNetwork(e.kind));
    } on SourceException catch (e) {
      final message = e.message.trim();
      return SetupFailed(SetupError.serverError, serverMessage: message.isEmpty ? null : message);
    } catch (e) {
      return SetupFailed(SetupError.unreachable, line: userLineOf(e));
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

/// By account: null adds one, an id manages that account.
final subsonicSetupProvider =
    NotifierProvider.autoDispose.family<SubsonicSetupViewModel, SubsonicSetupState, String?>(SubsonicSetupViewModel.new);

/// Adding an account.
final subsonicSetupViewModelProvider = subsonicSetupProvider(null);
