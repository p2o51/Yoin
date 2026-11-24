/// "View in Scrapbook" from a song sheet outside the album page, and "Manage
/// in Scrapbook" from Now Playing's About (Android ScrapbookFocusRequest):
/// the caller leaves this request and opens the album the usual way (Go to
/// album); the album page that lists the song takes it, moves to page 2 and
/// brings the song into view there, opening its track sheet on the About
/// row's editor when the request names one. A request nobody
/// takes within [ScrapbookFocusRequest.freshFor] is dropped, so it can't
/// hijack a later visit. A note's album mention asks with the album's own id:
/// the album page moves to page 2 and brings nothing in particular into view.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/about/song_about_store.dart';
import '../data/model/model.dart';

/// One waiting request: the song (or the album itself: page 2 alone), when it
/// was asked for, and the About row to open editing (null = just bring the
/// song into view).
@immutable
final class ScrapbookFocus {
  const ScrapbookFocus(this.songId, this.at, {this.edit});

  final MediaId songId;
  final DateTime at;
  final AboutRef? edit;
}

/// The request waiting to be taken, if any.
class ScrapbookFocusRequests extends Notifier<ScrapbookFocus?> {
  @override
  ScrapbookFocus? build() => null;

  void request(MediaId songId, {AboutRef? edit, DateTime? now}) =>
      state = ScrapbookFocus(songId, now ?? DateTime.now(), edit: edit);

  /// Takes the request when it is for one of [songIds] and still fresh; null
  /// otherwise (a stale one is dropped on the way).
  ScrapbookFocus? takeFor(Iterable<MediaId> songIds, {DateTime? now}) {
    final pending = state;
    if (pending == null) return null;
    if ((now ?? DateTime.now()).difference(pending.at) > ScrapbookFocusRequest.freshFor) {
      state = null;
      return null;
    }
    if (!songIds.contains(pending.songId)) return null;
    state = null;
    return pending;
  }
}

final scrapbookFocusRequestProvider = NotifierProvider<ScrapbookFocusRequests, ScrapbookFocus?>(
  ScrapbookFocusRequests.new,
);

abstract final class ScrapbookFocusRequest {
  /// How long a request waits for its album page.
  static const freshFor = Duration(seconds: 10);

  static void request(ProviderContainer container, MediaId songId) =>
      container.read(scrapbookFocusRequestProvider.notifier).request(songId);
}

/// Takes a waiting request for one of [songIds] for the album page it sits
/// on: once the page shows them, and whenever a new request comes while it
/// is the window's top page (a page covered by the one the request opens
/// leaves it to that one). Lays out as nothing.
class ScrapbookFocusTaker extends ConsumerStatefulWidget {
  const ScrapbookFocusTaker({super.key, required this.songIds, required this.onTake});

  final List<MediaId> songIds;
  final ValueChanged<ScrapbookFocus> onTake;

  @override
  ConsumerState<ScrapbookFocusTaker> createState() => _ScrapbookFocusTakerState();
}

class _ScrapbookFocusTakerState extends ConsumerState<ScrapbookFocusTaker> {
  @override
  void initState() {
    super.initState();
    _checkAfterFrame();
  }

  @override
  void didUpdateWidget(ScrapbookFocusTaker oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!listEquals(oldWidget.songIds, widget.songIds)) _checkAfterFrame();
  }

  /// After the frame: a request that opens another album page has pushed it by then.
  void _checkAfterFrame() => WidgetsBinding.instance.addPostFrameCallback((_) => _check());

  void _check() {
    if (!mounted) return;
    if (!(ModalRoute.of(context)?.isCurrent ?? true)) return;
    final taken = ref.read(scrapbookFocusRequestProvider.notifier).takeFor(widget.songIds);
    if (taken != null) widget.onTake(taken);
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(scrapbookFocusRequestProvider, (_, next) {
      if (next != null) _checkAfterFrame();
    });
    return const SizedBox.shrink();
  }
}
