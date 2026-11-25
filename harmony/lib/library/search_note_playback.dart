/// Play-from-a-note's second for Library search (#Yoin:#18; Android
/// ui/library/SearchNotePlayback.kt): once the song asked for is current and
/// prepared ([noteSeekReady], the search core's), seek to where the note was
/// written. Gives up after [noteSeekTimeout] (a provider that never reports
/// it). Where a note leads is the core's [noteOpenTarget].
library;

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/search/note_open_target.dart';
import '../platform/playback/playback_engine.dart' show PlaybackPhase;
import '../playback/now_playing_state.dart';
import '../playback/playback_controller.dart';

/// Whether [state] is ready for a note's seek on [rawTrackId]. The duration
/// only counts once the engine says the song is prepared: until then
/// HarmonyOS shows the song's own length as a hint.
bool noteSeekReadyIn(NowPlayingState state, String rawTrackId) =>
    noteSeekReady(state.current?.id.rawId, state.phase == PlaybackPhase.ready ? state.durationMs ?? 0 : 0, rawTrackId);

/// Seeks to [positionMs] once [rawTrackId] is current and prepared, within
/// [timeout]; nothing for a note at the start. Returns whether it sought.
Future<bool> seekWhenCurrent(
  ProviderContainer container,
  String rawTrackId,
  int positionMs, {
  Duration timeout = noteSeekTimeout,
}) async {
  if (positionMs <= 0) return false;
  final ready = Completer<bool>();
  final sub = container.listen<NowPlayingState>(playbackControllerProvider, (_, next) {
    if (!ready.isCompleted && noteSeekReadyIn(next, rawTrackId)) ready.complete(true);
  }, fireImmediately: true);
  final timer = Timer(timeout, () {
    if (!ready.isCompleted) ready.complete(false);
  });
  final sought = await ready.future;
  timer.cancel();
  sub.close();
  if (sought) await container.read(playbackControllerProvider.notifier).seekTo(positionMs);
  return sought;
}
