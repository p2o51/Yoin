/// The Play service card (Android widget/PlayWidget.kt, the "Play" family):
/// pick up recent listening from the home screen. Dart decides what it shows
/// and fetches the covers; the ArkTS host (yoin/cards) writes them for the
/// card and refreshes every placed card. Taps come back as [CardAction]s.
library;

import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show BuildContext, Navigator;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../detail/detail_play.dart';
import '../detail/detail_routes.dart';
import '../memories/memories_reveal_scope.dart';
import '../platform/cards/cards_channel.dart';
import '../platform/playback/playback_engine.dart' show PlaybackPhase;
import '../playback/now_playing_state.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import '../shell/shell_state.dart';
import '../theme/color_scheme.dart';
import '../theme/theme.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/artwork_retry.dart';
import 'memory_card.dart';

/// Items the Play card holds (Android playItems(limit = 6): the hero plus a shelf).
const playCardLimit = 6;

/// Cover edge requested for the card (Android loads 112dp at ~3x).
const playCardCoverPx = 320;

/// The card's play symbol, rendered once per theme (Android WidgetPlayButton: play_filled).
const playCardGlyphPx = 96;

/// What the Play card lists: albums and playlists the account played, newest
/// first, one row per entity (Android WidgetData.playItems: PLAYED and not an
/// artist; songs have no page of their own to reopen).
List<ActivityEvent> selectPlayEvents(List<ActivityEvent> events, {int limit = playCardLimit}) {
  final seen = <String>{};
  final out = <ActivityEvent>[];
  final sorted = [...events]..sort((a, b) => b.timestampMs.compareTo(a.timestampMs));
  for (final e in sorted) {
    if (e.actionType != ActivityActionType.played) continue;
    if (e.entityType != ActivityEntityType.album && e.entityType != ActivityEntityType.playlist) continue;
    if (!seen.add(e.entityKey)) continue;
    out.add(e);
    if (out.length == limit) break;
  }
  return out;
}

/// How long a card's play waits for the account (Android WidgetPlayActivity SOURCE_TIMEOUT_MS).
const cardPlaySourceTimeout = Duration(seconds: 8);

/// How long it then waits for the song to really play (Android PLAY_TIMEOUT_MS).
const cardPlayStartTimeout = Duration(seconds: 6);

/// Waits up to [timeout] for a playback state that passes [test]; null when none came.
typedef PlaybackStateWait = Future<NowPlayingState?> Function(bool Function(NowPlayingState state) test, Duration timeout);

/// The card's play key (Android WidgetPlayActivity.startPlayback): plays the
/// album or playlist [id] from its first song and says whether that really
/// started. False when there is no account (or it is another service's), the
/// page can't be read or has no songs, the play fails, or nothing of it plays
/// within [startTimeout]; the caller then opens the page. Artists have no queue
/// to resume: always false (their page opens).
Future<bool> startCardPlay({
  required String entity,
  required MediaId id,
  required Future<MusicSource?> Function() activeSource,
  required Future<void> Function(List<Track> tracks, PlaybackContext context) play,
  required PlaybackStateWait awaitState,
  Duration sourceTimeout = cardPlaySourceTimeout,
  Duration startTimeout = cardPlayStartTimeout,
}) async {
  final isPlaylist = entity == ActivityEntityType.playlist.name;
  if (!isPlaylist && entity != ActivityEntityType.album.name) return false;
  MusicSource? source;
  try {
    source = await activeSource().timeout(sourceTimeout);
  } catch (_) {
    source = null;
  }
  if (source == null || source.provider != id.provider) return false;
  final List<Track> tracks;
  final PlaybackContext context;
  try {
    if (isPlaylist) {
      final playlist = await source.library.getPlaylist(id);
      if (playlist == null) return false;
      tracks = playlist.tracks;
      context = playlistPlaybackContext(playlist, playlist.coverArt);
    } else {
      final album = await source.library.getAlbum(id);
      if (album == null) return false;
      tracks = album.tracks;
      context = albumPlaybackContext(album);
    }
  } catch (e) {
    debugPrint('cards: play read failed: ${e.runtimeType}');
    return false;
  }
  if (tracks.isEmpty) return false;
  await play(tracks, context);
  // Only states of this album / playlist count: something already playing (or
  // an old error) must not end the wait before the request lands.
  final outcome = await awaitState(
    (state) => _sameEntity(state.context, context) && (state.isPlaying || state.phase == PlaybackPhase.error),
    startTimeout,
  );
  return outcome != null && outcome.phase != PlaybackPhase.error;
}

