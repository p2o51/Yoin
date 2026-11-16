import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'account_switch.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_strings.dart';
import 'settings_view_model.dart';

/// Settings' blocking overlay while the account changes (Android
/// ProfileSwitchOverlay): a scrim over the whole page and a card that says
/// "Switching account" with the stage ("Closing the current session…" /
/// "Connecting to X…"), or, when the switch failed, why, with OK. It fades
/// in and out on the effects spring and takes every touch while it shows.
class AccountSwitchOverlay extends ConsumerStatefulWidget {
  const AccountSwitchOverlay({super.key});

  @override
  ConsumerState<AccountSwitchOverlay> createState() => _AccountSwitchOverlayState();
}

class _AccountSwitchOverlayState extends ConsumerState<AccountSwitchOverlay> with SingleTickerProviderStateMixin {
  late final AccountSwitcher _switcher = ref.read(accountSwitcherProvider.notifier);
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: ref.read(accountSwitcherProvider) is AccountSwitchIdle ? 0 : 1,
    spring: SettingsSprings.effects,
  );

  /// What the card shows: the last state that wasn't idle, kept while the
  /// overlay fades out so the card never empties under the fade.
  AccountSwitchState _shown = const AccountSwitchIdle();

  @override
  void initState() {
    super.initState();
    _shown = ref.read(accountSwitcherProvider);
  }

  @override
  void dispose() {
    // Settings went away: an error it shows goes with it, and a switch it
    // started leaves none behind for the next page that watches (Android
    // cancels the switch with its page).
    // After this frame: a provider can't change while its listeners unmount.
    final switcher = _switcher;
    scheduleMicrotask(switcher.releaseFromPage);
    _alpha.dispose();
    super.dispose();
  }

  void _follow(AccountSwitchState next) {
    final visible = next is! AccountSwitchIdle;
    if (MediaQuery.disableAnimationsOf(context)) {
      _alpha.snapTo(visible ? 1 : 0);
    } else {
      _alpha.animateTo(visible ? 1 : 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(accountSwitcherProvider, (_, next) => _follow(next));
    final state = ref.watch(accountSwitcherProvider);
    if (state is! AccountSwitchIdle) _shown = state;
    final shown = _shown;
    final name = switch (shown) {
      AccountSwitching(:final profileId) => switch (ref.watch(settingsViewModelProvider)) {
        SettingsContent(:final accounts) => accounts.where((a) => a.id == profileId).firstOrNull?.displayName,
        _ => null,
      },
      _ => null,
    };
    final showing = state is! AccountSwitchIdle;
    return AnimatedBuilder(
      animation: _alpha.animation,
      builder: (context, child) {
        final alpha = _alpha.value.clamp(0.0, 1.0);
        // The spring settles within its tolerance of 0, not on it.
        if (!showing && alpha < _goneAlpha) return const SizedBox.shrink();
        // Leaving, it lets touches through at once.
        return IgnorePointer(
          ignoring: !showing,
          child: Opacity(opacity: alpha, child: child),
        );
      },
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Takes every touch under it while it shows (the Surface on Android).
          ModalBarrier(dismissible: false, color: Colors.black.withValues(alpha: SettingsMetrics.switchScrimAlpha)),
          Center(
            child: AccountSwitchCard(state: shown, name: name, onAcknowledge: _switcher.acknowledgeError),
          ),
        ],
      ),
    );
  }
}

/// Below this the leaving overlay is gone.
const double _goneAlpha = 0.01;

/// The overlay's card for [state]; [name] is the target account's name.
class AccountSwitchCard extends StatelessWidget {
  const AccountSwitchCard({super.key, required this.state, required this.name, required this.onAcknowledge});

  final AccountSwitchState state;
  final String? name;
  final VoidCallback onAcknowledge;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final detail = text.bodySmall?.copyWith(color: colors.onSurfaceVariant);
    final children = switch (state) {
      AccountSwitching(:final stage) => [
        const YoinLoadingIndicator(size: SettingsMetrics.switchCardMark),
        Text(strings.switchTitle, style: text.titleMedium?.copyWith(color: colors.onSurface)),
        Text(_stageLabel(strings, stage, name), style: detail, textAlign: TextAlign.center),
      ],
      AccountSwitchFailed(:final failure) => [
        Icon(YoinSymbols.errorFilled, size: SettingsMetrics.switchCardMark, color: colors.error),
        Text(strings.switchFailed, style: text.titleMedium),
        Text(accountSwitchFailureText(strings, failure), style: detail, textAlign: TextAlign.center),
        FilledButton(onPressed: onAcknowledge, child: Text(strings.switchOk)),
      ],
      AccountSwitchIdle() => const <Widget>[],
    };
    // Drawn, not clipped: a clipped or physical shape over the page shows
    // black wedges at the corners on HarmonyOS.
    return Semantics(
      liveRegion: true,
      container: true,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colors.surfaceContainerHighest,
          borderRadius: const BorderRadius.all(Radius.circular(SettingsMetrics.switchCardCorner)),
          boxShadow: kElevationToShadow[SettingsMetrics.switchCardShadowElevation],
        ),
        child: SizedBox(
          width: SettingsMetrics.switchCardWidth,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: SettingsMetrics.switchCardPaddingH,
              vertical: SettingsMetrics.switchCardPaddingV,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final (i, child) in children.indexed) ...[
                  if (i > 0) const SizedBox(height: SettingsMetrics.switchCardGap),
                  child,
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

String _stageLabel(SettingsStrings strings, AccountSwitchStage stage, String? name) => switch (stage) {
  AccountSwitchStage.preparing => strings.switchPreparing,
  AccountSwitchStage.connecting =>
    (name == null || name.trim().isEmpty) ? strings.switchConnectingUnnamed : strings.switchConnecting(name),
};
