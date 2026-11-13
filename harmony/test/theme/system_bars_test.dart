import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/settings/settings_components.dart';
import 'package:yoin/theme/system_bars.dart';

Widget _page(Brightness brightness, Widget child) => MaterialApp(
  theme: ThemeData(brightness: brightness),
  home: child,
);

SystemUiOverlayStyle _innermostStyle(WidgetTester tester) => tester
    .widgetList<AnnotatedRegion<SystemUiOverlayStyle>>(find.byType(AnnotatedRegion<SystemUiOverlayStyle>))
    .last
    .value;

void main() {
  test('should_useDarkIcons_when_pageIsLight', () {
    expect(yoinStatusBarStyle(Brightness.light).statusBarIconBrightness, Brightness.dark);
    expect(yoinStatusBarStyle(Brightness.dark).statusBarIconBrightness, Brightness.light);
  });

  testWidgets('should_publishDarkIcons_when_settingsBarOnLightTheme', (tester) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _page(
        Brightness.light,
        Scaffold(appBar: SettingsBar(title: 'Settings', controller: controller, titleTop: 0, titleHeight: 40)),
      ),
    );
    expect(_innermostStyle(tester).statusBarIconBrightness, Brightness.dark);
  });

  testWidgets('should_followTheme_when_noPageSetsAStyle', (tester) async {
    await tester.pumpWidget(_page(Brightness.light, const YoinSystemBars(child: SizedBox.expand())));
    expect(_innermostStyle(tester).statusBarIconBrightness, Brightness.dark);
    await tester.pumpWidget(_page(Brightness.dark, const YoinSystemBars(child: SizedBox.expand())));
    await tester.pumpAndSettle();
    expect(_innermostStyle(tester).statusBarIconBrightness, Brightness.light);
  });
}
