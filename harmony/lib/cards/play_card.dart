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
import 'package:http/http.dart' as http;
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../detail/detail_play.dart';
import '../detail/detail_routes.dart';
import '../memories/memories_reveal_scope.dart';
import '../platform/cards/cards_channel.dart';
import '../shell/shell_state.dart';
import '../theme/color_scheme.dart';
import '../theme/theme.dart';
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

/// Keeps the Play card in step with the active account's listening.
final class PlayCardPublisher {
  PlayCardPublisher(this._ref, this._host, {http.Client? client}) : _client = client ?? http.Client();

  final Ref _ref;
  final CardsHost _host;
  final http.Client _client;

  StreamSubscription<List<ActivityEvent>>? _events;
  Timer? _debounce;
  String? _lastSignature;
  Uint8List? _glyphLight;
  Uint8List? _glyphDark;
  int _generation = 0;

  void start() {
    _host.listen(_onAction).catchError((Object e) {
      // No host (tests, other platforms): the card simply does not exist.
      if (e is! MissingPluginException) debugPrint('cards: listen failed: $e');
    });
    _ref.listen<AsyncValue<ActivityLog?>>(activityLogProvider, (_, next) => _attach(next.value), fireImmediately: true);
    // The Memory card shares the start (and this publisher's tap listener).
    _ref.read(memoryCardPublisherProvider);
  }

  void dispose() {
    _debounce?.cancel();
    _events?.cancel();
    _client.close();
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
    try {
      final res = await _client.get(Uri.parse(url)).timeout(const Duration(seconds: 15));
      final type = res.headers['content-type'] ?? '';
      if (res.statusCode != 200 || !type.startsWith('image/') || res.bodyBytes.length > 4 * 1024 * 1024) return null;
      return res.bodyBytes;
    } catch (_) {
      return null;
    }
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
        } else {
          DetailRoutes.openAlbum(context, mediaId);
        }
      case CardPlay(:final entity, :final id):
        final mediaId = MediaId.tryParse(id);
        if (mediaId == null) return;
        final source = await _ref.read(activeSourceProvider.future);
        if (source == null || source.provider != mediaId.provider) return;
        final player = DetailPlayer.ref(_ref);
        if (entity == ActivityEntityType.playlist.name) {
          final playlist = await source.library.getPlaylist(mediaId);
          if (playlist == null) return;
          await playFromDetail(player, playlist.tracks, context: playlistPlaybackContext(playlist, playlist.coverArt));
        } else {
          final album = await source.library.getAlbum(mediaId);
          if (album == null) return;
          await playFromDetail(player, album.tracks, context: albumPlaybackContext(album));
        }
    }
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
