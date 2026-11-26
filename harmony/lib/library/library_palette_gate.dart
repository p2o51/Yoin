import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../home/widgets/cover_palette.dart';

/// Library's palette gate, Home's rule (Android libraryColorGate, PR #343): covers under [child] are read
/// for their colour only while the scrollable directly inside rests, so a fling never decodes and
/// quantises covers; when it comes to rest the gate reopens and the covers on screen are read once (a
/// [BackdropColorBuilder] built while it was shut asks then: nothing is dropped). Open until the first
/// scroll.
class LibraryPaletteGate extends StatefulWidget {
  const LibraryPaletteGate({super.key, required this.child});

  final Widget child;

  @override
  State<LibraryPaletteGate> createState() => _LibraryPaletteGateState();
}

class _LibraryPaletteGateState extends State<LibraryPaletteGate> {
  final ValueNotifier<bool> _open = ValueNotifier(true);
  bool _scrolling = false;

  bool _onScroll(ScrollNotification n) {
    if (n.depth != 0) return false;
    if (n is ScrollStartNotification) {
      _scrolling = true;
      _open.value = false;
    } else if (n is ScrollEndNotification) {
      _scrolling = false;
      // A scroll can end inside a layout: the gate then opens after the frame.
      if (SchedulerBinding.instance.schedulerPhase != SchedulerPhase.persistentCallbacks) {
        _open.value = true;
      } else {
        SchedulerBinding.instance.addPostFrameCallback((_) {
          if (mounted && !_scrolling) _open.value = true;
        });
      }
    }
    return false;
  }

  @override
  void dispose() {
    _open.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => NotificationListener<ScrollNotification>(
    onNotification: _onScroll,
    child: HomePaletteGate(open: _open, child: widget.child),
  );
}
