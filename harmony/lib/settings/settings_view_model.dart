import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/profile/profile.dart';
import 'account_identity.dart';

/// One account as Settings shows it (ProfileCards.kt ProfileCard): Subsonic
/// leads with the username and puts the server's host on the service line.
final class AccountCard {
  const AccountCard({
    required this.id,
    required this.displayName,
    required this.title,
    required this.host,
    required this.isActive,
    required this.avatarShape,
  });

  final String id;

  /// The stored name ("user @ host"), as the remove dialog quotes it.
  final String displayName;

  /// The card's name: the username, else [displayName].
  final String title;

  /// The server's host, for the service line; null when unknown.
  final String? host;
  final bool isActive;
  final int avatarShape;
}

/// Settings (Android SettingsScreen, v1: the account list). Lists, switches
/// and removes accounts; adding one opens the service's setup page.
sealed class SettingsUiState {
  const SettingsUiState();
}

final class SettingsLoading extends SettingsUiState {
  const SettingsLoading();
}

final class SettingsContent extends SettingsUiState {
  const SettingsContent({required this.accounts});

  final List<AccountCard> accounts;

  /// null with no account.
  String? get activeId => accounts.where((a) => a.isActive).firstOrNull?.id;
}

class SettingsViewModel extends Notifier<SettingsUiState> {
  int _generation = 0;

  @override
  SettingsUiState build() {
    // Re-read whenever an account is added, switched or removed (each
    // invalidates the active source).
    ref.watch(activeSourceProvider);
    _load();
    // Keep the list on screen while it re-reads (no Loading flash).
    return stateOrNull ?? const SettingsLoading();
  }

  Future<void> _load() async {
    final generation = ++_generation;
    final repo = ref.read(profileRepositoryProvider);
    final profiles = await repo.profiles();
    final active = await repo.activeProfile();
    // Oldest first: the stored list is in the order accounts were added.
    final shapes = assignAvatarShapes([for (final p in profiles) p.id]);
    final cards = <AccountCard>[];
    for (final p in profiles) {
      ProfileCredentials? credentials;
      try {
        credentials = await repo.credentials(p.id);
      } catch (_) {
        credentials = null;
      }
      cards.add(_card(p, credentials, isActive: p.id == active?.id, avatarShape: shapes[p.id] ?? 0));
    }
    if (!ref.mounted || generation != _generation) return;
    state = SettingsContent(accounts: cards);
  }

  static AccountCard _card(Profile p, ProfileCredentials? credentials, {required bool isActive, required int avatarShape}) {
    final subsonic = credentials is SubsonicCredentials ? credentials : null;
    final host = subsonic == null ? null : Uri.tryParse(subsonic.baseUrl)?.host;
    final username = subsonic?.username.trim();
    return AccountCard(
      id: p.id,
      displayName: p.displayName,
      title: (username == null || username.isEmpty) ? p.displayName : username,
      host: (host == null || host.isEmpty) ? null : host,
      isActive: isActive,
      avatarShape: avatarShape,
    );
  }

  /// Makes [profileId] the active account. The playback chain, Home, Library
  /// and the detail caches follow the active source. Returns false when the
  /// switch could not be saved.
  Future<bool> switchTo(String profileId) async {
    final current = state;
    if (current is SettingsContent && current.activeId == profileId) return true;
    // Held across the await: storage and the running app must agree on the
    // active account even if this view model goes away meanwhile.
    final container = ref.container;
    try {
      await ref.read(profileRepositoryProvider).setActive(profileId);
    } catch (_) {
      return false;
    }
    if (!ref.mounted) {
      container.invalidate(activeSourceProvider);
      return true;
    }
    if (current is SettingsContent) {
      state = SettingsContent(accounts: [
        for (final a in current.accounts)
          AccountCard(
            id: a.id,
            displayName: a.displayName,
            title: a.title,
            host: a.host,
            isActive: a.id == profileId,
            avatarShape: a.avatarShape,
          ),
      ]);
    }
    ref.invalidate(activeSourceProvider);
    return true;
  }

  /// Removes [profileId] and its credentials. Removing the active account
  /// moves to the next one (or to none).
  Future<void> remove(String profileId) async {
    final current = state;
    final wasActive = current is SettingsContent && current.activeId == profileId;
    final repo = ref.read(profileRepositoryProvider);
    final container = ref.container;
    try {
      await repo.remove(profileId);
    } catch (_) {
      // Nothing changed that the list doesn't re-read below.
    }
    if (wasActive) {
      // The active account went: the app follows even if Settings closed meanwhile.
      container.invalidate(activeSourceProvider);
    } else if (ref.mounted) {
      await _load();
    }
  }
}

final settingsViewModelProvider = NotifierProvider<SettingsViewModel, SettingsUiState>(SettingsViewModel.new);
