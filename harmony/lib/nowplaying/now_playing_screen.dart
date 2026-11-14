import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../detail/detail_routes.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import '../playback/playhead.dart';
import '../shell/shell_state.dart';
import '../theme/motion.dart';
import '../theme/playback_theme.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/audio_format_tags.dart';
import 'cover_swap.dart';
import 'flight.dart';
import 'focus_budget.dart';
import 'focus_idle.dart';
import 'haptics.dart';
import 'lyrics_view.dart';
import 'marquee.dart';
import 'now_playing_background.dart';
import 'note_compact_pane.dart';
import 'note_controller.dart';
import 'note_logic.dart';
import 'note_page.dart';
import 'note_write_bar.dart';
import 'now_playing_presentation.dart';
import 'now_playing_view_model.dart';
import 'nowplaying_strings.dart';
import 'overlay_controller.dart';
import 'play_mode_cycle.dart';
import 'playhead_clock.dart';
import '../library/add_to_playlist_flow.dart';
import '../ui/component/track_menu_sheet.dart';
import 'queue_sheet.dart';
import 'rating_and_heart.dart';
import 'stage_budget.dart';
import 'stage_tabs.dart';
import 'transport.dart';

double _smooth(double edge0, double edge1, double x) {
  final t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// An [Animation] whose value is [map] of several parents' values (derived
/// reads for layout / paint; no status of its own).
class _Derived extends Animation<double> {
  _Derived(this.parents, this.map) : _merged = Listenable.merge(parents);

  final List<Listenable> parents;
  final Listenable _merged;
  final double Function() map;

  @override
  double get value => map();

  @override
  AnimationStatus get status => AnimationStatus.forward;

  @override
  void addListener(VoidCallback listener) => _merged.addListener(listener);

  @override
  void removeListener(VoidCallback listener) => _merged.removeListener(listener);

  @override
  void addStatusListener(AnimationStatusListener listener) {}

  @override
  void removeStatusListener(AnimationStatusListener listener) {}
}

/// An [Animation] whose value is [map] of its parent's (no rebuilds, just
/// derived reads for transitions).
class _Mapped extends Animation<double> with AnimationWithParentMixin<double> {
  _Mapped(this.parent, this.map);

  @override
  final Animation<double> parent;
  final double Function(double) map;

  @override
  double get value => map(parent.value);
}

/// Expanded top bar's docked cover (44 in a 48 slot after the 48 chevron).
const double _dockedCover = 44;
const double _dockedCoverX = 4 + 48 + 4 + 2;
const double _dockedCoverY = (NowPlayingTokens.topBarNominal - _dockedCover) / 2;

/// The Now Playing player (phone column). The column is one fixed-height
/// slot layout from [resolveStageColumnBudget]; the Expanded lyrics stage
/// interpolates every slot on the controller's detail spring by relayout
/// only (the column is not rebuilt per frame).
class NowPlayingScreen extends ConsumerStatefulWidget {
  const NowPlayingScreen({super.key, required this.controller});

  final NowPlayingOverlayController controller;

  @override
  ConsumerState<NowPlayingScreen> createState() => _NowPlayingScreenState();
}

class _NowPlayingScreenState extends ConsumerState<NowPlayingScreen> with TickerProviderStateMixin {
  final ValueNotifier<TransportKey> _pressed = ValueNotifier(TransportKey.none);
  final ValueNotifier<bool> _autoFollow = ValueNotifier(true);
  LyricFrame? _lastFrame;

  /// The song's notes, the draft and delete-with-undo (one per player).
  late final NowPlayingNotes _notes = NowPlayingNotes(
    loadStore: () => ref.read(memoryStoreProvider.future),
    positionMs: () => ref.read(playheadClockProvider).positionMs(),
    nowMs: () => ref.read(playbackClockProvider).wallMs(),
    seekTo: (ms) => ref.read(nowPlayingViewModelProvider.notifier).seekTo(ms),
  );

  /// The detail pager's ONE progress (0 = Lyrics, 1 = Note), shared by the
  /// Compact window, the Expanded page, the tabs and the accessory row; fed
  /// by the tabs, the horizontal swipe and the Write pill.
  late final OverlaySpring _page = OverlaySpring(vsync: this, initial: 0);
  NowPlayingDetailPage _pageTarget = NowPlayingDetailPage.lyrics;

  /// Keyboard up over the Expanded Note page: the hero steps aside (0 → 1).
  late final SpringValue _heroYield = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  /// The write bar's height; the accessory slot grows with it.
  late final SpringValue _barHeight = SpringValue(
    vsync: this,
    initial: NowPlayingNoteTokens.barIdleHeight,
    spring: YoinSprings.defaultSpatial,
  );
  bool _barOpen = false;
  MediaId? _scheduledTrack;
  MemoryStore? _lastStore;

  /// Focus at ease: the idle count and its shown progress (Standard slow spatial).
  final FocusIdle _focusIdle = FocusIdle();
  late final SpringValue _atEase = SpringValue(
    vsync: this,
    initial: 0,
    spring: YoinSprings.of(MotionTokens.standardSlowSpatial),
  );
  bool _idleEligible = false;

  /// At ease as shown: capped by the focus itself and gone in Expanded, so
  /// leaving focus unwinds it on the focus's own clock.
  late final Animation<double> _atEaseShown = _Derived(
    [_atEase.animation, widget.controller.focus.animation, widget.controller.detail.animation],
    () {
      final c = widget.controller;
      final f = c.focus.value.clamp(0.0, 1.0);
      return (math.min(_atEase.value, f) * (1 - c.detail.value.clamp(0.0, 1.0))).clamp(0.0, 1.0);
    },
  );

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onStage);
    widget.controller.detail.animation.addListener(_gateFocus);
    widget.controller.full.animation.addListener(_onFull);
    _page.animation.addListener(_gateFocus);
    _notes.addListener(_onNotes);
    _focusIdle.addListener(_onIdle);
  }

  void _onIdle() {
    if (!mounted) return;
    final target = _focusIdle.idle ? 1.0 : 0.0;
    if (_reduced) {
      _atEase.snapTo(target);
    } else {
      _atEase.animateTo(target);
    }
  }

  /// Focus at ease is armed while synced lyrics play in focus.
  void _trackIdle(NowPlayingUiState state) {
    final eligible =
        widget.controller.stage == NowPlayingStage.immersive &&
        state is NowPlayingContent &&
        state.isPlaying &&
        state.lyrics is LyricsReady &&
        (state.lyrics as LyricsReady).sheet.isSynced;
    if (eligible == _idleEligible) return;
    _idleEligible = eligible;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _focusIdle.track(_idleEligible);
    });
  }

  /// The body tells the controller whether its cover can focus; a stale
  /// Immersive drops back to rest after this frame.
  void _offerFocus(bool offered) {
    final c = widget.controller;
    c.focusOffered = offered;
    if (!offered && c.stage == NowPlayingStage.immersive) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) c.dropFocusIfUnoffered();
      });
    }
  }

  @override
  void didUpdateWidget(NowPlayingScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onStage);
      oldWidget.controller.detail.animation.removeListener(_gateFocus);
      widget.controller.addListener(_onStage);
      widget.controller.detail.animation.addListener(_gateFocus);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onStage);
    widget.controller.detail.animation.removeListener(_gateFocus);
    widget.controller.full.animation.removeListener(_onFull);
    _notes.removeListener(_onNotes);
    _notes.dispose();
    _page.dispose();
    _heroYield.dispose();
    _barHeight.dispose();
    _focusIdle
      ..removeListener(_onIdle)
      ..dispose();
    _atEase.dispose();
    _pressed.dispose();
    _autoFollow.dispose();
    super.dispose();
  }

  bool get _reduced => MediaQuery.disableAnimationsOf(context);

  /// Back to Compact (or the player closing) ends the writing.
  void _onStage() {
    if (widget.controller.stage != NowPlayingStage.expanded && _notes.focused) _notes.focus.unfocus();
  }

  /// The Write pill's keyboard waits for the page to land: the stage (nearly)
  /// expanded and the pager settled on Note.
  void _gateFocus() {
    if (!_notes.pendingFocus) return;
    if (widget.controller.detail.value >= noteWriteFocusStageProgress &&
        _pageTarget == NowPlayingDetailPage.note &&
        (_page.value - 1).abs() < 0.02) {
      _notes.takePendingFocus();
    }
  }

  void _onNotes() {
    if (_notes.open != _barOpen && mounted) setState(() => _barOpen = _notes.open);
  }

  void _selectPage(NowPlayingDetailPage page, {double velocity = 0}) {
    if (page != _pageTarget) {
      // Leaving the Note page puts the pen down.
      if (page != NowPlayingDetailPage.note && _notes.focused) _notes.focus.unfocus();
      setState(() => _pageTarget = page);
    }
    final target = page.index.toDouble();
    if (_reduced) {
      _page.snapTo(target);
    } else {
      _page.animateTo(target, YoinSprings.defaultSpatial, velocity: velocity);
    }
  }

  void _onPageDrag(double dx, double width) {
    if (width <= 0) return;
    _page.snapTo((_page.value - dx / width).clamp(0.0, 1.0));
  }

  void _onPageDragEnd(double velocityPx, double width) {
    final v = width <= 0 ? 0.0 : -velocityPx / width;
    final settled = v.abs() > 0.4 ? (v > 0 ? 1 : 0) : _page.value.round();
    _selectPage(NowPlayingDetailPage.values[settled.clamp(0, 1)], velocity: v);
  }

  /// The Write pill: the Note page, expanded, the keyboard once it has landed.
  void _write() {
    _notes.requestWriting();
    _selectPage(NowPlayingDetailPage.note);
    widget.controller.expandStage();
    _gateFocus();
  }

  /// Landscape has no Expanded stage: Write opens the Note page in place.
  void _writeLandscape() {
    _notes.requestWriting();
    _selectPage(NowPlayingDetailPage.note);
    _gateFocus();
  }

  void _onBarHeight(double height) {
    if (!mounted) return;
    if (_reduced) {
      _barHeight.snapTo(height);
    } else {
      _barHeight.animateTo(height);
    }
  }

  /// The song on stage feeds the notes after the frame (not during build).
  void _syncNotes(NowPlayingUiState state) {
    final track = state is NowPlayingContent ? state.track : null;
    if (track?.id == _scheduledTrack && _notes.current?.title == (track?.title ?? '')) return;
    _scheduledTrack = track?.id;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _notes.setCurrent(track == null ? null : NoteTarget.of(track));
    });
  }

  StageMetrics _metrics(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final scaler = MediaQuery.textScalerOf(context);
    double line(TextStyle s, {double sizeFactor = 1}) =>
        scaler.scale(s.fontSize! * sizeFactor) * (s.height ?? 1.25) / sizeFactor;
    return StageMetrics(
      heroArtistLine: line(text.titleMedium!),
      heroTitleLine: line(text.displaySmall!),
      activeLyricLine: line(text.bodyLarge!),
      inactiveLyricLine: line(text.bodyMedium!),
      timeLabelLine: line(text.labelLarge!),
    );
  }

  /// The enlarged phone's spec, or null for the phone column. On Medium the
  /// panel ⇄ Full container keeps ONE body: it is the enlarged phone as soon
  /// as the container leaves the panel, its rating column growing with the
  /// container so the change never pops (NowPlayingOverlayHost).
  NowPlayingEnlargedSpec? _enlargedSpec() {
    final c = widget.controller;
    final frame = c.frame;
    final f = frame.panelFamily ? c.full.value.clamp(0.0, 1.0) : 1.0;
    final enlargedBody = switch (frame.presentation) {
      NowPlayingPresentation.enlarged => true,
      NowPlayingPresentation.panel => frame.panelFamily && c.window.layoutMode == LayoutMode.medium && f > 0,
      _ => false,
    };
    if (!enlargedBody) return null;
    final spec = nowPlayingEnlargedSpec(frame.windowWidth);
    return NowPlayingEnlargedSpec(
      columnWidth: spec.columnWidth,
      ratingColumn: NowPlayingTokens.phoneRatingColumn + (spec.ratingColumn - NowPlayingTokens.phoneRatingColumn) * f,
      controlSize: f > 0.5 ? spec.controlSize : NowPlayingTokens.controlSize,
    );
  }

  /// Medium's panel ⇄ Full grows the enlarged spec with the container:
  /// rebuild per frame only while that container moves.
  void _onFull() {
    final c = widget.controller;
    if (!mounted || !c.frame.panelFamily) return;
    if (c.window.layoutMode == LayoutMode.medium) {
      setState(() {});
    } else if (_dualBody() != _lastDual) {
      // Wide swaps bodies at the container spring's midpoint: the two
      // columns are never laid out at panel width, the phone column never
      // stretched across the window.
      setState(() {});
    }
  }

  bool _lastDual = false;

  /// The two-column body shows (Wide Full; past the container's midpoint).
  bool _dualBody() {
    final c = widget.controller;
    if (c.frame.presentation != NowPlayingPresentation.dualPane) return false;
    return !c.frame.panelFamily || c.full.value > 0.5;
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(nowPlayingViewModelProvider);
    _syncNotes(state);
    _trackIdle(state);
    ref.listen(memoryStoreProvider, (_, next) {
      final store = next.value;
      if (!next.hasValue || identical(store, _lastStore)) return;
      final hadStore = _lastStore != null;
      _lastStore = store;
      if (hadStore) _notes.accountChanged();
    });
    final field = ref.watch(playbackThemeProvider.select((t) => t.field));
    final colors = Theme.of(context).colorScheme;
    final padding = MediaQuery.paddingOf(context);
    final insets = EdgeInsets.fromLTRB(
      padding.left,
      padding.top,
      padding.right,
      math.max(padding.bottom, NowPlayingTokens.columnBottomFloor),
    );
    final strings = NowPlayingStrings.of(context);
    // Rebuild on stage changes (the keyboard lift only applies to Expanded).
    NowPlayingOverlayScope.maybeOf(context);
    final expanded = widget.controller.stage == NowPlayingStage.expanded;
    final noteStage = expanded && _pageTarget == NowPlayingDetailPage.note;
    // The keyboard over the Expanded Note page: the column ends on it (the
    // nav bar's share already cleared by [insets]).
    final imeLift = noteStage ? math.max(0.0, MediaQuery.viewInsetsOf(context).bottom - insets.bottom) : 0.0;
    final yieldHero = noteStage && _barOpen && imeLift > 0;
    if (_reduced) {
      _heroYield.snapTo(yieldHero ? 1 : 0);
    } else {
      _heroYield.animateTo(yieldHero ? 1 : 0);
    }
    return FocusIdleTouchTracker(
      idle: _focusIdle,
      child: ColoredBox(
        color: colors.surface,
        child: Stack(
          fit: StackFit.expand,
          children: [
            NowPlayingBackground(field: field, focus: widget.controller.focus.animation),
            switch (state) {
              NowPlayingIdle() => Center(
                child: Text(
                  strings.emptyNothingPlaying,
                  style: Theme.of(context).textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant),
                ),
              ),
              NowPlayingContent() when widget.controller.frame.presentation == NowPlayingPresentation.landscape =>
                _LandscapePane(
                  state: state,
                  controller: widget.controller,
                  insets: insets,
                  metrics: _metrics(context),
                  pressed: _pressed,
                  autoFollow: _autoFollow,
                  notes: _notes,
                  page: _page.animation,
                  pageTarget: _pageTarget,
                  onSelectPage: _selectPage,
                  onPageDrag: _onPageDrag,
                  onPageDragEnd: _onPageDragEnd,
                  onWrite: _writeLandscape,
                  onBarHeight: _onBarHeight,
                  barHeight: _barHeight.animation,
                  atEase: _atEaseShown,
                  onOfferFocus: _offerFocus,
                ),
              NowPlayingContent() when (_lastDual = _dualBody()) => _DualPane(
                state: state,
                controller: widget.controller,
                insets: insets,
                metrics: _metrics(context),
                pressed: _pressed,
                autoFollow: _autoFollow,
                notes: _notes,
                page: _page.animation,
                pageTarget: _pageTarget,
                onSelectPage: _selectPage,
                onPageDrag: _onPageDrag,
                onPageDragEnd: _onPageDragEnd,
                onWrite: _write,
                onBarHeight: _onBarHeight,
                barHeight: _barHeight.animation,
                atEase: _atEaseShown,
                onOfferFocus: _offerFocus,
              ),
              NowPlayingContent() => Padding(
                padding: insets,
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final budget = resolveStageColumnBudget(
                      width: constraints.maxWidth,
                      height: constraints.maxHeight,
                      metrics: _metrics(context),
                      devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
                      previousFrame: _lastFrame,
                      enlarged: _enlargedSpec(),
                    );
                    _lastFrame = budget.lyricFrame;
                    final focus = resolveStageFocus(
                      rest: budget,
                      metrics: _metrics(context),
                      devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
                      enlarged: _enlargedSpec(),
                    );
                    _offerFocus(focus != null);
                    // The enlarged phone narrows to its cover row, centred (the
                    // stage lays its column out in the middle of the width).
                    return _Stage(
                      budget: budget,
                      focus: focus,
                      atEase: _atEaseShown,
                      state: state,
                      controller: widget.controller,
                      pressed: _pressed,
                      autoFollow: _autoFollow,
                      windowTop: insets.top,
                      notes: _notes,
                      page: _page.animation,
                      pageTarget: _pageTarget,
                      onSelectPage: _selectPage,
                      onPageDrag: _onPageDrag,
                      onPageDragEnd: _onPageDragEnd,
                      onWrite: _write,
                      heroYield: _heroYield.animation,
                      barHeight: _barHeight.animation,
                      onBarHeight: _onBarHeight,
                      imeLift: imeLift,
                    );
                  },
                ),
              ),
            },
          ],
        ),
      ),
    );
  }
}

