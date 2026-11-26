/// Which tips the user has read (Android ui/tips/TipReadStore.kt), and the
/// Tips page (settings/TipsPage.kt). Kept on this device in [KeyValueStore];
/// Home's bubble brings up only the unread ones ([unreadTips]).
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../home/home_bubble_tips.dart';
import '../nowplaying/haptics.dart';
import '../tips/tip_screen.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'settings_components.dart';
import 'settings_metrics.dart';
import 'settings_page.dart';
import 'settings_rows.dart';
import 'settings_strings.dart';

/// A read tip is kept as "name@2026-11-04": its name and the
/// [HomeBubbleTip.updated] day the user read. A tip updated since counts as
/// unread again; one renamed or removed simply drops out. Bare names were
/// written before tips had a day and stand for what every tip said up to
/// [legacyReadThrough] (Android encodeReadTips / decodeReadTips).
List<String> encodeReadTips(Set<HomeBubbleTip> read) => [
  for (final tip in HomeBubbleTip.values)
    if (read.contains(tip)) '${tip.name}@${_day(tip.updated)}',
];

Set<HomeBubbleTip> decodeReadTips(Iterable<String> saved) {
  final names = saved.toSet();
  return {
    for (final tip in HomeBubbleTip.values)
      if (names.contains('${tip.name}@${_day(tip.updated)}') ||
          (names.contains(tip.name) && !tip.updated.isAfter(legacyReadThrough)))
        tip,
  };
}

/// The last day a tip changed before read tips kept their day on HarmonyOS.
final DateTime legacyReadThrough = DateTime(2026, 11, 15);

String _day(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// The tips still unread, in the bubble's order: of [tips], the ones not in [read].
List<HomeBubbleTip> unreadTips(Set<HomeBubbleTip> read, [List<HomeBubbleTip>? tips]) => [
  for (final tip in tips ?? HomeBubbleTip.available())
    if (!read.contains(tip)) tip,
];

/// After [current] is read, the next unread tip after it (wrapping); null
/// once every one of [tips] is read (Android nextUnreadTip).
HomeBubbleTip? nextUnreadTip(HomeBubbleTip current, Set<HomeBubbleTip> read, List<HomeBubbleTip> tips) {
  final start = tips.indexOf(current);
  for (var step = 1; step < tips.length; step++) {
    final tip = tips[(start + step) % tips.length];
    if (!read.contains(tip) && tip != current) return tip;
  }
  return null;
}

/// The read tips. One per app, so the tip pages, Settings and Home see the same set at once.
class TipReadStore extends Notifier<Set<HomeBubbleTip>> {
  static const key = 'tips.read.v1';

  @override
  Set<HomeBubbleTip> build() {
    _load();
    return const {};
  }

  bool _touched = false;

  Future<void> _load() async {
    final String? raw;
    try {
      raw = await ref.read(keyValueStoreProvider).read(key);
    } catch (_) {
      // No store (a harness without one): everything unread, for this session.
      return;
    }
    // A change made while it loaded wins.
    if (!ref.mounted || _touched || raw == null) return;
    try {
      state = decodeReadTips((jsonDecode(raw) as List<Object?>).whereType<String>());
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
    try {
      await ref.read(keyValueStoreProvider).write(key, next.isEmpty ? null : jsonEncode(encodeReadTips(next)));
    } catch (_) {
      // No store: kept for this session only.
    }
  }
}

final tipReadProvider = NotifierProvider<TipReadStore, Set<HomeBubbleTip>>(TipReadStore.new);

/// "3 of 5 read" / "All read": the Tips row's summary.
String tipsReadSummary(SettingsStrings strings, Set<HomeBubbleTip> read, [List<HomeBubbleTip>? tips]) {
  final all = tips ?? HomeBubbleTip.available();
  final count = all.where(read.contains).length;
  return count >= all.length ? strings.tipsAllRead : strings.tipsReadCount(count, all.length);
}

/// "3 of 5 tips read" / "All tips read": root Settings' Help and feedback row summary (v4).
String helpTipsSummary(SettingsStrings strings, Set<HomeBubbleTip> read, [List<HomeBubbleTip>? tips]) {
  final all = tips ?? HomeBubbleTip.available();
  final count = all.where(read.contains).length;
  return count >= all.length ? strings.helpTipsAllRead : strings.helpTipsReadCount(count, all.length);
}

/// Opens [tip] on its own page (Android TipActivity): a pushed page with the back button.
void openTip(BuildContext context, HomeBubbleTip tip) {
  Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => TipScreen(startTip: tip)));
}

/// Settings › Help and about › Tips as a page: every tip, read ones checked;
/// a tap opens it on its own page. Once something is read, one more row
/// marks them all unread, so the bubble brings them up again.
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
      paneBack: inPane,
      children: [
        SettingsSegments(
          children: [
            for (final tip in HomeBubbleTip.available())
              SettingsItem(
                key: ValueKey(tip),
                icon: tip.icon,
                title: tip.title(zh: zh),
                summary: tip.text(zh: zh, largeScreen: homeTipLargeScreen(context)),
                onTap: () => openTip(context, tip),
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
              SettingsItem(
                icon: YoinSymbols.refresh,
                title: strings.tipsMarkAllUnread,
                onTap: () {
                  YoinHaptics.click();
                  store.setAllUnread();
                },
              ),
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
    child: Padding(
      padding: const EdgeInsets.only(top: SettingsMetrics.groupGap),
      child: child,
    ),
  );
}
