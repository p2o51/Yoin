import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'shell_state.dart';
import 'yoin_bottom_bar.dart';

/// The window's chrome, above the Navigator (adaptive-principles §2: one
/// window, one bar, one Now Playing chain): every route — shell or detail —
/// sits under the same bar, and the Now Playing overlay sits above all.
///
/// Layers, bottom to top: the Navigator [child], the floating bar, the Now
/// Playing overlay (to be built in lib/nowplaying). Its own [Overlay] gives
/// bar widgets tooltips/menus.
class AppChrome extends StatelessWidget {
  const AppChrome({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Overlay(
        initialEntries: [
          OverlayEntry(
            builder: (context) => Stack(
              fit: StackFit.expand,
              children: [
                child,
                const Align(alignment: Alignment.bottomCenter, child: _ChromeBar()),
              ],
            ),
          ),
        ],
      );
}

class _ChromeBar extends ConsumerWidget {
  const _ChromeBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) => SafeArea(
        top: false,
        child: Material(
          type: MaterialType.transparency,
          child: YoinBottomBar(
            selected: ref.watch(shellSectionProvider),
            onSelect: ref.read(shellSectionProvider.notifier).select,
          ),
        ),
      );
}