/// Whether [a] and [b] play the same album / playlist (the read's own id: a
/// service may resolve the card's id to another one, Apple Music library → catalogue).
bool _sameEntity(PlaybackContext a, PlaybackContext b) => switch ((a, b)) {
  (AlbumPlaybackContext(albumId: final x), AlbumPlaybackContext(albumId: final y)) => x == y,
  (PlaylistPlaybackContext(playlistId: final x), PlaylistPlaybackContext(playlistId: final y)) => x == y,
  _ => false,
};

/// Keeps the Play card in step with the active account's listening.
final class PlayCardPublisher {
  PlayCardPublisher(this._ref, this._host, {Future<Uint8List?> Function(String url)? coverBytes})
    : _coverBytes = coverBytes ?? loadArtworkBytes;

  final Ref _ref;
  final CardsHost _host;

  /// A cover's bytes through the app's cover cache, so the card keeps them offline.
  final Future<Uint8List?> Function(String url) _coverBytes;

  StreamSubscription<List<ActivityEvent>>? _events;
  Timer? _debounce;
  String? _lastSignature;
  Uint8List? _glyphLight;
  Uint8List? _glyphDark;
  int _generation = 0;

  /// The events last scheduled, and whether a cover was missing from the
  /// last publish: a failed cover asks again when failed artwork does
  /// (ArtworkRetrySignal: the network recovered, the app came back).
  List<ActivityEvent>? _lastEvents;
  bool _coversMissing = false;

  void _retryCovers() {
    final events = _lastEvents;
    if (!_coversMissing || events == null) return;
    _lastSignature = null;
    _schedule(events);
  }

  void start() {
    ArtworkRetrySignal.generation.addListener(_retryCovers);
    _host.listen(_onAction).catchError((Object e) {
      // No host (tests, other platforms): the card simply does not exist.
      if (e is! MissingPluginException) debugPrint('cards: listen failed: $e');
    });
    _ref.listen<AsyncValue<ActivityLog?>>(activityLogProvider, (_, next) => _attach(next.value), fireImmediately: true);
    // The Memory card shares the start (and this publisher's tap listener).
    _ref.read(memoryCardPublisherProvider);
  }

  void dispose() {
    ArtworkRetrySignal.generation.removeListener(_retryCovers);
    _debounce?.cancel();
    _events?.cancel();
  }

  void _attach(ActivityLog? log) {
    _events?.cancel();
    _events = null;
    if (log == null) {
      _schedule(const []);
      return;
    }
    _events = log.watchRecent().listen(_schedule);
  }

  void _schedule(List<ActivityEvent> events) {
    _lastEvents = events;
    _debounce?.cancel();
    // A burst of writes (play + visit) settles into one publish.
    _debounce = Timer(const Duration(milliseconds: 800), () => _publish(events));
  }

  Future<void> _publish(List<ActivityEvent> events) async {
    final generation = ++_generation;
    MusicSource? source;
    try {
      source = await _ref.read(activeSourceProvider.future);
    } catch (_) {
      source = null;
    }
    final chosen = source == null ? const <ActivityEvent>[] : selectPlayEvents(events);
    final signature = [source?.provider, for (final e in chosen) '${e.entityKey}|${e.title}|${e.subtitle}|${e.coverArtKey}'].join('\n');
    if (signature == _lastSignature) return;

    final covers = await Future.wait([for (final e in chosen) _cover(source!, e.coverArtKey)]);
    if (generation != _generation) return; // a newer change is on its way
    _glyphLight ??= await renderPlayGlyph(Brightness.light);
    _glyphDark ??= await renderPlayGlyph(Brightness.dark);
    final items = [
      for (var i = 0; i < chosen.length; i++)
        PlayCardItem(
          entity: chosen[i].entityType.name,
          id: MediaId(source!.provider, chosen[i].entityId).toString(),
          title: chosen[i].title,
          subtitle: chosen[i].subtitle,
          cover: covers[i],
        ),
    ];
    _coversMissing = [
      for (var i = 0; i < chosen.length; i++) chosen[i].coverArtKey != null && covers[i] == null,
    ].any((m) => m);
    try {
      await _host.publishPlay(items, glyphLight: _glyphLight!, glyphDark: _glyphDark!);
      _lastSignature = signature;
    } on MissingPluginException {
      _lastSignature = signature;
    } catch (e) {
      debugPrint('cards: publish failed: $e');
    }
  }

  Future<Uint8List?> _cover(MusicSource source, String? key) async {
    final ref = CoverRef.fromStorageKey(key);
    if (ref == null) return null;
    final url = source.resolveCoverUrl(ref, size: playCardCoverPx);
    if (url == null) return null;
    return _coverBytes(url);
  }

