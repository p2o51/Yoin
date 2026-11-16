import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../memories/memory_rate_sheet.dart';
import '../memories/palette/memory_palette.dart';
import '../nowplaying/haptics.dart';
import '../nowplaying/note_logic.dart' show formatNotePosition;
import '../playback/playback_controller.dart';
import '../theme/motion.dart';
import '../nowplaying/about_widgets.dart' show markdownBoldSpan;
import '../settings/material_shapes.dart' show PolygonBorder, avatarShapes;
import '../theme/tokens.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import 'album_scrapbook.dart';
import 'album_scrapbook_editor.dart';
import 'album_scrapbook_sheet_model.dart';
import 'album_scrapbook_track_sheet.dart';
import 'detail_listening.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_strings.dart';
import 'scrapbook_focus.dart';

/// The scrapbook's layout numbers (AlbumScrapbookPage.kt ScrapbookLayout, D3
/// §5.1) beyond the ones [DetailTokens] already carries.
abstract final class ScrapbookLayout {
  static const double laneMin = 280;
  static const double laneGap = 28;
  static const double rowGap = 28;
  static const double rowGapWide = 32;
  static const double maxWidth = 1040;
  static const double gutter = 16;
  static const double gutterWide = 24;
  static const double wideFrom = 600;
  static const double coverSideWide = 184;
  static const double emblemWide = 72;

  /// Above the first piece and under the last (the window's bar).
  static const double topInset = 4;
  static const double bottomInset = 112;

  /// Ticket: minimum heights, the perforation's dash and inset.
  static const double ticketMinHeight = 64;
  static const double ticketMiniHeight = 56;

  /// The heart sticker on a liked ticket and its offset off the corner.
  static const double sticker = 26;
  static const Offset stickerOffset = Offset(-9, -10);

  /// Notes clipping: the time-stamp column, the rail's inset and width.
  static const double noteStamp = 42;
  static const double railInset = 14;
  static const double railX = 11;
  static const double railWidth = 3;

  /// The blank review's dashed pen circle.
  static const double pen = 48;
  static const double penGlyph = 20;

  /// Receipt: its width cap, tooth width and depth.
  static const double receiptMaxWidth = 330;
  static const double receiptTooth = 14;
  static const double receiptToothDepth = 8;
  static const double receiptCorner = 20;

  /// Review clipping width cap (beside the cover).
  static const double reviewMaxWidth = 420;

  /// The question / About card's share of the cluster and its cap.
  static const double cardWidthFactor = 0.92;
  static const double cardMaxWidth = 380;

  /// The question card's "Q" sticker and its offset off the corner.
  static const double askSticker = 28;
  static const Offset askStickerOffset = Offset(-11, -12);

  /// Fact tags: how far the first rides up the card, then each the one above; side inset, width cap, corner.
  static const double tagOverlap = 6;
  static const double tagStackOverlap = -6;
  static const double tagInset = 12;
  static const double tagMaxWidth = 340;
  static const double tagCorner = 16;

  /// "View in Scrapbook" / "Manage in Scrapbook": the room left above the song's piece, and its swell.
  static const double focusMargin = 24;
  static const double focusSwell = 0.04;
}

/// Whether [piece] shows [songId] (its own cluster, or a contact sheet frame).
bool scrapPieceHolds(ScrapPiece piece, MediaId songId) => switch (piece) {
  ScrapTrackCluster(:final track) => track.track.id == songId,
  ScrapContactSheet(:final tracks) => tracks.any((t) => t.track.id == songId),
  _ => false,
};

/// Lanes for a content width: as many 280dp lanes (28dp apart) as fit, 1…3.
int scrapLanes(double contentWidth) =>
    ((contentWidth + ScrapbookLayout.laneGap) / (ScrapbookLayout.laneMin + ScrapbookLayout.laneGap)).floor().clamp(
      1,
      3,
    );

/// The page's colours, from the album's cover scheme (the scheme page 1 uses).
@immutable
final class ScrapbookColors {
  const ScrapbookColors({
    required this.ink,
    required this.onInk,
    required this.ticket,
    required this.onTicket,
    required this.ticketNow,
    required this.onTicketNow,
    required this.review,
    required this.onReview,
    required this.note,
    required this.onNote,
    required this.ask,
    required this.onAsk,
    required this.askMark,
    required this.onAskMark,
    required this.tag,
    required this.onTag,
    required this.receipt,
    required this.onReceipt,
    required this.outline,
    required this.muted,
    required this.dots,
  });

  factory ScrapbookColors.of(ColorScheme s) {
    final dark = s.surface.computeLuminance() < 0.5;
    return ScrapbookColors(
      ink: s.primary,
      onInk: s.onPrimary,
      ticket: s.surfaceContainerHigh,
      onTicket: s.onSurface,
      ticketNow: s.secondaryContainer,
      onTicketNow: s.onSecondaryContainer,
      review: s.primaryContainer,
      onReview: s.onPrimaryContainer,
      note: s.surfaceContainerHighest,
      onNote: s.onSurface,
      ask: s.tertiaryContainer,
      onAsk: s.onTertiaryContainer,
      askMark: s.tertiary,
      onAskMark: s.onTertiary,
      tag: s.secondaryContainer,
      onTag: s.onSecondaryContainer,
      receipt: s.surfaceContainer,
      onReceipt: s.onSurface,
      outline: s.outline,
      muted: s.onSurfaceVariant,
      dots: s.outlineVariant.withValues(alpha: dark ? 0.35 : 0.45),
    );
  }

  final Color ink, onInk, ticket, onTicket, ticketNow, onTicketNow, review, onReview;
  final Color note, onNote, ask, onAsk, askMark, onAskMark, tag, onTag, receipt, onReceipt, outline, muted, dots;

