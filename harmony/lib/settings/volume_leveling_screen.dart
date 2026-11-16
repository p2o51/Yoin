import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../playback/replay_gain.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_pane.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// "Off", "By track", "By album" (replayGainModeLabel).
String volumeLevelingModeLabel(SettingsStrings strings, ReplayGainMode mode) => switch (mode) {
  ReplayGainMode.off => strings.volumeLevelingOff,
  ReplayGainMode.track => strings.volumeLevelingTrack,
  ReplayGainMode.album => strings.volumeLevelingAlbum,
};

/// The account page's Volume leveling row (ProfileServiceSettings): the
/// choice, opening the Volume leveling page. Shown for a service that reports
/// ReplayGain (Capability.replayGain: Subsonic).
class VolumeLevelingRow extends ConsumerWidget {
  const VolumeLevelingRow({super.key, required this.profileId});

  /// The account whose page shows the row: back from the page returns there.
  final String profileId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final settings = ref.watch(replayGainProvider);
    return SettingsItem(
      icon: YoinSymbols.volumeUp,
      title: strings.volumeLevelingTitle,
      summary: settings == null ? null : volumeLevelingModeLabel(strings, settings.mode),
      onTap: () {
        // In the wide window's right pane, the page opens there; otherwise it is pushed.
        final openInPane = SettingsPaneScope.maybeOf(context);
        if (openInPane != null) {
          openInPane(VolumeLevelingPane(profileId));
        } else {
          Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const VolumeLevelingScreen()));
        }
      },
    );
  }
}

/// Settings › account › Volume leveling (VolumeLevelingPage.kt, owner task
/// 2026-11-04), a PushPage like Streaming quality: its own back button, no
/// gesture of its own. One group, one choice, for this device; it applies at
/// once, to the song playing too. On a wide window it is the Settings
/// list-detail's right pane ([inPane]).
class VolumeLevelingScreen extends ConsumerWidget {
  const VolumeLevelingScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final settings = ref.watch(replayGainProvider);
    final selected = settings?.mode;
    void select(ReplayGainMode mode) {
      if (mode == selected) return;
      // A context click on a new choice, like Android's page.
      YoinHaptics.contextClick();
      ref.read(replayGainProvider.notifier).setMode(mode);
    }

    return SettingsSubPage(
      title: strings.volumeLevelingTitle,
      inPane: inPane,
      // Opened from the account's page: back returns there.
      paneBack: inPane,
      children: [VolumeLevelingPageBody(selected: selected, onSelect: select)],
    );
  }
}

/// The page's one group (VolumeLevelingPageBody): Off, By track, By album as
/// radio rows. The tip pages' demo phone shows it with a choice of its own;
/// [rowWrapper] names a row for the demo's touch.
class VolumeLevelingPageBody extends StatelessWidget {
  const VolumeLevelingPageBody({super.key, required this.selected, required this.onSelect, this.rowWrapper});

  final ReplayGainMode? selected;
  final ValueChanged<ReplayGainMode> onSelect;
  final Widget Function(ReplayGainMode mode, Widget row)? rowWrapper;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return RadioGroup<ReplayGainMode>(
      groupValue: selected,
      onChanged: (mode) {
        if (mode != null) onSelect(mode);
      },
      child: SettingsSegments(
        children: [
          for (final mode in ReplayGainMode.values)
            (rowWrapper ?? (_, row) => row)(
              mode,
              SettingsItem(
                key: ValueKey(mode),
                title: volumeLevelingModeLabel(strings, mode),
                semanticsRadio: true,
                leading: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.radioInset),
                  child: Radio<ReplayGainMode>(value: mode),
                ),
                onTap: () => onSelect(mode),
              ),
            ),
        ],
      ),
    );
  }
}
