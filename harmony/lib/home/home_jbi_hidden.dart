/// Jump Back In's "not now" (HomeJbiHidden.kt; owner 2026-10-13): in edit
/// mode a tap on a card opens a menu over it — hide it for three days or a
/// week. Never for good: what Jump Back In picks is in the library, and the
/// library is where it leaves for good. Kept per account (Home's scope key),
/// by what the card opens, so the same album hides whether it comes up as a
/// cover or as its memory.
library;

import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import 'home_memory_signals.dart';
import 'home_selection.dart';
import 'home_ui_state.dart';

/// How long a hidden Jump Back In card stays away.
enum JbiHideSpan {
  threeDays(3),
  oneWeek(7);

  const JbiHideSpan(this.days);
  final int days;

  int get millis => days * Duration.millisecondsPerDay;
}

/// What a hidden card is known by: the album, song or playlist it opens. A
/// memory card is its album, so hiding one hides the album wherever Jump
/// Back In would bring it back. Null for a card that opens nothing to hide.
String? jbiHideKey(HomeWidgetCard card) => switch (card.target) {
  HomeOpenAlbum(:final album) => 'album:${album.id.rawId}',
  HomeOpenPlaylist(:final playlist) => 'playlist:${playlist.id.rawId}',
  HomePlaySong(:final track) => 'song:${track.id.rawId}',
  HomeOpenMemory(:final sessionId) => switch (_memoryCardAlbumRaw(card.stableId)) {
    final raw? => 'album:$raw',
    null => 'memory:$sessionId',
  },
  HomeOpenArtist() => null,
};

/// The raw album id a memory 1×2 points at, from its stable id (`grid-memory:<provider>:<rawAlbumId>`).
String? _memoryCardAlbumRaw(String stableId) {
  if (!stableId.startsWith(memoryCardPrefix)) return null;
  final rest = stableId.substring(memoryCardPrefix.length);
  // Raw ids may hold ':'.
  final colon = rest.indexOf(':');
  if (colon <= 0 || colon == rest.length - 1) return null;
  return rest.substring(colon + 1);
}

/// [cards] without the ones hidden by [hidden] keys.
List<HomeWidgetCard> withoutHiddenJbi(List<HomeWidgetCard> cards, Set<String> hidden) {
  if (hidden.isEmpty) return cards;
  return [
    for (final c in cards)
      if (!hidden.contains(jbiHideKey(c))) c,
  ];
}

/// The shelf Jump Back In seats (HomeViewModel.repackWidgetGrid): the wide
/// cards of [signals] (null: none, a switch in flight) then [covers] minus
/// the albums and songs a wide card shows, capped at [homeGridMaxCompacts] —
/// every hidden card left out first, so a hidden cover makes room for the
/// next in line and a hidden memory or note 1×2 for the next one.
List<HomeWidgetCard> jbiShelfCards(List<HomeWidgetCard> covers, HomeMemorySignals? signals, Set<String> hidden) {
  final wide = signals == null
      ? const <HomeWidgetCard>[]
      : HomeMemorySignals(
          memoryCards: withoutHiddenJbi(signals.memoryCards, hidden),
          noteCards: withoutHiddenJbi(signals.noteCards, hidden),
        ).wideCards;
  final shelf = withJbiSignals(withoutHiddenJbi(covers, hidden), wide);
  if (shelf.length <= wide.length + homeGridMaxCompacts) return shelf;
  return shelf.take(wide.length + homeGridMaxCompacts).toList();
}

/// Every account's hidden cards: scope → hide key → until (epoch ms).
@immutable
final class JbiHidden {
  const JbiHidden([this.until = const {}]);

  final Map<String, Map<String, int>> until;

  /// The keys hidden in [scope] past [nowMs].
  Set<String> keysAt(String scope, int nowMs) => {
    for (final e in (until[scope] ?? const <String, int>{}).entries)
      if (e.value > nowMs) e.key,
  };

  /// This with [key] hidden in [scope] until [untilMs], and every expired entry dropped.
  JbiHidden withHidden(String scope, String key, int untilMs, {required int nowMs}) {
    final next = <String, Map<String, int>>{};
    for (final s in until.entries) {
      final live = {
        for (final e in s.value.entries)
          if (e.value > nowMs) e.key: e.value,
      };
      if (live.isNotEmpty) next[s.key] = live;
    }
    (next[scope] ??= {})[key] = untilMs;
    return JbiHidden(next);
  }

  /// This with [other]'s entries on top.
  JbiHidden mergedWith(JbiHidden other) {
    if (other.until.isEmpty) return this;
    final next = {
      for (final s in until.entries) s.key: {...s.value},
    };
    for (final s in other.until.entries) {
      (next[s.key] ??= {}).addAll(s.value);
    }
    return JbiHidden(next);
  }

  String toJson() => jsonEncode(until);

  static JbiHidden fromJson(String raw) {
    final decoded = jsonDecode(raw);
    if (decoded is! Map) return const JbiHidden();
    return JbiHidden({
      for (final s in decoded.entries)
        if (s.value is Map)
          '${s.key}': {
            for (final e in (s.value as Map).entries)
              if (e.value is int) '${e.key}': e.value as int,
          },
    });
  }
}

/// Jump Back In's hidden cards, read once and written on every hide.
class JbiHiddenNotifier extends Notifier<JbiHidden> {
  static const String storeKey = 'home_jbi_hidden.v1';

  @override
  JbiHidden build() {
    Future.microtask(() async {
      try {
        final raw = await ref.read(keyValueStoreProvider).read(storeKey);
        if (!ref.mounted || raw == null) return;
        // A hide that landed first wins over what was on disk.
        state = JbiHidden.fromJson(raw).mergedWith(state);
      } catch (_) {
        // Unreadable: nothing is hidden.
      }
    });
    return const JbiHidden();
  }

  /// Hides [card] in [scope] for [span] from [nowMs]; false for a card that opens nothing to hide.
  bool hide(String scope, HomeWidgetCard card, JbiHideSpan span, {required int nowMs}) {
    final key = jbiHideKey(card);
    if (key == null) return false;
    state = state.withHidden(scope, key, nowMs + span.millis, nowMs: nowMs);
    ref.read(keyValueStoreProvider).write(storeKey, state.toJson()).catchError((Object _) {});
    return true;
  }
}

final jbiHiddenProvider = NotifierProvider<JbiHiddenNotifier, JbiHidden>(JbiHiddenNotifier.new);

/// While Home is being edited ([onTap] set): a tap on a Jump Back In card
/// opens its "not now" menu (at the card's global rect) instead of opening
/// the card. Always in the tree, so entering and leaving edit mode keep the
/// shelf's state; read at tap time, so nothing below rebuilds when it changes.
class JbiHideScope extends InheritedWidget {
  const JbiHideScope({super.key, required this.onTap, required super.child});

  final void Function(BuildContext cardContext, HomeWidgetCard card)? onTap;

  /// The "not now" tap in effect for [context], or null (not editing).
  static void Function(BuildContext cardContext, HomeWidgetCard card)? tapOf(BuildContext context) =>
      context.getInheritedWidgetOfExactType<JbiHideScope>()?.onTap;

  @override
  bool updateShouldNotify(JbiHideScope oldWidget) => false;
}