enum _Slot { topBar, cover, rating, tabs, lyricsWindow, lyricsFocus, lyricsFull, controls, hero, accessory }

class _StageDelegate extends MultiChildLayoutDelegate {
  _StageDelegate({
    required this.budget,
    required this.focusPose,
    required this.detail,
    required this.focus,
    required this.atEase,
    required this.page,
    required this.heroYield,
    required this.barHeight,
    required this.imeLift,
  }) : super(relayout: Listenable.merge([detail, focus, atEase, page, heroYield, barHeight]));

  final StageColumnBudget budget;

  /// The focus pose (null: the cover is a plain image).
  final StageFocusPose? focusPose;
  final Animation<double> detail;
  final Animation<double> focus;

  /// Focus at ease as shown: the transport's first row steps away and the
  /// focus lyrics run on into its place.
  final Animation<double> atEase;

  /// The pager (0 Lyrics → 1 Note): the write bar's extra height is the Note page's.
  final Animation<double> page;

  /// The hero stepping aside for the keyboard (0 → 1).
  final Animation<double> heroYield;
  final Animation<double> barHeight;

  /// The keyboard's height over the column's bottom inset (Expanded Note only).
  final double imeLift;

  @override
  void performLayout(Size size) {
    final d = detail.value.clamp(0.0, 1.0);
    final fp = focusPose;
    final f = fp == null ? 0.0 : focus.value.clamp(0.0, 1.0);
    final p = page.value.clamp(0.0, 1.0);
    final y = heroYield.value.clamp(0.0, 1.0);
    final s = stageSlotsAt(budget, fp, f: f, d: d);
    final full = StageSlots.lerp(budget, 1);
    // The column: centred, narrowing to the focus cover.
    final live = liveStageWidth(budget, fp, f: f, d: d);
    final x0 = (size.width - live) / 2;
    final restX = (size.width - budget.stageWidth) / 2;
    final w = live;
    // The open write bar grows the accessory slot (Expanded, Note page).
    final bar = barHeight.value + NowPlayingNoteTokens.barTopGap;
    final barExtra = d * p * math.max(0.0, bar - s.accessory);
    final fullBarExtra = p * math.max(0.0, bar - full.accessory);
    final heroH = s.hero * (1 - y);
    final accessoryH = s.accessory + barExtra;

    // The top bar keeps its 48 row; as it retreats over a shorter slot it
    // rides up with the slot's bottom edge.
    final barH = math.max(s.topBar, NowPlayingTokens.topBarMin);
    layoutChild(_Slot.topBar, BoxConstraints.tight(Size(w, barH)));
    positionChild(_Slot.topBar, Offset(x0, s.topBar - barH));

    // The cover grows (focus) or flies to the docked slot (Expanded); it only moves and scales.
    final g = _smooth(0, 1, d);
    final baseCover = fp == null ? budget.cover : lerpDouble(budget.cover, fp.cover, f)!;
    final coverSize = lerpDouble(baseCover, _dockedCover, g)!;
    if (hasChild(_Slot.cover)) {
      final baseX = fp == null
          ? restX + NowPlayingTokens.columnPadding
          : lerpDouble(restX + NowPlayingTokens.columnPadding, (size.width - fp.cover) / 2, f)!;
      final baseY = fp == null ? budget.topBar : lerpDouble(budget.topBar, fp.top, f)!;
      layoutChild(_Slot.cover, BoxConstraints.tight(Size.square(math.max(0, coverSize))));
      positionChild(
        _Slot.cover,
        Offset(lerpDouble(baseX, x0 + _dockedCoverX, g)!, lerpDouble(baseY, _dockedCoverY, g)!),
      );
    }
    if (hasChild(_Slot.rating)) {
      layoutChild(_Slot.rating, BoxConstraints.tight(Size(budget.ratingColumn, budget.cover)));
      positionChild(
        _Slot.rating,
        Offset(restX + budget.stageWidth - NowPlayingTokens.columnPadding - budget.ratingColumn, s.topBar),
      );
    }

    final tabsTop = s.topBar + s.coverRow + s.coverGap;
    if (hasChild(_Slot.tabs)) {
      layoutChild(_Slot.tabs, BoxConstraints.tight(Size(w, math.max(0, s.tabs))));
      positionChild(_Slot.tabs, Offset(x0, tabsTop));
    }

    final lyricsTop = tabsTop + s.tabs + s.tabGap;
    final lyrics = math.max(0.0, size.height - imeLift - lyricsTop - s.controls - heroH - accessoryH);
    // The resting window keeps the resting width (no re-wrap while focus narrows the column).
    layoutChild(_Slot.lyricsWindow, BoxConstraints.tight(Size(budget.stageWidth, lyrics)));
    positionChild(_Slot.lyricsWindow, Offset(restX, lyricsTop));
    if (hasChild(_Slot.lyricsFocus) && fp != null) {
      // Laid out at its focus width; at ease it runs on under the transport's first row.
      final runOn = atEase.value.clamp(0.0, 1.0) * (NowPlayingTokens.controlSize + NowPlayingTokens.controlRowGap);
      layoutChild(_Slot.lyricsFocus, BoxConstraints.tight(Size(fp.stageWidth, math.max(0, fp.lyrics + runOn))));
      positionChild(_Slot.lyricsFocus, Offset((size.width - fp.stageWidth) / 2, lyricsTop));
    }
    if (hasChild(_Slot.lyricsFull)) {
      // Laid out at its FINAL height always; only clipped / moved during the reshape.
      final fullH = math.max(0.0, full.lyrics - imeLift - fullBarExtra + full.hero * y);
      layoutChild(_Slot.lyricsFull, BoxConstraints.tight(Size(budget.stageWidth, fullH)));
      positionChild(_Slot.lyricsFull, Offset(restX, lyricsTop + (lyrics - fullH)));
    }

    final controlsTop = lyricsTop + lyrics;
    final controlsH = math.max(budget.controls, fp?.controls ?? 0);
    layoutChild(_Slot.controls, BoxConstraints.tightFor(width: w, height: controlsH));
    positionChild(_Slot.controls, Offset(x0, controlsTop));

    final heroTop = controlsTop + s.controls;
    layoutChild(_Slot.hero, BoxConstraints.tight(Size(w, heroH)));
    positionChild(_Slot.hero, Offset(x0, heroTop));

    layoutChild(_Slot.accessory, BoxConstraints.tight(Size(w, accessoryH)));
    positionChild(_Slot.accessory, Offset(x0, heroTop + heroH));
  }

