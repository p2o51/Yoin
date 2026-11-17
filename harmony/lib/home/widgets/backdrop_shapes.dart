import 'dart:ui';

import '../../ui/shapes/shape_path.dart';
import '../home_ui_state.dart';

/// The entity backdrop shapes (HomeWidgetGrid / WidgetArt): album
/// MaterialShapes.Bun, song (and the unreached artist) Circle, playlist
/// Ghostish; pressing morphs the backdrop into the rounded Triangle token
/// (PressShapeMorph.kt: `Morph(base, MaterialShapes.Triangle)`).
abstract final class BackdropShapes {
  static RoundedPolygon forKind(HomeCardKind kind) => switch (kind) {
    HomeCardKind.album => MaterialShapes.bun,
    HomeCardKind.song || HomeCardKind.artist => MaterialShapes.circle,
    HomeCardKind.playlist => MaterialShapes.ghostish,
  };

  /// rememberPressMorphShape's `pressed` default.
  static RoundedPolygon get pressed => MaterialShapes.triangle;

  static final Map<RoundedPolygon, Morph> _pressMorphs = {};

  /// The press morph of [kind]'s backdrop, matched once per shape.
  static Morph pressMorph(HomeCardKind kind) {
    final base = forKind(kind);
    return _pressMorphs[base] ??= Morph(base, pressed);
  }

  /// The backdrop of [kind] pressed [t] of the way to the Triangle, filling
  /// [size] (MorphProgressShape: unit square scaled to the box, [t] clamped
  /// to 0–1 as on Android).
  static Path pressedPath(HomeCardKind kind, double t, Size size) =>
      morphOutline(pressMorph(kind), t, Offset.zero & size);
}
