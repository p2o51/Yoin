/// Memories (ShellOverlayUp): the album-first journal pulled down from Home
/// (Android ui/memories MemoriesScreen + showcase/MemoriesShowcase,
/// MemoriesGestures, MemoriesMorph, MemoriesTopBar).
///
/// Two controllers and nothing else moves the page: the outer q
/// ([MemoriesRevealController], the retreat to Home) and the inner p
/// ([MemoriesDiaryController], card ⇄ diary). Every vertical drag is handed
/// to exactly one of them when it starts:
///  · top bar, up → q on the bar rule (56 dp / 450 dp/s); down with the
///    diary open → p, the diary's handle (1:1);
///  · card body, up → q on the body rule (112 dp / 600 dp/s); down → p's
///    rubber band below the card (0.3×, −90 dp), capped at the card;
///  · diary body → its own scroll; a pull past the top feeds p (the first
///    24 dp at half speed); a new upward drag that starts at the end is q.
/// Horizontal drags page the deck (from the bar too). HarmonyOS back has no
/// progress: it commits through the same controllers (diary → card, card →
/// Home).
library;

import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../detail/detail_routes.dart';
import '../nowplaying/haptics.dart';
import '../nowplaying/player_snapshot.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import '../playback/playhead.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import '../ui/component/cover_art.dart';
import '../ui/component/meta_line.dart';
import 'memories_layout.dart';
import 'memories_motion.dart';
import 'memories_reveal_scope.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';
import 'memories_view_model.dart';
import 'memory_card_face.dart';
import 'memory_diary.dart';
import 'memory_emblem_slot.dart';
import 'memory_palettes.dart';
import 'palette/memory_palette.dart';
import '../app/providers.dart';
import '../theme/playback_theme.dart';

/// The host's pose for Memories: the page rides q up and out
/// (translationY = −q·H, its only displacement), or under reduced motion
/// fades in place (alpha 1 − q). Unmounted while closed, so every opening
/// awards its emblems again.
class MemoriesLayer extends ConsumerWidget {
  const MemoriesLayer({super.key, required this.reveal});

  final MemoriesRevealController reveal;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reduced = MediaQuery.disableAnimationsOf(context);
    reveal.reducedMotion = reduced;
    return AnimatedBuilder(
      animation: reveal,
      builder: (context, child) {
        if (!reveal.isVisible && reveal.settleTarget != 0) return const SizedBox.shrink();
        final q = reveal.fraction;
        return LayoutBuilder(
          builder: (context, c) => reduced
              ? Opacity(opacity: (1 - q).clamp(0.0, 1.0), child: child)
              : Transform.translate(offset: Offset(0, -q * c.maxHeight), child: child),
        );
      },
      child: MemoriesSurface(reveal: reveal),
    );
  }
}

/// Home behind Memories: scale 0.94 / alpha 0.5 under the open deck, up to
/// 1 / 1 as Memories retreats, scaled from its top centre; reduced motion
/// keeps the alpha only.
class MemoriesHomeBehind extends StatelessWidget {
  const MemoriesHomeBehind({super.key, required this.reveal, required this.child});

  final MemoriesRevealController reveal;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final reduced = MediaQuery.disableAnimationsOf(context);
    return AnimatedBuilder(
      animation: reveal,
      child: child,
      builder: (context, child) {
        // One tree shape whatever q is: Home's state (scroll, the pull in progress) never rebuilds.
        final q = reveal.fraction.clamp(0.0, 1.0);
        final alpha = MemoriesTokens.homeBehindAlpha + (1 - MemoriesTokens.homeBehindAlpha) * q;
        final scale = reduced ? 1.0 : MemoriesTokens.homeBehindScale + (1 - MemoriesTokens.homeBehindScale) * q;
        return Opacity(
          opacity: alpha,
          child: Transform.scale(scale: scale, alignment: Alignment.topCenter, child: child),
        );
      },
    );
  }
}

class MemoriesSurface extends ConsumerStatefulWidget {
  const MemoriesSurface({super.key, required this.reveal});

  final MemoriesRevealController reveal;

  @override
  ConsumerState<MemoriesSurface> createState() => _MemoriesSurfaceState();
}

enum _Route { dismissBar, dismissBody, cardBand, diaryHandle, diaryPull, diaryEndDismiss }

class _MemoriesSurfaceState extends ConsumerState<MemoriesSurface> with TickerProviderStateMixin {
  late final MemoriesDiaryController _p = MemoriesDiaryController(vsync: this);
  PageController? _pages;
  final Map<int, ScrollController> _scrolls = {};
  final MemoryAwards _awards = MemoryAwards();
  late final MemoryPalettes _palettes = MemoryPalettes(vsync: this);
  late final MemoriesRevealHandle _handle = ref.read(memoriesRevealHandleProvider);

  _Route? _route;
  double _height = 800;
  bool _reduced = false;
  int _settledPage = 0;