  @override
  bool shouldRelayout(_StageDelegate old) =>
      old.budget != budget ||
      old.focusPose != focusPose ||
      old.detail != detail ||
      old.focus != focus ||
      old.atEase != atEase ||
      old.page != page ||
      old.heroYield != heroYield ||
      old.barHeight != barHeight ||
      old.imeLift != imeLift;
}

/// What steps away in focus (FocusRetreat.kt): [progress] 0 = here, 1 =
/// gone. It fades over the first 1/1.4 of the way, moves by [shift], and
/// stops taking taps once mostly gone.
class _Retreat extends StatelessWidget {
  const _Retreat({required this.progress, required this.child, this.shift = Offset.zero});

  final Animation<double> progress;
  final Offset shift;
  final Widget child;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: progress,
    child: child,
    builder: (context, child) {
      final p = progress.value.clamp(0.0, 1.0);
      final fade = math.min(1.0, p * NowPlayingFocusTokens.retreatFadeRate);
      return IgnorePointer(
        ignoring: p > 0.5,
        child: Opacity(
          opacity: (1 - fade * fade * (3 - 2 * fade)).clamp(0.0, 1.0),
          child: Transform.translate(offset: shift * p, child: child),
        ),
      );
    },
  );
}

class _Stage extends ConsumerWidget {
  const _Stage({
    required this.budget,
    required this.focus,
    required this.atEase,
    required this.state,
    required this.controller,
    required this.pressed,
    required this.autoFollow,
    required this.windowTop,
    required this.notes,
    required this.page,
    required this.pageTarget,
    required this.onSelectPage,
    required this.onPageDrag,
    required this.onPageDragEnd,
    required this.onWrite,
    required this.heroYield,
    required this.barHeight,
    required this.onBarHeight,
    required this.imeLift,
  });

  final StageColumnBudget budget;

  /// The focus pose, or null: the cover is a plain image (no tap).
  final StageFocusPose? focus;
  final Animation<double> atEase;
  final NowPlayingContent state;
  final NowPlayingOverlayController controller;
  final ValueNotifier<TransportKey> pressed;
  final ValueNotifier<bool> autoFollow;
  final double windowTop;
  final NowPlayingNotes notes;
  final Animation<double> page;
  final NowPlayingDetailPage pageTarget;
  final void Function(NowPlayingDetailPage page) onSelectPage;
  final void Function(double dx, double width) onPageDrag;
  final void Function(double velocity, double width) onPageDragEnd;
  final VoidCallback onWrite;
  final Animation<double> heroYield;
  final Animation<double> barHeight;
  final ValueChanged<double> onBarHeight;
  final double imeLift;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Rebuild on stage / open changes (not per frame).
    NowPlayingOverlayScope.maybeOf(context);
    final vm = ref.read(nowPlayingViewModelProvider.notifier);
    final clock = ref.watch(playheadClockProvider);
    final detail = controller.detail.animation;
    final focusAnim = controller.focus.animation;
    final compact = _Mapped(detail, (d) => (1 - d).clamp(0.0, 1.0));
    final expandedStage = controller.stage == NowPlayingStage.expanded;
    final inFocus = controller.stage == NowPlayingStage.immersive;
    final restStage = controller.stage == NowPlayingStage.compact;
    final focusPose = focus;
    final track = state.track;
    // The one-line lyric row is not a pager page: no swipe over it.
    final compactSwipe = budget.lyricFrame == LyricFrame.list;
    // Focus shown (0 = rest), less the Expanded share; what retreats with it.
    double focusShown() => focusPose == null
        ? 0
        : (controller.focus.value.clamp(0.0, 1.0) * (1 - controller.detail.value.clamp(0.0, 1.0)));
    final retreat = _Derived([focusAnim, detail], focusShown);
    final ratingRetreat = _Derived([focusAnim, detail], () {
      final f = focusPose == null ? 0.0 : controller.focus.value.clamp(0.0, 1.0);
      return math.max(f, controller.detail.value.clamp(0.0, 1.0));
    });
    // The resting window gives way to the Expanded list and to the focus list.
    final windowShown = _Derived([focusAnim, detail], () {
      final f = focusPose == null ? 0.0 : controller.focus.value.clamp(0.0, 1.0);
      return ((1 - controller.detail.value.clamp(0.0, 1.0)) * (1 - f)).clamp(0.0, 1.0);
    });
    final coverTap = expandedStage
        ? controller.stepBackStage
        : (focusPose != null || inFocus ? controller.toggleFocus : null);

