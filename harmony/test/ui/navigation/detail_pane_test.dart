import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
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
}
