/// Now Playing's format tags (Android NowPlayingViewModel.uiState's
/// formatTags / transcodedFrom / spatialPlayback, NowPlayingFormatTags):
/// what actually arrives — a transcode as "MP3 320" with "transcoded from
/// FLAC 16/44.1" for screen readers, a downloaded song at the quality it was
/// kept at — with the decoder's channel count and codec over the server's,
/// then "Spatial" while the system spatializes the song.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../platform/playback/playback_engine.dart';
import '../playback/output/output_providers.dart';
import '../playback/playback_controller.dart';
import '../playback/playing_format.dart';
import '../playback/spatial_audio.dart';
import '../theme/motion.dart';
import '../ui/component/audio_format_tags.dart';
import 'nowplaying_strings.dart';

/// What the tags say about the song playing.
@immutable
final class NowPlayingFormat {
  const NowPlayingFormat({this.tags = const [], this.transcodedFrom, this.spatialized = false});

  static const none = NowPlayingFormat();

  /// "FLAC 24/96", "MP3 320", "5.1"; empty when nothing is known (Apple Music).
  final List<String> tags;

  /// The file's own tag while a transcode plays ("FLAC 16/44.1").
  final String? transcodedFrom;

  /// The system spatializes the song (the "Spatial" tag).
  final bool spatialized;

  @override
  bool operator ==(Object other) =>
      other is NowPlayingFormat &&
      listEquals(other.tags, tags) &&
      other.transcodedFrom == transcodedFrom &&
      other.spatialized == spatialized;

  @override
  int get hashCode => Object.hash(Object.hashAll(tags), transcodedFrom, spatialized);
}

/// The tags of [track] (queue entry [entryId]): [deliveries] is the entry's
/// stream and download, [playing] the native player's last report (used only
/// while it is about this entry), [remote] whether a speaker or cast device
/// plays the queued stream itself (no local decoder: the server's word and the
/// stream's transcode, Android shows only the server's word there).
NowPlayingFormat nowPlayingFormatOf({
  required Track track,
  required String? entryId,
  required EntryDelivery? deliveries,
  required PlayingFormatEvent? playing,
  required bool remote,
  required SpatialAudioState spatial,
}) {
  final report = remote || playing == null || !_isFor(playing, track, entryId) ? null : playing;
  final decoded = report?.decoded;
  final delivery = remote ? deliveries?.stream : deliveries?.playing(fromFile: report?.fromFile);
  final transcodeKbps = delivery != null && delivery.transcoded ? delivery.maxBitRateKbps : null;
  return NowPlayingFormat(
    tags: playingFormatTags(
      source: track.audioFormat,
      decoded: decoded,
      transcodeFormat: transcodeKbps == null ? null : delivery?.format,
      transcodeKbps: transcodeKbps,
    ),
    transcodedFrom: transcodedFrom(track.audioFormat, transcodeKbps),
    // Only what the local player decodes: a speaker spatializes (or not) on its own.
    spatialized: spatialPlaybackOf(decoded?.channels, spatial) == SpatialPlayback.spatialized,
  );
}

bool _isFor(PlayingFormatEvent event, Track track, String? entryId) {
  final reported = event.entryId;
  if (reported != null && entryId != null) return reported == entryId;
  return event.id == track.id.toString();
}

/// Whether a remote renderer (DLNA speaker, the system's cast device) plays the queue.
class _RemoteOutput extends Notifier<bool> {
  @override
  bool build() {
    final router = ref.watch(playbackRouterProvider);
    if (router == null) return false;
    void update() {
      if (ref.mounted) state = router.isRemote;
    }

    router.route.addListener(update);
    ref.onDispose(() => router.route.removeListener(update));
    return router.isRemote;
  }
}

final _remoteOutputProvider = NotifierProvider<_RemoteOutput, bool>(_RemoteOutput.new);

final nowPlayingFormatProvider = Provider<NowPlayingFormat>((ref) {
  final (track, entryId) = ref.watch(playbackControllerProvider.select((s) => (s.current, s.currentEntryId)));
  if (track == null) return NowPlayingFormat.none;
  return nowPlayingFormatOf(
    track: track,
    entryId: entryId,
    deliveries: entryId == null ? null : ref.watch(entryDeliveriesProvider.select((m) => m[entryId])),
    playing: ref.watch(playingFormatProvider),
    remote: ref.watch(_remoteOutputProvider),
    spatial: ref.watch(spatialAudioProvider),
  );
});

/// The tags after the artist; they fade in and out on the effects spring as
/// the decoder reports (Android NowPlayingFormatTags' AnimatedVisibility).
/// [leadingGap] goes before them only while there are tags.
class NowPlayingFormatTags extends ConsumerStatefulWidget {
  const NowPlayingFormatTags({super.key, this.leadingGap = 8});

  final double leadingGap;

  @override
  ConsumerState<NowPlayingFormatTags> createState() => _NowPlayingFormatTagsState();
}

class _NowPlayingFormatTagsState extends ConsumerState<NowPlayingFormatTags> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  /// The last tags shown: kept through the fade-out.
  NowPlayingFormat _shown = NowPlayingFormat.none;
  bool _started = false;

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final format = ref.watch(nowPlayingFormatProvider);
    final visible = format.tags.isNotEmpty;
    if (visible) _shown = format;
    final reduced = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    if (!_started) {
      // Tags already known when the screen opens are there from its first frame.
      _started = true;
      _alpha.snapTo(visible ? 1 : 0);
    } else if (reduced) {
      _alpha.snapTo(visible ? 1 : 0);
    } else {
      _alpha.animateTo(visible ? 1 : 0);
    }
    final strings = NowPlayingStrings.of(context);
    final shown = _shown;
    if (shown.tags.isEmpty) return const SizedBox.shrink();
    final accent = shown.spatialized ? strings.formatSpatial : null;
    final joined = [...shown.tags, ?accent].join(' ');
    final from = shown.transcodedFrom;
    return AnimatedBuilder(
      animation: _alpha.animation,
      builder: (context, child) {
        final a = _alpha.value.clamp(0.0, 1.0);
        if (a == 0 && !visible) return const SizedBox.shrink();
        return Opacity(opacity: a, child: child);
      },
      child: Padding(
        padding: EdgeInsetsDirectional.only(start: widget.leadingGap),
        child: AudioFormatTagRow(
          tags: shown.tags,
          accentTag: accent,
          description: from == null
              ? strings.formatDescription(joined)
              : strings.formatTranscodedDescription(joined, from),
        ),
      ),
    );
  }
}