  // Raw pointer bookkeeping for the diary routes (the scroll owns the drag
  // until a pull past its edge hands it over).
  int? _pointer;
  bool _scrolledAtDown = false;
  bool _atEndAtDown = false;
  final VelocityTracker _diaryVelocity = VelocityTracker.withKind(PointerDeviceKind.touch);
  Drag? _barPageDrag;

  @override
  void initState() {
    super.initState();
    _handle.deckBack = _back;
    _p.addListener(_onDiary);
    widget.reveal.addListener(_onReveal);
    scheduleMicrotask(() {
      if (!mounted) return;
      final focus = _handle.pendingFocus;
      _handle.pendingFocus = null;
      ref.read(memoriesViewModelProvider.notifier).ensureLoaded(focus: focus);
    });
  }

  @override
  void dispose() {
    if (identical(_handle.deckBack, _back)) _handle.deckBack = null;
    widget.reveal.removeListener(_onReveal);
    _p.removeListener(_onDiary);
    _p.dispose();
    _pages?.dispose();
    for (final s in _scrolls.values) {
      s.dispose();
    }
    _awards.dispose();
    _palettes.dispose();
    super.dispose();
  }

  void _back() {
    if (_p.isDiaryLevel) {
      _p.close();
    } else {
      widget.reveal.close();
    }
  }

  void _onReveal() => _awards.revealChanged(widget.reveal.fraction, settling: widget.reveal.isSettling);

  void _onDiary() {
    // The diary's text returns to its top, unseen, once the card is back.
    if (_p.fraction <= 0.05 && !_p.isSettling) {
      final s = _scrolls[_settledPage];
      if (s != null && s.hasClients && s.offset != 0) s.jumpTo(0);
    }
    _awards.diaryChanged(_p.fraction);
  }

  ScrollController _scrollFor(int index) => _scrolls.putIfAbsent(index, ScrollController.new);

  PageController _pagesFor(int initial) => _pages ??= PageController(initialPage: initial)..addListener(_onPage);

  void _onPage() {
    final page = _pages?.page;
    if (page == null) return;
    final rounded = page.round();
    if ((page - rounded).abs() < 0.001 && rounded != _settledPage) {
      // Leaving a page: its diary scroll starts from the top next time.
      final old = _scrolls[_settledPage];
      if (old != null && old.hasClients) old.jumpTo(0);
      _settledPage = rounded;
      ref.read(memoriesViewModelProvider.notifier).setCurrentPage(rounded);
    }
    _awards.pageChanged(page);
  }

  // ---------------------------------------------------------------- vertical router

  void _verticalStart(bool bar) {
    _route = null;
    _barZone = bar;
    _awards.dragStarted();
  }

  bool _barZone = false;

  void _verticalUpdate(DragUpdateDetails d) {
    final dy = d.delta.dy;
    if (_route == null) {
      if (dy == 0) return;
      final diaryLevel = _p.isDiaryLevel;
      if (_barZone) {
        _route = diaryLevel && dy > 0 ? _Route.diaryHandle : _Route.dismissBar;
      } else {
        _route = dy < 0 ? _Route.dismissBody : _Route.cardBand;
      }
      if (_route == _Route.diaryHandle) _p.startPull();
      if (_route == _Route.cardBand) _p.startPull(ceiling: 0);
    }
    _routeDelta(dy);
  }

  void _routeDelta(double dy) {
    switch (_route) {
      case _Route.dismissBar || _Route.dismissBody || _Route.diaryEndDismiss:
        widget.reveal.dragBy(dy, _height);
      case _Route.cardBand || _Route.diaryHandle || _Route.diaryPull:
        _p.pullBy(dy);
      case null:
        break;
    }
  }

  void _verticalEnd(double velocityY) {
    final route = _route;
    _route = null;
    _awards.dragEnded();
    switch (route) {
      case _Route.dismissBar:
        _commitIfClosing(widget.reveal.settleDismiss(velocityY, _height, DismissRule.bar.afterSlop(kTouchSlop)));
      case _Route.dismissBody || _Route.diaryEndDismiss:
        _commitIfClosing(widget.reveal.settleDismiss(velocityY, _height, DismissRule.body.afterSlop(kTouchSlop)));
      case _Route.cardBand || _Route.diaryHandle || _Route.diaryPull:
        _p.releasePull(velocityY);
      case null:
        break;
    }
  }

  void _commitIfClosing(Future<double> settle) {
    if (widget.reveal.settleTarget == 1) YoinHaptics.confirm();
    unawaited(settle);
  }

  // ---------------------------------------------------------------- diary raw routes

  void _diaryDown(PointerDownEvent e, int page) {
    if (_pointer != null) return;
    _pointer = e.pointer;
    _diaryVelocity.addPosition(e.timeStamp, e.position);
    final s = _scrolls[page];
    final pos = s != null && s.hasClients ? s.position : null;
    _scrolledAtDown = (pos?.pixels ?? 0) > 0.5;
    _atEndAtDown = pos != null && _p.fraction >= 0.99 && !_p.isSettling && pos.pixels >= pos.maxScrollExtent - 0.5;
    _awards.dragStarted();
  }

