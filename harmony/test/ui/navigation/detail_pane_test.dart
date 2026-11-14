import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/navigation/pane/detail_pane.dart';

final _app = GlobalKey<NavigatorState>();

class _Host extends StatefulWidget {
  const _Host();
  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> with TickerProviderStateMixin {
  late final pane = DetailPaneController(vsync: this);

  @override
  void initState() {
    super.initState();
    // As the shell does: callers outside the shell's subtree find it here.
    DetailPaneController.active = pane;
  }

  @override
  void dispose() {
    pane.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => DetailPaneHost(
    controller: pane,
    shell: Builder(
      builder: (context) => Column(
        children: [
          Text('shell ${YoinWindowInfo.of(context).width.round()}'),
          TextButton(
            onPressed: () => DetailPane.open(context, (_) => const _Page('A')),
            child: const Text('open'),
          ),
        ],
      ),
    ),
  );
}

class _Page extends StatelessWidget {
  const _Page(this.name);
  final String name;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      Text('page $name ${YoinWindowInfo.of(context).width.round()}'),
      TextButton(onPressed: () => Navigator.of(context).maybePop(), child: Text('back $name')),
      TextButton(onPressed: () => DetailPane.open(context, (_) => const _Page('B')), child: Text('next $name')),
    ],
  );
}

Future<_HostState> _pump(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      navigatorKey: _app,
      builder: (context, child) => YoinWindowScope.measured(child: child!),
      home: const Scaffold(body: _Host()),
    ),
  );
  return tester.state<_HostState>(find.byType(_Host));
}

void main() {
  testWidgets('should_openBesideShell_when_wideWindow', (tester) async {
    await _pump(tester, const Size(1280, 800));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text('shell 600'), findsOneWidget);
    expect(find.text('page A 656'), findsOneWidget);
  });

  testWidgets('should_pushInColumnThenClose_when_backTwice', (tester) async {
    final host = await _pump(tester, const Size(1280, 800));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('next A'));
    await tester.pumpAndSettle();
    expect(find.text('page B 656'), findsOneWidget);
    await tester.tap(find.text('back B'));
    await tester.pumpAndSettle();
    expect(find.text('page B 656'), findsNothing);
    await tester.tap(find.text('back A'));
    await tester.pumpAndSettle();
    expect(host.pane.holdsColumn, isFalse);
    expect(find.text('shell 1280'), findsOneWidget);
  });

  testWidgets('should_notOpenColumn_when_narrowWindow', (tester) async {
    await _pump(tester, const Size(800, 1280));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text('shell 800'), findsOneWidget);
    expect(find.textContaining('page A'), findsNothing, reason: 'the caller pushes instead');
  });

  testWidgets('should_handPagesToApp_when_windowNarrows', (tester) async {
    final host = await _pump(tester, const Size(1280, 800));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    host.pane.handOver(_app.currentState!);
    await tester.pumpAndSettle();
    expect(host.pane.holdsColumn, isFalse);
    expect(find.text('page A 1280'), findsOneWidget);
  });

  testWidgets('should_keepColumnEnabled_when_sidePanelNarrowsTheContent', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    // BesideNowPlayingPanel: the content reads 860 (Wide) or 680 (Medium) beside
    // the panel, the window stays 1280 / 1100.
    for (final (window, beside) in [(1280.0, 860.0), (1100.0, 680.0)]) {
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: _app,
          home: NowPlayingHostWindowScope(
            window: YoinWindowInfo(window, 800),
            child: YoinWindowScope(
              info: YoinWindowInfo(beside, 800),
              child: Align(
                alignment: Alignment.topLeft,
                child: SizedBox(width: beside, child: const Scaffold(body: _Host())),
              ),
            ),
          ),
        ),
      );
      final host = tester.state<_HostState>(find.byType(_Host));
      expect(host.pane.enabled, isTrue, reason: 'the panel never turns the column off ($window)');
    }
  });

  testWidgets('should_followLiveWidth_when_contentWidensUnderFixedTier', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final width = ValueNotifier<double>(860);
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: _app,
        home: YoinWindowScope(
          // The tier stays at the panel's rest width while the drag widens the box.
          info: const YoinWindowInfo(860, 800),
          child: ValueListenableBuilder<double>(
            valueListenable: width,
            builder: (context, w, child) => Align(
              alignment: Alignment.topLeft,
              child: SizedBox(width: w, child: child),
            ),
            child: const Scaffold(body: _Host()),
          ),
        ),
      ),
    );
    final host = tester.state<_HostState>(find.byType(_Host));
    Rect shellRect() => tester.getRect(find.byKey(const ValueKey('shell')));
    expect(shellRect().width, 860, reason: 'no column: the shell fills the content');
    width.value = 1000;
    await tester.pump();
    expect(shellRect().width, 1000, reason: 'a panel drag frame: the shell follows 1:1, no window background shows');

    width.value = 860;
    await tester.pump();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(host.pane.holdsColumn, isTrue);
    Rect columnRect() => tester.getRect(find.byKey(ValueKey('column${host.pane.generation}')));
    expect(columnRect().right, closeTo(860, 0.5));
    width.value = 960;
    await tester.pump();
    expect(columnRect().right, closeTo(960, 0.5), reason: 'both columns widen with the drag');
    expect(shellRect().width + 24 + columnRect().width, closeTo(960, 0.5));
    expect(find.textContaining('page A 460'), findsOneWidget, reason: 'tiers read the rest width (860 → 376 | 24 | 460), not the drag');
  });

  testWidgets('should_openInColumnAndCollapseSearch_when_resultOpensFromSearchRoute', (tester) async {
    final host = await _pump(tester, const Size(1280, 800));
    final route = _YieldingRoute();
    _app.currentState!.push(route);
    await tester.pumpAndSettle();
    await tester.tap(find.text('search result'));
    await tester.pumpAndSettle();
    expect(route.handedToColumn, isTrue);
    expect(find.text('search result'), findsNothing, reason: 'search collapsed');
    expect(host.pane.holdsColumn, isTrue, reason: 'the result opened in the column like the grid');
    expect(find.text('page A 656'), findsOneWidget);
  });

  testWidgets('should_keepPushing_when_ordinaryPageIsOverShell', (tester) async {
    await _pump(tester, const Size(1280, 800));
    _app.currentState!.push(
      MaterialPageRoute<void>(
        builder: (context) => TextButton(
          onPressed: () => DetailPane.open(context, (_) => const _Page('A')),
          child: const Text('pushed open'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('pushed open'));
    await tester.pumpAndSettle();
    expect(tester.state<_HostState>(find.byType(_Host, skipOffstage: false)).pane.holdsColumn, isFalse);
  });
}

class _YieldingRoute extends PageRouteBuilder<void> with DetailPaneYieldingRoute<void> {
  _YieldingRoute()
    : super(
        opaque: false,
        pageBuilder: (context, _, _) => Material(
          child: Center(
            child: TextButton(
              onPressed: () => DetailPane.open(context, (_) => const _Page('A')),
              child: const Text('search result'),
            ),
          ),
        ),
      );
}
