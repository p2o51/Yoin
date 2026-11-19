import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/model/model.dart';

void main() {
  group('LrcParser', () {
    test('should_applyPositiveOffsetAndClampAtZero_when_offsetTagPresent', () {
      final parsed = LrcParser.parse('''
[offset:+500]
[00:00.20]Opening
[00:10.00]Main verse
''');
      expect(parsed, isA<SyncedLyrics>());
      final lines = (parsed as SyncedLyrics).lines;
      expect(lines.map((l) => (l.startMs, l.text)), [
        (0, 'Opening'),
        (9500, 'Main verse'),
      ]);
    });

    test('should_delayLines_when_negativeOffsetTagPresent', () {
      final parsed = LrcParser.parse('''
[offset:-750]
[00:01.00]Delayed line
''');
      final lines = (parsed as SyncedLyrics).lines;
      expect(lines.single.startMs, 1750);
      expect(lines.single.text, 'Delayed line');
    });

    test('should_stripEnhancedWordTimestamps_when_presentInLine', () {
      final parsed = LrcParser.parse('[00:05.00]<00:05.00>Hello <00:05.45>world <00:06.100>today');
      final lines = (parsed as SyncedLyrics).lines;
      expect(lines.single.startMs, 5000);
      expect(lines.single.text, 'Hello world today');
    });

    test('should_preservePrecedingWords_when_inlineTimestampAppearsMidLine', () {
      final parsed = LrcParser.parse('[00:12.00][01:12.00]Before [00:13.50]after');
      final lines = (parsed as SyncedLyrics).lines;
      expect(lines.map((l) => (l.startMs, l.text)), [
        (12000, 'Before after'),
        (72000, 'Before after'),
      ]);
    });
  });
}
