import 'package:flutter/material.dart';

import '../home/home_screen.dart';
import '../library/library_screen.dart';
import '../theme/motion.dart';
import 'yoin_bottom_bar.dart';
import 'yoin_section.dart';

/// The shell: Home and Library under the floating bar. Switching sections
/// crossfades the pages on the effects spring (design.md 关键转场动画:
/// 切换主页 ↔ Library), both kept alive so scroll positions survive.
class ShellScreen extends StatefulWidget {
  const ShellScreen({super.key});

  @override
  State<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends State<ShellScreen> with TickerProviderStateMixin {
  YoinSection _section = YoinSection.home;

  /// 1 = Home showing, 0 = Library showing.
  late final SpringValue _homeVisibility = SpringValue(
    vsync: this,
    initial: 1,
    spring: YoinSprings.defaultEffects,
  );

  void _select(YoinSection section) {
    if (section == _section) return;
    setState(() => _section = section);
    _homeVisibility.animateTo(section == YoinSection.home ? 1 : 0);
  }

  @override
  void dispose() {
    _homeVisibility.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBody: true,
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
      bottomNavigationBar: SafeArea(
        top: false,
        child: YoinBottomBar(selected: _section, onSelect: _select),
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
