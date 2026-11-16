/// Switching the account in use (Android ProfileManager.switchTo +
/// SwitchState): the outgoing account's playback is let go (a speaker must
/// confirm Stop), the target's source is built and its server asked once (a
/// ping), and only then does the account change. Every surface that switches
/// (Settings, Home's avatar card and hold, On Your Other Accounts, Memories,
/// Continue on) goes through [AccountSwitcher], so a failure anywhere leaves
/// the old account in use. Settings shows the progression as a blocking
/// overlay ([AccountSwitchOverlay]); Home and Memories say a failure once, in
/// the shell's snackbar.
library;

import 'dart:async';

import 'package:flutter/widgets.dart' show WidgetsBinding;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/profile/profile.dart';
import '../data/source/music_source.dart';
import '../playback/output/playback_router.dart' show SpeakerStopRefused;
import '../playback/playback_controller.dart';
import 'settings_strings.dart';

/// What a switch is doing (SwitchState.Stage).
enum AccountSwitchStage {
  /// The outgoing account's playback is let go.
  preparing,

  /// The target's source is built and its server asked (a ping).
  connecting,
}

/// Why a switch failed. The overlay says it (Android shows the exception's
/// message: these are the same lines).
sealed class AccountSwitchFailure implements Exception {
  const AccountSwitchFailure();
}

/// The speaker Yoin plays on did not confirm Stop (player_dlna_stop_failed).
final class SpeakerStopFailure extends AccountSwitchFailure {
  const SpeakerStopFailure();
}

/// The target's credentials can't be read.
final class InvalidCredentialsFailure extends AccountSwitchFailure {
  const InvalidCredentialsFailure(this.displayName);

  final String displayName;
}

/// The target's server didn't answer the ping in time.
final class NoAnswerFailure extends AccountSwitchFailure {
  const NoAnswerFailure(this.timeout);

  final Duration timeout;
}

/// The ping failed; [message] is what the source said.
final class SourceFailure extends AccountSwitchFailure {
  const SourceFailure(this.message);

  final String message;
}

/// The switch as the UI sees it (ProfileManager.SwitchState).
sealed class AccountSwitchState {
  const AccountSwitchState();
}

final class AccountSwitchIdle extends AccountSwitchState {
  const AccountSwitchIdle();
}

final class AccountSwitching extends AccountSwitchState {
  const AccountSwitching(this.profileId, this.stage);

  final String profileId;
  final AccountSwitchStage stage;

  @override
  bool operator ==(Object other) => other is AccountSwitching && other.profileId == profileId && other.stage == stage;

  @override
  int get hashCode => Object.hash(profileId, stage);
}

final class AccountSwitchFailed extends AccountSwitchState {
  const AccountSwitchFailed(this.profileId, this.failure);

  final String profileId;
  final AccountSwitchFailure failure;
}

/// How a switch begun somewhere other than Settings ended (HomeSwitchOutcome).
enum AccountSwitchOutcome {
  /// The account is the one in use.
  landed,

  /// It ran and failed (acknowledged: the caller tells it).
  failed,

  /// It didn't run: another switch was under way, or the account is gone.
  turnedAway,
}

/// How long the target's server has to answer (ProfileManager PING_TIMEOUT_MS).
const accountSwitchPingTimeout = Duration(seconds: 8);

/// Lets the outgoing account's playback go (AppContainer onSwitchPrepare):
/// the playback chain's [PlaybackController.disconnectForProfileChange], when
/// it is up (nothing plays without it). Throws [SpeakerStopRefused].
final accountSwitchPrepareProvider = Provider<Future<void> Function()>(
  (ref) => () async {
    if (!ref.exists(playbackControllerProvider)) return;
    await ref.read(playbackControllerProvider.notifier).disconnectForProfileChange();
  },
);

/// The ping's time limit; tests shorten it.
final accountSwitchPingTimeoutProvider = Provider<Duration>((ref) => accountSwitchPingTimeout);

class AccountSwitcher extends Notifier<AccountSwitchState> {
  /// The switch under way: its target and its end.
  (String, Future<void>)? _running;

  /// The running switch was begun by Settings, so it lives with that page
  /// (Android runs it in SettingsViewModel's scope).
  bool _pageOwned = false;

  /// Its page went away before it committed: it ends with nothing changed
  /// and no error left behind (Android cancels it with the page).
  bool _cancelled = false;

  @override
  AccountSwitchState build() => const AccountSwitchIdle();

  /// Switch to [profileId]. Nothing starts while a switch is under way, for
  /// an account that is gone, or for the account in use. Never throws: a
  /// failure is [AccountSwitchFailed] until [acknowledgeError]. [fromPage]:
  /// begun by Settings, so it is called off when that page goes
  /// ([releaseFromPage]) before it commits.
  Future<void> switchTo(String profileId, {bool fromPage = false}) {
    if (_running != null || state is AccountSwitching) return Future.value();
    _pageOwned = fromPage;
    _cancelled = false;
    final run = _switch(profileId);
    _running = (profileId, run);
    return run.whenComplete(() {
      if (identical(_running?.$2, run)) {
        _running = null;
        _pageOwned = false;
        _cancelled = false;
      }
    });
  }

  /// Throws [_SwitchCalledOff] once the page that owns the switch went away.
  void _checkpoint() {
    if (_cancelled) throw const _SwitchCalledOff();
  }

