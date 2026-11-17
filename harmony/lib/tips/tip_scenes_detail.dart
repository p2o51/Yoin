/// An album's page on the demo phone, as the Compact page lays it out
/// (lib/detail/reshape_scaffold.dart): the real pull-up state
/// ([RevealController]), the real header, spectrum, song rows and titles.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../detail/album_spectrum_layer.dart';
import '../detail/detail_page.dart';
import '../detail/detail_palette.dart';
import '../detail/detail_strings.dart';
import '../detail/detail_track_rows.dart' show AlbumTrackRow, TrackCountLine;
import '../detail/flowing_titles.dart';
import '../detail/playback_projection.dart' show trackPlayStateProvider;
import '../detail/pull_up_reveal.dart';
import '../detail/reshape_scaffold.dart' show DetailBarHeader;
import '../theme/tokens.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import 'demo_data.dart';
import 'demo_phone.dart';
import 'tip_scenes_library.dart' show DemoSceneState;

/// Cover stamp: pulling an album's page up flies its cover into a stamp at
/// the end of the bar (the spectrum forming behind the title); a tap on the
/// stamp brings the cover back. The user's own drag and tap on the phone
/// feed the same controller.
class CoverStampDemo extends StatefulWidget {
  const CoverStampDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<CoverStampDemo> createState() => _CoverStampDemoState();
}

class _CoverStampDemoState extends State<CoverStampDemo> with DemoSceneState, TickerProviderStateMixin {
  late final RevealController _reveal = RevealController(vsync: this);
  late final RevealScrollPhysics _physics = RevealScrollPhysics(_reveal);
  static const _album = DemoAlbum('Harbour Lights', 'Mira Sol', 0);
  static const _songs = 8;

