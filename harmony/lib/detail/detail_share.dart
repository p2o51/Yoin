/// Share and "Open in …" for a page or a song (Android ui/detail/DetailMenu.kt
/// rememberDetailWebLink / rememberDetailShare / detailShareText /
/// openInLabel): the name, with its public link on the next line — the
/// service's own page (Apple Music), or a share link the server makes when
/// asked (a Subsonic server's `createShare`, owner 2026-11-08: share links
/// only). When the server refuses, the name goes alone with a short notice.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/browser/url_opener.dart';
import '../platform/share/text_share.dart';
import '../playback/playback_controller.dart';
import 'detail_strings.dart';

/// What Share sends: the page's [title], with its [link] on the next line when it has one.
String detailShareText(String title, String? link) => link == null ? title : '$title\n$link';

/// "Open in Spotify" / "Open in Apple Music" for [link]'s host; null for any other link.
String? openInLabel(String? link, DetailStrings strings) {
  if (link == null) return null;
  if (link.startsWith('https://open.spotify.com/')) return strings.menuOpenSpotify;
  if (link.startsWith('https://music.apple.com/')) return strings.menuOpenAppleMusic;
  return null;
}

/// A song's share title: its name and artist (TrackMenu.kt `title — artist`).
String trackShareTitle(Track track) => [
  for (final part in [track.title, track.artist])
    if (part != null && part.trim().isNotEmpty) part.trim(),
].join(' — ');

/// The page's or song's public link ([MusicLinks.webUrl]) on the account in
/// use; null while it resolves, or without one (a Subsonic server, a
/// library-only item, an id from another service).
final detailWebLinkProvider = FutureProvider.autoDispose.family<String?, (WebLinkKind, MediaId)>((ref, key) async {
  final source = await ref.watch(activeSourceProvider.future);
  final (kind, id) = key;
  if (source == null || source.provider != id.provider) return null;
  try {
    return await source.links?.webUrl(kind, id);
  } on Object {
    return null;
  }
});

/// Opens [link] in the browser, or the app that owns its links (Android
/// ACTION_VIEW); quiet when nothing opens it, as on Android.
Future<void> openDetailLink(ProviderContainer container, String link) async {
  final uri = Uri.tryParse(link);
  if (uri == null) return;
  await container.read(urlOpenerProvider).open(uri);
}

/// Shares one page or song (Android rememberDetailShare). Each call is one tap.
final class DetailShare {
  DetailShare._();

  /// The pages and songs whose share link is being made: a second tap meanwhile is ignored.
  static final Set<String> _making = {};

  @visibleForTesting
  static void resetForTest() => _making.clear();

  /// Sends [title] with [webLink] (or the item's public page, read now when
  /// [webLink] hasn't resolved yet). Without one it asks the service to make
  /// a share link for [id] ([Capability.shareLinks]), then sends that; when
  /// the server refuses, the name goes alone and [linkFailedMessage] shows on
  /// the window's snackbar. Never throws.
  static Future<void> share(
    ProviderContainer container, {
    required WebLinkKind kind,
    required MediaId? id,
    required String title,
    required String linkFailedMessage,
    String? webLink,
  }) async {
    final textShare = container.read(textShareProvider);
    Future<void> send(String? link) async {
      await textShare.shareText(detailShareText(title, link), title: title);
    }

    if (webLink != null || id == null) return send(webLink);
    final key = '${kind.name}:$id';
    if (!_making.add(key)) return;
    try {
      MusicSource? source;
      try {
        source = await container.read(activeSourceProvider.future);
      } on Object {
        source = null;
      }
      if (source == null || source.provider != id.provider) return send(null);
      final links = source.links;
      String? page;
      try {
        page = await links?.webUrl(kind, id);
      } on Object {
        page = null;
      }
      if (page != null) return send(page);
      if (links == null || !source.capabilities.contains(Capability.shareLinks)) return send(null);
      final String? made;
      try {
        made = await links.createShareLink(kind, id, description: title);
      } on Object catch (error) {
        debugPrint('YoinShare: no share link for $key: $error');
        container.read(playbackControllerProvider.notifier).showMessage(linkFailedMessage);
        return send(null);
      }
      return send(made);
    } finally {
      _making.remove(key);
    }
  }
}