    return CustomMultiChildLayout(
      delegate: _StageDelegate(
        budget: budget,
        focusPose: focusPose,
        focus: focusAnim,
        atEase: atEase,
        detail: detail,
        page: page,
        heroYield: heroYield,
        barHeight: barHeight,
        imeLift: imeLift,
      ),
      children: [
        LayoutId(
          id: _Slot.topBar,
          child: _Retreat(
            progress: retreat,
            shift: const Offset(0, -NowPlayingFocusTokens.retreatLift),
            child: _TopBar(
              controller: controller,
              context0: state.context,
              onContextTap: _contextTarget(state.context, controller),
            ),
          ),
        ),
        if (!budget.coverHidden)
          LayoutId(
            id: _Slot.cover,
            child: GestureDetector(
              onTap: coverTap,
              child: FlightSlot(
                element: FlightElement.cover,
                pill: false,
                child: _StageCover(
                  state: state,
                  detail: detail,
                  requestEdge: math.max(budget.cover, focusPose?.cover ?? 0),
                  onLongPress: () => showNowPlayingTrackMenu(context, controller, state.track),
                ),
              ),
            ),
          ),
        if (!budget.coverHidden)
          LayoutId(
            id: _Slot.rating,
            child: IgnorePointer(
              ignoring: !restStage,
              child: AnimatedBuilder(
                animation: ratingRetreat,
                builder: (context, child) {
                  final r = ratingRetreat.value.clamp(0.0, 1.0);
                  return Opacity(
                    opacity: 1 - r,
                    child: Transform.translate(
                      offset: Offset(NowPlayingFocusTokens.ratingRetreatShift * r, 0),
                      child: child,
                    ),
                  );
                },
                child: Column(
                  children: [
                    Expanded(
                      // Local 0–10 rating (shown for every source, like Android's local_ratings).
                      child: RatingSlider(rating: state.rating, songKey: state.songKey, onCommit: vm.commitRating),
                    ),
                    if (state.canFavorite) ...[
                      const SizedBox(height: NowPlayingTokens.ratingToHeartGap - 2),
                      FavoriteHeart(starred: state.isStarred, onToggle: vm.toggleFavorite),
                    ],
                    const SizedBox(height: NowPlayingTokens.heartBottomGap - 2),
                  ],
                ),
              ),
            ),
          ),
        LayoutId(
          id: _Slot.tabs,
          child: ClipRect(
            child: _Retreat(
              progress: retreat,
              child: StageTabs(selected: pageTarget, detail: detail, onSelect: onSelectPage),
            ),
          ),
        ),
        LayoutId(
          id: _Slot.lyricsWindow,
          child: IgnorePointer(
            ignoring: !restStage,
            child: FadeTransition(
              opacity: windowShown,
              child: _PagerSurface(
                page: page,
                swipe: compactSwipe,
                onDrag: onPageDrag,
                onDragEnd: onPageDragEnd,
                onTap: controller.expandStage,
                lyrics: LyricsView(
                  mode: LyricsListMode.window,
                  lyrics: state.lyrics,
                  songKey: state.songKey,
                  clock: clock,
                  onSeek: vm.seekTo,
                ),
                note: ListenableBuilder(
                  listenable: notes,
                  builder: (context, _) => NoteCompactPane(notes: notes.notes, clock: clock),
                ),
              ),
            ),
          ),
        ),
        if (focusPose != null && (inFocus || controller.focus.value > 0.01))
          LayoutId(
            id: _Slot.lyricsFocus,
            child: IgnorePointer(
              ignoring: !inFocus,
              child: FadeTransition(
                opacity: _Mapped(retreat, (r) => _smooth(0.15, 1, r)),
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  // A tap on the focus lyrics opens them (the lyric window's tap).
                  onTap: controller.expandStage,
                  child: LyricsView(
                    mode: LyricsListMode.focus,
                    lyrics: state.lyrics,
                    songKey: state.songKey,
                    clock: clock,
                    onSeek: vm.seekTo,
                    upNext: state.upNext,
                    durationMs: state.durationMs,
                    horizontalInset: focusPose.lyricInset,
                  ),
                ),
              ),
            ),
          ),
        if (expandedStage || controller.detail.value > 0.01)
          LayoutId(
            id: _Slot.lyricsFull,
            child: AnimatedBuilder(
              animation: detail,
              builder: (context, child) {
                final d = detail.value.clamp(0.0, 1.0);
                return Opacity(
                  opacity: ((d - 0.1) / 0.6).clamp(0.0, 1.0),
                  child: Transform.scale(scale: 0.96 + 0.04 * d, child: child),
                );
              },
              child: _PagerSurface(
                page: page,
                swipe: expandedStage,
                onDrag: onPageDrag,
                onDragEnd: onPageDragEnd,
                lyrics: LyricsView(
                  mode: LyricsListMode.full,
                  lyrics: state.lyrics,
                  songKey: state.songKey,
                  clock: clock,
                  onSeek: vm.seekTo,
                  title: track.title,
                  artist: track.artist,
                  autoFollow: autoFollow,
                  upNext: state.upNext,
                  durationMs: state.durationMs,
                  windowTop:
                      windowTop +
                      NowPlayingTokens.topBarNominal +
                      NowPlayingTokens.coverGapMin +
                      NowPlayingTokens.expandedTabRow +
                      NowPlayingTokens.expandedTabGap,
                ),
                note: NotePage(notes: notes, clock: clock),
              ),
            ),
          ),
        LayoutId(
          id: _Slot.controls,
          child: IgnorePointer(
            ignoring: expandedStage,
            child: AnimatedBuilder(
              animation: detail,
              builder: (context, child) {
                final d = detail.value.clamp(0.0, 1.0);
                return Opacity(
                  opacity: 1 - d,
                  child: Transform.translate(offset: Offset(0, 48 * d), child: child),
                );
              },
              child: Align(
                alignment: Alignment.topCenter,
                child: ListenableBuilder(
                  listenable: notes,
                  builder: (context, _) => TransportControls(
                    noteAnchorsMs: [for (final n in notes.notes) ?n.positionMs],
                    isPlaying: state.isPlaying,
                    isWavePlaying: state.isWavePlaying,
                    playMode: state.playMode,
                    clock: clock,
                    durationMs: state.durationMs,
                    labelsUnder: inFocus && focusPose != null ? focusPose.labelsUnderWave : budget.labelsUnderWave,
                    waveVisible: !expandedStage,
                    atEase: atEase,
                    onPlayPause: vm.togglePlayPause,
                    onNext: vm.next,
                    onPrevious: vm.previous,
                    onSetPlayMode: vm.setPlayMode,
                    showPlayModeSplitOnFullCycle: ref.watch(playModeSplitHintProvider),
                    onShowedPlayModeSplit: ref.read(playModeSplitHintProvider.notifier).markShown,
                    onSeek: vm.seekToFraction,
                    pressed: pressed,
                  ),
                ),
              ),
            ),
          ),
        ),
        LayoutId(
          id: _Slot.hero,
          child: _HeroYield(
            heroYield: heroYield,
            height: budget.hero,
            child: _Hero(
              title: track.title ?? '',
              artist: track.artist ?? '',
              onTitleTap: switch (track.albumId) {
                final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, id)),
                null => null,
              },
              onArtistTap: switch (track.artistId) {
                final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openArtist(nav, id, name: track.artist)),
                null => null,
              },
              isPlaying: state.isPlaying,
              pressed: pressed,
              oneLine: budget.heroOneLine,
              formatTags: audioFormatTags(track.audioFormat),
            ),
          ),
        ),
        LayoutId(
          id: _Slot.accessory,
          child: _Accessory(
            controller: controller,
            compact: compact,
            retreat: retreat,
            autoFollow: autoFollow,
            onSkip: vm.skipTo,
            page: page,
            notes: notes,
            clock: clock,
            onWrite: onWrite,
            onBarHeight: onBarHeight,
          ),
        ),
      ],
    );
  }
}

/// Closes the player, then opens a detail page on the app Navigator (the
/// player sits above it; Android NowPlayingScreen onAlbumClick /
/// onArtistClick / onPlaylistClick). The chrome's back entry detaches in a
/// microtask scheduled by the close, so the push is queued after it.
void _leaveTo(NowPlayingOverlayController controller, void Function(BuildContext navigator) open) {
  controller.close();
  scheduleMicrotask(() {
    final navigator = appNavigatorKey.currentContext;
    if (navigator != null && navigator.mounted) open(navigator);
  });
}

/// The playing song's long-press sheet (cover and pill): Go to album /
/// artist close the player first, Add to playlist runs over it, messages go
/// to the shell's snackbar.
Future<void> showNowPlayingTrackMenu(BuildContext context, NowPlayingOverlayController controller, Track track) {
  final say = ProviderScope.containerOf(context, listen: false).read(playbackControllerProvider.notifier).showMessage;
  return showTrackMenuSheet(
    context,
    track: track,
    nowPlaying: true,
    onGoToAlbum: (id) => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, id)),
    onGoToArtist: (id) => _leaveTo(controller, (nav) => DetailRoutes.openArtist(nav, id, name: track.artist)),
    onAddToPlaylist: (tracks) {
      final nav = appNavigatorKey.currentContext;
      if (nav != null) addToPlaylistFlow(nav, tracks, onMessage: say);
    },
    onMessage: say,
  );
}

/// PLAYING FROM's destination: the album, artist or playlist the play
/// started from; null (not tappable) for none / Favorites.
VoidCallback? _contextTarget(PlaybackContext context, NowPlayingOverlayController controller) => switch (context) {
  AlbumPlaybackContext(:final albumId) => () => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, albumId)),
  ArtistPlaybackContext(:final artistId, :final name) => () => _leaveTo(
    controller,
    (nav) => DetailRoutes.openArtist(nav, artistId, name: name),
  ),
  PlaylistPlaybackContext(:final playlistId) => () => _leaveTo(
    controller,
    (nav) => DetailRoutes.openPlaylist(nav, playlistId),
  ),
  NoPlaybackContext() || FavoritesPlaybackContext() => null,
};

/// The resting cover (16 dp continuous corners, no stroke), flying to the
/// 44 dp docked thumb as the stage expands (radius 16 → 8). Decoded once at
/// the resting size so the flight never reloads it. Song changes cross-fade
/// in CoverSwap on the slow artworkDissolve spring (the DotDissolve sweep is
/// deferred).
class _StageCover extends StatelessWidget {
  const _StageCover({required this.state, required this.detail, required this.requestEdge, this.onLongPress});

  final NowPlayingContent state;
  final Animation<double> detail;
  final double requestEdge;

  /// Long-press: the song sheet for the playing song (showTrackMenuSheet, nowPlaying: true).
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final px = (requestEdge * MediaQuery.devicePixelRatioOf(context)).round();
    final cover = Semantics(
      image: true,
      label: strings.cdAlbumCover,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final edge = constraints.maxWidth;
          return AnimatedBuilder(
            animation: detail,
            builder: (context, _) {
              final radius = lerpDouble(ShapeTokens.nowPlayingCover, 8, _smooth(0, 1, detail.value.clamp(0.0, 1.0)))!;
              final cover = state.track.coverArt;
              if (cover == null) {
                return ClipRSuperellipse(
                  borderRadius: BorderRadius.circular(radius),
                  child: ColoredBox(
                    color: colors.surfaceContainerHighest,
                    child: Center(
                      child: Icon(YoinSymbols.playArrow, size: edge * 0.3, color: colors.onSurfaceVariant),
                    ),
                  ),
                );
              }
              return CoverSwap(
                cover: cover,
                size: edge,
                radius: radius,
                requestSize: px,
                // The pill's decode (in memory): under a cold first load.
                fallbackRequestSize: (pillCoverRequestDp * MediaQuery.devicePixelRatioOf(context)).round(),
                spring: CoverSwap.artworkDissolve,
              );
            },
          );
        },
      ),
    );
    final onLongPress = this.onLongPress;
    if (onLongPress == null) return cover;
    // The right click / two-finger tap opens the same song sheet.
    return GestureDetector(onLongPress: onLongPress, onSecondaryTap: onLongPress, child: cover);
  }
}

/// The hero's horizontal breathing scale (title, artist): held > playing >
/// paused, gentler under reduced motion. The shared flight lands on the
/// same values so the hand-over to the real text doesn't jump.
({double title, double artist}) heroRestScale({required bool playing, bool held = false, bool reduced = false}) => held
    ? (title: reduced ? 1.02 : 1.05, artist: reduced ? 1.015 : 1.04)
    : playing
    ? (title: reduced ? 1.01 : 1.03, artist: reduced ? 1.008 : 1.02)
    : (title: reduced ? 0.99 : 0.97, artist: reduced ? 0.992 : 0.98);

/// Chevron + "PLAYING FROM …". The chevron turns 180° with the drag and 90°
/// with the stage (Expanded: points back).
class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.controller,
    required this.context0,
    this.onContextTap,
    this.onClose,
    this.subline,
    this.trailing = const [],
  });

  final NowPlayingOverlayController controller;
  final PlaybackContext context0;

  /// The chevron's action when it is not the layered back (the two columns
  /// close outright; back steps to the panel).
  final VoidCallback? onClose;

  /// "Title - Artist" under the label where the window is too short for the
  /// two columns' title block.
  final String? subline;

  /// Icons before the panel toggle (the two columns' Queue and Write).
  final List<Widget> trailing;

  /// Opens where the play started (null: not a place).
  final VoidCallback? onContextTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final (kind, name) = switch (context0) {
      AlbumPlaybackContext(:final name) => (strings.fromAlbum, name),
      PlaylistPlaybackContext(:final name) => (strings.fromPlaylist, name),
      ArtistPlaybackContext(:final name) => (strings.fromArtist, name),
      NoPlaybackContext() || FavoritesPlaybackContext() => (strings.fromNowPlaying, null),
    };
    final expanded = controller.stage == NowPlayingStage.expanded;
    return Align(
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        height: math.max(NowPlayingTokens.topBarMin, 48),
        child: Padding(
          padding: const EdgeInsetsDirectional.only(start: 4, end: NowPlayingTokens.columnPadding),
          child: Row(
            children: [
              Semantics(
                button: true,
                label: expanded ? strings.cdBackNowPlaying : strings.cdCloseNowPlaying,
                excludeSemantics: true,
                child: IconButton(
                  tooltip: expanded ? strings.cdBackNowPlaying : strings.cdCloseNowPlaying,
                  onPressed: onClose ?? controller.onChevron,
                  icon: AnimatedBuilder(
                    animation: Listenable.merge([controller.dragDy.animation, controller.detail.animation]),
                    builder: (context, child) => Transform.rotate(
                      angle:
                          math.pi * controller.dismissFraction + math.pi / 2 * controller.detail.value.clamp(0.0, 1.0),
                      child: child,
                    ),
                    child: Icon(
                      YoinSymbols.chevronDown,
                      size: NowPlayingTokens.chevronGlyph,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 4),
              // The docked cover's slot opens with the stage (the cover itself is laid out by the stage).
              AnimatedBuilder(
                animation: controller.detail.animation,
                builder: (context, _) => SizedBox(
                  width:
                      48 * _smooth(0, 1, controller.detail.value.clamp(0.0, 1.0)) +
                      10 * controller.detail.value.clamp(0.0, 1.0),
                ),
              ),
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: onContextTap,
                  child: name == null
                      ? Text(
                          kind,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.72)),
                        )
                      : Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              kind,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.55)),
                            ),
                            Text(
                              name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.labelMedium!.copyWith(color: colors.onSurface),
                            ),
                            if (subline != null)
                              Text(
                                subline!,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant),
                              ),
                          ],
                        ),
                ),
              ),
              ...trailing,
              if (controller.frame.panelFamily) _PanelToggle(controller: controller),
            ],
          ),
        ),
      ),
    );
  }
}

