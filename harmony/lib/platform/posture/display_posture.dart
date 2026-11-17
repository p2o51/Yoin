/// The window's posture and the display shapes around it: the fold status, the
/// crease (hinge) and the camera cutout on the window's side edges.
///
/// Android reads these from Jetpack WindowManager (`FoldingFeature`) and
/// `WindowInsets.displayCutout` (WindowAdaptiveRuntime.kt); Flutter's
/// `MediaQuery.displayFeatures` on HarmonyOS reports neither (the embedder
/// leaves folds out and builds its cutouts once, in display coordinates).
///
/// HarmonyOS: `yoin/posture` → ArkTS `YoinPosturePlugin`
/// (ohos/entry/src/main/ets/yoin/posture/): `@ohos.display` `getFoldStatus` /
/// `on('foldStatusChange')`, `getCurrentFoldCreaseRegion`,
/// `getFoldDisplayMode` / `on('foldDisplayModeChange')`, and the main window's
/// `TYPE_CUTOUT` avoid area; every rect converted into the window's logical px.
/// Each change is pushed as `posture`. No permission.
///
/// Windows: [NoDisplayPostureHost] (flat, no crease, no cutout).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `display.FoldStatus` (single-axis values; a tri-fold's second axis folds into these).
enum FoldStatus {
  unknown,
  expanded,
  folded,
  halfFolded;

  /// From `display.FoldStatus`: 1 expanded, 2 folded, 3 half folded. A tri-fold
  /// reports two axes as tens·second + first (second axis: 1 expanded, 2 half
  /// folded; first axis: 1 / 2 / 3 as above), e.g. 13 = HALF_FOLDED_WITH_SECOND_EXPANDED,
  /// 21 = EXPANDED_WITH_SECOND_HALF_FOLDED. Either axis half folded makes the
  /// shown display half folded (the crease region then says which crease);
  /// otherwise the first axis decides.
  static FoldStatus fromCode(int code) => switch (code) {
    1 || 11 => FoldStatus.expanded,
    2 || 12 => FoldStatus.folded,
    3 || 13 || 21 || 22 || 23 => FoldStatus.halfFolded,
    _ => FoldStatus.unknown,
  };
}

/// `display.FoldDisplayMode`: which panel shows (inner full, main, sub / outer, both).
enum FoldDisplayMode {
  unknown,
  full,
  main,
  sub,
  coordination;

  static FoldDisplayMode fromCode(int code) =>
      code >= 0 && code < FoldDisplayMode.values.length ? FoldDisplayMode.values[code] : FoldDisplayMode.unknown;
}

/// A camera cutout on one side edge of the window, in window logical px: its
/// vertical extent and how far it reaches in from the edge.
@immutable
final class SideCutout {
  const SideCutout({required this.top, required this.bottom, required this.inset});

  final double top;
  final double bottom;
  final double inset;

  @override
  bool operator ==(Object other) =>
      other is SideCutout && other.top == top && other.bottom == bottom && other.inset == inset;

  @override
  int get hashCode => Object.hash(top, bottom, inset);

  @override
  String toString() => 'SideCutout($top..$bottom, inset $inset)';
}

/// One reading of the window's posture.
@immutable
final class DisplayPosture {
  const DisplayPosture({
    this.foldStatus = FoldStatus.unknown,
    this.foldDisplayMode = FoldDisplayMode.unknown,
    this.creaseRects = const [],
    this.leftCutout,
    this.rightCutout,
    this.foldable,
  });

  /// A flat window with no crease and no cutout (Windows, tests, a phone).
  static const DisplayPosture flat = DisplayPosture();

  factory DisplayPosture.fromMap(Map<Object?, Object?> map) {
    Rect? rect(Object? raw) {
      if (raw is! Map) return null;
      double read(String key) => (raw[key] as num?)?.toDouble() ?? 0;
      final r = Rect.fromLTWH(read('left'), read('top'), read('width'), read('height'));
      return r.width <= 0 || r.height <= 0 ? null : r;
    }

    SideCutout? side(Object? raw) => switch (rect(raw)) {
      final r? => SideCutout(top: r.top, bottom: r.bottom, inset: r.width),
      null => null,
    };
    final creases = map['creaseRects'];
    return DisplayPosture(
      foldStatus: FoldStatus.fromCode((map['foldStatus'] as num?)?.toInt() ?? 0),
      foldDisplayMode: FoldDisplayMode.fromCode((map['foldDisplayMode'] as num?)?.toInt() ?? 0),
      creaseRects: [
        if (creases is List)
          for (final c in creases) ?rect(c),
      ],
      leftCutout: side(map['leftCutout']),
      rightCutout: side(map['rightCutout']),
      foldable: map['foldable'] as bool?,
    );
  }

