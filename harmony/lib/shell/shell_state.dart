import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'yoin_section.dart';

/// The app's single Navigator. The chrome (bar, Now Playing) sits above it,
/// so it pushes routes through this key.
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'app');

/// Which root section the shell shows (the bar writes, the shell reads).
class ShellSection extends Notifier<YoinSection> {
  @override
  YoinSection build() => YoinSection.home;

  void select(YoinSection section) => state = section;
}

final shellSectionProvider = NotifierProvider<ShellSection, YoinSection>(ShellSection.new);
