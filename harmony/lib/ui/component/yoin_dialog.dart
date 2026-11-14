import 'package:flutter/material.dart';

import '../../shell/bar_suppression.dart';

/// Every dialog in the app goes through here, never raw [showDialog]: the
/// window's one bar sits above the app navigator, so a dialog's scrim would
/// otherwise leave it bright (and reachable) on top. Like the sheets, the
/// dialog sends the bar away while it is up ([ChromeBarSuppression]) and the
/// bar springs back as it starts to leave.
Future<T?> showYoinDialog<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  bool barrierDismissible = true,
}) => showDialog<T>(
  context: context,
  barrierDismissible: barrierDismissible,
  builder: (dialogContext) => ChromeBarSuppression(child: builder(dialogContext)),
);