  Future<void> _switch(String profileId) async {
    final repo = ref.read(profileRepositoryProvider);
    final target = (await repo.profiles()).where((p) => p.id == profileId).firstOrNull;
    if (target == null || !ref.mounted || _cancelled) return;
    if ((await repo.activeProfile())?.id == profileId || !ref.mounted || _cancelled) return;
    state = AccountSwitching(profileId, AccountSwitchStage.preparing);
    MusicSource? candidate;
    try {
      await ref.read(accountSwitchPrepareProvider)();
      if (!ref.mounted) return;
      _checkpoint();
      state = AccountSwitching(profileId, AccountSwitchStage.connecting);
      ProfileCredentials? credentials;
      try {
        credentials = await repo.credentials(profileId);
      } catch (_) {
        credentials = null;
      }
      _checkpoint();
      if (credentials == null) throw InvalidCredentialsFailure(target.displayName);
      final source = candidate = ref.read(musicSourceFactoryProvider)(credentials);
      final timeout = ref.read(accountSwitchPingTimeoutProvider);
      await source.library.ping().timeout(timeout, onTimeout: () => throw NoAnswerFailure(timeout));
      if (!ref.mounted) return;
      // The last point a closed page calls it off: past here the switch commits.
      _checkpoint();
      await repo.setActive(profileId);
      // The source that answered is the one the app uses.
      ref.read(preparedSourceProvider).hand(profileId, source);
      candidate = null;
      if (!ref.mounted) return;
      ref.invalidate(activeSourceProvider);
      state = const AccountSwitchIdle();
    } catch (e) {
      candidate?.dispose();
      if (!ref.mounted) return;
      // Its page went meanwhile: nothing failed that anyone is shown.
      state = e is _SwitchCalledOff || _cancelled
          ? const AccountSwitchIdle()
          : AccountSwitchFailed(profileId, _failureOf(e));
    }
  }

  static AccountSwitchFailure _failureOf(Object error) => switch (error) {
    final AccountSwitchFailure failure => failure,
    SpeakerStopRefused() => const SpeakerStopFailure(),
    TimeoutException() => const NoAnswerFailure(accountSwitchPingTimeout),
    SourceException(:final message) => SourceFailure(message),
    _ => SourceFailure(error.toString()),
  };

  /// The error card's OK (acknowledgeSwitchError).
  void acknowledgeError() {
    if (state is AccountSwitchFailed) state = const AccountSwitchIdle();
  }

  /// The page that shows the switch went away: an error it shows goes, and
  /// a switch it began is called off unless it already committed (Android
  /// cancels it with SettingsViewModel's scope); one begun elsewhere runs on.
  void releaseFromPage() {
    if (!ref.mounted) return;
    switch (state) {
      case AccountSwitchFailed():
        state = const AccountSwitchIdle();
      case AccountSwitching() when _pageOwned:
        _cancelled = true;
      case _:
        break;
    }
  }

  /// Switch to [profileId] for a page that tells its own failure (Android
  /// switchFromHome): an error is acknowledged here, so Settings never opens
  /// on a switch it didn't start. Turned away by a switch to the same account
  /// begun elsewhere, it waits that one out.
  Future<AccountSwitchOutcome> switchAndSettle(String profileId) async {
    final running = _running;
    if (running != null && running.$1 == profileId) {
      await running.$2;
    } else {
      await switchTo(profileId);
      final outcome = state;
      if (outcome is AccountSwitchFailed && outcome.profileId == profileId) {
        acknowledgeError();
        return AccountSwitchOutcome.failed;
      }
    }
    if (!ref.mounted) return AccountSwitchOutcome.turnedAway;
    final active = await ref.read(profileRepositoryProvider).activeProfile();
    return active?.id == profileId ? AccountSwitchOutcome.landed : AccountSwitchOutcome.turnedAway;
  }
}

/// A switch its page called off ([AccountSwitcher.releaseFromPage]).
final class _SwitchCalledOff implements Exception {
  const _SwitchCalledOff();
}

final accountSwitcherProvider = NotifierProvider<AccountSwitcher, AccountSwitchState>(AccountSwitcher.new);

/// The overlay's line for [failure] (Android SwitchState.Error.message).
String accountSwitchFailureText(SettingsStrings strings, AccountSwitchFailure failure) => switch (failure) {
  SpeakerStopFailure() => strings.playerDlnaStopFailed,
  InvalidCredentialsFailure(:final displayName) => 'Profile $displayName has invalid credentials',
  NoAnswerFailure(:final timeout) => 'Server did not respond within ${timeout.inSeconds}s',
  SourceFailure(:final message) => message,
};

/// Switches to [profileId] from Home, its other-accounts shelf or Memories
/// (AccountSwitcherViewModel.switchTo): a switch that doesn't land says so
/// once in the shell's snackbar ("Couldn't switch account"). True when the
/// account is in use.
Future<bool> switchAccountFromShell(ProviderContainer container, String profileId) async {
  final outcome = await container.read(accountSwitcherProvider.notifier).switchAndSettle(profileId);
  if (outcome == AccountSwitchOutcome.landed) return true;
  try {
    if (container.exists(playbackControllerProvider)) {
      container.read(playbackControllerProvider.notifier).showMessage(_shellStrings().switchFailed);
    }
  } on StateError {
    // The app (a test's container) went away meanwhile: nobody to tell.
  }
  return false;
}

SettingsStrings _shellStrings() {
  try {
    return SettingsStrings.forLanguage(chinese: WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'zh');
  } catch (_) {
    return const SettingsStrings.forLanguage(chinese: false);
  }
}
