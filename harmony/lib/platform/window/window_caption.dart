/// The window caption on 2in1 / free windows (`yoin/window`, ArkTS
/// `YoinWindowHost`): the system title bar is hidden, so every page's own
/// ground runs to the window's top edge and only the system's three buttons
/// float at the top right. The caption's height becomes a top inset like a
/// phone's status bar (headers, the Memories bar and the Home bubble sit under
/// it, clear of the buttons), and that strip is the window's drag handle: drag
/// to move the window, double-tap to maximize or restore.
library;

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

const _channel = MethodChannel('yoin/window');

@immutable
final class WindowCaption {
  const WindowCaption({this.active = false, this.height = 0, this.buttonsRight = 0, this.buttonsWidth = 0});

  static const none = WindowCaption();

  /// The title bar is hidden and the app draws under the buttons.
  final bool active;

  /// The caption strip's height (vp): the top inset.
  final double height;

  /// The three buttons: their right margin and width (vp, from the window's right edge).
  final double buttonsRight;
  final double buttonsWidth;

  static WindowCaption fromMap(Object? raw) {
    if (raw is! Map) return none;
    double d(Object? v) => v is num ? v.toDouble() : 0;
    return WindowCaption(
      active: raw['active'] == true,
      height: d(raw['height']),
      buttonsRight: d(raw['buttonsRight']),
      buttonsWidth: d(raw['buttonsWidth']),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is WindowCaption &&
      other.active == active &&
      other.height == height &&
      other.buttonsRight == buttonsRight &&
      other.buttonsWidth == buttonsWidth;

  @override
  int get hashCode => Object.hash(active, height, buttonsRight, buttonsWidth);
}

/// Wraps the app: pads the top by the caption and lays the drag handle over it.
/// On phones (no caption) it passes [child] through untouched.
class YoinWindowCaption extends StatefulWidget {
  const YoinWindowCaption({super.key, required this.child});

  final Widget child;

  @override
  State<YoinWindowCaption> createState() => _YoinWindowCaptionState();
}

class _YoinWindowCaptionState extends State<YoinWindowCaption> {
  WindowCaption _caption = WindowCaption.none;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'captionChanged') _set(WindowCaption.fromMap(call.arguments));
    });
    unawaited(_read());
  }

  Future<void> _read() async {
    try {
      _set(WindowCaption.fromMap(await _channel.invokeMethod<Object?>('caption')));
    } on MissingPluginException {
      // Tests and hosts without the plugin: no caption.
    } on PlatformException {
      // Same: keep none.
    }
  }

  void _set(WindowCaption caption) {
    if (mounted && caption != _caption) setState(() => _caption = caption);
  }

  @override
  void dispose() {
    _channel.setMethodCallHandler(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final caption = _caption;
    if (!caption.active || caption.height <= 0) return widget.child;
    final media = MediaQuery.of(context);
    double top(double v) => v > caption.height ? v : caption.height;
    return Stack(
      textDirection: TextDirection.ltr,
      children: [
        MediaQuery(
          data: media.copyWith(
            padding: media.padding.copyWith(top: top(media.padding.top)),
            viewPadding: media.viewPadding.copyWith(top: top(media.viewPadding.top)),
          ),
          child: widget.child,
        ),
        // The drag handle: the strip left of the system buttons.
        Positioned(
          left: 0,
          top: 0,
          right: caption.buttonsRight + caption.buttonsWidth + 8,
          height: caption.height,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onPanStart: (_) => unawaited(_invoke('startMoving')),
            onDoubleTap: () => unawaited(_invoke('toggleMaximize')),
          ),
        ),
      ],
    );
  }

  Future<void> _invoke(String method) async {
    try {
      await _channel.invokeMethod<void>(method);
    } on MissingPluginException {
      // No window host.
    } on PlatformException {
      // The system refused (not a free window): nothing to do.
    }
  }
}
