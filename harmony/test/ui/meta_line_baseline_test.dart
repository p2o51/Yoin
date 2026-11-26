// Meta lines put every group on one baseline (Android MetaLine / MetaFlow, PR #310): a Chinese run takes
// the CJK fallback's metrics, digits Google Sans Flex's. With HarmonyOS Sans SC on hand (YOIN_CJK_FONT_DIR,
// else ~/Library/Fonts) the two fonts really differ; without it the check still guards the layout.
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/settings/sync/portability_widgets.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/component/meta_line.dart';

const _cjkDir = String.fromEnvironment('YOIN_CJK_FONT_DIR');

Future<void> _load(String family, List<String> files) async {
  final loader = FontLoader(family);
  for (final f in files) {
    final file = File(f);
    if (file.existsSync()) loader.addFont(Future.value(ByteData.sublistView(file.readAsBytesSync())));
  }
  await loader.load();
}

/// Each text's baseline in global coordinates, by its words.
Map<String, double> _baselines(WidgetTester tester) => {
  for (final p in tester.renderObjectList<RenderParagraph>(find.byType(RichText)))
    p.text.toPlainText(): p.localToGlobal(Offset(0, p.getDryBaseline(p.constraints, TextBaseline.alphabetic)!)).dy,
};

Future<void> _pump(WidgetTester tester, Widget child) => tester.pumpWidget(
  MaterialApp(
    theme: yoinTheme(Brightness.light),
    home: Scaffold(
      body: Center(child: SizedBox(width: 400, child: child)),
    ),
  ),
);

void main() {
  setUpAll(() async {
    await _load('GoogleSansFlex', ['assets/fonts/GoogleSansFlex-Variable.ttf']);
    final dir = _cjkDir.isNotEmpty ? _cjkDir : '${Platform.environment['HOME']}/Library/Fonts';
    await _load('HarmonyOS Sans SC', ['$dir/HarmonyOS_Sans_SC_Regular.ttf', '$dir/HarmonyOS_Sans_SC_Medium.ttf']);
  });

  testWidgets('should_putDigitsAndChineseOnOneBaseline_when_metaLineMixesThem', (tester) async {
    for (final style in [null, yoinTextTheme().bodyMedium, yoinTextTheme().labelMedium]) {
      await _pump(
        tester,
        MetaLine(
          style: style,
          groups: const [
            MetaPlain('Joni'),
            MetaKind('专辑'),
            MetaPlain('2023'),
            MetaStat('29', '分钟'),
            MetaKind('今天 10:54'),
          ],
        ),
      );
      final lines = _baselines(tester);
      expect(lines.keys, containsAll(['专辑', '2023', '29', '分钟', '今天 10:54']));
      // And the line keeps its height (a header sized to it must not overflow).
      final used = style ?? yoinTextTheme().bodySmall!;
      expect(
        tester.getSize(find.byType(MetaLine)).height,
        moreOrLessEquals(used.fontSize! * used.height!, epsilon: 0.001),
      );
      for (final entry in lines.entries) {
        expect(
          entry.value,
          moreOrLessEquals(lines['2023']!, epsilon: 0.01),
          reason: '${entry.key} at ${style?.fontSize}',
        );
      }
    }
  });

  testWidgets('should_putEveryItemOnOneBaseline_when_metaFlowMixesThem', (tester) async {
    await _pump(
      tester,
      const MetaFlow(groups: [MetaStat('12', '首'), MetaPlain('2023'), MetaKind('专辑'), MetaStat('3', 'MB')]),
    );
    final lines = _baselines(tester);
    for (final entry in lines.entries) {
      expect(entry.value, moreOrLessEquals(lines['2023']!, epsilon: 0.01), reason: entry.key);
    }
  });
}
