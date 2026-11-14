/// Which tips the user has read (Android ui/tips/TipReadStore.kt), and the
/// Tips page (settings/TipsPage.kt). Kept on this device in [KeyValueStore];
/// Home's bubble can read [tipReadProvider] to bring up only unread tips.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../home/home_bubble_tips.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// The read tips, by name. A tip renamed or removed simply drops out.
class TipReadStore extends Notifier<Set<HomeBubbleTip>> {
  static const key = 'tips.read.v1';

  @override
  Set<HomeBubbleTip> build() {
    _load();
    return const {};
  }

  bool _touched = false;

  Future<void> _load() async {
    final raw = await ref.read(keyValueStoreProvider).read(key);
    // A change made while it loaded wins.
    if (!ref.mounted || _touched || raw == null) return;
    try {
      final names = (jsonDecode(raw) as List<Object?>).whereType<String>().toSet();
      state = {for (final tip in HomeBubbleTip.values) if (names.contains(tip.name)) tip};
    } on FormatException {
      // Unreadable: everything unread.
    }
  }

  Future<void> setRead(HomeBubbleTip tip, bool read) {
    final next = {...state};
    read ? next.add(tip) : next.remove(tip);
    return _put(next);
  }

  Future<void> setAllUnread() => _put(const {});

  Future<void> _put(Set<HomeBubbleTip> next) async {
    _touched = true;
    state = next;
    await ref.read(keyValueStoreProvider).write(
          key,
          next.isEmpty ? null : jsonEncode([for (final tip in HomeBubbleTip.values) if (next.contains(tip)) tip.name]),
        );
  }
}

final tipReadProvider = NotifierProvider<TipReadStore, Set<HomeBubbleTip>>(TipReadStore.new);

/// "3 of 5 read" / "All read": the Tips row's summary.
String tipsReadSummary(SettingsStrings strings, Set<HomeBubbleTip> read) {
  final total = HomeBubbleTip.values.length;
  final count = HomeBubbleTip.values.where(read.contains).length;
  return count >= total ? strings.tipsAllRead : strings.tipsReadCount(count, total);
}

/// Settings › Help and about › Tips as a page: every tip, read ones checked.
/// Harmony has no illustrated tip pages, so a tap marks the tip read (or
/// unread again) in place. Once something is read, one more row marks them
/// all unread.
class TipsScreen extends ConsumerWidget {
  const TipsScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = SettingsStrings.of(context);
    final zh = Localizations.maybeLocaleOf(context)?.languageCode == 'zh';
    final colors = Theme.of(context).colorScheme;
    final read = ref.watch(tipReadProvider);
    final store = ref.read(tipReadProvider.notifier);
    return SettingsSubPage(
      title: strings.aboutTips,
      inPane: inPane,
      children: [
        SettingsSegments(
          children: [
            for (final tip in HomeBubbleTip.values)
              SettingsItem(
                key: ValueKey(tip),
                icon: tip.icon,
                title: tip.title(zh: zh),
                summary: tip.text(zh: zh),
                onTap: () => store.setRead(tip, !read.contains(tip)),
                // The check fades on the effects spring; the row never changes size.
                trailing: SpringFade(
                  visible: read.contains(tip),
                  child: Icon(
                    YoinSymbols.check,
                    color: colors.primary,
                    semanticLabel: read.contains(tip) ? strings.tipRead : null,
                  ),
                ),
              ),
          ],
        ),
        SpringRevealGap(
          visible: read.isNotEmpty,
          child: SettingsSegments(
            children: [
              SettingsItem(icon: YoinSymbols.refresh, title: strings.tipsMarkAllUnread, onTap: store.setAllUnread),
            ],
          ),
        ),
      ],
    );
  }
}

/// [child] under a [SettingsMetrics.groupGap] gap, unfolding as a whole.
class SpringRevealGap extends StatelessWidget {
  const SpringRevealGap({super.key, required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  Widget build(BuildContext context) => SpringReveal(
        visible: visible,
        child: Padding(padding: const EdgeInsets.only(top: SettingsMetrics.groupGap), child: child),
      );
}
