import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/cover_cache.dart';
import 'package:yoin/ui/component/artwork_image.dart';
import 'package:yoin/ui/component/artwork_retry.dart';
import 'package:yoin/ui/component/artwork_swap.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

const _url = 'https://covers.example/a.png';

Future<Uint8List> _pngBytes() async {
  final recorder = ui.PictureRecorder();
  Canvas(recorder).drawRect(const Rect.fromLTWH(0, 0, 4, 4), Paint()..color = const Color(0xFF3366CC));
  final image = await recorder.endRecording().toImage(4, 4);
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  image.dispose();
  return data!.buffer.asUint8List();
}

Widget _host(String? url) => MaterialApp(
  home: Center(
    child: SizedBox.square(
      dimension: 96,
      child: ArtworkContent(url: url, fallback: const ArtworkFallbackIcon(YoinSymbols.album)),
    ),
  ),
);

/// Lets the fake fetch, the real decode and the retry callbacks land.
Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 4; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump();
  }
}

void main() {
  late Uint8List png;
  late List<int> responses;
  var fetches = 0;

  setUp(() {
    fetches = 0;
    responses = [];
    imageCache.clear();
    ArtworkImages.install(
      CoverDiskCache(
        fetch: (_) async {
          final code = fetches < responses.length ? responses[fetches] : 200;
          fetches++;
          return code == 200
              ? CoverResponse(statusCode: 200, bytes: png, contentType: 'image/png')
              : CoverResponse(statusCode: code, bytes: Uint8List(0));
        },
      ),
    );
  });

  tearDown(() {
    imageCache.clear();
    ArtworkImages.debugReset();
  });

  testWidgets('should_showTypeIcon_when_noUrl', (tester) async {
    await tester.pumpWidget(_host(null));
    expect(find.byIcon(YoinSymbols.album), findsOneWidget);
    expect(find.byType(ArtworkSwap), findsNothing);
    expect(fetches, 0);
  });

  testWidgets('should_keepIconAndAskAgainOnSignal_when_coverFails', (tester) async {
    png = (await tester.runAsync(_pngBytes))!;
    responses = [404, 200];
    await tester.pumpWidget(_host(_url));
    expect(find.byIcon(YoinSymbols.album), findsNothing, reason: 'a url with no failure yet shows no icon');
    await _settle(tester);
    expect(fetches, 1);
    expect(find.byIcon(YoinSymbols.album), findsOneWidget, reason: 'a failed cover shows the type icon');
    expect(find.byType(ArtworkSwap), findsNothing);

    // A 404 waits for a signal, not a timer.
    await tester.pump(const Duration(minutes: 2));
    expect(fetches, 1);

    ArtworkRetrySignal.bump();
    await tester.pump();
    await _settle(tester);
    expect(fetches, 2, reason: 'the signal asked again');
    expect(find.byType(ArtworkSwap), findsOneWidget);
    // The icon stays under the recovered cover until it has revealed over it.
    await tester.pumpAndSettle();
    expect(find.byIcon(YoinSymbols.album), findsNothing);
  });

  testWidgets('should_retryOnBackoff_when_serverErrors', (tester) async {
    png = (await tester.runAsync(_pngBytes))!;
    responses = [503, 200];
    await tester.pumpWidget(_host(_url));
    await _settle(tester);
    expect(fetches, 1);
    await tester.pump(ArtworkRetryPolicy.backoff.first);
    await _settle(tester);
    expect(fetches, 2);
    await tester.pumpAndSettle();
    expect(find.byIcon(YoinSymbols.album), findsNothing);
  });

  testWidgets('should_revealOverIcon_when_urlArrivesLate', (tester) async {
    png = (await tester.runAsync(_pngBytes))!;
    await tester.pumpWidget(_host(null));
    await tester.pumpWidget(_host(_url));
    await _settle(tester);
    expect(find.byIcon(YoinSymbols.album), findsOneWidget, reason: 'held under the reveal');
    await tester.pumpAndSettle();
    expect(find.byIcon(YoinSymbols.album), findsNothing);
  });

  testWidgets('should_finishRevealing_when_skipReturnsBeforeNextCoverLoads', (tester) async {
    png = (await tester.runAsync(_pngBytes))!;
    final never = Completer<CoverResponse>();
    ArtworkImages.install(
      CoverDiskCache(
        fetch: (uri) async => uri.path.endsWith('c.png')
            ? never.future
            : CoverResponse(statusCode: 200, bytes: png, contentType: 'image/png'),
      ),
    );
    var revealed = 0;
    Widget swap(String url) => MaterialApp(
      home: Center(
        child: SizedBox.square(
          dimension: 96,
          child: ArtworkSwap(url: url, onRevealed: () => revealed++),
        ),
      ),
    );
    await tester.pumpWidget(swap('https://covers.example/a.png'));
    await _settle(tester);
    final first = revealed;
    expect(first, greaterThan(0), reason: 'the first cover snaps in');

    await tester.pumpWidget(swap('https://covers.example/b.png'));
    await _settle(tester);
    await tester.pump(const Duration(milliseconds: 30));
    // A skip to a cover that never loads freezes b half-revealed ...
    await tester.pumpWidget(swap('https://covers.example/c.png'));
    await tester.pump(const Duration(seconds: 2));
    expect(revealed, first, reason: 'b is frozen and c never came');
    // ... and skipping back to b lets it finish.
    await tester.pumpWidget(swap('https://covers.example/b.png'));
    await tester.pumpAndSettle();
    expect(revealed, greaterThan(first));
  });

  testWidgets('should_dropStoredCoverAndAskAgain_when_pixelsFailToDecode', (tester) async {
    png = (await tester.runAsync(_pngBytes))!;
    final dir = (await tester.runAsync(() => Directory.systemTemp.createTemp('cover_frame')))!;
    addTearDown(() => dir.delete(recursive: true));
    final cache = CoverDiskCache(
      directory: () async => dir,
      budgetBytes: 1 << 20,
      fetch: (_) async => CoverResponse(statusCode: 200, bytes: png, contentType: 'image/png'),
    );
    final provider = CachedArtworkImage(_url, width: 4);
    Object? failure;
    await tester.runAsync(() async {
      // Opened on real time: its folder reads would wait on fake time otherwise.
      ArtworkImages.install(cache);
      // The codec is made, but its first frame fails (a header that parses
      // over pixels that don't).
      final completer = provider.loadImage(provider, (buffer, {getTargetSize}) async => _BrokenCodec());
      final done = Completer<void>();
      imageCache.putIfAbsent(provider, () => completer);
      completer.addListener(
        ImageStreamListener(
          (_, _) => done.complete(),
          onError: (error, _) {
            failure = error;
            done.complete();
          },
        ),
      );
      await done.future.timeout(const Duration(seconds: 20));
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    expect(failure, isNotNull);
    expect(cache.contains(_url), isFalse, reason: 'bytes that made no image leave the disk cache');
    expect(imageCache.containsKey(provider), isFalse, reason: 'and the image cache, so a retry asks again');
  });

  test('should_shareOneImage_when_sameCoverSignedWithAnotherSalt', () {
    final a = CachedArtworkImage('https://m.example/rest/getCoverArt.view?u=ana&t=1&s=a&id=9', width: 64);
    final b = CachedArtworkImage('https://m.example/rest/getCoverArt.view?u=ana&t=2&s=b&id=9', width: 64);
    expect(a, b, reason: 'one in-memory image whatever salt signed it');
    expect(a, isNot(CachedArtworkImage(a.url, width: 128)));
  });
}

class _BrokenCodec implements ui.Codec {
  @override
  int get frameCount => 1;

  @override
  int get repetitionCount => 0;

  @override
  Future<ui.FrameInfo> getNextFrame() => Future.error(Exception('Codec failed to produce an image'));

  @override
  void dispose() {}
}
