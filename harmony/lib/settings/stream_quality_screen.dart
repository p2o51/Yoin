import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../home/widgets/seam.dart';
import '../nowplaying/haptics.dart';
import '../platform/audio/audio_host.dart';
import '../playback/spatial_audio.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'settings_metrics.dart';
import 'settings_pane.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'stream_quality.dart';

/// "Auto", "Original" or "320 kbps" (streamQualityLabel).
String streamQualityLabel(SettingsStrings strings, StreamQuality quality) {
  if (quality == StreamQuality.auto) return strings.streamQualityAuto;
  final kbps = quality.maxBitRateKbps;
  return kbps == null ? strings.streamQualityOriginal : strings.streamQualityKbps(kbps);
}

/// What Auto asks for on each output: "Wired Original   Bluetooth 320 kbps
/// Speaker 192 kbps" (autoQualityDetail).
String autoQualityDetail(SettingsStrings strings) => strings.streamQualityAutoDetail(
  streamQualityLabel(strings, AudioOutputKind.wired.autoQuality),
  streamQualityLabel(strings, AudioOutputKind.bluetooth.autoQuality),
  streamQualityLabel(strings, AudioOutputKind.speaker.autoQuality),
);

/// What a 5.1 song would get right now, in a sentence (spatialAudioStatus).
String spatialAudioStatus(SettingsStrings strings, SpatialAudioState state) => switch (spatialPlaybackOf(6, state)) {
  SpatialPlayback.downmixedByChoice => strings.spatialAudioOff,
  SpatialPlayback.downmixedUnsupported => strings.spatialAudioUnsupported,
  SpatialPlayback.downmixedSystemOff => strings.spatialAudioSystemOff,
  SpatialPlayback.downmixedOutput => strings.spatialAudioOutput,
  SpatialPlayback.spatialized ||
  SpatialPlayback.notMultichannel => state.headTracking ? strings.spatialAudioReadyHead : strings.spatialAudioReady,
};

/// The account page's Playback row (ProfileServiceSettings): both choices,
/// opening the Streaming quality page.
class StreamQualityRow extends ConsumerWidget {
  const StreamQualityRow({super.key, required this.profileId});

  final String profileId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final settings = ref.watch(streamQualityProvider(profileId));
    return SettingsItem(
      icon: YoinSymbols.equalizer,
      title: strings.streamQualityTitle,
      summary: settings == null
          ? null
          : strings.streamQualitySummary(
              streamQualityLabel(strings, settings.wifi),
              streamQualityLabel(strings, settings.mobile),
            ),
      onTap: () {
        // In the wide window's right pane, the page opens there (like every
        // Settings page on a wide window); otherwise it is pushed.
        final openInPane = SettingsPaneScope.maybeOf(context);
        if (openInPane != null) {
          openInPane(StreamQualityPane(profileId));
        } else {
          Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => StreamQualityScreen(profileId: profileId)));
        }
      },
    );
  }
}

/// Settings › account › Streaming quality (StreamQualityPage.kt), a PushPage:
/// two groups, Wi‑Fi and mobile data, one choice each; it applies from the
/// next queue the player builds. Then spatial audio (2026-11-04), which
/// applies at once. On a wide window it is the Settings list-detail's right
/// pane ([inPane]).
class StreamQualityScreen extends ConsumerWidget {
  const StreamQualityScreen({super.key, required this.profileId, this.inPane = false});

  final String profileId;
  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final settings = ref.watch(streamQualityProvider(profileId));
    void select(StreamNetwork network, StreamQuality quality) {
      if (settings?[network] == quality) return;
      // A context click on a new choice, like Android's page.
      YoinHaptics.contextClick();
      ref.read(streamQualityProvider(profileId).notifier).select(network, quality);
    }