/// The panel's corner button: Full (the enlarged phone on Medium, the two
/// columns on Wide), or back to the side panel (PanelToggleButton).
class _PanelToggle extends StatelessWidget {
  const _PanelToggle({required this.controller});

  final NowPlayingOverlayController controller;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final full = controller.fullscreen;
    return SizedBox.square(
      dimension: NowPlayingPanelTokens.toggleSize,
      child: IconButton.filledTonal(
        padding: EdgeInsets.zero,
        style: IconButton.styleFrom(
          backgroundColor: colors.secondaryContainer,
          foregroundColor: colors.onSecondaryContainer,
        ),
        tooltip: full ? strings.cdBackToPanel : strings.cdFullScreen,
        onPressed: () {
          YoinHaptics.click();
          controller.toggleFullscreen();
        },
        icon: Icon(full ? YoinSymbols.closeFullscreen : YoinSymbols.openInFull, size: 20),
      ),
    );
  }
}

/// Artist over the marquee title, both stretched in X from the start edge
/// by the play state (title 1.05 held / 1.03 playing / 0.97 paused; artist
/// 1.04 / 1.02 / 0.98; reduced motion 1.02 / 1.01 / 0.99 and 1.015 / 1.008 /
/// 0.992) on the Expressive slow spatial spring.
class _Hero extends StatefulWidget {
  const _Hero({
    this.formatTags = const [],
    required this.title,
    required this.artist,
    required this.isPlaying,
    required this.pressed,
    required this.oneLine,
    this.onTitleTap,
    this.onArtistTap,
  });

  /// The format tags beside the artist ("FLAC 16/44.1").
  final List<String> formatTags;

  final String title;
  final String artist;

  /// The title opens the album, the artist line the artist (null: no id).
  final VoidCallback? onTitleTap;
  final VoidCallback? onArtistTap;
  final bool isPlaying;
  final ValueNotifier<TransportKey> pressed;
  final bool oneLine;

  @override
  State<_Hero> createState() => _HeroState();
}

class _HeroState extends State<_Hero> with TickerProviderStateMixin {
  // Mounted at rest (no 1 → 0.97 squeeze while the shared flight lands on it:
  // the flight draws the same rest scale, so the hand-over is seamless).
  late final SpringValue _title = SpringValue(
    vsync: this,
    initial: heroRestScale(playing: widget.isPlaying).title,
    spring: YoinSprings.slowSpatial,
  );
  late final SpringValue _artist = SpringValue(
    vsync: this,
    initial: heroRestScale(playing: widget.isPlaying).artist,
    spring: YoinSprings.slowSpatial,
  );

  @override
  void initState() {
    super.initState();
    widget.pressed.addListener(_retarget);
    WidgetsBinding.instance.addPostFrameCallback((_) => _retarget());
  }

  @override
  void didUpdateWidget(_Hero oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.pressed != widget.pressed) {
      oldWidget.pressed.removeListener(_retarget);
      widget.pressed.addListener(_retarget);
    }
    _retarget();
  }

  NowPlayingOverlayController? _overlay;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The shared flight lands on these springs' live values.
    final overlay = NowPlayingOverlayScope.read(context);
    if (overlay != _overlay) {
      _unregister();
      _overlay = overlay;
      overlay?.flightKeys.heroScale[FlightElement.title] = _title.animation;
      overlay?.flightKeys.heroScale[FlightElement.artist] = _artist.animation;
    }
  }

  void _unregister() {
    final scales = _overlay?.flightKeys.heroScale;
    if (scales == null) return;
    if (scales[FlightElement.title] == _title.animation) scales.remove(FlightElement.title);
    if (scales[FlightElement.artist] == _artist.animation) scales.remove(FlightElement.artist);
  }

  @override
  void dispose() {
    _unregister();
    widget.pressed.removeListener(_retarget);
    _title.dispose();
    _artist.dispose();
    super.dispose();
  }

  void _retarget() {
    if (!mounted) return;
    final reduced = MediaQuery.disableAnimationsOf(context);
    final held = widget.pressed.value != TransportKey.none;
    final playing = widget.isPlaying;
    final spring = reduced ? YoinSprings.standardFastSpatial : YoinSprings.slowSpatial;
    _title.spring = spring;
    _artist.spring = spring;
    final target = heroRestScale(playing: playing, held: held, reduced: reduced);
    _title.animateTo(target.title);
    _artist.animateTo(target.artist);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final artistStyle = text.titleMedium!.copyWith(
      fontSize: text.titleMedium!.fontSize! * 0.9,
      height: (text.titleMedium!.height ?? 1.5) / 0.9,
      color: colors.onSurfaceVariant,
    );
    final titleStyle = text.displaySmall!.copyWith(color: colors.onSurface);
    final artist = GestureDetector(
      onTap: widget.onArtistTap,
      child: FlightSlot(
        element: FlightElement.artist,
        pill: false,
        child: AnimatedBuilder(
          animation: _artist.animation,
          builder: (context, child) =>
              Transform.scale(scaleX: _artist.value, alignment: AlignmentDirectional.centerStart, child: child),
          child: Text(widget.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: artistStyle),
        ),
      ),
    );
    final title = GestureDetector(
      onTap: widget.onTitleTap,
      child: ClipRect(
        child: FlightSlot(
          element: FlightElement.title,
          pill: false,
          child: AnimatedBuilder(
            animation: _title.animation,
            builder: (context, child) =>
                Transform.scale(scaleX: _title.value, alignment: AlignmentDirectional.centerStart, child: child),
            child: Marquee(
              key: ValueKey(widget.title),
              text: widget.title,
              style: titleStyle,
              edgeFade: heroTitleEdgeFade,
              probe: _overlay?.flightKeys.heroTitleMarquee,
            ),
          ),
        ),
      ),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
      child: widget.oneLine
          ? Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Expanded(child: title),
                const SizedBox(width: NowPlayingTokens.heroOneLineGap),
                Flexible(
                  flex: 0,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: MediaQuery.sizeOf(context).width * NowPlayingTokens.heroOneLineArtistShare,
                    ),
                    child: artist,
                  ),
                ),
              ],
            )
          : Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (widget.formatTags.isEmpty)
                  artist
                else
                  Row(
                    children: [
                      Flexible(child: artist),
                      const SizedBox(width: 8),
                      AudioFormatTagRow(
                        tags: widget.formatTags,
                        description: NowPlayingStrings.of(context).formatDescription(widget.formatTags.join(' ')),
                      ),
                    ],
                  ),
                const SizedBox(height: NowPlayingTokens.heroLineGap),
                title,
              ],
            ),
    );
  }
}

/// The hero stepping aside (yield 0 → 1): its slot shrinks, the content
/// keeps its own height (clipped) and fades.
class _HeroYield extends StatelessWidget {
  const _HeroYield({required this.heroYield, required this.height, required this.child});

  final Animation<double> heroYield;
  final double height;
  final Widget child;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: heroYield,
    child: child,
    builder: (context, child) {
      final y = heroYield.value.clamp(0.0, 1.0);
      return IgnorePointer(
        ignoring: y > 0.5,
        child: ClipRect(
          child: OverflowBox(
            alignment: Alignment.topCenter,
            minHeight: height,
            maxHeight: height,
            child: Opacity(opacity: 1 - y, child: child),
          ),
        ),
      );
    },
  );
}

/// The detail pager's two pages side by side (Android HorizontalPager over
/// Lyrics | Note), translated by the shared [page] progress; a horizontal
/// swipe drags it 1:1 and settles with its velocity. The off-screen page
/// takes no input.
class _PagerSurface extends StatelessWidget {
  const _PagerSurface({
    required this.page,
    required this.swipe,
    required this.onDrag,
    required this.onDragEnd,
    required this.lyrics,
    required this.note,
    this.onTap,
  });

  final Animation<double> page;
  final bool swipe;
  final void Function(double dx, double width) onDrag;
  final void Function(double velocity, double width) onDragEnd;
  final Widget lyrics;
  final Widget note;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          onHorizontalDragUpdate: swipe ? (d) => onDrag(d.delta.dx, width) : null,
          onHorizontalDragEnd: swipe ? (d) => onDragEnd(d.velocity.pixelsPerSecond.dx, width) : null,
          onHorizontalDragCancel: swipe ? () => onDragEnd(0, width) : null,
          child: ClipRect(
            child: AnimatedBuilder(
              animation: page,
              builder: (context, _) {
                final p = page.value.clamp(0.0, 1.0);
                return Stack(
                  fit: StackFit.expand,
                  children: [
                    _PagerPage(offset: -p * width, shown: p < 0.999, active: p < 0.5, child: lyrics),
                    _PagerPage(offset: (1 - p) * width, shown: p > 0.001, active: p >= 0.5, child: note),
                  ],
                );
              },
            ),
          ),
        );
      },
    );
  }
}

class _PagerPage extends StatelessWidget {
  const _PagerPage({required this.offset, required this.shown, required this.active, required this.child});

  final double offset;
  final bool shown;
  final bool active;
  final Widget child;

  @override
  Widget build(BuildContext context) => Offstage(
    offstage: !shown,
    child: IgnorePointer(
      ignoring: !active,
      child: ExcludeSemantics(
        excluding: !active,
        child: Transform.translate(offset: Offset(offset, 0), child: child),
      ),
    ),
  );
}

