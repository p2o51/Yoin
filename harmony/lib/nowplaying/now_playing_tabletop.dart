part of 'now_playing_screen.dart';

typedef _TT = TabletopTokens;

/// Where the kickstand layout splits a [height] tall window: the display
/// ends at the hinge's top, the fold gap stays empty, the deck takes the
/// rest. No hinge reading: a centred 50 / 50 split.
({double display, double gap}) tabletopSplit(double height, Rect? hinge) {
  if (hinge == null) return (display: height / 2, gap: 0);
  final display = hinge.top.clamp(0.0, height);
  final gap = math.max(0.0, math.min(hinge.bottom, height) - display);
  return (display: display, gap: gap);
}

/// The kickstand display's cover: min(width × 0.42 − 32, display − 104), at least 96.
double tabletopCoverSize(double paneWidth, double displayHeight) => math.max(
  _TT.coverMin,
  math.min(paneWidth * _TT.coverWidthShare - _TT.coverWidthInset, displayHeight - _TT.coverHeightInset),
);

/// Kickstand (tabletop) player (NowPlayingScreen.kt TabletopPlayingContent):
/// half open on a HORIZONTAL hinge, the window splits into an upright TOP
/// half (the display: cover, PLAYING FROM, title, artist and a lyrics peek)
/// and a flat BOTTOM half (the control deck: the 72 transport and the
/// Queue / Devices pills). The hinge from [YoinWindowInfo.hingeBounds] puts
/// the split on the crease so nothing sits in the fold. Like the two columns
/// there is no Expanded stage, no focus and no drag-to-dismiss: the chevron
/// and back close it. The deck's expand toggle shifts emphasis from the
/// identity to the lyrics in place — the lyric font grows and fills the
/// column, the title and artist shrink — on ONE slow spatial spring.
class _TabletopPane extends ConsumerStatefulWidget {
  const _TabletopPane({
    required this.state,
    required this.controller,
    required this.insets,
    required this.pressed,
    required this.autoFollow,
    required this.notes,
    required this.onOfferFocus,
  });

  final NowPlayingContent state;
  final NowPlayingOverlayController controller;
  final EdgeInsets insets;
  final ValueNotifier<TransportKey> pressed;
  final ValueNotifier<bool> autoFollow;
  final NowPlayingNotes notes;
  final ValueChanged<bool> onOfferFocus;

  @override
  ConsumerState<_TabletopPane> createState() => _TabletopPaneState();
}

class _TabletopPaneState extends ConsumerState<_TabletopPane> with TickerProviderStateMixin {
  /// Lyrics emphasis: 0 = the identity leads, 1 = the lyrics fill the column (per song).
  late final SpringValue _emphasis = SpringValue(vsync: this, initial: 0, spring: YoinSprings.slowSpatial);
  bool _lyricsExpanded = false;
  String? _song;

  @override
  void dispose() {
    _emphasis.dispose();
    super.dispose();
  }

