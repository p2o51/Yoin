import 'package:flutter/widgets.dart';

/// For a form field in a scrolling page that makes room for the keyboard
/// (Android Modifier.keepInViewAboveKeyboard): while the field, or anything in
/// it, has focus, keep the whole field — label and box, not only the text line
/// a text field brings into view on its own — in view on every keyboard
/// change. Without it a field low on a short window (a tablet in landscape)
/// ends up partly under the keyboard.
class KeepInViewAboveKeyboard extends StatefulWidget {
  const KeepInViewAboveKeyboard({super.key, required this.child});

  final Widget child;

  @override
  State<KeepInViewAboveKeyboard> createState() => _KeepInViewAboveKeyboardState();
}

class _KeepInViewAboveKeyboardState extends State<KeepInViewAboveKeyboard> with WidgetsBindingObserver {
  bool _focused = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // The keyboard's every frame changes the window's insets; the page has its new height after the next layout.
  @override
  void didChangeMetrics() {
    if (_focused) _bringIntoView();
  }

  void _bringIntoView() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_focused) return;
      final box = context.findRenderObject();
      if (box is RenderBox && box.attached && box.hasSize) box.showOnScreen();
    });
  }

  @override
  Widget build(BuildContext context) => Focus(
    canRequestFocus: false,
    skipTraversal: true,
    onFocusChange: (focused) {
      _focused = focused;
      if (focused) _bringIntoView();
    },
    child: widget.child,
  );
}