/// The accessory row: Queue and Write at rest; in Expanded the page's tools,
/// paged with the detail pager — the Recenter key under Lyrics, the write
/// bar under Note.
class _Accessory extends StatelessWidget {
  const _Accessory({
    required this.controller,
    required this.compact,
    required this.retreat,
    required this.autoFollow,
    required this.onSkip,
    required this.page,
    required this.notes,
    required this.clock,
    required this.onWrite,
    required this.onBarHeight,
  });

  final NowPlayingOverlayController controller;
  final Animation<double> compact;

  /// Focus: the pills step away (fade and sink).
  final Animation<double> retreat;
  final ValueNotifier<bool> autoFollow;
  final ValueChanged<int> onSkip;
  final Animation<double> page;
  final NowPlayingNotes notes;
  final PlayheadClock clock;
  final VoidCallback onWrite;
  final ValueChanged<double> onBarHeight;

  @override
  Widget build(BuildContext context) {
    final expanded = controller.stage == NowPlayingStage.expanded;
    final rest = controller.stage == NowPlayingStage.compact;
    final pillSlot = NowPlayingTokens.pillHeight + NowPlayingTokens.accessoryAirMin;
    return Stack(
      children: [
        // Focus shrinks the slot under the pills: they keep their place and sink away.
        LayoutBuilder(
          builder: (context, constraints) => OverflowBox(
            alignment: Alignment.topCenter,
            minHeight: 0,
            maxHeight: math.max(constraints.maxHeight, pillSlot),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
              child: Align(
                alignment: AlignmentDirectional.bottomStart,
                child: IgnorePointer(
                  ignoring: !rest,
                  child: FadeTransition(
                    opacity: _Derived([compact, retreat], () => (compact.value * (1 - retreat.value)).clamp(0.0, 1.0)),
                    child: AnimatedBuilder(
                      animation: retreat,
                      builder: (context, child) => Transform.translate(
                        offset: Offset(0, NowPlayingFocusTokens.pillRetreatDrop * retreat.value.clamp(0.0, 1.0)),
                        child: child,
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _QueuePill(
                            onTap: () {
                              YoinHaptics.contextClick();
                              showQueueSheet(context, onSkip: onSkip);
                            },
                          ),
                          const SizedBox(width: NowPlayingTokens.pillGap),
                          _WritePill(
                            onTap: () {
                              YoinHaptics.contextClick();
                              onWrite();
                            },
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
        if (expanded || controller.detail.value > 0.01)
          Positioned.fill(
            child: IgnorePointer(
              ignoring: !expanded,
              child: FadeTransition(
                opacity: _Mapped(compact, (c) => (1 - c).clamp(0.0, 1.0)),
                child: LayoutBuilder(
                  builder: (context, constraints) => AnimatedBuilder(
                    animation: page,
                    builder: (context, _) {
                      final p = page.value.clamp(0.0, 1.0);
                      final width = constraints.maxWidth;
                      return ClipRect(
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            _PagerPage(
                              offset: -p * width,
                              shown: p < 0.999,
                              active: p < 0.5,
                              child: Padding(
                                padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                                child: Align(
                                  alignment: AlignmentDirectional.bottomStart,
                                  child: _RecenterKey(autoFollow: autoFollow),
                                ),
                              ),
                            ),
                            _PagerPage(
                              offset: (1 - p) * width,
                              shown: p > 0.001,
                              active: p >= 0.5,
                              child: Padding(
                                padding: const EdgeInsets.fromLTRB(
                                  NowPlayingTokens.columnPadding,
                                  NowPlayingNoteTokens.barTopGap,
                                  NowPlayingTokens.columnPadding,
                                  0,
                                ),
                                child: NoteWriteBar(notes: notes, clock: clock, onNaturalHeight: onBarHeight),
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// The Write pill beside Queue (Android BottomPills' Write): tonal, the note
/// glyph; opens the Note page expanded with the keyboard.
class _WritePill extends StatelessWidget {
  const _WritePill({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    const icon = NowPlayingTokens.pillHeight * 0.36;
    return Semantics(
      button: true,
      label: strings.pillWrite,
      excludeSemantics: true,
      child: Material(
        color: colors.secondaryContainer,
        shape: const StadiumBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: SizedBox(
            height: NowPlayingTokens.pillHeight,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 13.2),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(YoinSymbols.note, size: icon, color: colors.onSecondaryContainer),
                  const SizedBox(width: 8),
                  Text(
                    strings.pillWrite,
                    style: Theme.of(context).textTheme.labelMedium!.copyWith(color: colors.onSecondaryContainer),
                    maxLines: 1,
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

class _QueuePill extends StatefulWidget {
  const _QueuePill({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_QueuePill> createState() => _QueuePillState();
}

class _QueuePillState extends State<_QueuePill> with TickerProviderStateMixin {
  late final SpringValue _press = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final style = Theme.of(context).textTheme.labelMedium!.copyWith(color: colors.onPrimary);
    final labelWidth = (TextPainter(
      text: TextSpan(text: strings.pillQueue, style: style),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      maxLines: 1,
    )..layout()).width;
    const icon = NowPlayingTokens.pillHeight * 0.36;
    const pad = 13.2;
    return Semantics(
      button: true,
      label: strings.pillQueue,
      excludeSemantics: true,
      child: GestureDetector(
        onTapDown: (_) => _press.animateTo(1),
        onTapUp: (_) => _press.animateTo(0),
        onTapCancel: () => _press.animateTo(0),
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: _press.animation,
          builder: (context, _) => Container(
            height: NowPlayingTokens.pillHeight,
            width: pad * 2 + icon + 8 + labelWidth * (1 + 0.8 * _press.value),
            decoration: ShapeDecoration(color: colors.primary, shape: const StadiumBorder()),
            alignment: Alignment.center,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(YoinSymbols.queue, size: icon, color: colors.onPrimary),
                const SizedBox(width: 8),
                Text(strings.pillQueue, style: style, maxLines: 1),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RecenterKey extends StatelessWidget {
  const _RecenterKey({required this.autoFollow});

  final ValueNotifier<bool> autoFollow;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    return ValueListenableBuilder<bool>(
      valueListenable: autoFollow,
      builder: (context, following, _) => Semantics(
        button: true,
        enabled: !following,
        label: strings.cdReturnCurrentLine,
        excludeSemantics: true,
        child: Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Material(
            color: following ? colors.primaryContainer.withValues(alpha: 0.38) : colors.primaryContainer,
            shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(52 * 0.31)),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: following
                  ? null
                  : () {
                      YoinHaptics.tick();
                      autoFollow.value = true;
                    },
              child: SizedBox.square(
                dimension: 52,
                child: Icon(
                  YoinSymbols.recenter,
                  color: colors.onPrimaryContainer.withValues(alpha: following ? 0.38 : 1),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Wide Full: the two-column player (TabletNP / WidePlayingContent, rest
/// pose). Left, passive: the cover with the rating column at its end, the
/// transport under them, the title set at the bottom. Right, always
/// expanded: the text tabs, the Lyrics | Note pager, the page's tools. The
/// top bar spans both; Queue and Write ride it as icons beside the panel
/// toggle (owner 2026-11-06). No Expanded substate and no drag-dismiss: the
/// chevron closes, back steps to the panel.
class _DualPane extends ConsumerWidget {
  const _DualPane({
    required this.atEase,
    required this.onOfferFocus,
    required this.state,
    required this.controller,
    required this.insets,
    required this.metrics,
    required this.pressed,
    required this.autoFollow,
    required this.notes,
    required this.page,
    required this.pageTarget,
    required this.onSelectPage,
    required this.onPageDrag,
    required this.onPageDragEnd,
    required this.onWrite,
    required this.onBarHeight,
    required this.barHeight,
  });

  final Animation<double> barHeight;

  /// Focus at ease as shown (the transport's buttons step away).
  final Animation<double> atEase;

  /// Tells the controller whether this body's cover can focus.
  final ValueChanged<bool> onOfferFocus;
  final NowPlayingContent state;
  final NowPlayingOverlayController controller;
  final EdgeInsets insets;
  final StageMetrics metrics;
  final ValueNotifier<TransportKey> pressed;
  final ValueNotifier<bool> autoFollow;
  final NowPlayingNotes notes;
  final Animation<double> page;
  final NowPlayingDetailPage pageTarget;
  final void Function(NowPlayingDetailPage page) onSelectPage;
  final void Function(double dx, double width) onPageDrag;
  final void Function(double velocity, double width) onPageDragEnd;
  final VoidCallback onWrite;
  final ValueChanged<double> onBarHeight;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final vm = ref.read(nowPlayingViewModelProvider.notifier);
    final clock = ref.watch(playheadClockProvider);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final track = state.track;
    return Padding(
      padding: EdgeInsets.only(
        left: insets.left + DualPaneTokens.framePadding,
        right: insets.right + DualPaneTokens.framePadding,
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final budget = resolveDualPaneBudget(
            rowWidth: constraints.maxWidth,
            contentHeight: constraints.maxHeight - insets.top,
            metrics: metrics,
            navBottom: insets.bottom,
          );
          onOfferFocus(budget.focusCover != null);
          final inFocus = controller.stage == NowPlayingStage.immersive;
          final restStage = controller.stage == NowPlayingStage.compact;
          final titleInBar = budget.titleForm == DualPaneTitleForm.topBar;
          void openAlbum() {
            final id = track.albumId;
            if (id != null) _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, id));
          }

          void openArtist() {
            final id = track.artistId;
            if (id != null) _leaveTo(controller, (nav) => DetailRoutes.openArtist(nav, id, name: track.artist));
          }

          return Padding(
            padding: EdgeInsets.only(top: insets.top + budget.frameTop, bottom: budget.frameBottom),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  height: DualPaneTokens.topBarHeight,
                  child: Row(
                    children: [
                      Expanded(
                        child: _TopBar(
                          controller: controller,
                          context0: state.context,
                          onContextTap: _contextTarget(state.context, controller),
                          onClose: controller.close,
                          subline: titleInBar ? '${track.title ?? ''} - ${track.artist ?? ''}' : null,
                          trailing: [
                            IconButton(
                              tooltip: strings.queueTitle,
                              onPressed: () {
                                YoinHaptics.contextClick();
                                showQueueSheet(context, onSkip: vm.skipTo);
                              },
                              icon: Icon(YoinSymbols.queue, color: colors.onSurfaceVariant),
                            ),
                            IconButton(
                              tooltip: strings.pillWrite,
                              onPressed: () {
                                YoinHaptics.contextClick();
                                onWrite();
                              },
                              icon: Icon(YoinSymbols.editNote, color: colors.onSurfaceVariant),
                            ),
                            const SizedBox(width: 8),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                SizedBox(height: budget.barGap),
                Expanded(
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // LEFT — passive; focus grows the cover into the stack (TabletNP focus).
                      AnimatedBuilder(
                        animation: controller.focus.animation,
                        builder: (context, _) {
                          final f = budget.focusCover == null ? 0.0 : controller.focus.value.clamp(0.0, 1.0);
                          final slot = lerpDouble(budget.slot, budget.focusSlot, f)!;
                          final cover = lerpDouble(budget.cover, budget.focusCover ?? budget.cover, f)!;
                          return SizedBox(
                            width: slot,
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                SizedBox(
                                  height: cover,
                                  child: Row(
                                    children: [
                                      GestureDetector(
                                        onTap: budget.focusCover != null || inFocus ? controller.toggleFocus : null,
                                        child: FlightSlot(
                                          element: FlightElement.cover,
                                          pill: false,
                                          child: SizedBox.square(
                                            dimension: cover,
                                            child: _StageCover(
                                              state: state,
                                              detail: const AlwaysStoppedAnimation(0),
                                              requestEdge: math.max(budget.cover, budget.focusCover ?? 0),
                                            ),
                                          ),
                                        ),
                                      ),
                                      // The rating column retreats in focus (its slot closes, it fades).
                                      SizedBox(
                                        width: DualPaneTokens.ratingSide * (1 - f),
                                        child: ClipRect(
                                          child: OverflowBox(
                                            alignment: AlignmentDirectional.centerEnd,
                                            minWidth: DualPaneTokens.ratingSide,
                                            maxWidth: DualPaneTokens.ratingSide,
                                            child: IgnorePointer(
                                              ignoring: !restStage,
                                              child: Opacity(
                                                opacity: (1 - f).clamp(0.0, 1.0),
                                                child: Row(
                                                  children: [
                                                    const SizedBox(
                                                      width: DualPaneTokens.ratingSide - DualPaneTokens.ratingColumn,
                                                    ),
                                                    SizedBox(
                                                      width: DualPaneTokens.ratingColumn,
                                                      child: Column(
                                                        children: [
                                                          Expanded(
                                                            child: RatingSlider(
                                                              rating: state.rating,
                                                              songKey: state.songKey,
                                                              onCommit: vm.commitRating,
                                                            ),
                                                          ),
                                                          if (state.canFavorite) ...[
                                                            const SizedBox(
                                                              height: NowPlayingTokens.ratingToHeartGap - 2,
                                                            ),
                                                            FavoriteHeart(
                                                              starred: state.isStarred,
                                                              onToggle: vm.toggleFavorite,
                                                            ),
                                                          ],
                                                          const SizedBox(height: NowPlayingTokens.heartBottomGap - 2),
                                                        ],
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                SizedBox(
                                  height: lerpDouble(budget.coverToTransport, DualPaneTokens.coverToTransportMin, f),
                                ),
                                // The transport pads itself by the column padding: let it
                                // overhang so its controls span exactly the stack.
                                SizedBox(
                                  width: slot,
                                  height: dualPaneTransportHeight(metrics),
                                  child: OverflowBox(
                                    alignment: Alignment.topCenter,
                                    minWidth: slot + 2 * NowPlayingTokens.columnPadding,
                                    maxWidth: slot + 2 * NowPlayingTokens.columnPadding,
                                    child: TransportControls(
                                      noteAnchorsMs: [for (final n in notes.notes) ?n.positionMs],
                                      isPlaying: state.isPlaying,
                                      isWavePlaying: state.isWavePlaying,
                                      playMode: state.playMode,
                                      clock: clock,
                                      durationMs: state.durationMs,
                                      labelsUnder: budget.slot < transportLabelsInlineMinWidth,
                                      waveVisible: true,
                                      atEase: atEase,
                                      onPlayPause: vm.togglePlayPause,
                                      onNext: vm.next,
                                      onPrevious: vm.previous,
                                      onSetPlayMode: vm.setPlayMode,
                                      showPlayModeSplitOnFullCycle: ref.watch(playModeSplitHintProvider),
                                      onShowedPlayModeSplit: ref.read(playModeSplitHintProvider.notifier).markShown,
                                      onSeek: vm.seekToFraction,
                                      pressed: pressed,
                                    ),
                                  ),
                                ),
                                const Spacer(),
                                if (!titleInBar) ...[
                                  SizedBox(height: budget.transportToTitle),
                                  Row(
                                    children: [
                                      Flexible(
                                        child: GestureDetector(
                                          onTap: track.artistId == null ? null : openArtist,
                                          child: Text(
                                            track.artist ?? '',
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: text.titleMedium!.copyWith(color: colors.onSurfaceVariant),
                                          ),
                                        ),
                                      ),
                                      if (audioFormatTags(track.audioFormat).isNotEmpty) ...[
                                        const SizedBox(width: 8),
                                        AudioFormatTagRow(
                                          tags: audioFormatTags(track.audioFormat),
                                          description: strings.formatDescription(
                                            audioFormatTags(track.audioFormat).join(' '),
                                          ),
                                        ),
                                      ],
                                    ],
                                  ),
                                  const SizedBox(height: DualPaneTokens.titleLineGap),
                                  GestureDetector(
                                    onTap: track.albumId == null ? null : openAlbum,
                                    child: budget.titleForm == DualPaneTitleForm.twoLines
                                        ? Text(
                                            track.title ?? '',
                                            maxLines: 2,
                                            overflow: TextOverflow.ellipsis,
                                            style: text.headlineMedium!.copyWith(color: colors.onSurface),
                                          )
                                        : Marquee(
                                            text: track.title ?? '',
                                            style: text.headlineMedium!.copyWith(color: colors.onSurface),
                                          ),
                                  ),
                                ],
                              ],
                            ),
                          );
                        },
                      ),
                      const SizedBox(width: DualPaneTokens.columnGap),
                      // RIGHT — always expanded: tabs, pager, the page's tools.
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            SizedBox(
                              height: NowPlayingTokens.tabRowNominal,
                              child: StageTabs(
                                selected: pageTarget,
                                detail: const AlwaysStoppedAnimation(0),
                                onSelect: onSelectPage,
                              ),
                            ),
                            const SizedBox(height: DualPaneTokens.rightGap),
                            Expanded(
                              child: _PagerSurface(
                                page: page,
                                swipe: true,
                                onDrag: onPageDrag,
                                onDragEnd: onPageDragEnd,
                                lyrics: Padding(
                                  padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                                  child: LyricsView(
                                    mode: LyricsListMode.full,
                                    lyrics: state.lyrics,
                                    songKey: state.songKey,
                                    clock: clock,
                                    onSeek: vm.seekTo,
                                    title: track.title,
                                    artist: track.artist,
                                    autoFollow: autoFollow,
                                    windowTop:
                                        insets.top +
                                        budget.frameTop +
                                        DualPaneTokens.topBarHeight +
                                        budget.barGap +
                                        NowPlayingTokens.tabRowNominal +
                                        DualPaneTokens.rightGap,
                                  ),
                                ),
                                note: NotePage(notes: notes, clock: clock),
                              ),
                            ),
                            const SizedBox(height: DualPaneTokens.rightGap),
                            _DualTools(
                              page: page,
                              autoFollow: autoFollow,
                              notes: notes,
                              clock: clock,
                              onBarHeight: onBarHeight,
                              barHeight: barHeight,
                              bottom: insets.bottom,
                            ),
                          ],
                        ),
                      ),
                    ],
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

/// The right column's foot, paged with the pager: the Recenter key under
/// Lyrics, the write bar under Note.
class _DualTools extends StatelessWidget {
  const _DualTools({
    required this.page,
    required this.autoFollow,
    required this.notes,
    required this.clock,
    required this.onBarHeight,
    required this.barHeight,
    required this.bottom,
  });

  final Animation<double> barHeight;
  final Animation<double> page;
  final ValueNotifier<bool> autoFollow;
  final NowPlayingNotes notes;
  final PlayheadClock clock;
  final ValueChanged<double> onBarHeight;
  final double bottom;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(bottom: bottom),
    child: LayoutBuilder(
      builder: (context, constraints) => AnimatedBuilder(
        animation: Listenable.merge([page, barHeight]),
        builder: (context, _) {
          final p = page.value.clamp(0.0, 1.0);
          final width = constraints.maxWidth;
          // The open write bar grows the slot on the Note page.
          final bar = barHeight.value + NowPlayingNoteTokens.barTopGap;
          final height = DualPaneTokens.accessoryHeight + p * math.max(0.0, bar - DualPaneTokens.accessoryHeight);
          return SizedBox(
            height: height,
            child: ClipRect(
              child: Stack(
                fit: StackFit.expand,
                alignment: AlignmentDirectional.bottomStart,
                children: [
                  _PagerPage(
                    offset: -p * width,
                    shown: p < 0.999,
                    active: p < 0.5,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                      child: Align(
                        alignment: AlignmentDirectional.bottomStart,
                        child: _RecenterKey(autoFollow: autoFollow),
                      ),
                    ),
                  ),
                  _PagerPage(
                    offset: (1 - p) * width,
                    shown: p > 0.001,
                    active: p >= 0.5,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                      child: Align(
                        alignment: AlignmentDirectional.bottomStart,
                        child: NoteWriteBar(notes: notes, clock: clock, onNaturalHeight: onBarHeight),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    ),
  );
}

/// A landscape handset (LandscapeNP, rest pose): cover on the start side
/// with the rating column at its end. Wide windows put header, tabs, a few
/// lyric lines, the transport and the title in the right column; stacked
/// (narrower) windows put the transport and title under the cover and give
/// the right column a full page of lyrics. Queue and Write ride the header.
class _LandscapePane extends ConsumerWidget {
  const _LandscapePane({
    required this.atEase,
    required this.onOfferFocus,
    required this.state,
    required this.controller,
    required this.insets,
    required this.metrics,
    required this.pressed,
    required this.autoFollow,
    required this.notes,
    required this.page,
    required this.pageTarget,
    required this.onSelectPage,
    required this.onPageDrag,
    required this.onPageDragEnd,
    required this.onWrite,
    required this.onBarHeight,
    required this.barHeight,
  });

  final NowPlayingContent state;
  final NowPlayingOverlayController controller;
  final EdgeInsets insets;
  final StageMetrics metrics;
  final ValueNotifier<TransportKey> pressed;
  final ValueNotifier<bool> autoFollow;
  final NowPlayingNotes notes;
  final Animation<double> page;
  final NowPlayingDetailPage pageTarget;
  final void Function(NowPlayingDetailPage page) onSelectPage;
  final void Function(double dx, double width) onPageDrag;
  final void Function(double velocity, double width) onPageDragEnd;
  final VoidCallback onWrite;
  final ValueChanged<double> onBarHeight;
  final Animation<double> barHeight;

  /// Focus at ease as shown (the transport's buttons step away).
  final Animation<double> atEase;

  /// Tells the controller whether this body's cover can focus (always, in landscape).
  final ValueChanged<bool> onOfferFocus;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final vm = ref.read(nowPlayingViewModelProvider.notifier);
    final clock = ref.watch(playheadClockProvider);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final track = state.track;
    // Focus works on every landscape window (the header, tabs and rating step away).
    onOfferFocus(true);
    final inFocus = controller.stage == NowPlayingStage.immersive;
    final restStage = controller.stage == NowPlayingStage.compact;
    // Side insets: the cutout side's inset, at least the column padding.
    final padding = EdgeInsets.fromLTRB(
      math.max(insets.left, NowPlayingTokens.columnPadding),
      insets.top + 8,
      math.max(insets.right, NowPlayingTokens.columnPadding),
      math.max(insets.bottom, 8),
    );

    // [request]: decoded once at the larger of the rest / focus edges.
    Widget cover(double edge, double request) => GestureDetector(
      onTap: controller.toggleFocus,
      child: FlightSlot(
        element: FlightElement.cover,
        pill: false,
        child: SizedBox.square(
          dimension: edge,
          child: _StageCover(state: state, detail: const AlwaysStoppedAnimation(0), requestEdge: request),
        ),
      ),
    );

    // The rating column with its gap; focus fades it and ([closes]) closes its slot.
    Widget ratingColumn(double f, {bool closes = true}) => SizedBox(
      width: (LandscapeTokens.ratingGap + LandscapeTokens.ratingColumn) * (closes ? 1 - f : 1),
      child: ClipRect(
        child: OverflowBox(
          alignment: AlignmentDirectional.centerEnd,
          minWidth: LandscapeTokens.ratingGap + LandscapeTokens.ratingColumn,
          maxWidth: LandscapeTokens.ratingGap + LandscapeTokens.ratingColumn,
          child: IgnorePointer(
            ignoring: !restStage,
            child: Opacity(
              opacity: (1 - f).clamp(0.0, 1.0),
              child: Padding(
                padding: const EdgeInsetsDirectional.only(start: LandscapeTokens.ratingGap),
                child: Column(
                  children: [
                    Expanded(
                      child: RatingSlider(rating: state.rating, songKey: state.songKey, onCommit: vm.commitRating),
                    ),
                    if (state.canFavorite) ...[
                      const SizedBox(height: NowPlayingTokens.ratingToHeartGap - 2),
                      FavoriteHeart(starred: state.isStarred, onToggle: vm.toggleFavorite),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );

    // What steps away in focus: its slot closes from [height] and it fades.
    Widget retreating(double f, double height, Widget child) => SizedBox(
      height: height * (1 - f),
      child: ClipRect(
        child: OverflowBox(
          alignment: Alignment.bottomCenter,
          minHeight: height,
          maxHeight: height,
          child: IgnorePointer(
            ignoring: !restStage,
            child: Opacity(opacity: (1 - f).clamp(0.0, 1.0), child: child),
          ),
        ),
      ),
    );

    Widget transport(double width) => SizedBox(
      width: width,
      height: transportHeightAt(width + 2 * NowPlayingTokens.columnPadding, metrics),
      child: OverflowBox(
        alignment: Alignment.topCenter,
        minWidth: width + 2 * NowPlayingTokens.columnPadding,
        maxWidth: width + 2 * NowPlayingTokens.columnPadding,
        child: TransportControls(
          noteAnchorsMs: [for (final n in notes.notes) ?n.positionMs],
          isPlaying: state.isPlaying,
          isWavePlaying: state.isWavePlaying,
          playMode: state.playMode,
          clock: clock,
          durationMs: state.durationMs,
          labelsUnder: width < transportLabelsInlineMinWidth,
          waveVisible: true,
          atEase: atEase,
          onPlayPause: vm.togglePlayPause,
          onNext: vm.next,
          onPrevious: vm.previous,
          onSetPlayMode: vm.setPlayMode,
          showPlayModeSplitOnFullCycle: ref.watch(playModeSplitHintProvider),
          onShowedPlayModeSplit: ref.read(playModeSplitHintProvider.notifier).markShown,
          onSeek: vm.seekToFraction,
          pressed: pressed,
        ),
      ),
    );

    Widget title() => SizedBox(
      height: LandscapeTokens.titleRow,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Flexible(
            child: GestureDetector(
              onTap: switch (track.albumId) {
                final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, id)),
                null => null,
              },
              child: Marquee(
                text: track.title ?? '',
                style: text.titleLarge!.copyWith(color: colors.onSurface),
              ),
            ),
          ),
          const SizedBox(width: 12),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 200),
            child: GestureDetector(
              onTap: switch (track.artistId) {
                final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openArtist(nav, id, name: track.artist)),
                null => null,
              },
              child: Text(
                track.artist ?? '',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: text.titleSmall!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
          ),
        ],
      ),
    );

    Widget header() => SizedBox(
      height: LandscapeTokens.headerHeight,
      child: OverflowBox(
        alignment: AlignmentDirectional.centerStart,
        maxHeight: 48,
        child: _TopBar(
          controller: controller,
          context0: state.context,
          onContextTap: _contextTarget(state.context, controller),
          onClose: controller.close,
          trailing: [
            IconButton(
              tooltip: strings.queueTitle,
              onPressed: () {
                YoinHaptics.contextClick();
                showQueueSheet(context, onSkip: vm.skipTo);
              },
              icon: Icon(YoinSymbols.queue, color: colors.onSurfaceVariant),
            ),
            IconButton(
              tooltip: strings.pillWrite,
              onPressed: () {
                YoinHaptics.contextClick();
                onWrite();
              },
              icon: Icon(YoinSymbols.editNote, color: colors.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );

    Widget tabs() => SizedBox(
      height: LandscapeTokens.tabRow,
      child: Align(
        alignment: Alignment.centerLeft,
        child: SizedBox(
          height: NowPlayingTokens.tabRowNominal,
          child: StageTabs(selected: pageTarget, detail: const AlwaysStoppedAnimation(0), onSelect: onSelectPage),
        ),
      ),
    );

    Widget pager({required bool full}) => _PagerSurface(
      page: page,
      swipe: true,
      onDrag: onPageDrag,
      onDragEnd: onPageDragEnd,
      lyrics: full
          ? Padding(
              padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
              child: LyricsView(
                mode: LyricsListMode.full,
                lyrics: state.lyrics,
                songKey: state.songKey,
                clock: clock,
                onSeek: vm.seekTo,
                autoFollow: autoFollow,
                windowTop: padding.top + LandscapeTokens.headerHeight + LandscapeTokens.tabRow,
              ),
            )
          : LyricsView(
              mode: LyricsListMode.window,
              lyrics: state.lyrics,
              songKey: state.songKey,
              clock: clock,
              onSeek: vm.seekTo,
            ),
      note: NotePage(notes: notes, clock: clock),
    );

    // The write bar under the Note page (the Lyrics page keeps its Recenter key).
    Widget tools() => _DualTools(
      page: page,
      autoFollow: autoFollow,
      notes: notes,
      clock: clock,
      onBarHeight: onBarHeight,
      barHeight: barHeight,
      bottom: 0,
    );

    // Focus: the focus lyrics (current line on the third row, two above it
    // fading) take the pager's place; the resting pager fades out under it.
    Widget focusLyrics(double f) => Opacity(
      opacity: _smooth(0.15, 1, f),
      child: IgnorePointer(
        ignoring: !inFocus,
        child: LyricsView(
          mode: LyricsListMode.focus,
          lyrics: state.lyrics,
          songKey: state.songKey,
          clock: clock,
          onSeek: vm.seekTo,
          upNext: state.upNext,
          durationMs: state.durationMs,
          linesAbove: 2,
        ),
      ),
    );

    Widget pagerOrFocus({required bool full, required double f}) {
      if (f <= 0.001) return pager(full: full);
      return Stack(
        fit: StackFit.expand,
        children: [
          if (f < 0.999)
            IgnorePointer(
              ignoring: !restStage,
              child: Opacity(
                opacity: (1 - f).clamp(0.0, 1.0),
                child: pager(full: full),
              ),
            ),
          focusLyrics(f),
        ],
      );
    }

    return Padding(
      padding: padding,
      child: LayoutBuilder(
        builder: (context, constraints) => AnimatedBuilder(
          animation: controller.focus.animation,
          builder: (context, _) {
            final l = resolveLandscapeLayout(
              width: constraints.maxWidth,
              height: constraints.maxHeight,
              metrics: metrics,
            );
            final f = controller.focus.value.clamp(0.0, 1.0);
            final request = math.max(l.cover, l.focusCover);
            final coverEdge = l.stacked ? l.cover : lerpDouble(l.cover, l.focusCover, f)!;
            final columnX = lerpDouble(l.columnX, l.focusColumnX, f)!;
            final rightWidth = constraints.maxWidth - columnX;
            final left = l.stacked
                ? SizedBox(
                    width: l.leftWidth,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          height: l.cover,
                          child: Row(children: [cover(l.cover, request), ratingColumn(f, closes: false)]),
                        ),
                        const SizedBox(height: LandscapeTokens.coverToTransport),
                        transport(l.leftWidth),
                        const SizedBox(height: LandscapeTokens.transportToTitle),
                        title(),
                      ],
                    ),
                  )
                : SizedBox(
                    width: coverEdge + (LandscapeTokens.ratingGap + LandscapeTokens.ratingColumn) * (1 - f),
                    height: coverEdge,
                    child: Row(children: [cover(coverEdge, request), ratingColumn(f)]),
                  );
            final right = SizedBox(
              width: rightWidth,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  retreating(f, LandscapeTokens.headerHeight, header()),
                  retreating(f, LandscapeTokens.tabRow, tabs()),
                  Expanded(
                    child: pagerOrFocus(full: l.stacked, f: f),
                  ),
                  if (!l.stacked) ...[
                    // The write bar opens under the Note page only.
                    AnimatedBuilder(
                      animation: page,
                      builder: (context, child) => ClipRect(
                        child: Align(
                          alignment: Alignment.topCenter,
                          heightFactor: (page.value * (1 - f)).clamp(0.0, 1.0),
                          child: child,
                        ),
                      ),
                      child: tools(),
                    ),
                    const SizedBox(height: LandscapeTokens.lyricsToTransport),
                    transport(rightWidth),
                    title(),
                  ] else
                    retreating(f, DualPaneTokens.accessoryHeight, tools()),
                ],
              ),
            );
            return Row(
              crossAxisAlignment: l.stacked ? CrossAxisAlignment.start : CrossAxisAlignment.center,
              children: [
                left,
                const SizedBox(width: LandscapeTokens.columnGap),
                right,
              ],
            );
          },
        ),
      ),
    );
  }
}
