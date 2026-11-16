import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Hct;
import 'package:yoin/nowplaying/focus_field.dart';
import 'package:yoin/theme/playback_theme.dart';

// FocusFieldTest.kt (gradient lab round 4, owner 2026-11-03), ported.

int _argb(int r, int g, int b) => 0xFF000000 | (r << 16) | (g << 8) | b;

final int _red = _argb(220, 40, 40);
final int _blue = _argb(40, 60, 220);

/// A [size]² cover: left half red, right half blue (RGBA bytes).
Uint8List _splitCover([int size = 48]) {
  final bytes = Uint8List(size * size * 4);
  for (var y = 0; y < size; y++) {
    for (var x = 0; x < size; x++) {
      final c = x < size / 2 ? _red : _blue;
      final i = (y * size + x) * 4;
      bytes[i] = (c >> 16) & 0xFF;
      bytes[i + 1] = (c >> 8) & 0xFF;
      bytes[i + 2] = c & 0xFF;
      bytes[i + 3] = 255;
    }
  }
  return bytes;
}

CoverField _field() => buildCoverField(_splitCover(), 48, 48);

double _lum(int c) => relativeLuminance(c);

double _dist(FieldPoint p, double x, double y) => math.sqrt((p.x - x) * (p.x - x) + (p.y - y) * (p.y - y));

List<FieldPoint> _points(CoverField field, {bool dark = false, double spread = 1, double t = 0, RoomPalette? room}) =>
    focusFieldPoints(
      field: field,
      dark: dark,
      width: 400,
      height: 800,
      coverCenterX: 200,
      coverCenterY: 300,
      coverSize: 300,
      spread: spread,
      timeMs: t,
      room: room,
    );

