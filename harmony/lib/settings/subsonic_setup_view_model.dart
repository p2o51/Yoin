import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/media_id.dart';
import '../data/profile/profile.dart';
import '../data/source/music_source.dart';

enum SetupField { address, username, password }

enum SetupFailure { unreachable, serverError, saveFailed }

/// Adding a Subsonic account (Android ServiceSetupActivity, Subsonic page).
sealed class SubsonicSetupState {
  const SubsonicSetupState();
}

final class SetupEditing extends SubsonicSetupState {
  const SetupEditing({this.missing = const {}, this.failure});

  /// Required fields left empty on the last attempt.
  final Set<SetupField> missing;
  final SetupFailure? failure;
}

final class SetupConnecting extends SubsonicSetupState {
  const SetupConnecting();
}

final class SetupConnected extends SubsonicSetupState {
  const SetupConnected();
}

class SubsonicSetupViewModel extends Notifier<SubsonicSetupState> {
  @override
  SubsonicSetupState build() => const SetupEditing();

  /// Validates, pings the server with the credentials, then saves and activates the profile.
  Future<void> connect({required String address, required String username, required String password}) async {
    if (state is SetupConnecting) return;
    final missing = {
      if (address.trim().isEmpty) SetupField.address,
      if (username.trim().isEmpty) SetupField.username,
      if (password.isEmpty) SetupField.password,
    };
    if (missing.isNotEmpty) {
      state = SetupEditing(missing: missing);
      return;
    }
    state = const SetupConnecting();
    final credentials = SubsonicCredentials(
      baseUrl: normalizeAddress(address),
      username: username.trim(),
      password: password,
    );
    final source = ref.read(musicSourceFactoryProvider)(credentials);
    try {
      if (!await source.library.ping()) {
        state = const SetupEditing(failure: SetupFailure.serverError);
        return;
      }
    } on SourceNetworkException {
      state = const SetupEditing(failure: SetupFailure.unreachable);
      return;
    } on SourceException {
      state = const SetupEditing(failure: SetupFailure.serverError);
      return;
    } finally {
      source.dispose();
    }
    try {
      await ref.read(profileRepositoryProvider).add(
            provider: MediaId.providerSubsonic,
            displayName: Uri.parse(credentials.baseUrl).host,
            credentials: credentials,
          );
    } catch (_) {
      state = const SetupEditing(failure: SetupFailure.saveFailed);
      return;
    }
    ref.invalidate(activeSourceProvider);
    state = const SetupConnected();
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
}

final subsonicSetupViewModelProvider =
    NotifierProvider.autoDispose<SubsonicSetupViewModel, SubsonicSetupState>(SubsonicSetupViewModel.new);
