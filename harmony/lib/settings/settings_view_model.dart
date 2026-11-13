import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/profile/profile.dart';

/// Settings (Android SettingsScreen, v1: the account list). Lists, switches
/// and removes accounts; adding one opens the service's setup page.
sealed class SettingsUiState {
  const SettingsUiState();
}

final class SettingsLoading extends SettingsUiState {
  const SettingsLoading();
}

final class SettingsContent extends SettingsUiState {
  const SettingsContent({required this.profiles, required this.activeId});

  final List<Profile> profiles;

  /// null with no account.
  final String? activeId;
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
    if (!ref.mounted || generation != _generation) return;
    state = SettingsContent(profiles: profiles, activeId: active?.id);
  }

  /// Makes [profileId] the active account. The playback chain, Home, Library
  /// and the detail caches follow the active source. Returns false when the
  /// switch could not be saved.
  Future<bool> switchTo(String profileId) async {
    final current = state;
    if (current is SettingsContent && current.activeId == profileId) return true;
    try {
      await ref.read(profileRepositoryProvider).setActive(profileId);
    } catch (_) {
      return false;
    }
    if (!ref.mounted) return true;
    if (current is SettingsContent) {
      state = SettingsContent(profiles: current.profiles, activeId: profileId);
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
    try {
      await repo.remove(profileId);
    } catch (_) {
      // Nothing changed that the list doesn't re-read below.
    }
    if (!ref.mounted) return;
    if (wasActive) {
      ref.invalidate(activeSourceProvider);
    } else {
      await _load();
    }
  }
}

final settingsViewModelProvider = NotifierProvider<SettingsViewModel, SettingsUiState>(SettingsViewModel.new);
