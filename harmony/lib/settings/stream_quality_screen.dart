import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../ui/navigation/yoin_push_route.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';
import 'stream_quality.dart';

/// "Original" or "320 kbps" (streamQualityLabel).
String streamQualityLabel(SettingsStrings strings, StreamQuality quality) {
  final kbps = quality.maxBitRateKbps;
  return kbps == null ? strings.streamQualityOriginal : strings.streamQualityKbps(kbps);
}

/// The account page's Playback row (ProfileServiceSettings): the choice in
/// force, opening the Streaming quality page.
class StreamQualityRow extends ConsumerWidget {
  const StreamQualityRow({super.key, required this.profileId});

  final String profileId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final quality = ref.watch(streamQualityProvider(profileId));
    return SettingsItem(
      icon: YoinSymbols.equalizer,
      title: strings.streamQualityTitle,
      summary: quality == null ? null : streamQualityLabel(strings, quality),
      onTap: () => Navigator.of(context)
          .push(YoinPushRoute<void>(builder: (_) => StreamQualityScreen(profileId: profileId))),
    );
  }
}

/// Settings › account › Streaming quality (StreamQualityPage.kt), a PushPage:
/// one choice for this account, applied from the next song that opens. One
/// group, not Android's Wi‑Fi and mobile data pair (see stream_quality.dart).
class StreamQualityScreen extends ConsumerWidget {
  const StreamQualityScreen({super.key, required this.profileId});

  final String profileId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final selected = ref.watch(streamQualityProvider(profileId));
    void select(StreamQuality? quality) {
      if (quality != null) ref.read(streamQualityProvider(profileId).notifier).select(quality);
    }

    return SettingsSubPage(
      title: strings.streamQualityTitle,
      children: [
        RadioGroup<StreamQuality>(
          groupValue: selected,
          onChanged: select,
          child: SettingsSegments(
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
                  onTap: () => select(quality),
                ),
            ],
          ),
        ),
      ],
    );
  }
}