    return SettingsSubPage(
      title: strings.streamQualityTitle,
      inPane: inPane,
      // Opened from the account's page: back returns there.
      paneBack: inPane,
      children: [
        _QualityGroup(
          title: strings.streamQualityWifi,
          selected: settings?.wifi,
          onSelect: (q) => select(StreamNetwork.wifi, q),
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        _QualityGroup(
          title: strings.streamQualityMobile,
          selected: settings?.mobile,
          onSelect: (q) => select(StreamNetwork.mobile, q),
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        SpatialAudioGroup(
          state: ref.watch(spatialAudioProvider),
          onModeChange: (mode) {
            YoinHaptics.contextClick();
            ref.read(spatialAudioProvider.notifier).setMode(mode);
          },
          onOpenSystemSettings: () => ref.read(spatialAudioProvider.notifier).openSystemSettings(),
        ),
      ],
    );
  }
}

/// Spatial audio: Yoin's switch with what the system makes of it now, and a
/// way to the system switch — Yoin can ask for spatialization (or opt out)
/// but only the system turns it on, per output. Where Yoin can't choose
/// ([SpatialAudioState.canChoose] false) the status stands alone, on the
/// headphones row, with no switch.
class SpatialAudioGroup extends StatelessWidget {
  const SpatialAudioGroup({
    super.key,
    required this.state,
    required this.onModeChange,
    required this.onOpenSystemSettings,
  });

  final SpatialAudioState state;
  final ValueChanged<SpatialAudioMode> onModeChange;
  final VoidCallback onOpenSystemSettings;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    final theme = Theme.of(context);
    final on = state.mode == SpatialAudioMode.auto;
    void toggle() => onModeChange(on ? SpatialAudioMode.off : SpatialAudioMode.auto);
    final note = theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingsGroup(
          title: strings.spatialAudioTitle,
          children: [
            SettingsItem(
              key: const ValueKey('spatial-switch'),
              icon: YoinSymbols.headphones,
              title: strings.spatialAudioSwitch,
              summary: spatialAudioStatus(strings, state),
              onTap: state.canChoose ? toggle : null,
              trailing: state.canChoose
                  ? ExcludeSemantics(
                      child: Switch(value: on, onChanged: (_) => toggle()),
                    )
                  : null,
            ),
            // Kept while Yoin's switch is off too: the switch never adds or removes a row.
            if (state.supported)
              SettingsItem(
                key: const ValueKey('spatial-system-settings'),
                icon: YoinSymbols.settings,
                title: strings.spatialAudioSystemSettings,
                summary: strings.spatialAudioSystemSettingsDetail,
                onTap: onOpenSystemSettings,
              ),
          ],
        ),
        const SizedBox(height: SettingsMetrics.groupLabelGap),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.labelInset),
          child: SeamFade(
            fontSize: note.fontSize ?? 14,
            child: Text(strings.spatialAudioNote, style: note),
          ),
        ),
      ],
    );
  }
}

class _QualityGroup extends StatelessWidget {
  const _QualityGroup({required this.title, required this.selected, required this.onSelect});

  final String title;
  final StreamQuality? selected;
  final ValueChanged<StreamQuality> onSelect;

  @override
  Widget build(BuildContext context) {
    final strings = SettingsStrings.of(context);
    return RadioGroup<StreamQuality>(
      groupValue: selected,
      onChanged: (q) {
        if (q != null) onSelect(q);
      },
      child: SettingsGroup(
        title: title,
        children: [
          for (final quality in StreamQuality.values)
            SettingsItem(
              key: ValueKey(quality),
              title: streamQualityLabel(strings, quality),
              summary: quality == StreamQuality.auto ? autoQualityDetail(strings) : null,
              semanticsRadio: true,
              leading: Padding(
                padding: const EdgeInsets.symmetric(horizontal: SettingsMetrics.radioInset),
                child: Radio<StreamQuality>(value: quality),
              ),
              onTap: () => onSelect(quality),
            ),
        ],
      ),
    );
  }
}