  void _diaryMove(PointerMoveEvent e) {
    if (e.pointer != _pointer) return;
    _diaryVelocity.addPosition(e.timeStamp, e.position);
    if (_route == _Route.diaryPull || _route == _Route.diaryEndDismiss) _routeDelta(e.delta.dy);
  }

  void _diaryUp(PointerEvent e) {
    if (e.pointer != _pointer) return;
    _pointer = null;
    final v = _diaryVelocity.getVelocity().pixelsPerSecond.dy;
    if (_route == _Route.diaryPull || _route == _Route.diaryEndDismiss) {
      _verticalEnd(v);
    } else {
      _awards.dragEnded();
    }
  }

  /// The diary's scroll ran past an edge under the finger: past the top
  /// hands the drag to p, past the end (a drag that started there) to q.
  bool _diaryOverscroll(OverscrollNotification n, ScrollController scroll) {
    if (n.dragDetails == null || _route != null || _pointer == null) return false;
    if (n.overscroll < 0) {
      _route = _Route.diaryPull;
      _p.startPull(banded: true, fromScrolled: _scrolledAtDown);
    } else if (n.overscroll > 0 && _atEndAtDown) {
      _route = _Route.diaryEndDismiss;
    } else {
      return false;
    }
    // The scroll lets go of this drag; the raw pointer carries it from here.
    scroll.jumpTo(scroll.offset);
    _routeDelta(-n.overscroll);
    return true;
  }

  // ---------------------------------------------------------------- actions

  void _openAlbum(MemoryEntry memory) => DetailRoutes.openAlbum(context, memory.albumId);

  AlbumPlaybackContext _context(MemoryEntry m) =>
      AlbumPlaybackContext(albumId: m.albumId, name: m.albumName, artist: m.artistName, cover: m.coverArt);

  void _playTrack(MemoryEntry m, MemoryTrack t) {
    YoinHaptics.click();
    ref
        .read(playbackControllerProvider.notifier)
        .playTracks(m.playbackSongs, startIndex: t.playbackIndex, context: _context(m));
  }

  void _playNote(MemoryEntry m, MemoryTrack t, MemoryWriting note) {
    YoinHaptics.click();
    ref
        .read(playbackControllerProvider.notifier)
        .playTracks(
          m.playbackSongs,
          startIndex: t.playbackIndex,
          context: _context(m),
          startPositionMs: note.positionMs ?? 0,
        );
  }