  Future<void> _onAction(CardAction action) async {
    switch (action) {
      case CardOpenApp():
        return;
      case CardOpenMemory(:final sessionId):
        await _openMemory(sessionId);
      case CardOpen(:final entity, :final id):
        final mediaId = MediaId.tryParse(id);
        final context = await _navigatorContext();
        if (mediaId == null || context == null || !context.mounted) return;
        if (entity == ActivityEntityType.playlist.name) {
          DetailRoutes.openPlaylist(context, mediaId);
        } else if (entity == ActivityEntityType.artist.name) {
          DetailRoutes.openArtist(context, mediaId);
        } else {
          DetailRoutes.openAlbum(context, mediaId);
        }
      case CardPlay(:final entity, :final id):
        await _play(entity, id);
    }
  }

  /// The latest card play: only it may fall back to its page (an earlier one a
  /// newer tap replaced simply stops waiting).
  int _playGeneration = 0;

  /// The play key (the card's `call`, Yoin left behind other apps): starts the
  /// album / playlist and, when it can't (Android WidgetPlayActivity), shows its page.
  Future<void> _play(String entity, String id) async {
    final mediaId = MediaId.tryParse(id);
    if (mediaId == null) return;
    final generation = ++_playGeneration;
    var started = false;
    try {
      started = await startCardPlay(
        entity: entity,
        id: mediaId,
        activeSource: () => _ref.read(activeSourceProvider.future),
        play: (tracks, context) => playFromDetail(DetailPlayer.ref(_ref), tracks, context: context),
        awaitState: _awaitPlayback,
      );
    } catch (e) {
      debugPrint('cards: play failed: ${e.runtimeType}');
    }
    if (started || generation != _playGeneration) return;
    try {
      await _host.openDetail(entity, id);
    } catch (e) {
      debugPrint('cards: opening the page failed: $e');
    }
  }

  Future<NowPlayingState?> _awaitPlayback(bool Function(NowPlayingState state) test, Duration timeout) {
    final done = Completer<NowPlayingState?>();
    final subscription = _ref.container.listen<NowPlayingState>(playbackControllerProvider, (_, next) {
      if (!done.isCompleted && test(next)) done.complete(next);
    }, fireImmediately: true);
    final timer = Timer(timeout, () {
      if (!done.isCompleted) done.complete(null);
    });
    return done.future.whenComplete(() {
      timer.cancel();
      subscription.close();
    });
  }

  /// Memories on [sessionId] (the Home pill's way in): back to the shell
  /// first, then the reveal, once the shell has registered it (cold start).
  Future<void> _openMemory(int? sessionId) async {
    final context = await _navigatorContext();
    if (context == null || !context.mounted) return;
    Navigator.of(context).popUntil((route) => route.isFirst);
    final handle = _ref.read(memoriesRevealHandleProvider);
    for (var i = 0; i < 40 && handle.controller == null; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    handle.open(focus: sessionId);
  }

  /// The app navigator's context; on a cold start from a card it appears a
  /// few frames after the tap arrives.
  Future<BuildContext?> _navigatorContext() async {
    for (var i = 0; i < 40; i++) {
      final context = appNavigatorKey.currentContext;
      if (context != null) return context;
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    return null;
  }
}

/// The Yoin `play_filled` symbol in [brightness]'s onPrimary, as PNG bytes
/// (the card can't load Yoin's symbol font, so it gets the glyph as an image).
Future<Uint8List> renderPlayGlyph(Brightness brightness, {int px = playCardGlyphPx}) async {
  const icon = YoinSymbols.playFilled;
  final color = yoinExpressiveScheme(yoinSeed, brightness).onPrimary;
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  final painter = TextPainter(
    textDirection: TextDirection.ltr,
    text: TextSpan(
      text: String.fromCharCode(icon.codePoint),
      style: TextStyle(
        fontFamily: icon.fontFamily,
        package: icon.fontPackage,
        fontSize: px.toDouble(),
        height: 1,
        color: color,
      ),
    ),
  )..layout();
  painter.paint(canvas, Offset((px - painter.width) / 2, (px - painter.height) / 2));
  final image = await recorder.endRecording().toImage(px, px);
  final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
  image.dispose();
  painter.dispose();
  return bytes!.buffer.asUint8List();
}

/// The one publisher; read once at start-up (main.dart).
final playCardPublisherProvider = Provider<PlayCardPublisher>((ref) {
  final publisher = PlayCardPublisher(ref, ref.read(cardsHostProvider));
  publisher.start();
  ref.onDispose(publisher.dispose);
  return publisher;
});
