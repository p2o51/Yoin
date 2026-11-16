/// An account in use that needs its owner (Android, owner 2026-10-11:
/// ProfileManager.activeNeedsReconnect + retryActiveSource and
/// AccountSwitcherViewModel.reconnect). Its source settled without coming —
/// its credentials couldn't be read (a restore without them, a key that
/// didn't come along) — so Home's avatar wears a small error-coloured dot, the
/// account card offers Reconnect, and Library's error reads "Account needs
/// reconnecting". Reconnect tries the account's source once more without
/// switching; what it can't fix opens the account's own page.
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/profile/profile.dart';
import '../data/profile/profile_reconnect.dart';
import '../data/profile/profile_repository.dart';
import '../data/source/music_source.dart';
import 'account_switch.dart';
import 'settings_view_model.dart';

/// The account in use needs its owner before it works: true when its source
/// has settled without coming (its credentials couldn't be read), or its
/// credentials say so ([credentialsNeedReconnect]). False with no account, and
/// while its source is still being built. Clears as soon as the account works
/// again (re-entered credentials, a sign-in, [retryActiveSource]). Read from
/// local state only: nothing is asked of any server for it.
///
/// Read `.value ?? false`: while it re-reads, the last answer stands.
final activeNeedsReconnectProvider = FutureProvider<bool>((ref) async {
  // Re-read when an account's stored credentials change while the source stays.
  ref.watch(accountsRevisionProvider);
  final source = ref.watch(activeSourceProvider);
  // A failed read (Riverpod retries it, loading meanwhile) is no source.
  final failed = source.hasError;
  // Still being built (cold start, a switch, a retry's hand-over): not yet.
  if (source.isLoading && !failed) return false;
  return _accountNeedsReconnect(ref.watch(profileRepositoryProvider), sourceUp: !failed && source.value != null);
});

/// Whether the account in use needs its owner (Android accountNeedsReconnect):
/// with no source, once none is coming; with one, when its credentials say so.
Future<bool> _accountNeedsReconnect(ProfileRepository repo, {required bool sourceUp}) async {
  final profile = await _activeProfile(repo);
  if (profile == null) return false;
  if (!sourceUp) return true;
  return credentialsNeedReconnect(await _credentials(repo, profile.id));
}

Future<Profile?> _activeProfile(ProfileRepository repo) async {
  try {
    return await repo.activeProfile();
  } catch (_) {
    return null;
  }
}

/// [profileId]'s credentials; null when they can't be read (gone, or unreadable).
Future<ProfileCredentials?> _credentials(ProfileRepository repo, String profileId) async {
  try {
    return await repo.credentials(profileId);
  } catch (_) {
    return null;
  }
}

/// The active source once it has settled; null when none came. A failed read
/// Riverpod is retrying is none: its retries aren't waited out.
Future<MusicSource?> _settledSource(ProviderContainer container) async {
  if (container.read(activeSourceProvider).retrying) return null;
  return _awaitSource(container);
}

/// The active source the build under way comes to; null when none comes.
Future<MusicSource?> _awaitSource(ProviderContainer container) async {
  try {
    return await container.read(activeSourceProvider.future);
  } catch (_) {
    return null;
  }
}

/// Reconnect: one more try at the account in use, without switching (Android
/// ProfileManager.retryActiveSource). With no source, its credentials are
/// read again; if they read, its source is built and pinged, and published
/// unless the service turned the credentials down ([refusesCredentials]) — a
/// server that doesn't answer (offline, a timeout) gets the source all the
/// same, as a cold start would, and Home and Library tell their own network
/// errors. Published only if the account is still the one in use with no
/// source and no switch is under way — a switch or an edit meanwhile wins —
/// and then the app follows it as it follows a switch. A source that is up is
/// left alone. True when the account needs nothing from its owner afterwards;
/// false: its page is the way on.
Future<bool> retryActiveSource(ProviderContainer container) async {
  final repo = container.read(profileRepositoryProvider);
  final profile = await _activeProfile(repo);
  if (profile == null) return false;
  var published = false;
  if (await _settledSource(container) == null && container.read(accountSwitcherProvider) is! AccountSwitching) {
    published = await _publishRetriedSource(container, profile);
  }
  // Read again: the cards follow, and the answer is the one Home shows.
  container.read(accountsRevisionProvider.notifier).bump();
  // Published: the rebuild under way takes it.
  final source = published ? await _awaitSource(container) : await _settledSource(container);
  return !await _accountNeedsReconnect(repo, sourceUp: source != null);
}

/// [retryActiveSource]'s build: [profile]'s source from its credentials read
/// again, pinged, and handed to [activeSourceProvider] unless the service
/// turned the credentials down or the account in use has moved on (or got a
/// source) meanwhile. True when it was published; one that wasn't is disposed.
Future<bool> _publishRetriedSource(ProviderContainer container, Profile profile) async {
  final repo = container.read(profileRepositoryProvider);
  final credentials = await _credentials(repo, profile.id);
  if (credentials == null || credentialsNeedReconnect(credentials)) return false;
  final MusicSource source;
  try {
    source = container.read(musicSourceFactoryProvider)(credentials);
  } catch (_) {
    return false;
  }
  var published = false;
  try {
    try {
      await source.library.ping().timeout(container.read(accountSwitchPingTimeoutProvider));
    } on TimeoutException {
      // A server that didn't answer, not a refusal.
    } catch (error) {
      if (refusesCredentials(error)) return false;
    }
    if ((await _activeProfile(repo))?.id != profile.id) return false;
    if (container.read(accountSwitcherProvider) is AccountSwitching) return false;
    if (await _settledSource(container) != null) return false;
    // The source that answered is the one the app uses (as a switch hands it over).
    container.read(preparedSourceProvider).hand(profile.id, source);
    published = true;
    container.invalidate(activeSourceProvider);
    return true;
  } finally {
    if (!published) source.dispose();
  }
}

/// How a Reconnect ended (Android ReconnectOutcome).
enum AccountReconnectOutcome {
  /// The account works again: the card goes back into the avatar, which wears no dot now.
  fixed,

  /// Not fixed, and still the account in use: once the card is back in the avatar, the account's own page.
  openPage,
}

/// A Reconnect under way (true) from the tap until [retryActiveSource] is
/// over: the account card waits meanwhile, as through a switch.
class AccountReconnector extends Notifier<bool> {
  @override
  bool build() => false;

  /// Reconnects [accountId], the account in use. Null when nothing starts (a
  /// Reconnect or a switch is under way), or when it ended with another
  /// account in use (switched elsewhere meanwhile: not this one's page).
  Future<AccountReconnectOutcome?> reconnect(String accountId) async {
    if (state || ref.read(accountSwitcherProvider) is AccountSwitching) return null;
    state = true;
    final container = ref.container;
    try {
      final fixed = await retryActiveSource(container);
      if (fixed) return AccountReconnectOutcome.fixed;
      final active = await _activeProfile(container.read(profileRepositoryProvider));
      return active == null || active.id == accountId ? AccountReconnectOutcome.openPage : null;
    } finally {
      if (ref.mounted) state = false;
    }
  }
}

final accountReconnectorProvider = NotifierProvider<AccountReconnector, bool>(AccountReconnector.new);

/// What a page says when the account in use has no source and none is coming
/// because its credentials couldn't be read: "Account needs reconnecting"
/// (Library's error, as Home's dot says it).
final class AccountNeedsReconnectError implements Exception {
  const AccountNeedsReconnectError();

  @override
  String toString() => 'AccountNeedsReconnectError: the account in use needs reconnecting';
}
