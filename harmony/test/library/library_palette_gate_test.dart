import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/widgets/cover_palette.dart';
import 'package:yoin/library/library_palette_gate.dart';

void main() {
  group('LibraryPaletteGate', () {
    late List<bool> seen;
    ValueNotifier<bool>? gate;

    Widget grid() => MaterialApp(
      home: LibraryPaletteGate(
        child: ListView.builder(
          itemCount: 200,
          itemBuilder: (context, i) {
            if (i == 0) gate = HomePaletteGate.maybeOf(context);
            return SizedBox(height: 80, child: Text('cover $i'));
          },
        ),
      ),
    );

    setUp(() {
      seen = [];
      gate = null;
    });

    testWidgets('should_beOpen_when_theGridRests', (tester) async {
      await tester.pumpWidget(grid());
      expect(gate, isNotNull);
      expect(gate!.value, isTrue);
    });

    testWidgets('should_shutWhileScrollingAndReopenOnRest_when_theGridIsFlung', (tester) async {
      await tester.pumpWidget(grid());
      final g = gate!..addListener(() => seen.add(gate!.value));
      await tester.fling(find.byType(ListView), const Offset(0, -600), 2000);
      // Mid-fling: no cover is read for its colour.
      await tester.pump(const Duration(milliseconds: 50));
      expect(g.value, isFalse);
      await tester.pumpAndSettle();
      expect(g.value, isTrue, reason: 'on rest the covers on screen are read');
      expect(seen.first, isFalse);
      expect(seen.last, isTrue);
    });

    testWidgets('should_readACoverOnlyOnRest_when_itWasBuiltWhileShut', (tester) async {
      final open = ValueNotifier(false);
      addTearDown(open.dispose);
      final cache = _CountingCache();
      await tester.pumpWidget(
        MaterialApp(
          home: HomePaletteGate(
            open: open,
            child: BackdropColorBuilder(
              cacheKey: 'https://covers.test/a',
              image: const AssetImage('none'),
              fallback: Colors.grey,
              cache: cache,
              builder: (context, base, _) => ColoredBox(color: base),
            ),
          ),
        ),
      );
      expect(cache.reads, 0, reason: 'shut: nothing read');
      open.value = true;
      await tester.pump();
      expect(cache.reads, 1, reason: 'the cover built while shut is read once the gate opens, never dropped');
    });
  });

  group('PalettePasses', () {
    test('should_runAtMostTwoAtOnce_when_manyCoversAskTogether', () async {
      final passes = PalettePasses();
      final gates = [for (var i = 0; i < 5; i++) Completer<void>()];
      var running = 0;
      var peak = 0;
      final done = [
        for (final g in gates)
          passes.run(() async {
            running++;
            peak = running > peak ? running : peak;
            await g.future;
            running--;
          }),
      ];
      await Future<void>.delayed(Duration.zero);
      expect(running, 2);
      for (final g in gates) {
        g.complete();
        await Future<void>.delayed(Duration.zero);
      }
      await Future.wait(done);
      expect(peak, 2);
      expect(passes.running, 0);
    });
  });
}

/// Counts colour reads; never resolves an image.
class _CountingCache extends CoverPaletteCache {
  int reads = 0;

  @override
  bool contains(String key) => false;

  @override
  Future<BackdropColors?> colorsFor(String key, ImageProvider image, {ValueChanged<Object>? onLoadFailed}) {
    reads++;
    return Completer<BackdropColors?>().future;
  }
}
