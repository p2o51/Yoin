import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../ui/navigation/yoin_push_route.dart';

/// The only way to open a detail page (PushPage, YoinPushRoute). Home,
/// Library, Now Playing and search call these; the pages themselves live
/// in lib/detail/. [preview] lets the page draw what the caller already
/// knows (name, cover) while the full item loads.
abstract final class DetailRoutes {
  static Future<void> openAlbum(BuildContext context, MediaId id, {Album? preview}) =>
      Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => _PendingDetail(title: preview?.name)));

  static Future<void> openArtist(BuildContext context, MediaId id, {String? name}) =>
      Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => _PendingDetail(title: name)));

  static Future<void> openPlaylist(BuildContext context, MediaId id, {Playlist? preview}) =>
      Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => _PendingDetail(title: preview?.name)));
}

/// Placeholder until the detail pages land (replace, don't keep).
class _PendingDetail extends StatelessWidget {
  const _PendingDetail({this.title});

  final String? title;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(YoinSymbols.back),
            tooltip: MaterialLocalizations.of(context).backButtonTooltip,
            onPressed: () => Navigator.of(context).pop(),
          ),
          title: Text(title ?? ''),
        ),
      );
}
