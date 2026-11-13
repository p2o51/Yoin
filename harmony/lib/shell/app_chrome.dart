import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../nowplaying/flight.dart';
import '../nowplaying/now_playing_overlay.dart';
import '../nowplaying/now_playing_pill.dart';
import '../nowplaying/overlay_controller.dart';
import '../nowplaying/player_snapshot.dart';
import '../playback/system_requests.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'bar_suppression.dart';
import 'chrome_back.dart';
import 'detail_chrome.dart';
import 'shell_state.dart';
import 'yoin_bottom_bar.dart';

/// The window's chrome, above the Navigator (adaptive-principles §2: one
/// window, one bar, one Now Playing chain): every route — shell or detail —
/// sits under the same bar, and the Now Playing overlay sits above all.
///
/// Layers, bottom to top: the Navigator [child], the floating bar, the Now
/// Playing overlay (scrim + player), the pill ⇄ player flight. Owns the one
/// [NowPlayingOverlayController]: the pill, the chevron, the drag and system
/// back (through [ChromeBackEntry] while open) all feed it. Its own
/// [Overlay] gives bar widgets tooltips/menus.
class AppChrome extends ConsumerStatefulWidget {
  const AppChrome({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<AppChrome> createState() => _AppChromeState();
}

class _AppChromeState extends ConsumerState<AppChrome> with TickerProviderStateMixin {
  late final NowPlayingOverlayController _controller = NowPlayingOverlayController(vsync: this);
  late final ChromeBackEntry _back = ChromeBackEntry(() => _controller.handleBack());
  late final OverlayEntry _entry = OverlayEntry(builder: _buildLayers);

  /// 0 = the bar may show, 1 = a bar-less page (setup, search) sent it away.
  late final OverlaySpring _barAway = OverlaySpring(vsync: this, initial: 0);

  @override
  void initState() {
    super.initState();
    _controller.addListener(_syncBack);
  }

  @override
  void didUpdateWidget(AppChrome oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.child != widget.child) _entry.markNeedsBuild();
  }

  @override
  void dispose() {
    _controller.removeListener(_syncBack);
    _back.dispose();
    _barAway.dispose();
    _controller.dispose();
    // _entry lives and dies with this chrome's own Overlay (already unmounted here).
    super.dispose();
  }

  /// Back belongs to the overlay exactly while it is open.
  void _syncBack() {
    final navigator = appNavigatorKey.currentState;
    if (_controller.expanded && navigator != null) {
      _back.attach(navigator);
    } else if (!_controller.expanded) {
      // Not synchronously: a close can come from inside the route's own
      // pop-entry loop (system back), which must not change under it.
      scheduleMicrotask(() {
        if (!_controller.expanded) _back.detach();
      });
    }
  }

  /// An open request that came before the queue (card tap while the queue
  /// is still being rebuilt from the native host) waits until then.
  DateTime? _pendingOpenUntil;

  void _open() {
    if (ref.read(playerSnapshotProvider).hasTrack) {
      _pendingOpenUntil = null;
      _controller.openPlayer();
    } else {
      _pendingOpenUntil = DateTime.now().add(nowPlayingOpenRequestTtl);
    }
  }

  void _onHasTrack(bool has) {
    if (!has) {
      // Queue emptied while open (explicit clear): close (spec item 18).
      _controller.close();
      return;
    }
    final until = _pendingOpenUntil;
    _pendingOpenUntil = null;
    if (until != null && DateTime.now().isBefore(until)) _controller.openPlayer();
  }

  Widget _buildLayers(BuildContext context) => NowPlayingOverlayScope(
    controller: _controller,
    child: Stack(
      fit: StackFit.expand,
      children: [
        widget.child,
        Align(
          alignment: Alignment.bottomCenter,
          child: _ChromeBar(controller: _controller, away: _barAway),
        ),
        NowPlayingLayer(controller: _controller),
        _Flight(controller: _controller),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    ref.listen(nowPlayingOpenRequestsProvider, (_, _) => _open());
    ref.listen(playerSnapshotProvider.select((s) => s.hasTrack), (_, has) => _onHasTrack(has));
    ref.listen(barSuppressedProvider, (_, away) {
      if (MediaQuery.disableAnimationsOf(context)) {
        _barAway.snapTo(away ? 1 : 0);
      } else {
        _barAway.animateTo(away ? 1 : 0, YoinSprings.standardDefaultSpatial);
      }
    });
    return Overlay(initialEntries: [_entry]);
  }
}

class _ChromeBar extends ConsumerWidget {
  const _ChromeBar({required this.controller, required this.away});

  final NowPlayingOverlayController controller;

  /// Bar-less pages push the bar out on their own spring; the bar leaves by
  /// whichever of the two is further out.
  final OverlaySpring away;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasTrack = ref.watch(playerSnapshotProvider.select((s) => s.hasTrack));
    final actions = ref.watch(topDetailActionsProvider);
    final navBottom = MediaQuery.paddingOf(context).bottom;
    return AnimatedBuilder(
      animation: Listenable.merge([controller.bar.animation, away.animation]),
      builder: (context, child) {
        // Not composed while the player is fully up (its wave stops ticking)
        // or while a bar-less page has sent it away.
        if (!controller.barComposed || (away.value >= 0.999 && !away.isAnimating)) return const SizedBox.shrink();
        final b = math.max(controller.bar.value, away.value).clamp(0.0, 1.0);
        final travel = BarTokens.height + 2 * BarTokens.portraitVerticalMargin + navBottom;
        return IgnorePointer(
          ignoring: b > 0.01,
          child: Opacity(
            opacity: 1 - b,
            child: Transform.translate(offset: Offset(0, travel * b), child: child),
          ),
        );
      },
      child: SafeArea(
        top: false,
        child: Material(
          type: MaterialType.transparency,
          child: YoinBottomBar(
            selected: ref.watch(shellSectionProvider),
            onSelect: ref.read(shellSectionProvider.notifier).select,
            hasTrack: hasTrack,
            detailActions: actions,
            pillBuilder: (context, onPress) => NowPlayingPill(onPressChanged: onPress),
          ),
        ),
      ),
    );
  }
}

/// The shared flight's content, from the playing track and the live theme.
class _Flight extends ConsumerWidget {
  const _Flight({required this.controller});

  final NowPlayingOverlayController controller;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final track = ref.watch(playerSnapshotProvider.select((s) => s.track));
    final source = ref.watch(activeSourceProvider).value;
    if (track == null) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final cover = track.coverArt;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final url = cover == null ? null : source?.resolveCoverUrl(cover, size: (pillCoverRequestDp * dpr).round());
    controller.coverCanFly = url != null;
    final artistBase = text.titleMedium!;
    return FlightLayer(
      controller: controller,
      content: FlightContent(
        title: track.title ?? '',
        artist: track.artist ?? '',
        coverUrl: url,
        pillTitleStyle: text.labelLarge!.copyWith(color: colors.onPrimaryContainer),
        pillArtistStyle: text.labelSmall!.copyWith(
          color: colors.onPrimaryContainer.withValues(alpha: PillMetrics.artistAlpha),
        ),
        heroTitleStyle: text.displaySmall!.copyWith(color: colors.onSurface),
        heroArtistStyle: artistBase.copyWith(
          fontSize: artistBase.fontSize! * 0.9,
          height: (artistBase.height ?? 1.5) / 0.9,
          color: colors.onSurfaceVariant,
        ),
      ),
    );
  }
}
