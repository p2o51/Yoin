import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../playback/playback_controller.dart';

/// The current track's id only — rows watch this (with a select on their own
/// id), never the whole NowPlayingState, so position samples rebuild nothing.
final currentTrackIdProvider = Provider<MediaId?>(
  (ref) => ref.watch(playbackControllerProvider.select((s) => s.current?.id)),
);

final isPlayingProvider = Provider<bool>((ref) => ref.watch(playbackControllerProvider.select((s) => s.isPlaying)));

/// Whether [id] is the current track (null when it isn't), and whether it plays.
final trackPlayStateProvider = Provider.family<({bool current, bool playing}), MediaId>((ref, id) {
  final current = ref.watch(currentTrackIdProvider) == id;
  return (current: current, playing: current && ref.watch(isPlayingProvider));
});