  final FoldStatus foldStatus;
  final FoldDisplayMode foldDisplayMode;

  /// Whether this device folds at all (`display.isFoldable()`); null when the host doesn't say.
  final bool? foldable;

  /// The crease in window logical px (empty: none, or not in this window).
  final List<Rect> creaseRects;

  final SideCutout? leftCutout;
  final SideCutout? rightCutout;

  /// The hinge when the window sits half folded on a HORIZONTAL crease (the
  /// fold line runs left to right, splitting the window top / bottom): the
  /// kickstand posture Android calls Tabletop (a separating horizontal
  /// FoldingFeature in HALF_OPENED). Null otherwise: flat, folded, a vertical
  /// (book) crease, or a crease outside a [windowSize] window.
  Rect? tabletopHinge(Size windowSize) {
    if (foldStatus != FoldStatus.halfFolded) return null;
    for (final crease in creaseRects) {
      if (crease.width <= crease.height) continue;
      // The crease must cross this window, not lie beside it (a split-screen half).
      if (crease.bottom <= 0 || crease.top >= windowSize.height) continue;
      if (crease.right <= 0 || crease.left >= windowSize.width) continue;
      return crease;
    }
    return null;
  }

  @override
  bool operator ==(Object other) =>
      other is DisplayPosture &&
      other.foldStatus == foldStatus &&
      other.foldDisplayMode == foldDisplayMode &&
      listEquals(other.creaseRects, creaseRects) &&
      other.leftCutout == leftCutout &&
      other.rightCutout == rightCutout &&
      other.foldable == foldable;

  @override
  int get hashCode =>
      Object.hash(foldStatus, foldDisplayMode, Object.hashAll(creaseRects), leftCutout, rightCutout, foldable);

  @override
  String toString() =>
      'DisplayPosture($foldStatus, $foldDisplayMode, creases $creaseRects, left $leftCutout, right $rightCutout)';
}

abstract interface class DisplayPostureHost {
  /// The posture now, then every change. Never errors; flat where there is nothing to read.
  Stream<DisplayPosture> watch();
}

/// HarmonyOS: the `yoin/posture` channel.
final class ChannelDisplayPostureHost implements DisplayPostureHost {
  ChannelDisplayPostureHost({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel {
    _controller = StreamController<DisplayPosture>.broadcast(onListen: _start);
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'posture' && call.arguments is Map) {
        _push(DisplayPosture.fromMap(call.arguments as Map<Object?, Object?>));
      }
    });
  }

  static const channelName = 'yoin/posture';

  final MethodChannel _channel;
  late final StreamController<DisplayPosture> _controller;
  DisplayPosture? _last;

  void _push(DisplayPosture posture) {
    if (posture == _last) return;
    _last = posture;
    _controller.add(posture);
  }

  Future<void> _start() async {
    final last = _last;
    if (last != null) _controller.add(last);
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('read');
      if (raw != null) _push(DisplayPosture.fromMap(raw));
    } on MissingPluginException {
      _push(DisplayPosture.flat);
    } on PlatformException {
      _push(DisplayPosture.flat);
    }
  }

  @override
  Stream<DisplayPosture> watch() => _controller.stream;
}

/// No posture to read (Windows, tests): always flat.
final class NoDisplayPostureHost implements DisplayPostureHost {
  const NoDisplayPostureHost();

  @override
  Stream<DisplayPosture> watch() => Stream.value(DisplayPosture.flat);
}

/// The platform's posture host; main.dart wires the platform's, tests a fake.
final displayPostureHostProvider = Provider<DisplayPostureHost>((ref) => const NoDisplayPostureHost());

/// The live posture (flat until the first reading).
final displayPostureProvider = StreamProvider<DisplayPosture>((ref) => ref.watch(displayPostureHostProvider).watch());
