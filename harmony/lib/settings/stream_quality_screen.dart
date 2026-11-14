import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../ui/navigation/yoin_push_route.dart';
import 'settings_metrics.dart';
import 'settings_pane.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'stream_quality.dart';

/// "Original" or "320 kbps" (streamQualityLabel).
String streamQualityLabel(SettingsStrings strings, StreamQuality quality) {
  final kbps = quality.maxBitRateKbps;
  return kbps == null ? strings.streamQualityOriginal : strings.streamQualityKbps(kbps);
}

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
/// next song that opens. On a wide window it is the Settings list-detail's
/// right pane ([inPane]).
class StreamQualityScreen extends ConsumerWidget {
  const StreamQualityScreen({super.key, required this.profileId, this.inPane = false});

  final String profileId;
  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final settings = ref.watch(streamQualityProvider(profileId));
    return SettingsSubPage(
      title: strings.streamQualityTitle,
      inPane: inPane,
      children: [
        _QualityGroup(
          title: strings.streamQualityWifi,
          selected: settings?.wifi,
          onSelect: (q) => ref.read(streamQualityProvider(profileId).notifier).select(StreamNetwork.wifi, q),
        ),
        const SizedBox(height: SettingsMetrics.sectionGap),
        _QualityGroup(
          title: strings.streamQualityMobile,
          selected: settings?.mobile,
          onSelect: (q) => ref.read(streamQualityProvider(profileId).notifier).select(StreamNetwork.mobile, q),
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
