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

/// The tags of what is playing (AudioFormatTags.kt playingFormatTags).
/// [source] is the file as the server describes it, [decoded] what the
/// player's decoder reports (null until it does, and on a speaker that plays
/// the url itself), [transcodeKbps] and [transcodeFormat] the transcode the
/// player asked for (null for the file).
///
/// A transcode shows what arrives ("MP3 320"); the file's own tag is the
/// caller's to say beside it ([transcodedFrom]). Otherwise the server's
/// description with the decoder's channel count, which is what actually
/// plays; where the decoder names another codec than the server, the decoder
/// wins (the server transcoded on its own).
List<String> playingFormatTags({
  required AudioFormat? source,
  required AudioFormat? decoded,
  String? transcodeFormat,
  int? transcodeKbps,
}) {
  if (transcodeKbps != null) {
    // A codec name round-trips through codecForSuffix ("mp3", "opus", "aac").
    return audioFormatTags(
      AudioFormat(suffix: decoded?.codec ?? transcodeFormat, bitRateKbps: transcodeKbps, channels: decoded?.channels),
    );
  }
  if (source == null) return audioFormatTags(decoded);
  if (decoded == null) return audioFormatTags(source);
  final sourceCodec = source.codec;
  final decodedCodec = decoded.codec;
  if (decodedCodec != null && sourceCodec != null && decodedCodec != sourceCodec) return audioFormatTags(decoded);
  return audioFormatTags(
    AudioFormat(
      suffix: sourceCodec ?? decodedCodec,
      bitRateKbps: source.bitRateKbps,
      bitDepth: source.bitDepth ?? decoded.bitDepth,
      sampleRateHz: source.sampleRateHz ?? decoded.sampleRateHz,
      channels: decoded.channels ?? source.channels,
    ),
  );
}

/// The file's codec tag when [playingFormatTags] shows a transcode of it
/// ("FLAC 16/44.1"), for "transcoded from" (np_format_transcoded_description).
String? transcodedFrom(AudioFormat? source, int? transcodeKbps) =>
    transcodeKbps == null || source == null ? null : codecTag(source);

/// The tags as small tonal chips (6 radius, labelSmall, tabular figures).
/// [accentTag] follows them in the primary tone ("Spatial" while the system
/// spatializes the song). Nothing is drawn when [tags] is empty.
class AudioFormatTagRow extends StatelessWidget {
  const AudioFormatTagRow({super.key, required this.tags, required this.description, this.accentTag});

  final List<String> tags;

  /// "Audio format FLAC 16/44.1" (np_format_description), or with "transcoded
  /// from" (np_format_transcoded_description), for screen readers.
  final String description;
  final String? accentTag;

  @override
  Widget build(BuildContext context) {
    if (tags.isEmpty) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    final base = Theme.of(context).textTheme.labelSmall!.copyWith(fontFeatures: const [FontFeature.tabularFigures()]);
    Widget chip(String tag, Color container, Color content) => DecoratedBox(
      decoration: BoxDecoration(color: container, borderRadius: BorderRadius.circular(6)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
        child: Text(tag, maxLines: 1, softWrap: false, style: base.copyWith(color: content)),
      ),
    );
    return Semantics(
      label: description,
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        spacing: 4,
        children: [
          for (final tag in tags) chip(tag, colors.secondaryContainer, colors.onSecondaryContainer),
          if (accentTag case final accent?) chip(accent, colors.primaryContainer, colors.onPrimaryContainer),
        ],
      ),
    );
  }
}
