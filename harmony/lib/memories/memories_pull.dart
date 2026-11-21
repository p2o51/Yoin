/// Home's pull-down into Memories (Android HomeScreen's nested-scroll
/// onPreScroll feeding the shell's RevealState): with the feed at its top, a
/// downward drag moves q 1:1 with the finger, and the release springs to the
/// closer end (or the fling's) with the finger's velocity. While the pull
/// owns the gesture the feed holds still at its top.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../nowplaying/haptics.dart';
import 'memories_reveal_scope.dart';

class MemoriesPullDetector extends ConsumerStatefulWidget {
  const MemoriesPullDetector({super.key, required this.scroll, required this.child, this.enabled = true});

  final ScrollController scroll;
  final Widget child;

  /// Off while Home can't open Memories (edit mode, no account).
  final bool enabled;

  @override
  ConsumerState<MemoriesPullDetector> createState() => _MemoriesPullDetectorState();
}

class _MemoriesPullDetectorState extends ConsumerState<MemoriesPullDetector> {
  int? _pointer;
  Offset _travel = Offset.zero;
  bool _vertical = false;
  bool _pulling = false;
  bool _decided = false;
  bool _crossed = false;
  VelocityTracker _velocity = VelocityTracker.withKind(PointerDeviceKind.touch);

  bool get _atTop => !widget.scroll.hasClients || widget.scroll.position.pixels <= 0.5;

  void _down(PointerDownEvent e) {
    if (_pointer != null || !widget.enabled) return;
    final handle = ref.read(memoriesRevealHandleProvider);
    if (handle.isPullSuppressed(e.pointer)) return;
    final reveal = handle.controller;
    if (reveal == null || reveal.ownsBack) return;
    _pointer = e.pointer;
    // A fresh tracker per touch: no sample from the last gesture leaks into this release.
    _velocity = VelocityTracker.withKind(e.kind);
    _travel = Offset.zero;
    _vertical = false;
    _pulling = false;
    _decided = false;
    _crossed = false;
    _velocity.addPosition(e.timeStamp, e.position);
  }

  void _move(PointerMoveEvent e) {
    if (e.pointer != _pointer || !mounted) return;
    final handle = ref.read(memoriesRevealHandleProvider);
    final reveal = handle.controller;
    final height = context.size?.height ?? 800;
    if (handle.isPullSuppressed(e.pointer)) {
      _pointer = null;
      if (_pulling) {
        _pulling = false;
        reveal?.settle(0, height);
      }
      return;
    }
    _velocity.addPosition(e.timeStamp, e.position);
    if (reveal == null) return;
    if (!_decided) {
      _travel += e.delta;
      if (_travel.distance < kTouchSlop) return;
      _decided = true;
      // A sideways drag (a shelf, a pager) is never the pull.
      _vertical = _travel.dy.abs() > _travel.dx.abs();
      // Down from the very top: the pull takes it, the slop included.
      if (_vertical && _travel.dy > 0 && _atTop) {
        _pulling = true;
        reveal.dragBy(_travel.dy, height);
        return;
      }
    }
    // Like Android's onPreScroll: any downward move with the feed at its top
    // starts the pull, also a drag that scrolled the feed back up to it.
    if (!_pulling && _vertical && e.delta.dy > 0 && _atTop) _pulling = true;
    if (!_pulling) return;
    // The feed lets go of this drag and holds at its top.
    if (widget.scroll.hasClients && widget.scroll.position.pixels != 0) widget.scroll.jumpTo(0);
    reveal.dragBy(e.delta.dy, height);
    final past = reveal.fraction < 0.5;
    if (past != _crossed) {
      _crossed = past;
      YoinHaptics.tick();
    }
  }

  void _up(PointerEvent e) {
    if (mounted) ref.read(memoriesRevealHandleProvider).releasePullForPointer(e.pointer);
    if (e.pointer != _pointer) return;
    _pointer = null;
    if (!mounted) return;
    if (!_pulling) return;
    _pulling = false;
    final reveal = ref.read(memoriesRevealHandleProvider).controller;
    final height = context.size?.height ?? 800;
    reveal?.settle(_velocity.getVelocity().pixelsPerSecond.dy, height);
  }

  @override
  Widget build(BuildContext context) => Listener(
    onPointerDown: _down,
    onPointerMove: _move,
    onPointerUp: _up,
    onPointerCancel: _up,
    child: NotificationListener<OverscrollIndicatorNotification>(
      // No stretch at the top: the pull is Memories'.
      onNotification: (n) {
        if (n.leading) n.disallowIndicator();
        return false;
      },
      child: widget.child,
    ),
  );
}
