/// How the top seam under fixed chrome looks (SeamTopStyle.kt): the user's
/// choice in Settings › Appearance › Scroll edge and the landing's edge step.
/// Only that seam: Home's status-bar tide and the text fades are the same in
/// every style.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/providers.dart';

enum SeamTopStyle {
  /// The tide line: graphics sink under two waves. The default.
  tide('tide'),

  /// Curve C halftone: graphics break into dots over the band.
  dots('dots'),

  /// 曲奇浪口: a wave lip that morphs Cookie → sine → SoftBurst over the exit.
  cookie('cookie');

  const SeamTopStyle(this.key);

  /// Stable persisted key (Android's, synced by Cloud sync); never rename.
  final String key;

  static const SeamTopStyle defaultStyle = tide;

  /// Text inside a print is lifted out of it near this seam: only where the print itself breaks up.
  bool get liftsText => this != tide;

  /// Unknown or missing keys fall back to [defaultStyle].
  static SeamTopStyle fromKey(String? key) => values.where((s) => s.key == key).firstOrNull ?? defaultStyle;
}

/// The process-wide top seam style: every open chrome seam watches it, so a
/// change in Settings repaints them at once. Stored under Android's
/// `seam_top_style` key (SettingsSyncAdapter's SEAM_TOP_STYLE), its value one
/// of [SeamTopStyle.key].
class SeamTopStylePreference extends Notifier<SeamTopStyle> {
  /// The key Android stores and syncs the choice under.
  static const key = 'seam_top_style';

  bool _touched = false;

  @override
  SeamTopStyle build() {
    _load();
    return SeamTopStyle.defaultStyle;
  }

  Future<void> _load() async {
    final String? raw;
    try {
      raw = await ref.read(keyValueStoreProvider).read(key);
    } catch (_) {
      // No store (a harness without one): the default, for this session.
      return;
    }
    // A choice made while it loaded wins.
    if (!ref.mounted || _touched || raw == null) return;
    state = SeamTopStyle.fromKey(raw);
  }

  /// The user's choice: shown at once everywhere, and stored.
  Future<void> select(SeamTopStyle style) async {
    // Stored even when it is what shows: before the stored value has loaded,
    // the default on screen is no choice, and the pick must not lose to it.
    _touched = true;
    if (state != style) state = style;
    try {
      await ref.read(keyValueStoreProvider).write(key, style.key);
    } catch (_) {
      // Kept for this session; the next start reads the old value.
    }
  }

  /// A synced value arrived (Cloud sync applies Android's row): shown and
  /// stored like a choice. Unknown keys are ignored. Returns whether it applied.
  Future<bool> applyKey(String? value) async {
    if (!SeamTopStyle.values.any((s) => s.key == value)) return false;
    await select(SeamTopStyle.fromKey(value));
    return true;
  }
}

final seamTopStyleProvider = NotifierProvider<SeamTopStylePreference, SeamTopStyle>(SeamTopStylePreference.new);