  // ---------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    _reduced = MediaQuery.disableAnimationsOf(context);
    _p.reducedMotion = _reduced;
    _awards.reducedMotion = _reduced;
    final state = ref.watch(memoriesViewModelProvider);
    final colors = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final top = MediaQuery.paddingOf(context).top;
    return LayoutBuilder(
      builder: (context, c) {
        _height = c.maxHeight;
        final layout = memoriesLayoutFor(c.maxWidth, c.maxHeight);
        final memories = state is MemoriesContent ? state.memories : const <MemoryEntry>[];
        _awards.sync(memories);
        final source = ref.watch(activeSourceProvider).value;
        final fallback = MemoryPalette.fallback(colors.primary);
        for (final m in memories) {
          final cover = m.coverArt;
          _palettes.request(
            m,
            cover == null ? null : source?.resolveCoverUrl(cover, size: NetworkCoverColorLoader.decodeSize),
            fallback,
          );
        }
        if (state is MemoriesContent) {
          final pages = _pagesFor(state.currentPage);
          if (!pages.hasClients) _settledPage = state.currentPage;
        }
        return MemoryPaletteScope(
          palettes: _palettes,
          child: Material(
            color: colors.surface,
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (memories.isNotEmpty)
                  Positioned.fill(
                    child: _Wash(memories: memories, pages: _pages!, dark: dark),
                  ),
                switch (state) {
                  MemoriesContent() => _deck(context, state, layout, top, dark),
                  _ => GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onVerticalDragStart: (_) => _verticalStart(false),
                    onVerticalDragUpdate: (d) {
                      _route ??= _Route.dismissBody;
                      _routeDelta(d.delta.dy);
                    },
                    onVerticalDragEnd: (d) => _verticalEnd(d.velocity.pixelsPerSecond.dy),
                    child: Center(
                      child: Text(
                        switch (state) {
                          MemoriesError() => MemoriesStrings.of(context).errorLoad,
                          MemoriesEmpty() => MemoriesStrings.of(context).emptyTitle,
                          _ => '',
                        },
                        style: gsf(
                          16,
                          weight: 500,
                          height: 1.4,
                          letterSpacing: 0,
                        ).copyWith(color: colors.onSurfaceVariant),
                      ),
                    ),
                  ),
                },
                Positioned(
                  left: 0,
                  right: 0,
                  top: 0,
                  height: top + MemoriesTokens.barHeight,
                  child: _TopBar(
                    memories: memories,
                    pages: _pages,
                    diary: _p,
                    layout: layout,
                    dark: dark,
                    statusTop: top,
                    reducedMotion: _reduced,
                    onHome: () {
                      YoinHaptics.click();
                      widget.reveal.close();
                    },
                    onCloseDiary: () {
                      YoinHaptics.click();
                      _p.close();
                    },
                    onPage: (i) => _pages?.animateToPageSpring(i, _reduced),
                    onVerticalStart: () => _verticalStart(true),
                    onVerticalUpdate: _verticalUpdate,
                    onVerticalEnd: _verticalEnd,
                    onHorizontalStart: (d) => _barPageDrag = _pages?.position.drag(d, () => _barPageDrag = null),
                    onHorizontalUpdate: (d) => _barPageDrag?.update(d),
                    onHorizontalEnd: (d) {
                      _barPageDrag?.end(d);
                      _barPageDrag = null;
                    },
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _deck(BuildContext context, MemoriesContent state, MemoriesLayout layout, double top, bool dark) {
    final pages = _pagesFor(state.currentPage);
    return PageView.builder(
      controller: pages,
      itemCount: state.memories.length,
      physics: const PageScrollPhysics(parent: ClampingScrollPhysics()),
      itemBuilder: (context, index) {
        final memory = state.memories[index];
        return _MemoryPage(
          key: ValueKey(memory.sessionId),
          memory: memory,
          index: index,
          count: state.memories.length,
          pages: pages,
          layout: layout,
          statusTop: top,
          dark: dark,
          diary: _p,
          scroll: _scrollFor(index),
          awards: _awards,
          reducedMotion: _reduced,
          onCardVerticalStart: () => _verticalStart(false),
          onCardVerticalUpdate: _verticalUpdate,
          onCardVerticalEnd: _verticalEnd,
          onDiaryDown: (e) => _diaryDown(e, index),
          onDiaryMove: _diaryMove,
          onDiaryUp: _diaryUp,
          onDiaryOverscroll: (n) => _diaryOverscroll(n, _scrollFor(index)),
          onOpenDiary: _p.open,
          onOpenAlbum: () => _openAlbum(memory),
          onPlayTrack: (t) => _playTrack(memory, t),
          onPlayNote: (t, n) => _playNote(memory, t, n),
          onSaveReview: (text) => ref.read(memoriesViewModelProvider.notifier).saveReview(memory, text),
        );
      },
    );
  }
}

extension on PageController {
  void animateToPageSpring(int page, bool reduced) {
    if (!hasClients) return;
    if (reduced) {
      jumpToPage(page);
      return;
    }
    final target = page * position.viewportDimension;
    final sim = SpringSimulation(YoinSprings.defaultSpatial, position.pixels, target, 0);
    // A one-off spring ride on the page's own position (no tween).
    final ctrl = AnimationController.unbounded(vsync: position.context.vsync, value: position.pixels);
    ctrl.addListener(() => position.jumpTo(ctrl.value));
    ctrl.animateWith(sim).whenCompleteOrCancel(ctrl.dispose);
  }
}

/// The page's ground: the album's tint over the surface, following the
/// deck's position (the palette moves with the finger, so it never snaps).
class _Wash extends StatelessWidget {
  const _Wash({required this.memories, required this.pages, required this.dark});

  final List<MemoryEntry> memories;
  final PageController pages;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final surface = Theme.of(context).colorScheme.surface;
    return AnimatedBuilder(
      animation: pages,
      builder: (context, _) {
        final page = pages.hasClients && pages.position.haveDimensions
            ? (pages.page ?? 0)
            : pages.initialPage.toDouble();
        final i = page.floor().clamp(0, memories.length - 1);
        final j = (i + 1).clamp(0, memories.length - 1);
        final t = (page - i).clamp(0.0, 1.0);
        return _PaletteTints(a: memories[i], b: memories[j], t: t, dark: dark, surface: surface);
      },
    );
  }
}

class _PaletteTints extends StatelessWidget {
  const _PaletteTints({required this.a, required this.b, required this.t, required this.dark, required this.surface});

  final MemoryEntry a;
  final MemoryEntry b;
  final double t;
  final bool dark;
  final Color surface;

  @override
  Widget build(BuildContext context) {
    final ta = memoryTonesFor(context, a, dark);
    final tb = memoryTonesFor(context, b, dark);
    final tint = Color.lerp(ta.tint, tb.tint, t)!;
    final glow = Color.lerp(ta.ink, tb.ink, t)!;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color.alphaBlend(glow.withValues(alpha: dark ? 0.16 : 0.10), surface),
            Color.alphaBlend(tint, surface),
            surface,
          ],
          stops: const [0, 0.45, 1],
        ),
      ),
    );
  }
}

class _MemoryPage extends ConsumerWidget {
  const _MemoryPage({
    super.key,
    required this.memory,
    required this.index,
    required this.count,
    required this.pages,
    required this.layout,
    required this.statusTop,
    required this.dark,
    required this.diary,
    required this.scroll,
    required this.awards,
    required this.reducedMotion,
    required this.onCardVerticalStart,
    required this.onCardVerticalUpdate,
    required this.onCardVerticalEnd,
    required this.onDiaryDown,
    required this.onDiaryMove,
    required this.onDiaryUp,
    required this.onDiaryOverscroll,
    required this.onOpenDiary,
    required this.onOpenAlbum,
    required this.onPlayTrack,
    required this.onPlayNote,
    required this.onSaveReview,
  });

