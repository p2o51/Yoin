import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/replay_gain.dart';
import 'package:yoin/settings/settings_pane.dart';
import 'package:yoin/settings/settings_strings.dart';
import 'package:yoin/settings/volume_leveling_screen.dart';

void main() {
  late InMemoryStore store;

  setUp(() => store = InMemoryStore());

  Widget app(Widget home, {Locale? locale}) => ProviderScope(
    overrides: [keyValueStoreProvider.overrideWithValue(store)],
    child: MaterialApp(
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: const [DefaultWidgetsLocalizations.delegate, DefaultMaterialLocalizations.delegate],
      home: home,
    ),
  );

  Future<void> settle(WidgetTester tester) async {
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pumpAndSettle();
  }

  testWidgets('should_showThreeModesWithAlbumChosen_when_nothingStored', (tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(app(const VolumeLevelingScreen()));
    await settle(tester);

    expect(find.text('Volume leveling'), findsWidgets);
    expect(find.text('Off'), findsOneWidget);
    expect(find.text('By track'), findsOneWidget);
    expect(find.text('By album'), findsOneWidget);
    final group = tester.widget<RadioGroup<ReplayGainMode>>(find.byType(RadioGroup<ReplayGainMode>));
    expect(group.groupValue, ReplayGainMode.album);
  });

  testWidgets('should_saveTheChoice_when_rowTapped', (tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(app(const VolumeLevelingScreen()));
    await settle(tester);

    await tester.tap(find.text('By track'));
    await settle(tester);

    expect(await tester.runAsync(() => store.read(ReplayGainStore.key)), 'track');
    final group = tester.widget<RadioGroup<ReplayGainMode>>(find.byType(RadioGroup<ReplayGainMode>));
    expect(group.groupValue, ReplayGainMode.track);
  });

  testWidgets('should_summariseTheChoice_when_rowShown', (tester) async {
    await tester.runAsync(() => store.write(ReplayGainStore.key, 'off'));
    await tester.pumpWidget(app(const Material(child: VolumeLevelingRow(profileId: 'p1'))));
    await settle(tester);
    expect(find.text('Volume leveling'), findsOneWidget);
    expect(find.text('Off'), findsOneWidget);
  });

  testWidgets('should_openThePage_when_rowTapped', (tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(app(const Material(child: VolumeLevelingRow(profileId: 'p1'))));
    await settle(tester);
    await tester.tap(find.text('Volume leveling'));
    await settle(tester);
    expect(find.byType(VolumeLevelingScreen), findsOneWidget);
  });

  testWidgets('should_openInThePane_when_wideWindowListDetail', (tester) async {
    SettingsPane? opened;
    await tester.pumpWidget(
      app(
        Material(
          child: SettingsPaneScope(
            open: (pane) => opened = pane,
            child: const VolumeLevelingRow(profileId: 'p1'),
          ),
        ),
      ),
    );
    await settle(tester);
    await tester.tap(find.text('Volume leveling'));
    await settle(tester);
    expect(opened, const VolumeLevelingPane('p1'));
    expect(opened!.parent, const AccountPane('p1'), reason: 'back returns to the account page');
    expect(find.byType(VolumeLevelingScreen), findsNothing);
  });

  test('should_speakChinese_when_localeIsZh', () {
    const zh = SettingsStrings.forLanguage(chinese: true);
    expect(zh.volumeLevelingTitle, '音量平衡');
    expect(volumeLevelingModeLabel(zh, ReplayGainMode.off), '关');
    expect(volumeLevelingModeLabel(zh, ReplayGainMode.track), '按曲目');
    expect(volumeLevelingModeLabel(zh, ReplayGainMode.album), '按专辑');
  });
}
