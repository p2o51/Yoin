import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Status-bar icons for content drawn in [brightness]: dark icons over a
/// light page, light icons over a dark one. Only the status-bar fields, as
/// AppBar itself publishes, so the navigation bar is left to the system.
SystemUiOverlayStyle yoinStatusBarStyle(Brightness brightness) {
  final icons = brightness == Brightness.light ? Brightness.dark : Brightness.light;
  return SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: icons,
    // iOS-style field: the brightness of the bar's background, not of its icons.
    statusBarBrightness: brightness,
  );
}

/// The app-wide status-bar style: follows the theme, so whatever a page
/// published (a transparent AppBar reads as a dark bar) is put back as soon
/// as that page is gone. Pages with their own region (AppBar
/// systemOverlayStyle) still win while they are on top.
class YoinSystemBars extends StatelessWidget {
  const YoinSystemBars({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => AnnotatedRegion<SystemUiOverlayStyle>(
    value: yoinStatusBarStyle(Theme.of(context).brightness),
    child: child,
  );
}