  final MemoryEntry memory;
  final int index;
  final int count;
  final PageController pages;
  final MemoriesLayout layout;
  final double statusTop;
  final bool dark;
  final MemoriesDiaryController diary;
  final ScrollController scroll;
  final MemoryAwards awards;
  final bool reducedMotion;
  final VoidCallback onCardVerticalStart;
  final void Function(DragUpdateDetails) onCardVerticalUpdate;
  final void Function(double velocity) onCardVerticalEnd;
  final void Function(PointerDownEvent) onDiaryDown;
  final void Function(PointerMoveEvent) onDiaryMove;
  final void Function(PointerEvent) onDiaryUp;
  final bool Function(OverscrollNotification) onDiaryOverscroll;
  final VoidCallback onOpenDiary;
  final VoidCallback onOpenAlbum;
  final void Function(MemoryTrack) onPlayTrack;
  final void Function(MemoryTrack, MemoryWriting) onPlayNote;
  final Future<bool> Function(String) onSaveReview;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = MemoriesStrings.of(context);
    final tones = memoryTonesFor(context, memory, dark);
    final barBottom = statusTop + MemoriesTokens.barHeight;
    final now = ref.watch(memoriesClockProvider)();
    final today = ref.watch(memoriesDayOfProvider)(now);
    final playingId = ref.watch(playerSnapshotProvider.select((s) => s.track?.id));
    final playingHere = playingId != null && memory.diaryTracks.any((t) => t.track.trackId == playingId);
    final litNote = playingHere
        ? ref.watch(playheadProvider.select((h) => memoryLitNoteId(memory, playingId, h.positionMs)))
        : null;

