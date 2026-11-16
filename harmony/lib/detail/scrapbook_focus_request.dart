/// "Manage in Scrapbook" from Now Playing's About (Android
/// ScrapbookFocusRequest.kt): the request is left here and the album opens
/// the usual way; the album page that lists the song takes it, moves to
/// page 2, brings the song into view and opens its track sheet on that About
/// row's editor. A request nobody takes within [freshFor] is dropped, so it
/// can't hijack a later visit.
library;

import 'package:flutter/foundation.dart';

import '../data/about/song_about_store.dart';
import '../data/model/model.dart';

@immutable
final class ScrapbookFocus {
  const ScrapbookFocus(this.trackId, this.atMs, {this.edit});

  final MediaId trackId;
  final int atMs;

  /// The About row to open editing; null = just bring the song into view.
  final AboutRef? edit;
}

abstract final class ScrapbookFocusRequest {
  static const Duration freshFor = Duration(seconds: 10);

  /// The request waiting to be taken; listeners hear a new one (a page already open takes it too).
  static final ValueNotifier<ScrapbookFocus?> pending = ValueNotifier(null);

  /// Test seam for the clock.
  @visibleForTesting
  static int Function() clock = () => DateTime.now().millisecondsSinceEpoch;

  static void request(MediaId trackId, {AboutRef? edit}) =>
      pending.value = ScrapbookFocus(trackId, clock(), edit: edit);

  /// Takes the request when it is for one of [trackIds] and still fresh; null otherwise.
  static ScrapbookFocus? takeFor(Iterable<MediaId> trackIds) {
    final request = pending.value;
    if (request == null) return null;
    if (clock() - request.atMs > freshFor.inMilliseconds) {
      pending.value = null;
      return null;
    }
    if (!trackIds.contains(request.trackId)) return null;
    pending.value = null;
    return request;
  }
}
