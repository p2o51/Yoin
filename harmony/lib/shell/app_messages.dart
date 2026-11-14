import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../playback/playback_controller.dart';

/// Shows [text] on the window's one snackbar (the shell's YoinSnackbar host,
/// Android postMessage → YoinNavHost's YoinSnackbarHost): the same pill, the
/// same place (above the bar, or at the bottom while the bar is away), one at
/// a time. Feature code never shows Material's own SnackBar.
void showAppMessage(BuildContext context, String text) =>
    ProviderScope.containerOf(context, listen: false).read(playbackControllerProvider.notifier).showMessage(text);

/// How far the snackbar showing now reaches above the bar's band, in logical
/// pixels (0 when none shows). Floating things that sit on the same baseline —
/// Library's New playlist FAB — rise by this, as Android's Scaffold lifts its
/// FAB over a snackbar. Written by the shell's snackbar host only.
class SnackbarLift extends Notifier<double> {
  @override
  double build() => 0;

  void set(double lift) {
    if (ref.mounted && state != lift) state = lift;
  }
}

final snackbarLiftProvider = NotifierProvider<SnackbarLift, double>(SnackbarLift.new);

/// Reports [child]'s laid-out height after each layout (0 while not
/// [active]) — the snackbar host's measure for [snackbarLiftProvider]. The
/// report lands after the frame: providers can't change during layout.
class SnackbarLiftReporter extends SingleChildRenderObjectWidget {
  const SnackbarLiftReporter({super.key, required this.active, required this.onLift, super.child});

  final bool active;
  final ValueChanged<double> onLift;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderSnackbarLiftReporter(active, onLift);

  @override
  void updateRenderObject(BuildContext context, RenderSnackbarLiftReporter renderObject) {
    renderObject
      ..active = active
      ..onLift = onLift;
  }
}

/// The render object of [SnackbarLiftReporter].
class RenderSnackbarLiftReporter extends RenderProxyBox {
  RenderSnackbarLiftReporter(this._active, this.onLift);

  bool _active;
  set active(bool value) {
    if (value == _active) return;
    _active = value;
    markNeedsLayout();
  }

  ValueChanged<double> onLift;
  double? _reported;

  @override
  void performLayout() {
    super.performLayout();
    final lift = _active ? size.height : 0.0;
    if (lift == _reported) return;
    _reported = lift;
    WidgetsBinding.instance.addPostFrameCallback((_) => onLift(lift));
  }
}
