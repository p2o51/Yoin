import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../home/home_screen.dart';
import '../library/library_screen.dart';
import '../theme/motion.dart';
import 'shell_state.dart';
import 'yoin_section.dart';

/// The shell (RootSection): Home and Library. The bar lives in AppChrome
/// above the Navigator and writes [shellSectionProvider]; switching
/// crossfades the pages on the effects spring (design.md 关键转场动画:
/// 切换主页 ↔ Library), both kept alive so scroll positions survive.
class ShellScreen extends ConsumerStatefulWidget {
  const ShellScreen({super.key});

  @override
  ConsumerState<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends ConsumerState<ShellScreen> with TickerProviderStateMixin {
  /// 1 = Home showing, 0 = Library showing.
  late final SpringValue _homeVisibility = SpringValue(
    vsync: this,
    initial: ref.read(shellSectionProvider) == YoinSection.home ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void dispose() {
    _homeVisibility.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(shellSectionProvider, (_, section) => _homeVisibility.animateTo(section == YoinSection.home ? 1 : 0));
    return Scaffold(
      body: AnimatedBuilder(
        animation: _homeVisibility.animation,
        builder: (context, _) {
          final home = _homeVisibility.value.clamp(0.0, 1.0);
          return Stack(
            fit: StackFit.expand,
            children: [
              _Page(visibility: 1 - home, child: const LibraryScreen()),
              _Page(visibility: home, child: const HomeScreen()),
            ],
          );
        },
      ),
    );
  }
}

class _Page extends StatelessWidget {
  const _Page({required this.visibility, required this.child});

  final double visibility;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      ignoring: visibility < 0.5,
      child: Opacity(
        opacity: visibility,
        // Keep the subtree mounted (state, scroll) but skip painting when hidden.
        child: TickerMode(enabled: visibility > 0, child: child),
      ),
    );
  }
}
