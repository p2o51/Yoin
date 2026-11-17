import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/memories/memories_pull.dart';
import 'package:yoin/memories/memories_reveal_scope.dart';

class _Host extends ConsumerStatefulWidget {
  const _Host({required this.scroll, required this.onReveal});
  final ScrollController scroll;
  final void Function(MemoriesRevealController) onReveal;

  @override
  ConsumerState<_Host> createState() => _HostState();
}

class _HostState extends ConsumerState<_Host> with TickerProviderStateMixin {
  late final MemoriesRevealController _reveal = MemoriesRevealController(vsync: this);
  late final MemoriesRevealHandle _handle = ref.read(memoriesRevealHandleProvider);

  @override
  void initState() {
    super.initState();
    _handle.attach(_reveal);
    widget.onReveal(_reveal);
  }

  @override
  void dispose() {
    _handle.detach(_reveal);
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MemoriesPullDetector(
    scroll: widget.scroll,
    child: ListView(
      controller: widget.scroll,
      children: [for (var i = 0; i < 40; i++) SizedBox(height: 80, child: Text('Row $i'))],
    ),
  );
}

void main() {
  Future<MemoriesRevealController> pump(WidgetTester tester, ScrollController scroll) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    late MemoriesRevealController reveal;
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(body: _Host(scroll: scroll, onReveal: (r) => reveal = r)),
        ),
      ),
    );
    return reveal;
  }

  Future<void> slowPull(WidgetTester tester, double dy) async {
    final gesture = await tester.startGesture(const Offset(200, 200));
    const step = 10.0;
    for (var moved = 0.0; moved < dy; moved += step) {
      await gesture.moveBy(const Offset(0, step));
      await tester.pump(const Duration(milliseconds: 16));
    }
    // Held still before letting go: no fling, the position decides.
    await tester.pump(const Duration(milliseconds: 200));
    await gesture.up();
    for (var i = 0; i < 60; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
  }

  testWidgets('should_openMemories_when_theFirstPullAtTheTopPassesHalfway', (tester) async {
    final scroll = ScrollController();
    final reveal = await pump(tester, scroll);
    await slowPull(tester, 460);
    expect(reveal.fraction, lessThan(0.01));
  });

  testWidgets('should_openMemories_when_aDragScrollsBackToTheTopAndKeepsPulling', (tester) async {
    final scroll = ScrollController(initialScrollOffset: 60);
    final reveal = await pump(tester, scroll);
    // Not at the top at the touch: the feed scrolls up first, then the same drag pulls.
    await slowPull(tester, 60 + 460);
    expect(scroll.offset, 0);
    expect(reveal.fraction, lessThan(0.01));
  });

  testWidgets('should_stayShut_when_theDragIsSideways', (tester) async {
    final scroll = ScrollController();
    final reveal = await pump(tester, scroll);
    final gesture = await tester.startGesture(const Offset(100, 200));
    for (var i = 0; i < 20; i++) {
      await gesture.moveBy(const Offset(15, 3));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await gesture.up();
    await tester.pump(const Duration(seconds: 1));
    expect(reveal.fraction, 1);
  });
}