  void _setExpanded(bool expanded) {
    setState(() => _lyricsExpanded = expanded);
    if (MediaQuery.disableAnimationsOf(context)) {
      _emphasis.snapTo(expanded ? 1 : 0);
    } else {
      _emphasis.animateTo(expanded ? 1 : 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final controller = widget.controller;
    // A new song starts with the identity leading (remember(state.songId)).
    if (_song != state.songKey) {
      if (_song != null && _lyricsExpanded) {
        _lyricsExpanded = false;
        _emphasis.snapTo(0);
      }
      _song = state.songKey;
    }
    // The hinge layout has no focus: the cover takes no tap.
    widget.onOfferFocus(false);
    final vm = ref.read(nowPlayingViewModelProvider.notifier);
    final clock = ref.watch(playheadClockProvider);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final tabletopStrings = TabletopStrings.of(context);
    final track = state.track;
    final hinge = YoinWindowInfo.of(context).hingeBounds;
    final insets = widget.insets;
    final mq = MediaQuery.of(context);

    return LayoutBuilder(
      builder: (context, constraints) {
        final split = tabletopSplit(constraints.maxHeight, hinge);
        final displayWidth = constraints.maxWidth;
        final cover = tabletopCoverSize(displayWidth, split.display);
        // The deck's keys: 72 where the row fits them, the compact 48 on a narrow
        // (flip phone) kickstand, so the transport never overflows.
        final controlSize = fitTransportControlSize(
          rowWidth: displayWidth - insets.left - insets.right - 2 * _TT.deckPaddingH,
          controlSize: _TT.controlSize,
          playKeyWidth: transportPlayKeyMaxWidth(context),
          hasExtraKey: true,
        );

        Widget display() => Stack(
          children: [
            Padding(
              padding: EdgeInsets.fromLTRB(
                insets.left + _TT.displayPaddingH,
                insets.top + _TT.displayPaddingV,
                insets.right + _TT.displayPaddingH,
                _TT.displayPaddingV,
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Padding(
                    padding: const EdgeInsets.all(_TT.coverAir),
                    child: SizedBox.square(
                      dimension: cover,
                      // No shared element here (a posture swap resizes the window mid-flight).
                      child: _StageCover(
                        state: state,
                        detail: const AlwaysStoppedAnimation(0),
                        requestEdge: cover,
                        onTap: null,
                        onLongPress: () => _coverMenu(context, controller, track),
                      ),
                    ),
                  ),
                  const SizedBox(width: _TT.coverToText),
                  Expanded(
                    child: AnimatedBuilder(
                      animation: _emphasis.animation,
                      builder: (context, _) {
                        final e = _emphasis.value;
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _TabletopPlayingFrom(controller: controller, context0: state.context),
                            const SizedBox(height: _TT.eyebrowGap),
                            // Pinned to the designed geometry: the fixed display doesn't scale the
                            // title with the user's font size (the lyrics do; they scroll).
                            GestureDetector(
                              onTap: switch (track.albumId) {
                                final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, id)),
                                null => null,
                              },
                              child: Text(
                                track.title ?? '',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                textScaler: TextScaler.noScaling,
                                style: text.headlineSmall!.copyWith(
                                  fontSize: _TT.titleSize - _TT.titleShrink * e,
                                  color: colors.onSurface,
                                ),
                              ),
                            ),
                            const SizedBox(height: _TT.titleToArtist),
                            Row(
                              children: [
                                Flexible(
                                  child: GestureDetector(
                                    onTap: switch (track.artistId) {
                                      final id? => () => _leaveTo(
                                        controller,
                                        (nav) => DetailRoutes.openArtist(nav, id, name: track.artist),
                                      ),
                                      null => null,
                                    },
                                    child: Text(
                                      track.artist ?? '',
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      textScaler: TextScaler.noScaling,
                                      style: text.titleMedium!.copyWith(
                                        fontSize: _TT.artistSize - _TT.artistShrink * e,
                                        color: colors.onSurfaceVariant,
                                      ),
                                    ),
                                  ),
                                ),
                                // What actually arrives (transcode, download, decoder), as the hero shows.
                                const NowPlayingFormatTags(),
                              ],
                            ),
                            const SizedBox(height: _TT.artistToLyrics),
                            // Collapsed: a short peek centred in the free space. Expanded: a
                            // bigger font that fills it. Height and font size ride the emphasis.
                            Expanded(
                              child: LayoutBuilder(
                                builder: (context, box) => Center(
                                  child: SizedBox(
                                    height: lerpDouble(math.min(_TT.collapsedLyrics, box.maxHeight), box.maxHeight, e),
                                    child: MediaQuery(
                                      data: mq.copyWith(
                                        textScaler: TextScaler.linear(
                                          mq.textScaler.scale(1) * (_TT.lyricScale + _TT.lyricScaleGrowth * e),
                                        ),
                                      ),
                                      child: LyricsView(
                                        mode: LyricsListMode.full,
                                        showTranslation: state.showTranslation,
                                        lyrics: state.lyrics,
                                        songKey: state.songKey,
                                        clock: clock,
                                        onSeek: _lineSeek(context, vm),
                                        autoFollow: widget.autoFollow,
                                        // The column already holds the side padding.
                                        horizontalInset: -NowPlayingTokens.columnPadding,
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
            Positioned(
              top: insets.top + _TT.closeInset,
              right: insets.right + _TT.closeInset,
              child: IconButton(
                key: const ValueKey('np-tabletop-close'),
                tooltip: tabletopStrings.cdClose,
                onPressed: controller.close,
                icon: AnimatedBuilder(
                  animation: controller.dragDy.animation,
                  builder: (context, child) =>
                      Transform.rotate(angle: math.pi * controller.dismissFraction, child: child),
                  child: Icon(YoinSymbols.chevronDown, color: colors.onSurfaceVariant),
                ),
              ),
            ),
          ],
        );

        Widget deck() => Padding(
          padding: EdgeInsets.fromLTRB(
            insets.left + _TT.deckPaddingH - NowPlayingTokens.columnPadding,
            _TT.deckPaddingV,
            insets.right + _TT.deckPaddingH - NowPlayingTokens.columnPadding,
            insets.bottom + _TT.deckPaddingV,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.end,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TransportControls(
                noteAnchorsMs: [for (final n in widget.notes.notes) ?n.positionMs],
                isPlaying: state.isPlaying,
                isWavePlaying: state.isWavePlaying,
                playMode: state.playMode,
                clock: clock,
                durationMs: state.durationMs,
                labelsUnder: false,
                waveVisible: true,
                controlSize: controlSize,
                beforePlayMode: _ExpandLyricsKey(
                  expanded: _lyricsExpanded,
                  size: controlSize,
                  label: _lyricsExpanded ? tabletopStrings.cdCollapseLyrics : tabletopStrings.cdExpandLyrics,
                  onTap: () {
                    YoinHaptics.tick();
                    _setExpanded(!_lyricsExpanded);
                  },
                ),
                onPlayPause: vm.togglePlayPause,
                onNext: vm.next,
                onPrevious: vm.previous,
                onSetPlayMode: vm.setPlayMode,
                showPlayModeSplitOnFullCycle: ref.watch(playModeSplitHintProvider),
                onShowedPlayModeSplit: ref.read(playModeSplitHintProvider.notifier).markShown,
                onSeek: _barSeek(context, vm),
                pressed: widget.pressed,
              ),
              const SizedBox(height: _TT.controlsToPills),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
                child: SizedBox(
                  height: _TT.pillHeight,
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: _PillsRow(onSkip: vm.skipTo, onWrite: () {}, showWrite: false),
                  ),
                ),
              ),
            ],
          ),
        );

        return Column(
          children: [
            SizedBox(height: split.display, child: display()),
            SizedBox(height: split.gap),
            Expanded(child: deck()),
          ],
        );
      },
    );
  }
}

/// "PLAYING FROM (kind)" over the place's name (PlayingFromLabel): the hinge
/// layout has no top bar of its own, so the eyebrow heads the display.
class _TabletopPlayingFrom extends StatelessWidget {
  const _TabletopPlayingFrom({required this.controller, required this.context0});

  final NowPlayingOverlayController controller;
  final PlaybackContext context0;

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
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _contextTarget(context0, controller),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            kind,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: text.labelSmall!.copyWith(
              color: colors.onSurfaceVariant.withValues(alpha: name == null ? 0.72 : 0.55),
            ),
          ),
          if (name != null)
            Text(
              name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: text.labelMedium!.copyWith(color: colors.onSurface),
            ),
        ],
      ),
    );
  }
}

/// The deck's expand-lyrics key (PlaybackControls onExpandLyrics): a round
/// filled key, tertiaryContainer at rest and primary while the lyrics lead,
/// its colours on the effects spring; UnfoldMore / UnfoldLess.
class _ExpandLyricsKey extends StatefulWidget {
  const _ExpandLyricsKey({required this.expanded, required this.size, required this.label, required this.onTap});

  final bool expanded;
  final double size;
  final String label;
  final VoidCallback onTap;

  @override
  State<_ExpandLyricsKey> createState() => _ExpandLyricsKeyState();
}

class _ExpandLyricsKeyState extends State<_ExpandLyricsKey> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.expanded ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_ExpandLyricsKey oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (MediaQuery.disableAnimationsOf(context)) {
      _on.snapTo(widget.expanded ? 1 : 0);
    } else {
      _on.animateTo(widget.expanded ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: _on.animation,
      builder: (context, _) {
        final t = _on.value.clamp(0.0, 1.0);
        final container = Color.lerp(colors.tertiaryContainer, colors.primary, t)!;
        final content = Color.lerp(colors.onTertiaryContainer, colors.onPrimary, t)!;
        return SizedBox.square(
          key: const ValueKey('np-tabletop-expand-lyrics'),
          dimension: widget.size,
          child: ExpressiveSurfaceButton(
            shapes: const InteractionShapes.fixed(CornerRadii.full),
            height: widget.size,
            color: container,
            contentColor: content,
            semanticLabel: widget.label,
            onTap: widget.onTap,
            child: Icon(
              widget.expanded ? YoinSymbols.unfoldLess : YoinSymbols.unfoldMore,
              size: widget.size * 0.5,
              color: content,
            ),
          ),
        );
      },
    );
  }
}

/// A posture swap into or out of the hinge layout (fold ⇄ half fold): the
/// bodies cross-fade on the fast effects spring while the incoming one
/// scales up from 0.88 and the outgoing one down to it on the Expressive
/// spring (AnimatedContent "nowPlayingPosture"; no shared elements — the
/// hinge resizes the window mid-measure). Other swaps are left to their bodies.
class _PostureSwap extends StatefulWidget {
  const _PostureSwap({required this.tabletop, required this.child});

  final bool tabletop;
  final Widget child;

  @override
  State<_PostureSwap> createState() => _PostureSwapState();
}

class _PostureSwapState extends State<_PostureSwap> with TickerProviderStateMixin {
  /// 0 → 1 as the incoming body arrives.
  late final SpringValue _fade = SpringValue(vsync: this, initial: 1, spring: YoinSprings.fastEffects);
  late final SpringValue _scale = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultSpatial);
  Widget? _outgoing;
  bool _outgoingTabletop = false;

  @override
  void initState() {
    super.initState();
    _fade.animation.addStatusListener(_onStatus);
  }

  @override
  void didUpdateWidget(_PostureSwap oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.tabletop == widget.tabletop) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      _outgoing = null;
      return;
    }
    _outgoing = oldWidget.child;
    _outgoingTabletop = oldWidget.tabletop;
    _fade.snapTo(0);
    _fade.animateTo(1);
    _scale.snapTo(0);
    _scale.animateTo(1);
  }

  void _onStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed && _outgoing != null && _fade.value >= 0.999) {
      setState(() => _outgoing = null);
    }
  }

  @override
  void dispose() {
    _fade.animation.removeStatusListener(_onStatus);
    _fade.dispose();
    _scale.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final outgoing = _outgoing;
    return AnimatedBuilder(
      animation: Listenable.merge([_fade.animation, _scale.animation]),
      builder: (context, _) {
        final f = _fade.value.clamp(0.0, 1.0);
        final s = _scale.value;
        const from = _TT.postureSwapScale;
        // The same wrapper either way, keyed by posture: a body keeps its state from incoming to outgoing.
        Widget body(
          bool tabletop,
          Widget child, {
          required double opacity,
          required double scale,
          bool outgoing = false,
        }) => KeyedSubtree(
          key: ValueKey(tabletop),
          child: IgnorePointer(
            ignoring: outgoing,
            child: Opacity(
              opacity: opacity,
              child: Transform.scale(scale: scale, child: child),
            ),
          ),
        );
        return Stack(
          fit: StackFit.expand,
          children: [
            if (outgoing != null && f < 0.999)
              body(_outgoingTabletop, outgoing, opacity: 1 - f, scale: 1 + (from - 1) * s, outgoing: true),
            body(
              widget.tabletop,
              widget.child,
              opacity: outgoing == null ? 1 : f,
              scale: outgoing == null ? 1 : from + (1 - from) * s,
            ),
          ],
        );
      },
    );
  }
}
