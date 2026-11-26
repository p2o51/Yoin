/// What Home edit mode remembers about teaching itself, per device (Android
/// ui/home/HomeEditHintStore.kt): the person learns the gesture, not the
/// account. How many edit sessions have started, and which section ids the
/// person has already met in the tray. A section the saved layout never knew,
/// appended hidden ([HomeLayout.newSections]), carries a "New" chip in the
/// tray, and the feed footer's "Edit Home" carries one while such a section
/// waits there; the session that shows a chip marks its section seen.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import 'home_section.dart';

/// Edit sessions recorded for the retired header hint (proto.js HINT_SESSIONS).
const int homeEditHeaderHintSessions = 2;

/// Whether the session starting after [sessionsBefore] earlier ones shows the header hint.
bool showEditHeaderHint(int sessionsBefore) => sessionsBefore < homeEditHeaderHintSessions;

/// [layout]'s appended-hidden sections whose ids aren't in [seenIds], in layout
/// order — only sections this port builds (the tray offers no other).
Set<HomeSection> unseenNewSections(HomeLayout layout, Set<String> seenIds) {
  final out = <HomeSection>{};
  for (final p in layout.sections) {
    final s = p.section;
    if (layout.newSections.contains(s) && s.availableInV1 && !seenIds.contains(s.id)) out.add(s);
  }
  return out;
}

/// One edit session's hints, snapshotted when it starts (HomeEditSessionHints).
@immutable
final class HomeEditSessionHints {
  const HomeEditSessionHints({this.showHeaderHint = false, this.newBadges = const {}});

  /// Kept for parity: the header hint no longer draws (Android HomeEditHeaderHint is a no-op).
  final bool showHeaderHint;

  /// The tray rows that carry "New" this session.
  final Set<HomeSection> newBadges;

  @override
  bool operator ==(Object other) =>
      other is HomeEditSessionHints && other.showHeaderHint == showHeaderHint && setEquals(other.newBadges, newBadges);

  @override
  int get hashCode => Object.hash(showHeaderHint, Object.hashAllUnordered(newBadges));

  @override
  String toString() => 'HomeEditSessionHints(header: $showHeaderHint, new: $newBadges)';
}

/// The device's record: [sessions] started, [seen] section ids. [loaded] once
/// the stored record has been read (until then the footer shows no chip).
@immutable
final class HomeEditHintRecord {
  const HomeEditHintRecord({this.sessions = 0, this.seen = const {}, this.loaded = false});

  final int sessions;
  final Set<String> seen;
  final bool loaded;

  Map<String, Object?> toJson() => {'sessions': sessions, 'seen': seen.toList()};

  static HomeEditHintRecord fromJson(Map<String, Object?> json) => HomeEditHintRecord(
    sessions: (json['sessions'] as int?) ?? 0,
    seen: {for (final id in (json['seen'] as List?) ?? const []) '$id'},
    loaded: true,
  );
}

/// The device's hint record on the key-value store (Android
/// SharedPrefsHomeEditHintStore, prefs `yoin_ui_hints`). Not per account and
/// not cloud-synced. Read once; writes go through in order.
class HomeEditHintsNotifier extends Notifier<HomeEditHintRecord> {
  static const String storageKey = 'home.editHints.v1';

  Future<void> _writes = Future.value();

  @override
  HomeEditHintRecord build() {
    Future.microtask(_load);
    return const HomeEditHintRecord();
  }

  Future<void> _load() async {
    HomeEditHintRecord stored;
    try {
      final raw = await ref.read(keyValueStoreProvider).read(storageKey);
      stored = raw == null
          ? const HomeEditHintRecord(loaded: true)
          : HomeEditHintRecord.fromJson((jsonDecode(raw) as Map).cast<String, Object?>());
    } catch (_) {
      // Unreadable: start over.
      stored = const HomeEditHintRecord(loaded: true);
    }
    if (!ref.mounted) return;
    // A session that started before the read landed is added on top, never lost.
    final early = state;
    state = HomeEditHintRecord(
      sessions: stored.sessions + early.sessions,
      seen: {...stored.seen, ...early.seen},
      loaded: true,
    );
    if (early.sessions > 0 || early.seen.isNotEmpty) _save();
  }

  void _save() {
    final value = jsonEncode(state.toJson());
    // Best effort: a failed write costs a chip at worst.
    _writes = _writes.then((_) => ref.read(keyValueStoreProvider).write(storageKey, value)).catchError((Object _) {});
  }

  /// An edit session starts on [layout]: snapshots this session's hints, then
  /// records the session and marks its "New" sections seen (HomeViewModel
  /// onEditSessionStarted). Writes no layout.
  HomeEditSessionHints startSession(HomeLayout layout) {
    final record = state;
    final hints = HomeEditSessionHints(
      showHeaderHint: showEditHeaderHint(record.sessions),
      // Before the read lands nothing is known to be unseen: no chip (and none marked seen), never a
      // stale one for a section this device has already met. It waits for the next session.
      newBadges: record.loaded ? unseenNewSections(layout, record.seen) : const {},
    );
    state = HomeEditHintRecord(
      sessions: record.sessions + 1,
      seen: {...record.seen, for (final s in hints.newBadges) s.id},
      loaded: record.loaded,
    );
    // Before the read lands, the read's merge saves it.
    if (record.loaded) _save();
    return hints;
  }
}

/// The device's Home edit hints.
final homeEditHintsProvider = NotifierProvider<HomeEditHintsNotifier, HomeEditHintRecord>(HomeEditHintsNotifier.new);

/// Whether the feed footer's "Edit Home" carries "New": a new section waits
/// in the tray that this device hasn't met.
final homeEditFooterNewProvider = Provider<bool>((ref) {
  final record = ref.watch(homeEditHintsProvider);
  if (!record.loaded) return false;
  return unseenNewSections(ref.watch(homeLayoutProvider), record.seen).isNotEmpty;
});
