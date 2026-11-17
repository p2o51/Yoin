/// Plain-text wording for Cloud sync (Android ui/settings/sync/CloudSyncTexts.kt),
/// kept out of the widgets so the Settings row, the page and the tests read
/// the same strings.
library;

import 'package:flutter/material.dart';

import '../../data/sync/cloud_sync_state.dart';
import 'cloud_sync_strings.dart';

const int _minuteMs = 60 * 1000;
const int _hourMs = 60 * _minuteMs;
const int _dayMs = 24 * _hourMs;

/// An absolute day: [sameYear] leaves the year out ("Sep 12" / "Sep 12, 2025").
typedef SyncDateFormat = String Function(DateTime local, {required bool sameYear});

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// English "MMM d" / "MMM d, yyyy" (and Chinese "9月12日" / "2025年9月12日"), for tests and contexts without localizations.
String fixedSyncDate(DateTime local, {required bool sameYear, bool chinese = false}) {
  if (chinese) return sameYear ? '${local.month}月${local.day}日' : '${local.year}年${local.month}月${local.day}日';
  final day = '${_months[local.month - 1]} ${local.day}';
  return sameYear ? day : '$day, ${local.year}';
}

/// The locale's short dates (MaterialLocalizations), as Android's best pattern for "MMMd" / "yMMMd".
SyncDateFormat localizedSyncDate(BuildContext context) {
  final material = MaterialLocalizations.of(context);
  return (local, {required sameYear}) =>
      sameYear ? material.formatShortMonthDay(local) : material.formatShortDate(local);
}

/// "just now" / "2 min ago" / "3 hr ago" / "yesterday" / "4 days ago" / "Sep 12" / "Sep 12, 2025".
/// A time slightly in the future (clock skew) reads as "just now".
String formatRelativeTime(int thenMs, int nowMs, CloudSyncStrings strings, {SyncDateFormat? date}) {
  final elapsed = nowMs - thenMs < 0 ? 0 : nowMs - thenMs;
  if (elapsed < _minuteMs) return strings.relativeJustNow;
  if (elapsed < _hourMs) return strings.relativeMinutes(elapsed ~/ _minuteMs);
  if (elapsed < _dayMs) return strings.relativeHours(elapsed ~/ _hourMs);
  if (elapsed < 2 * _dayMs) return strings.relativeYesterday;
  if (elapsed < 7 * _dayMs) return strings.relativeDays(elapsed ~/ _dayMs);
  final then = DateTime.fromMillisecondsSinceEpoch(thenMs);
  final now = DateTime.fromMillisecondsSinceEpoch(nowMs);
  final sameYear = then.year == now.year;
  return date != null
      ? date(then, sameYear: sameYear)
      : fixedSyncDate(then, sameYear: sameYear, chinese: strings.isChinese);
}

/// Status on the first line, detail on the second. No connector between them.
@immutable
final class CloudSyncEntryText {
  const CloudSyncEntryText(this.status, [this.detail]);
  final String status;
  final String? detail;

  @override
  bool operator ==(Object other) => other is CloudSyncEntryText && other.status == status && other.detail == detail;

  @override
  int get hashCode => Object.hash(status, detail);

  @override
  String toString() => 'CloudSyncEntryText($status, $detail)';
}

/// The Settings row: where sync stands, then the detail under it.
CloudSyncEntryText cloudSyncEntrySummary(CloudSyncState state, int nowMs, CloudSyncStrings s, {SyncDateFormat? date}) =>
    switch (state.phase) {
      SyncOff() => CloudSyncEntryText(s.summaryOff),
      SyncMisconfiguredPhase() => CloudSyncEntryText(s.summaryMisconfigured),
      SyncResetElsewhere(:final deviceName) => CloudSyncEntryText(
        deviceName != null ? s.summaryResetOn(deviceName) : s.summaryResetOther,
      ),
      SyncSyncing() => CloudSyncEntryText(s.summarySyncing),
      SyncUpToDate() =>
        state.pendingChanges
            ? CloudSyncEntryText(s.summaryPending)
            : state.lastSyncAt != null
            ? CloudSyncEntryText(
                s.summaryOn,
                s.summarySynced(formatRelativeTime(state.lastSyncAt!, nowMs, s, date: date)),
              )
            : CloudSyncEntryText(s.summaryOn),
      SyncOffline() =>
        state.pendingChanges
            ? CloudSyncEntryText(s.summaryPendingOffline)
            : CloudSyncEntryText(s.summaryOn, s.summaryOffline),
      SyncNeedsReauth() => CloudSyncEntryText(s.statusPaused, s.summaryReauth),
      SyncStorageFullPhase() => CloudSyncEntryText(s.summaryStorage),
      SyncNeedsReview(:final count) => CloudSyncEntryText(s.summaryNeedsReviewStatus, s.summaryNeedsReview(count)),
      SyncCloudCopyRemoved() => CloudSyncEntryText(s.statusPaused, s.summaryCopyRemoved),
      SyncUpdateRequired() => CloudSyncEntryText(s.summaryOn, s.summaryUpdate),
      SyncErrorPhase() => CloudSyncEntryText(s.summaryError),
    };

/// What the page's status row says: a title and the lines under it.
@immutable
final class CloudSyncStatusText {
  const CloudSyncStatusText(this.title, this.lines);
  final String title;
  final List<String> lines;
}

CloudSyncStatusText cloudSyncStatusText(CloudSyncState state, int nowMs, CloudSyncStrings s, {SyncDateFormat? date}) {
  final last = state.lastSyncAt;
  final lastSynced = last == null ? null : s.statusLastSynced(formatRelativeTime(last, nowMs, s, date: date));
  final lines = [?lastSynced];
  return switch (state.phase) {
    SyncSyncing() => CloudSyncStatusText(s.statusSyncing, lines),
    SyncUpToDate() => CloudSyncStatusText(state.pendingChanges ? s.statusPending : s.statusUpToDate, [
      lastSynced ?? s.statusNotYet,
    ]),
    SyncOffline() => CloudSyncStatusText(s.statusOffline, lines),
    // The Reconnect panel under the row explains what to do.
    SyncNeedsReauth() => CloudSyncStatusText(s.statusPaused, lines),
    SyncStorageFullPhase() => CloudSyncStatusText(s.statusStorage, lines),
    SyncNeedsReview() => CloudSyncStatusText(s.statusReview, lines),
    SyncCloudCopyRemoved() => CloudSyncStatusText(s.statusRemoved, lines),
    SyncUpdateRequired(:final deviceName) => CloudSyncStatusText(s.statusUpdate, [?deviceName, ...lines]),
    SyncErrorPhase(:final message) => CloudSyncStatusText(s.statusError, [message, ...lines]),
    // Off states don't show the status row; keep the mapping total.
    SyncOff() || SyncMisconfiguredPhase() || SyncResetElsewhere() => CloudSyncStatusText(s.statusOff, const []),
  };
}