  static ScrapbookColors lerp(ScrapbookColors a, ScrapbookColors b, double t) {
    Color l(Color x, Color y) => Color.lerp(x, y, t)!;
    return ScrapbookColors(
      ink: l(a.ink, b.ink),
      onInk: l(a.onInk, b.onInk),
      ticket: l(a.ticket, b.ticket),
      onTicket: l(a.onTicket, b.onTicket),
      ticketNow: l(a.ticketNow, b.ticketNow),
      onTicketNow: l(a.onTicketNow, b.onTicketNow),
      review: l(a.review, b.review),
      onReview: l(a.onReview, b.onReview),
      note: l(a.note, b.note),
      onNote: l(a.onNote, b.onNote),
      ask: l(a.ask, b.ask),
      onAsk: l(a.onAsk, b.onAsk),
      askMark: l(a.askMark, b.askMark),
      onAskMark: l(a.onAskMark, b.onAskMark),
      tag: l(a.tag, b.tag),
      onTag: l(a.onTag, b.onTag),
      receipt: l(a.receipt, b.receipt),
      onReceipt: l(a.onReceipt, b.onReceipt),
      outline: l(a.outline, b.outline),
      muted: l(a.muted, b.muted),
      dots: l(a.dots, b.dots),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ScrapbookColors &&
      other.ink == ink &&
      other.ticket == ticket &&
      other.ticketNow == ticketNow &&
      other.review == review &&
      other.note == note &&
      other.ask == ask &&
      other.tag == tag &&
      other.receipt == receipt &&
      other.dots == dots &&
      other.onTicket == onTicket &&
      other.muted == muted;

  @override
  int get hashCode => Object.hash(ink, ticket, ticketNow, review, note, receipt, dots, onTicket, muted);
}

/// The album's page 2 (AlbumScrapbookPage): dotted paper that scrolls with
/// the pieces, laid out in 280dp lanes (one on a phone). [page] is the
/// pager position (0 = overview, 1 = here): while it drags, each piece trails
/// by its depth and leans a little more. Back leaves the album from here
/// exactly as from page 1 — page 2 is an in-page state, not a place.
///
/// [focus] is "View in Scrapbook" (a song's sheet) or "Manage in Scrapbook"
/// (Now Playing's About): the page scrolls that song's piece into view,
/// swells it a touch and lets it settle, then clears [focus]; a request that
/// names an About row opens the song's sheet on that row's editor.
class AlbumScrapbookPage extends ConsumerStatefulWidget {
  const AlbumScrapbookPage({super.key, required this.album, required this.coverUrl, required this.page, this.focus});

  final Album album;
  final String? coverUrl;
  final ValueListenable<double> page;
  final ValueNotifier<ScrapbookFocus?>? focus;

  @override
  ConsumerState<AlbumScrapbookPage> createState() => _AlbumScrapbookPageState();
}

class _AlbumScrapbookPageState extends ConsumerState<AlbumScrapbookPage> with TickerProviderStateMixin {
  late final SpringValue _t = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  final _colors = ValueNotifier<ScrapbookColors?>(null);
  ScrapbookColors? _from;
  ScrapbookColors? _to;

  final _scroll = ScrollController();
  final _pieceKeys = <String, GlobalKey>{};

  /// The scroll toward a focused piece, on the spatial spring.
  late final AnimationController _scrollSpring = AnimationController.unbounded(vsync: this)
    ..addListener(() {
      if (_scroll.hasClients) _scroll.jumpTo(_scrollSpring.value);
    });

  /// The focused piece's swell (0 → 1 → 0) and which piece it is.
  late final AnimationController _pulse = AnimationController.unbounded(vsync: this);
  String? _pulsedKey;
  bool _focusScheduled = false;
  StreamSubscription<ScrapEvent>? _events;

  @override
  void initState() {
    super.initState();
    _t.animation.addListener(() {
      final from = _from, to = _to;
      if (from != null && to != null) _colors.value = ScrapbookColors.lerp(from, to, _t.value.clamp(0.0, 1.0));
    });
    widget.focus?.addListener(_scheduleFocus);
    _scheduleFocus();
  }

  @override
  void didUpdateWidget(AlbumScrapbookPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.focus != widget.focus) {
      oldWidget.focus?.removeListener(_scheduleFocus);
      widget.focus?.addListener(_scheduleFocus);
      _scheduleFocus();
    }
  }

  /// After the next layout: the page may have only just been built (the pager moving here).
  void _scheduleFocus() {
    if (_focusScheduled || widget.focus?.value == null) return;
    _focusScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _focusScheduled = false;
      _bringFocusIntoView();
    });
  }

  Future<void> _bringFocusIntoView() async {
    final focus = widget.focus;
    final request = focus?.value;
    if (!mounted || focus == null || request == null) return;
    // The paper may still be coming in: the build schedules this again when it lands.
    final pieces = ref.read(albumScrapbookProvider(widget.album.id));
    if (pieces == null) return;
    focus.value = null;
    final songId = request.songId;
    // "Manage in Scrapbook": the song's sheet opens on that About row's editor.
    if (request.edit case final edit?) {
      showScrapTrackSheet(
        context,
        album: widget.album,
        trackId: songId,
        colors: _colors.value ?? ScrapbookColors.of(Theme.of(context).colorScheme),
        editing: edit,
      );
    }
    final piece = pieces.where((p) => scrapPieceHolds(p, songId)).firstOrNull;
    final box = piece == null ? null : _pieceKeys[piece.key]?.currentContext?.findRenderObject() as RenderBox?;
    if (piece == null || box == null || !box.attached || !_scroll.hasClients) return;
    final viewport = RenderAbstractViewport.maybeOf(box);
    if (viewport == null) return;
    final position = _scroll.position;
    final target = (viewport.getOffsetToReveal(box, 0).offset - ScrapbookLayout.focusMargin).clamp(
      position.minScrollExtent,
      position.maxScrollExtent,
    );
    if (MediaQuery.disableAnimationsOf(context)) {
      _scroll.jumpTo(target);
      return;
    }
    _scrollSpring
      ..value = position.pixels
      ..animateWith(SpringSimulation(YoinSprings.defaultSpatial, position.pixels, target, 0));
    setState(() => _pulsedKey = piece.key);
    _pulse.value = 0;
    await _pulse.animateWith(SpringSimulation(YoinSprings.defaultSpatial, 0, 1, 0));
    if (!mounted) return;
    await _pulse.animateWith(SpringSimulation(YoinSprings.defaultSpatial, _pulse.value, 0, _pulse.velocity));
    if (mounted) setState(() => _pulsedKey = null);
  }

  void _onEvent(ScrapEvent event) {
    if (!mounted || ScrapTrackSheetScope.isOpen(widget.album.id)) return;
    final strings = DetailStrings.of(context);
    final say = ref.read(playbackControllerProvider.notifier).showMessage;
    final editor = ref.read(albumScrapbookEditorProvider(widget.album.id).notifier);
    switch (event) {
      case ScrapRegenerated(:final restore):
        say(strings.scrapRegenerated, actionLabel: strings.scrapUndo, long: true, onAction: () => editor.edit(restore));
      case ScrapFailed(:final failure):
        say(scrapFailureText(strings, failure));
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final scheme = DetailPaletteScope.maybeOf(context)?.scheme ?? Theme.of(context).colorScheme;
    final target = ScrapbookColors.of(scheme);
    if (target == _to) return;
    final current = _colors.value;
    _to = target;
    // Colours never snap: the cover scheme's arrival springs on the effects spring.
    if (current == null || MediaQuery.disableAnimationsOf(context)) {
      _from = target;
      _colors.value = target;
      _t.snapTo(1);
    } else {
      _from = current;
      _t.snapTo(0);
      _t.animateTo(1);
    }
  }

  @override
  void dispose() {
    widget.focus?.removeListener(_scheduleFocus);
    _events?.cancel();
    _scrollSpring.dispose();
    _t.dispose();
    _pulse.dispose();
    _scroll.dispose();
    _colors.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pieces = ref.watch(albumScrapbookProvider(widget.album.id));
    // The sheet's AI work stays alive while the page is open; what it came to
    // is said here once the sheet has closed.
    ref.watch(albumScrapbookEditorProvider(widget.album.id));
    _events ??= ref.read(albumScrapbookEditorProvider(widget.album.id).notifier).events.listen(_onEvent);
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    if (pieces == null) return const SizedBox.expand();
    if (widget.focus?.value != null) _scheduleFocus();
    final reduce = MediaQuery.disableAnimationsOf(context);
    return ValueListenableBuilder<ScrapbookColors?>(
      valueListenable: _colors,
      builder: (context, colors, _) {
        final c = colors ?? ScrapbookColors.of(Theme.of(context).colorScheme);
        return LayoutBuilder(
          builder: (context, constraints) {
            final gutter = constraints.maxWidth >= ScrapbookLayout.wideFrom
                ? ScrapbookLayout.gutterWide
                : ScrapbookLayout.gutter;
            final contentWidth = math.min(constraints.maxWidth - gutter * 2, ScrapbookLayout.maxWidth);
            final lanes = scrapLanes(contentWidth);
            final wide = lanes > 1;
            final motion = _ScrapMotion(reduce ? null : widget.page, constraints.maxWidth);
            return _ScrapScope(
              motion: motion,
              colors: c,
              // Scrolls under the fixed header through the chrome seam (the user's
              // Scroll edge style), as the track list does; the paper goes under with the pieces.
              child: DetailColorsBuilder(
                builder: (context, page) => ChromeSeam(
                  background: SeamBackground([
                    page.wash,
                    Theme.of(context).colorScheme.surface,
                    Theme.of(context).colorScheme.surfaceContainerLow,
                  ]),
                  child: NotificationListener<ScrollStartNotification>(
                    // A drag takes the page back from a focus scroll.
                    onNotification: (n) {
                      if (n.dragDetails != null) _scrollSpring.stop();
                      return false;
                    },
                    child: SingleChildScrollView(
                      controller: _scroll,
                      child: CustomPaint(
                        painter: _DottedPaper(c.dots),
                        child: ConstrainedBox(
                          constraints: BoxConstraints(minHeight: constraints.maxHeight),
                          child: Padding(
                            padding: EdgeInsets.only(
                              top: ScrapbookLayout.topInset,
                              bottom: ScrapbookLayout.bottomInset + bottomInset,
                            ),
                            child: Align(
                              alignment: Alignment.topCenter,
                              child: SizedBox(
                                width: contentWidth,
                                child: _ScrapMasonry(
                                  lanes: lanes,
                                  rowGap: wide ? ScrapbookLayout.rowGapWide : ScrapbookLayout.rowGap,
                                  children: [
                                    for (final piece in pieces)
                                      _ScrapSpan(
                                        key: ValueKey(piece.key),
                                        fullLine: piece.fullLine,
                                        child: KeyedSubtree(
                                          key: _pieceKeys.putIfAbsent(piece.key, GlobalKey.new),
                                          child: AnimatedBuilder(
                                            animation: _pulse,
                                            builder: (context, child) => Transform.scale(
                                              scale: _pulsedKey == piece.key
                                                  ? 1 + ScrapbookLayout.focusSwell * _pulse.value
                                                  : 1,
                                              child: child,
                                            ),
                                            child: _piece(piece, wide),
                                          ),
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        );
      },
    );
  }

  /// Each piece fades into the seam like text (AlbumScrapbookPage.kt seamFade);
  /// the opening's cover breaks up as a graphic (seamDissolve, in _Opening).
  Widget _piece(ScrapPiece piece, bool wide) => switch (piece) {
    ScrapOpening() => _Opening(piece: piece, album: widget.album, coverUrl: widget.coverUrl, wide: wide),
    ScrapTrackCluster() => _scrapFade(_TrackCluster(piece: piece, album: widget.album, wide: wide)),
    ScrapContactSheet() => _scrapFade(_ContactSheet(piece: piece, album: widget.album)),
    ScrapNotYet() => _scrapFade(const _NotYet()),
    ScrapReceipt() => _scrapFade(_Receipt(piece: piece)),
  };
}

/// A scrap that fades into the chrome seam over a body-text band.
Widget _scrapFade(Widget child) => Builder(
  builder: (context) => SeamFade(fontSize: Theme.of(context).textTheme.bodyMedium?.fontSize ?? 14, child: child),
);

/// The pager drag as the pieces read it, and the page's colours.
final class _ScrapMotion {
  const _ScrapMotion(this.page, this.width);

  /// null = reduced motion (pieces hold still).
  final ValueListenable<double>? page;
  final double width;
}

class _ScrapScope extends InheritedWidget {
  const _ScrapScope({required this.motion, required this.colors, required super.child});

  final _ScrapMotion motion;
  final ScrapbookColors colors;

  static _ScrapScope of(BuildContext context) => context.dependOnInheritedWidgetOfExactType<_ScrapScope>()!;

  @override
  bool updateShouldNotify(_ScrapScope old) =>
      old.colors != colors || old.motion.page != motion.page || old.motion.width != motion.width;
}

/// A piece's resting tilt plus its share of the pager drag: it trails by
/// [depth] × 12% of the page and leans 3° further while away, settling to its
/// own angle (scrapLayer). Paint-only: layout and hit areas stay square.
class _ScrapLayer extends StatelessWidget {
  const _ScrapLayer({required this.tilt, required this.depth, required this.child});

  final double tilt;
  final double depth;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final motion = _ScrapScope.of(context).motion;
    final page = motion.page;
    Widget at(double off) => Transform.translate(
      offset: Offset(off * motion.width * depth * DetailTokens.scrapbookParallaxShare, 0),
      child: Transform.rotate(
        angle: (tilt + tilt.sign * DetailTokens.scrapbookLeanDegrees * off) * math.pi / 180,
        child: child,
      ),
    );
    if (page == null) return at(0);
    return ValueListenableBuilder<double>(
      valueListenable: page,
      child: child,
      builder: (context, p, _) => at((1 - p).clamp(-1.0, 1.0)),
    );
  }
}

// ---------------------------------------------------------------------------
// Pieces.
// ---------------------------------------------------------------------------

void _playAlbumFrom(WidgetRef ref, Album album, int index, {int positionMs = 0}) {
  final player = DetailPlayer.widget(ref);
  player.controller.playTracks(
    album.tracks,
    startIndex: index,
    context: albumPlaybackContext(album),
    startPositionMs: positionMs,
  );
}

class _Opening extends ConsumerWidget {
  const _Opening({required this.piece, required this.album, required this.coverUrl, required this.wide});

  final ScrapOpening piece;
  final Album album;
  final String? coverUrl;
  final bool wide;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final coverSide = wide ? ScrapbookLayout.coverSideWide : DetailTokens.scrapbookCover;
    final emblem = wide ? ScrapbookLayout.emblemWide : DetailTokens.scrapbookBadge;
    final title = piece.title;
    final review = piece.review;
    // A long review gets the full width under the cover; on a phone the
    // column beside the cover belongs to the title when there is one.
    final reviewBeside =
        (wide || review == null || weightedLength(review) <= ScrapbookRules.openingBesideBudget) &&
        (wide || title == null);
    final url = coverUrl;
    return MemoryPaletteBuilder(
      cacheKey: url,
      image: url == null ? null : ResizeImage(NetworkImage(url), width: 200, height: 200),
      builder: (context, palette) {
        void openSheet({bool focusReview = false}) => showAlbumRateSheet(
          context,
          albumId: album.id,
          albumName: albumTitleWithoutVersion(album.name, album.version),
          palette: palette,
          focusReview: focusReview,
        );
        Widget reviewPiece() => _scrapFade(
          review == null
              ? _BlankReview(tilt: piece.reviewTilt, onTap: () => openSheet(focusReview: true))
              : _ReviewClipping(
                  text: review,
                  writtenAt: piece.reviewAt,
                  tilt: piece.reviewTilt,
                  onTap: () => openSheet(focusReview: true),
                ),
        );
        final besideGap = emblem * DetailTokens.scrapbookBadgeOverhangX + 8;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _CoverWithEmblem(
                  album: album,
                  coverUrl: url,
                  side: coverSide,
                  emblem: emblem,
                  tilt: piece.coverTilt,
                  palette: palette,
                  onEmblemTap: openSheet,
                ),
                if (title != null) ...[
                  SizedBox(width: besideGap),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SeamFade(
                            fontSize: Theme.of(context).textTheme.headlineSmall?.fontSize ?? 24,
                            child: Text(title, style: Theme.of(context).textTheme.headlineSmall),
                          ),
                          const SizedBox(height: 16),
                          _scrapFade(_ScrapLastPlay(albumId: album.id)),
                          if (reviewBeside) ...[
                            const SizedBox(height: 16),
                            ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: ScrapbookLayout.reviewMaxWidth),
                              child: reviewPiece(),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ] else if (reviewBeside) ...[
                  SizedBox(width: besideGap),
                  Flexible(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: ScrapbookLayout.reviewMaxWidth),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            reviewPiece(),
                            const SizedBox(height: 16),
                            _scrapFade(_ScrapLastPlay(albumId: album.id)),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ],
            ),
            if (!reviewBeside) ...[const SizedBox(height: 4), reviewPiece()],
          ],
        );
      },
    );
  }
}

/// Last Play in full, under the Memory title (AlbumScrapLastPlay).
class _ScrapLastPlay extends ConsumerWidget {
  const _ScrapLastPlay({required this.albumId});

  final MediaId albumId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final at = ref.watch(albumLastPlayedProvider(albumId));
    final label = at == null ? null : lastPlayLabels(at, strings);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          strings.albumLastPlay,
          style: theme.textTheme.labelLarge?.copyWith(
            decoration: TextDecoration.underline,
            decorationColor: theme.colorScheme.onSurface,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label == null ? strings.albumNever : '${label.$1} ${label.$2}',
          maxLines: 1,
          softWrap: false,
          style: theme.textTheme.titleMedium?.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
        ),
      ],
    );
  }
}

class _CoverWithEmblem extends StatelessWidget {
  const _CoverWithEmblem({
    required this.album,
    required this.coverUrl,
    required this.side,
    required this.emblem,
    required this.tilt,
    required this.palette,
    required this.onEmblemTap,
  });

  final Album album;
  final String? coverUrl;
  final double side;
  final double emblem;
  final double tilt;
  final MemoryPalette palette;
  final VoidCallback onEmblemTap;

  @override
  Widget build(BuildContext context) {
    final dpr = MediaQuery.devicePixelRatioOf(context);
    // The box reserves the emblem's downward overhang; the sideways one rides into the gap.
    return SizedBox(
      width: side,
      height: side + emblem * DetailTokens.scrapbookBadgeOverhangY,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned(
            left: 0,
            top: 0,
            width: side,
            height: side,
            child: _ScrapLayer(
              tilt: tilt,
              depth: 0.25,
              child: Semantics(
                image: true,
                label: album.name,
                // The cover and the emblem break up at the chrome seam, each its own print.
                child: SeamDissolve(
                  child: MediaArtwork(
                    url: coverUrl,
                    fallbackIcon: YoinSymbols.album,
                    radius: ShapeTokens.hero,
                    decodeWidth: math.min(640, (side * dpr).round()),
                  ),
                ),
              ),
            ),
          ),
          PositionedDirectional(
            end: -emblem * DetailTokens.scrapbookBadgeOverhangX,
            bottom: 0,
            width: emblem,
            height: emblem,
            child: _ScrapLayer(
              tilt: 0,
              depth: 0.6,
              child: Semantics(
                button: true,
                label: DetailStrings.of(context).scrapCdRate,
                child: SeamDissolve(
                  round: true,
                  child: AlbumScoreEmblem(albumId: album.id, palette: palette, onTap: onEmblemTap, size: emblem),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The listener's review: told apart by its paper colour alone (no label, no rail).
class _ReviewClipping extends StatelessWidget {
  const _ReviewClipping({required this.text, required this.writtenAt, required this.tilt, required this.onTap});

  final String text;
  final int? writtenAt;
  final double tilt;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final at = writtenAt;
    return _ScrapLayer(
      tilt: tilt,
      depth: 0.35,
      child: Semantics(
        button: true,
        hint: strings.scrapCdEditComment,
        child: Material(
          color: colors.review,
          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    text,
                    style: theme.textTheme.bodyLarge?.copyWith(fontSize: 15, height: 1.55, color: colors.onReview),
                  ),
                  // When it was written, quietly at the foot (owner 2026-10-05).
                  if (at != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      lastPlayLabels(at, strings).$1,
                      style: theme.textTheme.labelMedium?.copyWith(color: colors.onReview.withValues(alpha: 0.6)),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// No comment yet: just a pen in a dashed circle (owner 2026-10-05: no text block).
class _BlankReview extends StatelessWidget {
  const _BlankReview({required this.tilt, required this.onTap});

  final double tilt;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    return Align(
      alignment: AlignmentDirectional.centerStart,
      child: _ScrapLayer(
        tilt: tilt,
        depth: 0.35,
        child: Semantics(
          button: true,
          label: DetailStrings.of(context).scrapCdWriteComment,
          excludeSemantics: true,
          child: Material(
            type: MaterialType.transparency,
            shape: const CircleBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onTap,
              child: CustomPaint(
                painter: _DashedCircle(colors.outline),
                child: SizedBox.square(
                  dimension: ScrapbookLayout.pen,
                  child: Icon(
                    YoinSymbols.edit,
                    size: ScrapbookLayout.penGlyph,
                    color: Theme.of(context).colorScheme.onSurface,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _DashedCircle extends CustomPainter {
  _DashedCircle(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const width = 1.5, dash = 5.0, gap = 5.0;
    final r = size.shortestSide / 2 - width / 2;
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;
    final n = (2 * math.pi * r / (dash + gap)).floor().clamp(1, 1000);
    final step = 2 * math.pi / n;
    final sweep = step * dash / (dash + gap);
    final rect = Rect.fromCircle(center: size.center(Offset.zero), radius: r);
    for (var i = 0; i < n; i++) {
      canvas.drawArc(rect, i * step - math.pi / 2, sweep, false, paint);
    }
  }

  @override
  bool shouldRepaint(_DashedCircle old) => old.color != color;
}

class _TrackCluster extends StatelessWidget {
  const _TrackCluster({required this.piece, required this.album, required this.wide});

  final ScrapTrackCluster piece;
  final Album album;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    void manage({ScrapSheetTab tab = ScrapSheetTab.about}) =>
        showScrapTrackSheet(context, album: album, trackId: piece.track.track.id, colors: colors, tab: tab);
    final ask = piece.ask;
    final aboutLine = piece.aboutLine;
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: _ScrapStack(
        mirror: piece.mirror,
        children: [
          // The album's best track needs no caption: its ticket is the ink-filled, big-score one.
          _ScrapSlot(
            cross: false,
            overlap: 0,
            widthFactor: 0.66,
            minWidth: 200,
            maxWidth: 280,
            child: _Ticket(track: piece.track, album: album, best: piece.best, mini: false, tilt: piece.ticketTilt),
          ),
          if (piece.notes.isNotEmpty)
            _ScrapSlot(
              cross: true,
              overlap: DetailTokens.scrapbookPieceOverlap,
              widthFactor: 0.78,
              maxWidth: 330,
              child: _NotesClipping(
                piece: piece,
                album: album,
                onMore: () => manage(tab: ScrapSheetTab.notes),
              ),
            ),
          if (ask != null)
            _ScrapSlot(
              cross: false,
              overlap: DetailTokens.scrapbookPieceOverlap,
              widthFactor: ScrapbookLayout.cardWidthFactor,
              maxWidth: ScrapbookLayout.cardMaxWidth,
              child: _AskCard(
                ask: ask,
                moreAsks: piece.moreAsks,
                budget: wide ? ScrapbookRules.answerBudgetWide : ScrapbookRules.answerBudget,
                tilt: piece.cardTilt,
                trackTitle: piece.track.title,
                onTap: manage,
              ),
            )
          else if (aboutLine != null)
            _ScrapSlot(
              cross: false,
              overlap: DetailTokens.scrapbookPieceOverlap,
              widthFactor: ScrapbookLayout.cardWidthFactor,
              maxWidth: ScrapbookLayout.cardMaxWidth,
              child: _AboutCard(line: aboutLine, tilt: piece.cardTilt, trackTitle: piece.track.title, onTap: manage),
            ),
          for (final (i, tag) in piece.tags.indexed)
            _ScrapSlot(
              cross: true,
              overlap: i == 0 ? ScrapbookLayout.tagOverlap : ScrapbookLayout.tagStackOverlap,
              child: Align(
                // A tag keeps its own width on the cluster's other side.
                alignment: piece.mirror ? AlignmentDirectional.centerStart : AlignmentDirectional.centerEnd,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: ScrapbookLayout.tagInset),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: ScrapbookLayout.tagMaxWidth),
                    child: _FactTag(tag: tag, extra: i == piece.tags.length - 1 ? piece.hiddenTags : 0, onTap: manage),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// The text a listener wrote or read on the page (userTextStyle): 15sp, 1.5 lines.
TextStyle? _userText(BuildContext context) =>
    Theme.of(context).textTheme.bodyLarge?.copyWith(fontSize: 15, height: 1.5, letterSpacing: 0);

/// A question asked about the track: the listener's words, the AI's title and
/// whole sentences of its answer; "Read all" (and "+N" more questions) when
/// the sheet holds more. A "Q" sticker hangs off its corner.
class _AskCard extends StatelessWidget {
  const _AskCard({
    required this.ask,
    required this.moreAsks,
    required this.budget,
    required this.tilt,
    required this.trackTitle,
    required this.onTap,
  });

  final ScrapAsk ask;
  final int moreAsks;
  final int budget;
  final double tilt;
  final String trackTitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final excerpt = answerExcerpt(ask.answer, budget);
    final title = ask.title;
    return _ScrapLayer(
      tilt: tilt,
      depth: 0.35,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Semantics(
            button: true,
            label: strings.scrapCdAsk(trackTitle, title ?? ask.question),
            onTapHint: strings.scrapCdReadAll,
            excludeSemantics: true,
            child: Material(
              color: colors.ask,
              shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: onTap,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        ask.question,
                        style: _userText(
                          context,
                        )?.copyWith(fontSize: 14, height: 1.45, color: colors.onAsk.withValues(alpha: 0.8)),
                      ),
                      if (title != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: Text(
                            title,
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w600,
                              height: 1.3,
                              color: colors.onAsk,
                            ),
                          ),
                        ),
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text.rich(
                          markdownBoldSpan(excerpt.text),
                          style: theme.textTheme.bodyMedium?.copyWith(
                            fontSize: 14.5,
                            height: 1.55,
                            color: colors.onAsk,
                          ),
                        ),
                      ),
                      if (excerpt.truncated || moreAsks > 0)
                        Padding(
                          padding: const EdgeInsets.only(top: 6),
                          child: Row(
                            children: [
                              Text(
                                strings.scrapReadAll,
                                style: theme.textTheme.labelLarge?.copyWith(
                                  fontWeight: FontWeight.w600,
                                  letterSpacing: 0,
                                  color: colors.askMark,
                                ),
                              ),
                              if (moreAsks > 0) ...[
                                const SizedBox(width: 8),
                                DecoratedBox(
                                  decoration: ShapeDecoration(color: colors.askMark, shape: const StadiumBorder()),
                                  child: Padding(
                                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                                    child: Text(
                                      '+$moreAsks',
                                      style: theme.textTheme.labelSmall?.copyWith(
                                        color: colors.onAskMark,
                                        fontFeatures: const [FontFeature.tabularFigures()],
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          PositionedDirectional(
            start: ScrapbookLayout.askStickerOffset.dx,
            top: ScrapbookLayout.askStickerOffset.dy,
            child: ExcludeSemantics(
              child: Transform.rotate(
                angle: tilt * 6 * math.pi / 180,
                child: DecoratedBox(
                  decoration: ShapeDecoration(color: colors.askMark, shape: PolygonBorder(avatarShapes[1])),
                  child: SizedBox.square(
                    dimension: ScrapbookLayout.askSticker,
                    child: Center(
                      child: Text(
                        strings.scrapQ,
                        style: theme.textTheme.labelLarge?.copyWith(
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0,
                          color: colors.onAskMark,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The About paragraph's first sentence, for a track with About but no question.
class _AboutCard extends StatelessWidget {
  const _AboutCard({required this.line, required this.tilt, required this.trackTitle, required this.onTap});

  final String line;
  final double tilt;
  final String trackTitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    return _ScrapLayer(
      tilt: tilt,
      depth: 0.35,
      child: Semantics(
        button: true,
        label: strings.scrapCdAbout(trackTitle, line),
        onTapHint: strings.scrapCdReadAll,
        excludeSemantics: true,
        child: Material(
          color: colors.ask,
          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    strings.scrapAbout,
                    style: theme.textTheme.labelLarge?.copyWith(
                      decoration: TextDecoration.underline,
                      decorationColor: theme.colorScheme.onSurface,
                      fontWeight: FontWeight.w500,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text.rich(
                      markdownBoldSpan(line),
                      style: theme.textTheme.bodyMedium?.copyWith(fontSize: 14.5, height: 1.55, color: colors.onAsk),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A fact as a tag: its label small, its value up to two lines (a capped
/// radius, not a stadium: a long value wraps without turning into an oval).
class _FactTag extends StatelessWidget {
  const _FactTag({required this.tag, required this.extra, required this.onTap});

  final ScrapTag tag;
  final int extra;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    return _ScrapLayer(
      tilt: tag.tilt,
      depth: 0.7,
      child: Material(
        color: colors.tag,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapbookLayout.tagCorner)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Text(
                  strings.scrapFact(tag.key),
                  style: theme.textTheme.labelMedium?.copyWith(
                    fontSize: 11.5,
                    letterSpacing: 0,
                    color: colors.onTag.withValues(alpha: 0.75),
                  ),
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    extra > 0 ? strings.scrapFactMore(tag.value, extra) : tag.value,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelLarge?.copyWith(height: 1.35, letterSpacing: 0, color: colors.onTag),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ContactSheet extends StatelessWidget {
  const _ContactSheet({required this.piece, required this.album});

  final ScrapContactSheet piece;
  final Album album;

  @override
  Widget build(BuildContext context) {
    if (piece.tracks.length == 1) {
      return Padding(
        padding: const EdgeInsets.only(top: 6),
        child: _ScrapStack(
          mirror: piece.mirror,
          children: [
            _ScrapSlot(
              cross: false,
              overlap: 0,
              widthFactor: 0.52,
              minWidth: 168,
              maxWidth: 220,
              child: _Ticket(
                track: piece.tracks.single,
                album: album,
                best: false,
                mini: true,
                tilt: piece.tilts.single,
              ),
            ),
          ],
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final (i, t) in piece.tracks.indexed) ...[
            if (i > 0) const SizedBox(width: 14),
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(top: i.isOdd ? 12 : 0),
                child: _Ticket(track: t, album: album, best: false, mini: true, tilt: piece.tilts[i]),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// A ticket stub: the number in a perforated stub, then title, duration and
/// plays, and the score in the album ink. The heart sticker marks a liked
/// track; the playing track's ticket takes the secondary container, the
/// album's best the ink. Tap plays the album from it; long-press opens its
/// track sheet (score, About, notes).
class _Ticket extends ConsumerStatefulWidget {
  const _Ticket({required this.track, required this.album, required this.best, required this.mini, required this.tilt});

  final ScrapTrack track;
  final Album album;
  final bool best;
  final bool mini;
  final double tilt;

  @override
  ConsumerState<_Ticket> createState() => _TicketState();
}

class _TicketState extends ConsumerState<_Ticket> with TickerProviderStateMixin {
  late final SpringValue _now = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void dispose() {
    _now.dispose();
    super.dispose();
  }

  String _description(DetailStrings s) {
    final t = widget.track;
    final score = t.score == null ? null : scrapScoreText(t.score!);
    return s.scrapCdTrack(t.number, t.title, score: score, liked: t.starred, plays: t.plays);
  }

  @override
  Widget build(BuildContext context) {
    final playing = ref.watch(playbackControllerProvider.select((s) => s.current?.id == widget.track.track.id));
    final reduce = MediaQuery.disableAnimationsOf(context);
    reduce ? _now.snapTo(playing ? 1 : 0) : _now.animateTo(playing ? 1 : 0);
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final t = widget.track;
    final mini = widget.mini;
    final best = widget.best;
    final tabular = const [FontFeature.tabularFigures()];
    final duration = t.track.durationSec == null ? null : formatTrackDuration(t.track.durationSec);
    return _ScrapLayer(
      tilt: widget.tilt,
      depth: 0.5,
      child: Semantics(
        button: true,
        label: _description(strings),
        value: playing ? strings.scrapCdPlaying : null,
        excludeSemantics: true,
        child: AnimatedBuilder(
          animation: _now.animation,
          builder: (context, _) {
            final p = _now.value.clamp(0.0, 1.0);
            final container = best ? colors.ink : Color.lerp(colors.ticket, colors.ticketNow, p)!;
            final ink = best ? colors.onInk : Color.lerp(colors.onTicket, colors.onTicketNow, p)!;
            final scoreInk = best ? ink : Color.lerp(colors.ink, ink, p)!;
            final score = t.score == null
                ? null
                : Text(
                    scrapScoreText(t.score!),
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                      fontSize: best ? 22 : 16,
                      color: scoreInk,
                      fontFeatures: tabular,
                    ),
                  );
            final meta = <MetaGroup>[
              if (duration != null) MetaPlain(duration),
              if (t.plays > 0) MetaStat('${t.plays}', strings.unitPlay(t.plays)),
            ];
            return Stack(
              clipBehavior: Clip.none,
              children: [
                ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: mini ? ScrapbookLayout.ticketMiniHeight : ScrapbookLayout.ticketMinHeight,
                  ),
                  child: Material(
                    color: container,
                    shape: const _TicketBorder(),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () {
                        YoinHaptics.click();
                        _playAlbumFrom(ref, widget.album, t.index);
                      },
                      // A long press scores and edits the track on its sheet (Android's ticket onLongClick = Manage).
                      onLongPress: () {
                        YoinHaptics.longPress();
                        showScrapTrackSheet(context, album: widget.album, trackId: t.track.id, colors: colors);
                      },
                      // A right click is the long press on a desktop.
                      onSecondaryTap: () =>
                          showScrapTrackSheet(context, album: widget.album, trackId: t.track.id, colors: colors),
                      child: CustomPaint(
                        painter: _Perforation(ink.withValues(alpha: 0.24)),
                        child: Row(
                          children: [
                            SizedBox(
                              width: DetailTokens.scrapbookTicketStub,
                              child: Text(
                                '${t.number}'.padLeft(2, '0'),
                                textAlign: TextAlign.center,
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.w500,
                                  fontSize: mini ? 16 : 18,
                                  letterSpacing: 0,
                                  color: ink.withValues(alpha: 0.72),
                                  fontFeatures: tabular,
                                ),
                              ),
                            ),
                            Expanded(
                              child: Padding(
                                padding: EdgeInsetsDirectional.only(start: 13, top: 10, bottom: 10, end: mini ? 12 : 8),
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      t.title,
                                      style: theme.textTheme.titleMedium?.copyWith(
                                        fontSize: mini ? 15 : 16,
                                        fontWeight: FontWeight.w600,
                                        height: 1.25,
                                        color: ink,
                                      ),
                                    ),
                                    // A small ticket's score trails the meta line instead of taking a column.
                                    if (meta.isNotEmpty || (mini && score != null))
                                      Padding(
                                        padding: const EdgeInsets.only(top: 3),
                                        child: Row(
                                          children: [
                                            Expanded(
                                              child: meta.isEmpty
                                                  ? const SizedBox.shrink()
                                                  : MetaLine(
                                                      groups: meta,
                                                      style: theme.textTheme.labelMedium?.copyWith(
                                                        fontSize: 12,
                                                        letterSpacing: 0,
                                                      ),
                                                      color: ink.withValues(alpha: 0.72),
                                                    ),
                                            ),
                                            if (mini) ?score,
                                          ],
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                            ),
                            if (!mini && score != null)
                              Padding(padding: const EdgeInsetsDirectional.only(end: 14), child: score),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                if (t.starred)
                  PositionedDirectional(
                    start: ScrapbookLayout.stickerOffset.dx,
                    top: ScrapbookLayout.stickerOffset.dy,
                    child: Transform.rotate(
                      angle: t.heartTilt * math.pi / 180,
                      child: Icon(YoinSymbols.favoriteFilled, size: ScrapbookLayout.sticker, color: colors.ink),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// The dashed perforation between stub and body.
class _Perforation extends CustomPainter {
  _Perforation(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const stub = DetailTokens.scrapbookTicketStub;
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1.5;
    for (var y = 8.0; y < size.height - 8; y += 8) {
      canvas.drawLine(Offset(stub, y), Offset(stub, math.min(y + 4, size.height - 8)), paint);
    }
  }

  @override
  bool shouldRepaint(_Perforation old) => old.color != color;
}

/// Card corners with a half-circle notch top and bottom at the stub's perforation.
class _TicketBorder extends ShapeBorder {
  const _TicketBorder();

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.zero;

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) => getOuterPath(rect, textDirection: textDirection);

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) {
    final body = Path()..addRRect(RRect.fromRectAndRadius(rect, const Radius.circular(ShapeTokens.card)));
    final x = rect.left + DetailTokens.scrapbookTicketStub;
    const r = DetailTokens.scrapbookTicketNotch;
    final notches = Path()
      ..addOval(Rect.fromCircle(center: Offset(x, rect.top), radius: r))
      ..addOval(Rect.fromCircle(center: Offset(x, rect.bottom), radius: r));
    return Path.combine(PathOperation.difference, body, notches);
  }

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {}

  @override
  ShapeBorder scale(double t) => this;
}

/// A track's notes on neutral paper with the journal rail (the rail is the
/// notes' mark alone): time stamp and words on one baseline; a tap plays the
/// song from the note's moment.
class _NotesClipping extends ConsumerWidget {
  const _NotesClipping({required this.piece, required this.album, required this.onMore});

  final ScrapTrackCluster piece;
  final Album album;

  /// "+N notes": the track sheet on its Note tab.
  final VoidCallback onMore;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    return _ScrapLayer(
      tilt: piece.notesTilt,
      depth: 0.4,
      child: Material(
        color: colors.note,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
        clipBehavior: Clip.antiAlias,
        child: CustomPaint(
          painter: _JournalRail(colors.ink),
          child: Padding(
            padding: const EdgeInsetsDirectional.only(start: 24, end: 12, top: 8, bottom: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final n in piece.notes.take(ScrapbookRules.notesShown))
                  _NoteLine(
                    note: n,
                    onTap: () => _playAlbumFrom(ref, album, piece.track.index, positionMs: n.positionMs ?? 0),
                  ),
                if (piece.hiddenNotes > 0)
                  InkWell(
                    customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
                    onTap: onMore,
                    child: Padding(
                      padding: const EdgeInsetsDirectional.only(
                        start: ScrapbookLayout.noteStamp,
                        top: 6,
                        bottom: 4,
                        end: 8,
                      ),
                      child: Text(
                        strings.scrapHiddenNotes(piece.hiddenNotes),
                        style: theme.textTheme.labelLarge?.copyWith(
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0,
                          color: colors.ink,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _NoteLine extends StatelessWidget {
  const _NoteLine({required this.note, required this.onTap});

  final SongNote note;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final at = note.positionMs == null ? null : formatNotePosition(note.positionMs!);
    return Semantics(
      button: true,
      label: at == null ? strings.scrapCdNote(note.content) : strings.scrapCdNoteAt(at, note.content),
      excludeSemantics: true,
      child: InkWell(
        customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              SizedBox(
                width: ScrapbookLayout.noteStamp,
                child: Text(
                  at ?? '',
                  style: theme.textTheme.labelMedium?.copyWith(
                    fontWeight: FontWeight.w500,
                    fontSize: 12,
                    letterSpacing: 0,
                    color: colors.ink,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
              ),
              Expanded(
                child: Text(
                  note.content,
                  style: theme.textTheme.bodyLarge?.copyWith(fontSize: 15, height: 1.5, color: colors.onNote),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _JournalRail extends CustomPainter {
  _JournalRail(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const inset = ScrapbookLayout.railInset;
    if (size.height <= inset * 2) return;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(ScrapbookLayout.railX, inset, ScrapbookLayout.railWidth, size.height - inset * 2),
        const Radius.circular(ScrapbookLayout.railWidth / 2),
      ),
      Paint()..color = color,
    );
  }

  @override
  bool shouldRepaint(_JournalRail old) => old.color != color;
}

/// No label, no moulds, no instructions: one line, and the tracks stay on page 1.
class _NotYet extends StatelessWidget {
  const _NotYet();

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: ScrapbookLayout.reviewMaxWidth),
      child: Text(
        DetailStrings.of(context).scrapNotYet,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 13, height: 1.5, color: colors.muted),
      ),
    );
  }
}

class _Receipt extends StatelessWidget {
  const _Receipt({required this.piece});

  final ScrapReceipt piece;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final mono = TextStyle(
      fontFamily: 'monospace',
      fontFamilyFallback: const ['Menlo', 'Courier'],
      fontSize: 12.5,
      height: 1.9,
      letterSpacing: 0,
      color: colors.onReceipt,
    );
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: ScrapbookLayout.receiptMaxWidth),
        child: _ScrapLayer(
          tilt: piece.tilt,
          depth: 0.35,
          child: Semantics(
            container: true,
            child: ClipPath(
              clipper: const _ReceiptClipper(),
              child: ColoredBox(
                color: colors.receipt,
                child: Padding(
                  padding: const EdgeInsetsDirectional.only(start: 18, end: 18, top: 16, bottom: 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: Text(
                          piece.header,
                          style: mono.copyWith(
                            fontSize: 11.5,
                            fontWeight: FontWeight.w500,
                            color: colors.onReceipt.withValues(alpha: 0.7),
                          ),
                        ),
                      ),
                      for (final (label, value) in piece.lines)
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(label, style: mono),
                            Expanded(
                              child: Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 6),
                                child: SizedBox(
                                  height: 12,
                                  child: CustomPaint(painter: _DottedLeader(colors.onReceipt.withValues(alpha: 0.3))),
                                ),
                              ),
                            ),
                            Text(value, style: mono),
                          ],
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _DottedLeader extends CustomPainter {
  _DottedLeader(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round;
    final y = size.height - 4;
    canvas.drawPoints(ui.PointMode.points, [for (var x = 0.0; x <= size.width; x += 4) Offset(x, y)], paint);
  }

  @override
  bool shouldRepaint(_DottedLeader old) => old.color != color;
}

/// A panel top over a torn, zig-zag bottom edge.
class _ReceiptClipper extends CustomClipper<Path> {
  const _ReceiptClipper();

  @override
  Path getClip(Size size) {
    final corner = math.min(ScrapbookLayout.receiptCorner, size.shortestSide / 2);
    const depth = ScrapbookLayout.receiptToothDepth;
    final teeth = math.max(1, (size.width / ScrapbookLayout.receiptTooth).round());
    final step = size.width / teeth;
    final path = Path()
      ..moveTo(0, corner)
      ..arcToPoint(Offset(corner, 0), radius: Radius.circular(corner))
      ..lineTo(size.width - corner, 0)
      ..arcToPoint(Offset(size.width, corner), radius: Radius.circular(corner))
      ..lineTo(size.width, size.height - depth);
    var x = size.width;
    for (var i = 0; i < teeth; i++) {
      path.lineTo(x - step / 2, size.height);
      x -= step;
      path.lineTo(x, size.height - depth);
    }
    return path..close();
  }

  @override
  bool shouldReclip(_ReceiptClipper old) => false;
}

/// The dot grid (16dp pitch) as one repeated tile: a single draw however long the page.
class _DottedPaper extends CustomPainter {
  _DottedPaper(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const pitch = DetailTokens.scrapbookDotSpacing;
    final paint = Paint()..color = color;
    final points = <Offset>[];
    for (var y = pitch / 2; y < size.height; y += pitch) {
      for (var x = pitch / 2; x < size.width; x += pitch) {
        points.add(Offset(x, y));
      }
    }
    canvas.drawPoints(
      ui.PointMode.points,
      points,
      paint
        ..strokeWidth = DetailTokens.scrapbookDotRadius * 2
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(_DottedPaper old) => old.color != color;
}

// ---------------------------------------------------------------------------
// Masonry + stacking layouts.
// ---------------------------------------------------------------------------

class _ScrapMasonryParentData extends ContainerBoxParentData<RenderBox> {
  bool fullLine = false;
}

/// Masonry span: full-line items cross every lane.
class _ScrapSpan extends ParentDataWidget<_ScrapMasonryParentData> {
  const _ScrapSpan({super.key, required this.fullLine, required super.child});

  final bool fullLine;

  @override
  void applyParentData(RenderObject renderObject) {
    final data = renderObject.parentData! as _ScrapMasonryParentData;
    if (data.fullLine != fullLine) {
      data.fullLine = fullLine;
      renderObject.parent?.markNeedsLayout();
    }
  }

  @override
  Type get debugTypicalAncestorWidgetClass => _ScrapMasonry;
}

/// Full-line pieces span all lanes at the lowest lane's bottom; the rest go
/// to the shortest lane (ties: the start-most).
class _ScrapMasonry extends MultiChildRenderObjectWidget {
  const _ScrapMasonry({required this.lanes, required this.rowGap, super.children});

  final int lanes;
  final double rowGap;

  @override
  _RenderScrapMasonry createRenderObject(BuildContext context) =>
      _RenderScrapMasonry(lanes, rowGap, Directionality.of(context));

  @override
  void updateRenderObject(BuildContext context, _RenderScrapMasonry renderObject) => renderObject
    ..lanes = lanes
    ..rowGap = rowGap
    ..direction = Directionality.of(context);
}

class _RenderScrapMasonry extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _ScrapMasonryParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _ScrapMasonryParentData> {
  _RenderScrapMasonry(this._lanes, this._rowGap, this._direction);

  int _lanes;
  set lanes(int v) {
    if (v == _lanes) return;
    _lanes = v;
    markNeedsLayout();
  }

  double _rowGap;
  set rowGap(double v) {
    if (v == _rowGap) return;
    _rowGap = v;
    markNeedsLayout();
  }

  TextDirection _direction;
  set direction(TextDirection v) {
    if (v == _direction) return;
    _direction = v;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _ScrapMasonryParentData) child.parentData = _ScrapMasonryParentData();
  }

  @override
  void performLayout() {
    final width = constraints.maxWidth;
    const laneGap = ScrapbookLayout.laneGap;
    final laneWidth = math.max(0.0, (width - laneGap * (_lanes - 1)) / _lanes);
    final bottoms = List<double>.filled(_lanes, 0);
    var child = firstChild;
    var any = false;
    while (child != null) {
      any = true;
      final data = child.parentData! as _ScrapMasonryParentData;
      if (_lanes == 1 || data.fullLine) {
        child.layout(BoxConstraints.tightFor(width: width), parentUsesSize: true);
        final y = bottoms.reduce(math.max);
        data.offset = Offset(0, y);
        bottoms.fillRange(0, _lanes, y + child.size.height + _rowGap);
      } else {
        var lane = 0;
        for (var i = 1; i < _lanes; i++) {
          if (bottoms[i] < bottoms[lane]) lane = i;
        }
        child.layout(BoxConstraints.tightFor(width: laneWidth), parentUsesSize: true);
        final x = lane * (laneWidth + laneGap);
        data.offset = Offset(_direction == TextDirection.rtl ? width - x - laneWidth : x, bottoms[lane]);
        bottoms[lane] += child.size.height + _rowGap;
      }
      child = data.nextSibling;
    }
    size = constraints.constrain(Size(width, any ? math.max(0, bottoms.reduce(math.max) - _rowGap) : 0));
  }

  @override
  void paint(PaintingContext context, Offset offset) => defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

class _ScrapStackParentData extends ContainerBoxParentData<RenderBox> {
  bool cross = false;
  double overlap = 0;
  double widthFactor = 1;
  double minWidth = 0;
  double maxWidth = double.infinity;
}

/// A piece's side in a [_ScrapStack] (the cluster's main side, or the other),
/// how far it rides up the one above, and its width (a share of the
/// cluster, clamped).
class _ScrapSlot extends ParentDataWidget<_ScrapStackParentData> {
  const _ScrapSlot({
    required this.cross,
    required this.overlap,
    this.widthFactor = 1,
    this.minWidth = 0,
    this.maxWidth = double.infinity,
    required super.child,
  });

  final bool cross;
  final double overlap;
  final double widthFactor;
  final double minWidth;
  final double maxWidth;

  @override
  void applyParentData(RenderObject renderObject) {
    final d = renderObject.parentData! as _ScrapStackParentData;
    if (d.cross == cross &&
        d.overlap == overlap &&
        d.widthFactor == widthFactor &&
        d.minWidth == minWidth &&
        d.maxWidth == maxWidth) {
      return;
    }
    d
      ..cross = cross
      ..overlap = overlap
      ..widthFactor = widthFactor
      ..minWidth = minWidth
      ..maxWidth = maxWidth;
    renderObject.parent?.markNeedsLayout();
  }

  @override
  Type get debugTypicalAncestorWidgetClass => _ScrapStack;
}

/// A cluster: pieces top to bottom, each riding up the one above by its
/// slot's overlap (later pieces draw on top), on the cluster's main side or —
/// for a cross slot — the other one.
class _ScrapStack extends MultiChildRenderObjectWidget {
  const _ScrapStack({required this.mirror, super.children});

  final bool mirror;

  @override
  _RenderScrapStack createRenderObject(BuildContext context) => _RenderScrapStack(mirror, Directionality.of(context));

  @override
  void updateRenderObject(BuildContext context, _RenderScrapStack renderObject) => renderObject
    ..mirror = mirror
    ..direction = Directionality.of(context);
}

class _RenderScrapStack extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _ScrapStackParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _ScrapStackParentData> {
  _RenderScrapStack(this._mirror, this._direction);

  bool _mirror;
  set mirror(bool v) {
    if (v == _mirror) return;
    _mirror = v;
    markNeedsLayout();
  }

  TextDirection _direction;
  set direction(TextDirection v) {
    if (v == _direction) return;
    _direction = v;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _ScrapStackParentData) child.parentData = _ScrapStackParentData();
  }

  @override
  void performLayout() {
    final width = constraints.maxWidth;
    var y = 0.0;
    var first = true;
    var child = firstChild;
    while (child != null) {
      final d = child.parentData! as _ScrapStackParentData;
      final w = (width * d.widthFactor).clamp(math.min(d.minWidth, width), math.min(d.maxWidth, width)).toDouble();
      child.layout(BoxConstraints.tightFor(width: w), parentUsesSize: true);
      if (!first) y -= d.overlap;
      y = math.max(0, y);
      final end = d.cross != _mirror;
      final atRight = end != (_direction == TextDirection.rtl);
      d.offset = Offset(atRight ? width - child.size.width : 0, y);
      y += child.size.height;
      first = false;
      child = d.nextSibling;
    }
    size = constraints.constrain(Size(width, math.max(0, y)));
  }

  @override
  void paint(PaintingContext context, Offset offset) => defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

/// The page dots (AlbumPageDots): one 24 × 20 cell per page, a 4dp dot each,
/// coloured by how far the pager is from it. Tappable: a dot pages to it.
class AlbumPageDots extends StatelessWidget {
  const AlbumPageDots({
    super.key,
    required this.page,
    required this.active,
    required this.inactive,
    required this.onPage,
    this.count = 2,
  });

  final ValueListenable<double> page;
  final Color active;
  final Color inactive;
  final ValueChanged<int> onPage;
  final int count;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    return ValueListenableBuilder<double>(
      valueListenable: page,
      builder: (context, p, _) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 0; i < count; i++)
            Semantics(
              button: true,
              selected: p.round() == i,
              label: switch (i) {
                0 => strings.albumPageOverview,
                1 => strings.albumPageScrapbook,
                _ => strings.albumPageN(i + 1),
              },
              excludeSemantics: true,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => onPage(i),
                child: SizedBox(
                  width: AlbumPageDotsTokens.cellWidth,
                  height: AlbumPageDotsTokens.cellHeight,
                  child: Center(
                    child: Container(
                      width: AlbumPageDotsTokens.dot * 2,
                      height: AlbumPageDotsTokens.dot * 2,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: Color.lerp(active, inactive, (i - p).abs().clamp(0.0, 1.0)),
                      ),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

abstract final class AlbumPageDotsTokens {
  static const double cellWidth = 24;
  static const double cellHeight = 20;
  static const double dot = 4;

  /// The other page's dot: onSurfaceVariant at this alpha; over the formed bar, white at this one.
  static const double inactiveAlpha = 0.3;
  static const double inactiveOnBarAlpha = 0.4;
}
