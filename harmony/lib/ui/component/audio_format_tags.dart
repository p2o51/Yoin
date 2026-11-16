import 'package:flutter/material.dart';

import '../../data/model/model.dart';

/// The format tags (AudioFormatTags.kt / AudioFormatTagRow.kt): short
/// labels, each its own tag, never joined with "·" — "FLAC 24/96",
/// "MP3 320", "5.1". Codec names and numbers read the same in every
/// language, so the tags are not translated.

/// "flac" → "FLAC", "opus" → "Opus". E-AC-3 JOC is "Dolby Atmos", in words
/// only: the logo needs a licence, and the trademark reads in English in
/// every language.
String codecLabel(String codec) => switch (codec.toLowerCase()) {
  'opus' => 'Opus',
  'vorbis' => 'Vorbis',
  'wavpack' => 'WavPack',
  'ac3' => 'AC-3',
  'eac3' => 'E-AC-3',
  'ac4' => 'AC-4',
  AudioFormat.codecEac3Joc => 'Dolby Atmos',
  final other => other.toUpperCase(),
};

/// 6 → "5.1"; null for mono and stereo.
String? channelLayoutLabel(int? channels) => switch (channels) {
  null || 0 || 1 || 2 => null,
  3 => '2.1',
  4 => '4.0',
  5 => '5.0',
  6 => '5.1',
  7 => '6.1',
  8 => '7.1',
  final n => '${n}ch',
};

/// 44100 → "44.1", 96000 → "96".
String khzLabel(int hz) {
  final tenths = (hz / 100).round();
  return tenths % 10 == 0 ? '${tenths ~/ 10}' : '${tenths ~/ 10}.${tenths % 10}';
}

/// Lossless as depth/kHz ("FLAC 24/96"), lossy as its bit rate ("MP3 320"),
/// Dolby Atmos alone.
String? codecTag(AudioFormat format) {
  final codec = format.codec;
  if (codec == null || codec.isEmpty) return null;
  final label = codecLabel(codec);
  if (format.isDolbyAtmos) return label;
  if (format.isLossless) {
    final khz = format.sampleRateHz == null ? null : khzLabel(format.sampleRateHz!);
    if (format.bitDepth != null && khz != null) return '$label ${format.bitDepth}/$khz';
    if (khz != null) return '$label $khz';
    return label;
  }
  return format.bitRateKbps == null ? label : '$label ${format.bitRateKbps}';
}

/// Every tag of [format], codec first: ["FLAC 16/44.1", "5.1"]; Dolby Atmos
/// alone (its channel count is the base layer's, not the content's layout).
List<String> audioFormatTags(AudioFormat? format) {
  if (format == null) return const [];
  final layout = format.isDolbyAtmos ? null : channelLayoutLabel(format.channels);
  return [?codecTag(format), ?layout];
}

/// The tags as small tonal chips (6 radius, labelSmall, tabular figures).
class AudioFormatTagRow extends StatelessWidget {
  const AudioFormatTagRow({super.key, required this.tags, required this.description});

  final List<String> tags;

  /// "Audio format FLAC 16/44.1" (np_format_description), for screen readers.
  final String description;

  @override
  Widget build(BuildContext context) {
    if (tags.isEmpty) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelSmall!.copyWith(
      color: colors.onSecondaryContainer,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    return Semantics(
      label: description,
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        spacing: 4,
        children: [
          for (final tag in tags)
            DecoratedBox(
              decoration: BoxDecoration(color: colors.secondaryContainer, borderRadius: BorderRadius.circular(6)),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                child: Text(tag, maxLines: 1, softWrap: false, style: style),
              ),
            ),
        ],
      ),
    );
  }
}