void main() {
  test('should_readTheCoverByPosition_when_built', () {
    final field = _field();
    expect(field.at(0.1, 0.5), _red);
    expect(field.at(0.9, 0.5), _blue);
    expect(field.grid, hasLength(CoverField.gridSize * CoverField.gridSize));
  });

  test('should_findBothHuesAndPadThePalette_when_theCoverHasTwo', () {
    final field = _field();
    expect(field.palette, hasLength(4));
    expect(field.argbPalette, containsAll([_red, _blue]));
  });

  test('should_readTheMainHue_when_theCoverHasNoGrid', () {
    final field = CoverField(palette: [Color(_red)], accent: Color(_blue));
    expect(field.at(0.9, 0.9), _red);
  });

  test('should_keepTheHueButPinTheLightness_when_toningForTheRoom', () {
    final (h, _, l) = argbToHsl(toneForRoom(_red, false));
    expect(h, closeTo(0, 2));
    expect(l, inInclusiveRange(lightRoomFloor, lightRoomFloor + lightRoomSpan));
    final (_, _, dl) = argbToHsl(toneForRoom(_argb(250, 250, 250), true));
    expect(dl, lessThanOrEqualTo(darkRoomFloor + darkRoomSpan + 0.001));
  });

  test('should_darkenBrightHues_when_toningForTheDarkRoom', () {
    final lime = toneForRoom(_argb(180, 220, 60), true, 0.12);
    final red = toneForRoom(_argb(200, 40, 40), true);
    final (_, _, limeL) = argbToHsl(lime);
    final (_, _, redL) = argbToHsl(red);
    expect(limeL, lessThan(darkRoomFloor + darkRoomSpan * 0.55 + 0.12));
    expect((lime >> 8) & 0xFF, lessThan(170));
    expect(redL, greaterThan(0.25));
  });

  test('should_openOutFromTheCover_when_focusSpreads', () {
    final field = _field();
    final packed = _points(field, spread: 0);
    final open = _points(field, spread: 1);
    expect(open, hasLength(16));
    for (var i = 0; i < open.length; i++) {
      expect(_dist(packed[i], 200, 300), lessThanOrEqualTo(_dist(open[i], 200, 300) + 0.01));
      expect(packed[i].reach, lessThan(open[i].reach));
    }
    // The wanderer is last and carries the cover's most vivid colour.
    expect(open.last.color, toneForRoom(field.accentArgb, false, -0.02));
  });

  test('should_holdStill_when_timeIsHeld', () {
    final field = _field();
    expect(_points(field, dark: true, t: 5000), _points(field, dark: true, t: 5000));
    expect(_points(field, dark: true, t: 0), isNot(_points(field, dark: true, t: 5000)));
  });

  test('should_paintThePointsColour_when_aVertexSitsOnIt', () {
    final points = [FieldPoint(0, 50, _red, 20, 1), FieldPoint(400, 50, _blue, 20, 1)];
    final out = Int32List(9 * 3);
    renderFocusField(points, _argb(0, 0, 0), 400, 100, 8, 2, out);
    // Vertex (0, 1) sits on the red point, vertex (8, 1) on the blue one.
    expect((out[9] >> 16) & 0xFF, closeTo(220, 25));
    expect(out[17] & 0xFF, closeTo(220, 25));
  });

  test('should_keepTheGeometryAndCrossTheColours_when_theCoverChangesInFocus', () {
    final from = [FieldPoint(10, 20, _red, 5, 1)];
    final to = [FieldPoint(30, 40, _blue, 7, 2)];
    expect(crossFieldColors(from, to, 0), [to.single.withColor(_red)]);
    expect(crossFieldColors(from, to, 1), to);
    final half = crossFieldColors(from, to, 0.5).single;
    expect(half.x, 30);
    expect((half.color >> 16) & 0xFF, closeTo(130, 1));
  });

  test('should_paintOnlyThePalettesColours_when_aRoomPaletteIsGiven', () {
    final field = _field();
    final room = RoomPalette(
      colors: [_argb(30, 40, 90), _argb(60, 30, 70), _argb(20, 70, 60), _argb(40, 40, 50)],
      accent: _argb(90, 80, 160),
    );
    final withRoom = _points(field, dark: true, t: 12000, room: room);
    final without = _points(field, dark: true, t: 12000);
    expect([for (final p in withRoom) (p.x, p.y)], [for (final p in without) (p.x, p.y)]);
    expect(withRoom[6].color, room.colors[0]);
    expect(withRoom[7].color, room.colors[1]);
    expect(withRoom[14].color, room.colors[1]);
    expect(withRoom.last.color, room.accent);
    // The rim creeps between neighbouring palette colours.
    expect(room.around(2), room.colors[2]);
    expect(room.around(4), room.colors[0]);
  });

  test('should_keepWhiteTextReadableButColourful_when_theRoomComesFromABrightDarkScheme', () {
    final room = darkRoomOf(_argb(220, 240, 150), _argb(200, 200, 180), _argb(150, 230, 220));
    for (final c in room.colors) {
      expect(1.05 / (_lum(c) + 0.05), greaterThanOrEqualTo(8));
    }
    expect(1.05 / (_lum(room.accent) + 0.05), greaterThanOrEqualTo(4.5));
    expect(argbToHsl(room.colors[0]).$1, closeTo(75, 20));
    for (final c in room.colors) {
      expect(argbToHsl(c).$2, greaterThan(0.3));
    }
  });

  test('should_startEverywhereAtNothingAndEndEverywhereFull_when_theSongPlaysThrough', () {
    for (final x in [0.0, 100.0, 200.0, 300.0, 400.0]) {
      expect(journeyAt(x, 400, 0, fromRight: true), 0);
      expect(journeyAt(x, 400, 1, fromRight: true), 1);
      expect(journeyAt(x, 400, 1, fromRight: false), 1);
    }
  });

  test('should_enterFromTheSideTheSongCameFrom_when_halfwayThrough', () {
    expect(journeyAt(380, 400, 0.4, fromRight: true), greaterThan(journeyAt(20, 400, 0.4, fromRight: true)));
    expect(journeyAt(20, 400, 0.4, fromRight: false), greaterThan(journeyAt(380, 400, 0.4, fromRight: false)));
    expect(journeyAt(380, 400, 0.4, fromRight: true), closeTo(journeyAt(20, 400, 0.4, fromRight: false), 0.0001));
  });

  test('should_moveTheColoursOnlyTowardTheSecondHue_when_journeying', () {
    final base = [FieldPoint(0, 0, _red, 5, 1), FieldPoint(400, 0, _red, 5, 1)];
    final toward = [for (final p in base) FieldPoint(p.x + 1, p.y, _blue, p.reach, p.weight)];
    expect(journeyFieldColors(base, toward, 400, 0, fromRight: true), base);
    final mid = journeyFieldColors(base, toward, 400, 0.5, fromRight: true);
    expect([for (final p in mid) p.x], [for (final p in base) p.x]);
    expect(mid[1].color & 0xFF, greaterThan(mid[0].color & 0xFF));
    for (final p in journeyFieldColors(base, toward, 400, 1, fromRight: true)) {
      expect(p.color & 0xFF, closeTo(40 + (220 - 40) * journeyMaxMix, 1));
    }
  });

  test('should_journeyToTheMostDifferentHue_when_journeyed', () {
    final peach = _argb(240, 170, 120);
    final apricot = _argb(245, 185, 130);
    final teal = _argb(40, 160, 160);
    final grey = _argb(90, 90, 90);
    final order = journeyOrder([peach, apricot, teal, grey], candidates: 3);
    expect(order, [teal, peach, apricot, grey]);
    final dark = _argb(20, 20, 30);
    final j = journeyedRoom(RoomPalette(colors: [peach, teal, apricot, dark], accent: _red));
    expect(j.colors[0], teal);
    expect(j.colors[3], dark);
    expect(j.accent, _red);
  });

  test('should_sweepInFromTheSideTheSongCameFrom_when_theCoverChangesInFocus', () {
    final from = [
      for (final x in [0.0, 400.0]) FieldPoint(x, 0, _red, 5, 1),
    ];
    final to = [for (final p in from) p.withColor(_blue)];
    final even = crossFieldColors(from, to, 0.5);
    expect(even[0].color, even[1].color);
    final fwd = crossFieldColors(from, to, 0.5, fromRight: true, width: 400);
    expect(fwd[0].color, _red);
    expect(fwd[1].color, _blue);
    final back = crossFieldColors(from, to, 0.5, fromRight: false, width: 400);
    expect(back[0].color, _blue);
    expect(back[1].color, _red);
    expect(crossFieldColors(from, to, 1, fromRight: true, width: 400), to);
  });

  test('should_keepTheLyricReadable_when_theRoomIsTooDeep', () {
    final accent = _argb(103, 80, 164);
    final deep = FieldPoint(0, 0, _argb(230, 120, 170), 5, 1);
    final pale = deep.withColor(_argb(250, 240, 245));
    final out = readableUnder([deep, pale], accent);
    final contrast = (_lum(out[0].color) + 0.05) / (_lum(accent) + 0.05);
    expect(contrast, greaterThanOrEqualTo(focusLyricContrast - 0.01));
    expect(argbToHsl(out[0].color).$1, closeTo(argbToHsl(deep.color).$1, 3));
    expect(out[1], pale);
  });

  test('should_keepTheDarkRoomNearThePrimaryHue_when_theSchemeTurnsItsAccentsAway', () {
    final room = darkRoomOf(_argb(200, 170, 255), _argb(150, 210, 160), _argb(140, 220, 150));
    final primaryHue = Hct.fromInt(_argb(200, 170, 255)).hue;
    for (final c in [...room.colors, room.accent]) {
      final d = (((Hct.fromInt(c).hue - primaryHue + 540) % 360) - 180).abs();
      expect(d, lessThanOrEqualTo(darkRoomMaxHueTurn + 8));
    }
    expect(nearHue(0, 90, 120), closeTo(90, 0.001));
    expect(nearHue(0, 160, 50), closeTo(50, 0.001));
    expect(nearHue(0, 200, 50), closeTo(310, 0.001));
  });

  test('should_takeTheDarkRoomsOtherHuesFromTheCover_when_itHasThem', () {
    final magenta = _argb(255, 160, 220);
    final cyan = _argb(40, 180, 200);
    final grey = _argb(120, 120, 120);
    final room = darkRoomOf(magenta, _argb(150, 210, 160), _argb(140, 220, 150), [magenta, grey, cyan]);
    final cyanHue = Hct.fromInt(cyan).hue;
    expect(Hct.fromInt(room.colors[1]).hue, closeTo(cyanHue, 10));
    final (a, b) = coverSeedHues(Hct.fromInt(magenta).hue, [magenta, grey, cyan]);
    expect(a, closeTo(cyanHue, 0.5));
    expect(b, isNull);
  });

  test('should_paintTheLightRoomPaleInTheCoversOwnHues_when_lightFocus', () {
    final magenta = _argb(180, 40, 120);
    final cyan = _argb(40, 180, 200);
    final room = lightRoomOf(magenta, _argb(60, 140, 80), _argb(50, 150, 90), [magenta, cyan]);
    for (final c in room.colors) {
      expect(_lum(c), greaterThan(0.55));
    }
    expect(Hct.fromInt(room.colors[1]).hue, closeTo(Hct.fromInt(cyan).hue, 10));
  });

  test('should_glideTowardThePlayhead_when_theJourneyStepsOnTheFrameClock', () {
    final journey = JourneyState();
    // The first frame takes the playhead as is, and latches the entry side.
    expect(journey.step(0.02, enterFromRight: false, nowMs: 0), 0.02);
    expect(journey.fromRight, isFalse);
    // A seek: it eases toward the new spot (1.2 s), never jumps.
    final eased = journey.step(0.8, enterFromRight: true, nowMs: 100);
    expect(eased, greaterThan(0.02));
    expect(eased, lessThan(0.8));
    // Past the start, the side holds.
    expect(journey.fromRight, isFalse);
    // A long gap (focus left and came back): the playhead as is.
    expect(journey.step(0.5, enterFromRight: true, nowMs: 5000), 0.5);
  });

  test('should_offerTheCoversChromaticColours_when_pickingSwatches', () {
    final counts = {_red: 300, _blue: 200, _argb(128, 128, 128): 500};
    final swatches = coverSwatches(counts);
    expect(swatches, containsAll([_red, _blue]));
    // The grey is the dominant colour: offered last, for the room to skip.
    expect(swatches.last, _argb(128, 128, 128));
  });
}