  /// The screen's height as laid out (a fraction of the phone → pixels for the drag).
  double _screenHeight = 0;

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    _reveal.setExpanded(false, animate: false);
    await f.wait(700);
    await f.moveTo('cover', fy: 0.75);
    // Up the cover: the finger's travel moves the page as it would (positive = down).
    final from = f.at('cover', fy: 0.75);
    var last = 0.0;
    await f.drag(
      from.dx,
      from.dy - TipSceneTokens.stampPull,
      700,
      onProgress: (t) {
        _reveal.dragBy(-(t - last) * TipSceneTokens.stampPull * _screenHeight);
        last = t;
      },
    );
    _reveal.release(-TipSceneTokens.stampFling);
    await f.lift();
    await f.wait(1600);
    // The stamp is the cover, docked: its tap opens the page again.
    await f.moveTo('cover');
    await f.wait(300);
    await f.tap(() => _reveal.setExpanded(false));
    await f.lift();
    await f.wait(1500);
  }

  @override
  Widget build(BuildContext context) {
    final reduce = MediaQuery.disableAnimationsOf(context);
    final strings = DetailStrings.of(context);
    final tracks = [for (var i = 0; i < _songs; i++) demoTrack(i, _album)];
    final totalSec = tracks.fold(0, (sum, t) => sum + (t.durationSec ?? 0));
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      // Nothing in the phone plays: the rows don't ask the app's player.
      child: ProviderScope(
        overrides: [trackPlayStateProvider.overrideWith((ref, id) => (current: false, playing: false))],
        child: DetailPalette(
          coverUrl: _album.coverUrl,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final w = constraints.maxWidth;
              final h = constraints.maxHeight;
              _screenHeight = h;
              _reveal.travelPx = PullUpMath.travel(h);
              // The Compact page's geometry (reshape_scaffold _buildPage), the phone's top air for the status bar.
              const topInset = TipSceneTokens.pageTop;
              const headerBottom = topInset + DetailHeaderMetrics.bandHeight;
              final side = PullUpMath.heroCoverSide(
                w,
                fraction: DetailTokens.albumCoverWidthFraction,
                max: DetailTokens.albumCoverMax,
              );
              final restCover = Rect.fromLTWH((w - side) / 2, headerBottom + DetailTokens.albumCoverTopGap, side, side);
              final stamp = Rect.fromLTWH(
                w - DetailTokens.albumStampEndInset - DetailTokens.albumStamp,
                topInset +
                    DetailHeaderMetrics.paddingTop +
                    (DetailHeaderMetrics.rowHeight - DetailTokens.albumStamp) / 2 -
                    PullUpTokens.titleLift,
                DetailTokens.albumStamp,
                DetailTokens.albumStamp,
              );
              return Stack(
                children: [
                  const Positioned.fill(child: DetailPageBackground()),
                  Positioned.fill(
                    child: AlbumSpectrumLayer(
                      reveal: _reveal,
                      coverUrl: _album.coverUrl,
                      cover: restCover,
                      bar: Rect.fromLTWH(0, 0, w, headerBottom),
                      reduce: reduce,
                      ticks: false,
                    ),
                  ),
                  Positioned.fill(
                    top: headerBottom,
                    child: _DemoAlbumBody(
                      reveal: _reveal,
                      physics: _physics,
                      slotHeight: side + PullUpTokens.heroBand,
                      rows: [
                        Padding(
                          padding: const EdgeInsetsDirectional.only(start: 8, top: 2, bottom: 10),
                          child: TrackCountLine(count: tracks.length, totalSec: totalSec),
                        ),
                        for (final (i, track) in tracks.indexed)
                          AlbumTrackRow(track: track, index: i, featArtist: null, showHeart: false, onTap: () {}),
                      ],
                      below: FlowingTitles(
                        titles: [for (final t in tracks) FlowingTitle(t.title ?? '')],
                        onTap: (_) {},
                        enabled: false,
                      ),
                    ),
                  ),
                  Positioned(
                    left: 0,
                    right: 0,
                    top: topInset,
                    height: DetailHeaderMetrics.bandHeight,
                    child: DetailBarHeader(
                      reveal: _reveal,
                      page: const AlwaysStoppedAnimation<double>(0),
                      docks: true,
                      reduce: reduce,
                      title: _album.name,
                      titleTrailing: null,
                      meta: [MetaPlain(_album.artist), MetaKind(strings.albumKind), const MetaPlain('2024')],
                      endPadding: DetailHeaderMetrics.paddingEndAlbum,
                      onBack: () {},
                    ),
                  ),
                  // The cover: from the hero to the stamp with the pull-up; docked, a tap brings it back.
                  AnimatedBuilder(
                    animation: _reveal,
                    builder: (context, _) {
                      final progress = _reveal.barProgress.clamp(0.0, 1.0);
                      final docked = progress >= PullUpTokens.barDockedThreshold;
                      return Positioned.fromRect(
                        rect: reduce ? restCover : PullUpMath.coverRect(restCover, stamp, progress),
                        child: Opacity(
                          opacity: reduce ? PullUpMath.heroAlpha(progress) : 1,
                          child: DemoTarget(
                            'cover',
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: docked ? () => _reveal.setExpanded(false) : null,
                              onVerticalDragUpdate: docked ? null : (d) => _reveal.dragBy(d.delta.dy),
                              onVerticalDragEnd: docked ? null : (d) => _reveal.release(d.velocity.pixelsPerSecond.dy),
                              child: MediaArtwork(
                                url: _album.coverUrl,
                                fallbackIcon: YoinSymbols.album,
                                radius: PullUpMath.coverCorner(progress),
                                decodeWidth: 640,
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

/// The page under the header (reshape_scaffold _Body): the list fades in
/// under the hero as it leaves; the hero's drag is the pull-up, and the
/// list pulled down at its top gives the page back to the hero.
class _DemoAlbumBody extends StatelessWidget {
  const _DemoAlbumBody({
    required this.reveal,
    required this.physics,
    required this.slotHeight,
    required this.rows,
    required this.below,
  });

  final RevealController reveal;
  final RevealScrollPhysics physics;
  final double slotHeight;
  final List<Widget> rows;
  final Widget below;

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      AnimatedBuilder(
        animation: reveal,
        child: ListView(physics: physics, padding: const EdgeInsets.fromLTRB(16, 4, 16, 16), children: rows),
        builder: (context, child) {
          final expand = reveal.expand;
          if (expand <= 0.001) return const SizedBox.shrink();
          return IgnorePointer(
            ignoring: expand < 0.5,
            child: Opacity(
              opacity: PullUpMath.listAlpha(expand),
              child: Transform.translate(offset: Offset(0, PullUpMath.listShift(expand)), child: child),
            ),
          );
        },
      ),
      AnimatedBuilder(
        animation: reveal,
        child: ClipRect(
          child: OverflowBox(
            alignment: Alignment.topCenter,
            minHeight: 0,
            maxHeight: double.infinity,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(height: slotHeight),
                Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), child: below),
              ],
            ),
          ),
        ),
        builder: (context, child) {
          final expand = reveal.expand;
          if (expand >= 0.999) return const SizedBox.shrink();
          return IgnorePointer(
            ignoring: expand >= 0.5,
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onVerticalDragUpdate: reveal.expanded ? null : (d) => reveal.dragBy(d.delta.dy),
              onVerticalDragEnd: reveal.expanded ? null : (d) => reveal.release(d.velocity.pixelsPerSecond.dy),
              child: Opacity(
                opacity: PullUpMath.heroAlpha(expand),
                child: Transform.translate(offset: Offset(0, PullUpMath.heroShift(expand)), child: child),
              ),
            ),
          );
        },
      ),
    ],
  );
}