    return LayoutBuilder(
      builder: (context, c) {
        final width = c.maxWidth;
        final cardTop = barBottom;
        final cover = memoryCardCoverOffset(layout, width);
        final cardCover = Rect.fromLTWH(cover.dx, cardTop + cover.dy, layout.cover, layout.cover);
        final barCover = Rect.fromLTWH(
          layout.barCoverStart,
          statusTop + (MemoriesTokens.barHeight - MemoriesTokens.barCover) / 2,
          MemoriesTokens.barCover,
          MemoriesTokens.barCover,
        );
        return AnimatedBuilder(
          animation: diary,
          builder: (context, _) {
            final p = diary.fraction;
            final pc = p.clamp(0.0, 1.0);
            final diaryOpen = p >= 0.5;
            return Stack(
              fit: StackFit.expand,
              children: [
                // The diary, composed once the morph starts.
                if (p > 0.001)
                  Positioned.fill(
                    child: IgnorePointer(
                      ignoring: !diaryOpen,
                      child: ExcludeSemantics(
                        excluding: !diaryOpen,
                        child: Listener(
                          onPointerDown: onDiaryDown,
                          onPointerMove: onDiaryMove,
                          onPointerUp: onDiaryUp,
                          onPointerCancel: onDiaryUp,
                          child: NotificationListener<OverscrollIndicatorNotification>(
                            // No stretch at either edge: past the top is p's, past the end is q's.
                            onNotification: (n) {
                              n.disallowIndicator();
                              return false;
                            },
                            child: NotificationListener<OverscrollNotification>(
                              onNotification: onDiaryOverscroll,
                              child: MemoryDiary(
                                memory: memory,
                                tones: tones,
                                layout: layout,
                                diary: diary.animation,
                                scroll: scroll,
                                topInset: barBottom,
                                emblem: _diaryEmblem(context, tones, pc),
                                today: today,
                                reducedMotion: reducedMotion,
                                interactive: diaryOpen,
                                playingTrackId: playingHere ? playingId : null,
                                litNoteId: litNote,
                                onPlayTrack: onPlayTrack,
                                onPlayNote: onPlayNote,
                                onOpenAlbum: onOpenAlbum,
                                onSaveReview: onSaveReview,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                // The cover: one element, flying from the card to the bar's 40 dp slot.
                _flyingCover(cardCover.translate(0, p < 0 ? -p * MemoriesTokens.diaryMorphDistance : 0), barCover, pc),
                // The card (the cover slot is left to the flying cover).
                Positioned(
                  left: 0,
                  right: 0,
                  top: cardTop,
                  bottom: 0,
                  child: IgnorePointer(
                    ignoring: diaryOpen,
                    child: ExcludeSemantics(
                      excluding: diaryOpen,
                      child: GestureDetector(
                        behavior: HitTestBehavior.translucent,
                        onVerticalDragStart: (_) => onCardVerticalStart(),
                        onVerticalDragUpdate: onCardVerticalUpdate,
                        onVerticalDragEnd: (d) => onCardVerticalEnd(d.velocity.pixelsPerSecond.dy),
                        child: Transform.translate(
                          // The card's own pull-down (p < 0) follows the finger.
                          offset: Offset(0, p < 0 ? -p * MemoriesTokens.diaryMorphDistance : 0),
                          child: Semantics(
                            label: strings.cdPage(index + 1, count, memory.memoryTitle),
                            child: MemoryCardFace(
                              memory: memory,
                              tones: tones,
                              layout: layout,
                              diary: diary.animation,
                              reducedMotion: reducedMotion,
                              emblem: _cardEmblem(context, tones, pc),
                              onOpenDiary: onOpenDiary,
                              onOpenAlbum: onOpenAlbum,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Widget _flyingCover(Rect card, Rect bar, double p) {
    final double alpha;
    final Rect rect;
    final double radius;
    if (reducedMotion) {
      alpha = 1 - smoothstep(0, MemoriesTokens.reducedSwap, p);
      rect = card;
      radius = MemoriesTokens.coverCornerCard;
    } else {
      // Size leads position.
      final sizeT = 1 - (1 - p) * (1 - p);
      final size = lerpDouble(card.width, bar.width, sizeT);
      final cx = lerpDouble(card.center.dx, bar.center.dx, p);
      final cy = lerpDouble(card.center.dy, bar.center.dy, p);
      rect = Rect.fromCenter(center: Offset(cx, cy), width: size, height: size);
      radius = lerpDouble(MemoriesTokens.coverCornerCard, MemoriesTokens.coverCornerBar, p);
      // Hands over to the bar's own cover inside its window.
      alpha = 1 - smoothstep(0.62, 0.9, p);
    }
    if (alpha <= 0.001) return const SizedBox.shrink();
    return Positioned.fromRect(
      rect: rect,
      child: IgnorePointer(
        child: Opacity(
          opacity: alpha.clamp(0.0, 1.0),
          child: CoverArt(cover: memory.coverArt, size: rect.width, radius: radius, requestSize: 512),
        ),
      ),
    );
  }

  Widget _cardEmblem(BuildContext context, MemoryPaletteTones tones, double p) {
    final a = reducedMotion
        ? 1 - smoothstep(0, MemoriesTokens.reducedSwap, p)
        : 1 - smoothstep(MemoriesTokens.titleHold, MemoriesTokens.emblemSwapFrom, p);
    return Opacity(
      opacity: a.clamp(0.0, 1.0),
      child: MemoryEmblemSlot(memory: memory, size: layout.seal, awards: awards, diary: false),
    );
  }

  Widget _diaryEmblem(BuildContext context, MemoryPaletteTones tones, double p) {
    final a = reducedMotion
        ? smoothstep(MemoriesTokens.reducedSwap, 1, p)
        : smoothstep(MemoriesTokens.emblemSwapFrom, MemoriesTokens.emblemSwapTo, p);
    return Opacity(
      opacity: a.clamp(0.0, 1.0),
      child: MemoryEmblemSlot(memory: memory, size: MemoriesTokens.diaryEmblem, awards: awards, diary: true),
    );
  }
}

/// The note the playhead is inside, on the playing song: the latest anchored
/// note at or before [positionMs].
String? memoryLitNoteId(MemoryEntry memory, MediaId? trackId, int positionMs) {
  if (trackId == null) return null;
  String? lit;
  var best = -1;
  for (final group in memory.diaryTracks) {
    if (group.track.trackId != trackId) continue;
    for (final n in group.notes) {
      final at = n.positionMs;
      if (at != null && at <= positionMs && at > best) {
        best = at;
        lit = n.noteId;
      }
    }
  }
  return lit;
}

/// The shared top bar: the "⌃ Home" pill and the page dots, with each
/// page's slots riding its parallax (slot A "Memories / Last heard", slot B
/// the diary's 40 dp cover, album and ⌄). Drags that start here page the
/// deck (horizontal) or go to the router as the bar zone (vertical).
class _TopBar extends ConsumerWidget {
  const _TopBar({
    required this.memories,
    required this.pages,
    required this.diary,
    required this.layout,
    required this.dark,
    required this.statusTop,
    required this.reducedMotion,
    required this.onHome,
    required this.onCloseDiary,
    required this.onPage,
    required this.onVerticalStart,
    required this.onVerticalUpdate,
    required this.onVerticalEnd,
    required this.onHorizontalStart,
    required this.onHorizontalUpdate,
    required this.onHorizontalEnd,
  });

  final List<MemoryEntry> memories;
  final PageController? pages;
  final MemoriesDiaryController diary;
  final MemoriesLayout layout;
  final bool dark;
  final double statusTop;
  final bool reducedMotion;
  final VoidCallback onHome;
  final VoidCallback onCloseDiary;
  final void Function(int) onPage;
  final VoidCallback onVerticalStart;
  final void Function(DragUpdateDetails) onVerticalUpdate;
  final void Function(double) onVerticalEnd;
  final void Function(DragStartDetails) onHorizontalStart;
  final void Function(DragUpdateDetails) onHorizontalUpdate;
  final void Function(DragEndDetails) onHorizontalEnd;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final strings = MemoriesStrings.of(context);
    final now = ref.watch(memoriesClockProvider)();
    final dayOf = ref.watch(memoriesDayOfProvider);
    final count = memories.length;
    final dotsWidth = count <= 1 ? 0.0 : MemoriesTokens.dotBox + (count - 1) * MemoriesTokens.dotPitch;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onVerticalDragStart: (_) => onVerticalStart(),
      onVerticalDragUpdate: onVerticalUpdate,
      onVerticalDragEnd: (d) => onVerticalEnd(d.velocity.pixelsPerSecond.dy),
      onHorizontalDragStart: pages == null ? null : onHorizontalStart,
      onHorizontalDragUpdate: pages == null ? null : onHorizontalUpdate,
      onHorizontalDragEnd: pages == null ? null : onHorizontalEnd,
      child: AnimatedBuilder(
        animation: Listenable.merge([diary, ?pages]),
        builder: (context, _) {
          final p = diary.fraction.clamp(0.0, 1.0);
          final page = pages != null && pages!.hasClients && pages!.position.haveDimensions
              ? (pages!.page ?? 0)
              : (pages?.initialPage.toDouble() ?? 0);
          final width = MediaQuery.sizeOf(context).width;
          final slotEnd = layout.dotsEnd - MemoriesTokens.dotOverhang + dotsWidth + MemoriesTokens.slotClearOfDots;
          return Padding(
            padding: EdgeInsets.only(top: statusTop),
            child: Stack(
              children: [
                for (var i = 0; i < count; i++)
                  if ((i - page).abs() < 1.6)
                    _PageSlots(
                      memory: memories[i],
                      rel: i - page,
                      width: width,
                      p: p,
                      layout: layout,
                      dark: dark,
                      slotEnd: slotEnd,
                      reducedMotion: reducedMotion,
                      lastHeard: memories[i].lastHeardAt == null
                          ? null
                          : strings.topbarLastHeard(strings.chromeDate(dayOf(memories[i].lastHeardAt!), dayOf(now))),
                      onCloseDiary: onCloseDiary,
                    ),
                // The Home pill: 88 → 36 over the cover's flight, its label first.
                Positioned(
                  left: layout.pillStart,
                  top: (MemoriesTokens.barHeight - MemoriesTokens.pillHeight) / 2,
                  child: Semantics(
                    button: true,
                    label: strings.cdCloseHome,
                    excludeSemantics: true,
                    child: SizedBox(
                      width: lerpDouble(
                        MemoriesTokens.pillWidth,
                        MemoriesTokens.pillWidthDiary,
                        smoothstep(0, MemoriesTokens.pillNarrowTo, p),
                      ),
                      height: MemoriesTokens.pillHeight,
                      child: Material(
                        color: colors.surfaceContainerHigh,
                        shape: const StadiumBorder(),
                        clipBehavior: Clip.antiAlias,
                        child: InkWell(
                          onTap: onHome,
                          child: Padding(
                            padding: const EdgeInsetsDirectional.only(start: 9),
                            child: Row(
                              children: [
                                Icon(YoinSymbols.chevronUp, size: 18, color: colors.onSurface),
                                const SizedBox(width: 3),
                                Flexible(
                                  child: Opacity(
                                    opacity: 1 - smoothstep(0, MemoriesTokens.pillLabelFadeTo, p),
                                    child: Text(
                                      strings.topbarHome,
                                      style: gsf(
                                        14,
                                        weight: 600,
                                        height: 1,
                                        letterSpacing: 0,
                                      ).copyWith(color: colors.onSurface),
                                      maxLines: 1,
                                      softWrap: false,
                                      overflow: TextOverflow.clip,
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
                // Page dots: 32×48 boxes on an 18 dp pitch; a tap lands on the nearest dot.
                if (count > 1)
                  Positioned(
                    right: layout.dotsEnd - MemoriesTokens.dotOverhang,
                    top: (MemoriesTokens.barHeight - MemoriesTokens.hitHeight) / 2,
                    width: dotsWidth,
                    height: MemoriesTokens.hitHeight,
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTapUp: (d) {
                        YoinHaptics.tick();
                        onPage(
                          nearestDot(d.localPosition.dx, count, MemoriesTokens.dotPitch, MemoriesTokens.dotBox / 2),
                        );
                      },
                      child: CustomPaint(
                        painter: _DotsPainter(
                          count: count,
                          page: page,
                          current: memoryTonesFor(context, memories[page.round().clamp(0, count - 1)], dark).dot,
                          other: colors.onSurfaceVariant.withValues(alpha: 0.38),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _DotsPainter extends CustomPainter {
  _DotsPainter({required this.count, required this.page, required this.current, required this.other});

  final int count;
  final double page;
  final Color current;
  final Color other;

  @override
  void paint(Canvas canvas, Size size) {
    for (var i = 0; i < count; i++) {
      final w = (1 - (i - page).abs()).clamp(0.0, 1.0);
      final d = lerpDouble(MemoriesTokens.dotSize, MemoriesTokens.dotSizeCurrent, w);
      final c = Offset(MemoriesTokens.dotBox / 2 + i * MemoriesTokens.dotPitch, size.height / 2);
      canvas.drawCircle(c, d / 2, Paint()..color = Color.lerp(other, current, w)!);
    }
  }

  @override
  bool shouldRepaint(_DotsPainter old) =>
      old.page != page || old.count != count || old.current != current || old.other != other;
}

class _PageSlots extends StatelessWidget {
  const _PageSlots({
    required this.memory,
    required this.rel,
    required this.width,
    required this.p,
    required this.layout,
    required this.dark,
    required this.slotEnd,
    required this.reducedMotion,
    required this.lastHeard,
    required this.onCloseDiary,
  });

  final MemoryEntry memory;

  /// This page's distance from the deck's position (+ = to the right).
  final double rel;
  final double width;
  final double p;
  final MemoriesLayout layout;
  final bool dark;
  final double slotEnd;
  final bool reducedMotion;
  final String? lastHeard;
  final VoidCallback onCloseDiary;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = MemoriesStrings.of(context);
    final visible = barVisibility(rel);
    if (visible <= 0) return const SizedBox.shrink();
    // The page itself moves −rel·W; the slots add 0.6·W·rel, travelling at 40%.
    final dx = rel * width - barParallax(rel, width);
    final slotA = reducedMotion
        ? 1 - smoothstep(0, MemoriesTokens.reducedSwap, p)
        : 1 - smoothstep(0, MemoriesTokens.slotAFadeTo, p);
    final slotB = reducedMotion
        ? smoothstep(MemoriesTokens.reducedSwap, 1, p)
        : smoothstep(MemoriesTokens.slotBFadeFrom, MemoriesTokens.slotBFadeTo, p);
    final coverAlpha = reducedMotion ? slotB : smoothstep(0.62, 0.9, p);
    final artist = memory.artistName ?? '';
    final year = memory.year?.toString();
    return Positioned.fill(
      child: Transform.translate(
        offset: Offset(dx, 0),
        child: Opacity(
          opacity: visible,
          child: Stack(
            children: [
              if (slotA > 0.001)
                Positioned(
                  left: layout.slotAStart,
                  right: slotEnd,
                  top: 0,
                  bottom: 0,
                  child: IgnorePointer(
                    child: Opacity(
                      opacity: slotA,
                      child: Transform.translate(
                        offset: Offset(0, reducedMotion ? 0 : -MemoriesTokens.slotALift * p),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              strings.topbarMemories,
                              style: gsf(
                                15,
                                weight: 600,
                                height: 1.25,
                                letterSpacing: 0,
                              ).copyWith(color: colors.onSurface),
                              maxLines: 1,
                              overflow: TextOverflow.clip,
                            ),
                            if (lastHeard != null)
                              Text(
                                lastHeard!,
                                style: gsf(
                                  12,
                                  weight: 500,
                                  height: 1.35,
                                  letterSpacing: 0,
                                ).copyWith(color: colors.onSurfaceVariant),
                                maxLines: 1,
                                overflow: TextOverflow.clip,
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              if (p > 0.001) ...[
                // The bar's cover (the flying cover's twin, handed over by alpha).
                Positioned(
                  left: layout.barCoverStart,
                  top: (MemoriesTokens.barHeight - MemoriesTokens.barCover) / 2,
                  child: Opacity(
                    opacity: coverAlpha,
                    child: GestureDetector(
                      onTap: p >= 0.5 ? onCloseDiary : null,
                      child: CoverArt(
                        cover: memory.coverArt,
                        size: MemoriesTokens.barCover,
                        radius: MemoriesTokens.coverCornerBar,
                        requestSize: 128,
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: layout.slotBStart,
                  right: slotEnd,
                  top: 0,
                  bottom: 0,
                  child: Opacity(
                    opacity: slotB,
                    child: Transform.translate(
                      offset: Offset(0, reducedMotion ? 0 : (1 - slotB) * MemoriesTokens.slotBDrop),
                      child: Semantics(
                        button: p >= 0.5,
                        label: strings.cdBarAlbum(memory.albumName, artist.isNotEmpty ? artist : (year ?? '')),
                        hint: strings.cdCloseDiary,
                        excludeSemantics: true,
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: p >= 0.5 ? onCloseDiary : null,
                          child: Row(
                            children: [
                              Expanded(
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      memory.albumName,
                                      style: gsf(
                                        15,
                                        weight: 600,
                                        height: 1.3,
                                        letterSpacing: 0,
                                      ).copyWith(color: colors.onSurface),
                                      maxLines: 1,
                                      overflow: TextOverflow.fade,
                                      softWrap: false,
                                    ),
                                    MetaLine(
                                      style: gsf(13, weight: 500, height: 1.3, letterSpacing: 0),
                                      color: colors.onSurfaceVariant,
                                      groups: [
                                        if (artist.isNotEmpty) MetaPlain(artist),
                                        if (year != null) MetaMuted(year),
                                      ],
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(width: 6),
                              Icon(YoinSymbols.chevronDown, size: 18, color: colors.onSurfaceVariant),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
