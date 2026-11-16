/// The bar's Home edit pose (Android ui/component/BarEditPose.kt): while Home
/// is being edited the bar's two destinations become `[Undo|Add] [Done]`,
/// riding the idle path (two labelled halves, the pill folded). The shell
/// builds it from Home's edit controller; the bar only reads it.
library;

import 'package:flutter/foundation.dart';

/// What the bar's left (Home) slot offers while Home is being edited.
enum BarEditLeftSlot {
  /// Something to undo this session.
  undo,

  /// Nothing to undo, but hidden sections to bring back: scrolls to the tray.
  add,

  /// Neither: a dimmed, inert Undo.
  undoDisabled,
}

/// Undo wins while the session has history; otherwise Add while the tray has rows.
BarEditLeftSlot resolveEditLeftSlot(int undoDepth, int trayCount) {
  if (undoDepth > 0) return BarEditLeftSlot.undo;
  if (trayCount > 0) return BarEditLeftSlot.add;
  return BarEditLeftSlot.undoDisabled;
}

/// Home → [Undo|Add] and Library → Done cross-fade over the middle of the pose.
double barEditSwap(double edit) {
  final t = ((edit - BarEditPose.swapStart) / (BarEditPose.swapEnd - BarEditPose.swapStart)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

@immutable
final class BarEditPose {
  const BarEditPose({required this.editing, required this.leftSlot, required this.onLeftSlot, required this.onDone});

  static const double swapStart = 0.35;
  static const double swapEnd = 0.65;

  /// The dimmed Undo's content alpha.
  static const double disabledAlpha = 0.38;

  /// Home is being edited (the bar springs its pose toward it).
  final bool editing;
  final BarEditLeftSlot leftSlot;

  /// The controller resolves Undo, Add or nothing (disabled).
  final VoidCallback onLeftSlot;
  final VoidCallback onDone;
}
