import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/home/home_snapshot_store.dart';
import '../data/model/media_id.dart';
import '../data/profile/profile.dart';
import '../data/profile/profile_repository.dart';
import '../detail/detail_cache.dart';
import 'account_identity.dart';

/// One account as Settings shows it (ProfileCards.kt ProfileCard): Subsonic
/// leads with the username and puts the server's host on the service line;
/// Apple Music leads with the service name and has no host line.
final class AccountCard {
  const AccountCard({
    required this.id,
    required this.provider,
    required this.displayName,
    required this.title,
    required this.host,
    required this.isActive,
    required this.avatarShape,
    this.credentialsMissing = false,
  });

  final String id;

  /// [MediaId] provider token: decides the card's colour and mark.
  final String provider;

  /// The stored name ("user @ host"), as the remove dialog quotes it.
  final String displayName;

  /// The card's name: the username, else [displayName].
  final String title;

  /// The server's host, for the service line; null when unknown.
  final String? host;
  final bool isActive;
  final int avatarShape;

  /// The account is listed but its credentials can't be read (lost from the
  /// secret store, or unreadable): its card says so and opens its page.
  final bool credentialsMissing;

  /// Subsonic and Apple Music accounts have a page of their own (Subsonic:
  /// edit credentials, playback; Apple Music: reconnect, song names).
  bool get canManage => provider == MediaId.providerSubsonic || provider == MediaId.providerAppleMusic;

  /// A tap opens the account's page instead of switching: the account in
  /// use, or one that needs attention (Android `opensPage`).
  bool get tapOpensPage => canManage && (isActive || credentialsMissing);

  AccountCard withActive(bool active) => AccountCard(
        id: id,
        provider: provider,
        displayName: displayName,
        title: title,
        host: host,
        isActive: active,
        avatarShape: avatarShape,
        credentialsMissing: credentialsMissing,
      );

  /// The service's face (colour and mark).
  ServiceIdentity get identity => ServiceIdentity.forProvider(provider);
}

/// The card for [p] (ProfileCards.kt toProfileCard). Subsonic leads with the
/// username and shows its host; other providers (Apple Music) lead with the
/// stored name and have no host line. [credentials] null = missing.
AccountCard accountCardFor(Profile p, ProfileCredentials? credentials, {required bool isActive, required int avatarShape}) {
  final subsonic = credentials is SubsonicCredentials ? credentials : null;
  final host = subsonic == null ? null : Uri.tryParse(subsonic.baseUrl)?.host;
  final username = subsonic?.username.trim();
  return AccountCard(
    id: p.id,
    provider: p.provider,
    displayName: p.displayName,
    title: (username == null || username.isEmpty) ? p.displayName : username,
    host: (host == null || host.isEmpty) ? null : host,
    isActive: isActive,
    avatarShape: avatarShape,
    credentialsMissing: credentials == null,
  );
}

/// Bumped when an account's name or credentials change without the active
/// source changing (editing an account not in use): Settings re-reads.
class AccountsRevision extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state++;
}

final accountsRevisionProvider = NotifierProvider<AccountsRevision, int>(AccountsRevision.new);

/// Rewrites [profileId]'s credentials and its stored name (ProfileManager.update).
/// ProfileRepository has no update yet; this writes the same two records it
/// does, in the same order (secret first).
Future<void> updateProfileCredentials(
  Ref ref,
  String profileId, {
  required String displayName,
  required ProfileCredentials credentials,
}) async {
  final repo = ref.read(profileRepositoryProvider);
  final profiles = await repo.profiles();
  if (!profiles.any((p) => p.id == profileId)) throw StateError('No account $profileId');
  await ref.read(secretStoreProvider).write(ProfileRepository.credentialsKey(profileId), jsonEncode(credentials.toJson()));
  await ref.read(keyValueStoreProvider).write(
        ProfileRepository.profilesKey,
        jsonEncode([
          for (final p in profiles)
            (p.id == profileId ? Profile(id: p.id, provider: p.provider, displayName: displayName, createdAt: p.createdAt) : p).toJson(),
        ]),
      );
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

  /// How many accounts Yoin keeps (ProfileManager.MAX_PROFILES).
  static const maxProfiles = ProfileRepository.maxProfiles;

  /// Another account fits: Settings offers Add (SettingsViewModel canAddProfile).
  bool get canAddProfile => accounts.length < maxProfiles;

  /// null with no account.
  String? get activeId => accounts.where((a) => a.isActive).firstOrNull?.id;
}

class SettingsViewModel extends Notifier<SettingsUiState> {
  int _generation = 0;

  @override
  SettingsUiState build() {
    // Re-read whenever an account is added, switched or removed (each
    // invalidates the active source), or edited.
    ref.watch(activeSourceProvider);
    ref.watch(accountsRevisionProvider);
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
      cards.add(accountCardFor(p, credentials, isActive: p.id == active?.id, avatarShape: shapes[p.id] ?? 0));
    }
    if (!ref.mounted || generation != _generation) return;
    state = SettingsContent(accounts: cards);
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
        for (final a in current.accounts) a.withActive(a.id == profileId),
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
    // What Yoin kept of the account on disk goes with it (Android deletes its snapshot and detail rows).
    unawaited(container.read(homeSnapshotStoreProvider)?.delete(profileId));
    unawaited(container.read(detailCacheStoreProvider)?.removeProfile(profileId));
    if (wasActive) {
      // The active account went: the app follows even if Settings closed meanwhile.
      container.invalidate(activeSourceProvider);
    } else if (ref.mounted) {
      await _load();
    }
  }
}

final settingsViewModelProvider = NotifierProvider<SettingsViewModel, SettingsUiState>(SettingsViewModel.new);
