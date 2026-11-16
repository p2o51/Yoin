/// The review a restore shows before it writes anything (Android
/// data/transfer/RestoreReview.kt; docs/playlist-transfer.md §7): one row per
/// backed-up track, with what the target offers for it. Pure, so the rules
/// are unit-tested on their own.
///
/// - [ReviewStatus.matched]: checked by default, its match chosen.
/// - [ReviewStatus.uncertain]: up to [RestoreReview.maxCandidates]
///   candidates, the best chosen but unchecked; the user checks it or picks another.
/// - [ReviewStatus.notFound]: nothing to check — until the user searches the
///   target for it and picks a song: then [ReviewStatus.picked], checked.
library;

import 'package:flutter/foundation.dart';

import '../model/model.dart';
import 'playlist_transfer.dart';
import 'track_matcher.dart';

enum ReviewStatus { matched, uncertain, notFound, picked }

@immutable
final class ReviewRow {
  const ReviewRow({
    required this.position,
    required this.status,
    required this.candidates,
    required this.chosen,
    required this.checked,
  });

  /// The track's place in the backup.
  final int position;
  final ReviewStatus status;

  /// What the target offers, best first; empty for [ReviewStatus.notFound].
  final List<Track> candidates;

  /// Index into [candidates] of the one that would be written.
  final int chosen;
  final bool checked;

  Track? get choice => chosen >= 0 && chosen < candidates.length ? candidates[chosen] : null;
  bool get checkable => candidates.isNotEmpty;

  ReviewRow copyWith({ReviewStatus? status, List<Track>? candidates, int? chosen, bool? checked}) => ReviewRow(
    position: position,
    status: status ?? this.status,
    candidates: candidates ?? this.candidates,
    chosen: chosen ?? this.chosen,
    checked: checked ?? this.checked,
  );

  @override
  bool operator ==(Object other) =>
      other is ReviewRow &&
      other.position == position &&
      other.status == status &&
      listEquals(other.candidates, candidates) &&
      other.chosen == chosen &&
      other.checked == checked;

  @override
  int get hashCode => Object.hash(position, status, Object.hashAll(candidates), chosen, checked);
}

@immutable
final class ReviewSummary {
  const ReviewSummary({required this.matched, required this.toCheck, required this.notFound, this.picked = 0});

  final int matched;
  final int toCheck;
  final int notFound;
  final int picked;

  @override
  bool operator ==(Object other) =>
      other is ReviewSummary &&
      other.matched == matched &&
      other.toCheck == toCheck &&
      other.notFound == notFound &&
      other.picked == picked;

  @override
  int get hashCode => Object.hash(matched, toCheck, notFound, picked);

  @override
  String toString() => 'ReviewSummary($matched, $toCheck, $notFound, $picked)';
}

abstract final class RestoreReview {
  static const int maxCandidates = 3;

  static List<ReviewRow> rows(List<TrackResolution> resolutions) {
    final sorted = [...resolutions]..sort((a, b) => a.position.compareTo(b.position));
    return [
      for (final resolution in sorted)
        switch (resolution.decision) {
          MatchMatched(:final track) => ReviewRow(
            position: resolution.position,
            status: ReviewStatus.matched,
            candidates: [track],
            chosen: 0,
            checked: true,
          ),
          MatchAmbiguous(:final candidates) => ReviewRow(
            position: resolution.position,
            status: candidates.isEmpty ? ReviewStatus.notFound : ReviewStatus.uncertain,
            candidates: candidates.take(maxCandidates).toList(),
            chosen: 0,
            checked: false,
          ),
          MatchNotFound() => ReviewRow(
            position: resolution.position,
            status: ReviewStatus.notFound,
            candidates: const [],
            chosen: 0,
            checked: false,
          ),
        },
    ];
  }

  /// How one resolution will show in the review.
  static ReviewStatus statusOf(TrackResolution resolution) => switch (resolution.decision) {
    MatchMatched() => ReviewStatus.matched,
    MatchAmbiguous(:final candidates) => candidates.isEmpty ? ReviewStatus.notFound : ReviewStatus.uncertain,
    MatchNotFound() => ReviewStatus.notFound,
  };

  /// [rows] with the row at [position] checked or unchecked; a row with no candidate stays unchecked.
  static List<ReviewRow> setChecked(List<ReviewRow> rows, int position, bool checked) => [
    for (final row in rows) row.position == position && row.checkable ? row.copyWith(checked: checked) : row,
  ];

  /// [rows] with candidate [index] chosen for the row at [position], and the row checked: picking is consent.
  static List<ReviewRow> choose(List<ReviewRow> rows, int position, int index) => [
    for (final row in rows)
      row.position == position && index >= 0 && index < row.candidates.length
          ? row.copyWith(chosen: index, checked: true)
          : row,
  ];

  /// [rows] with [track] — found by the user searching the target — as the
  /// row at [position]'s song, checked. Any row can take one; it becomes
  /// [ReviewStatus.picked].
  static List<ReviewRow> pick(List<ReviewRow> rows, int position, Track track) => [
    for (final row in rows)
      row.position == position
          ? row.copyWith(status: ReviewStatus.picked, candidates: [track], chosen: 0, checked: true)
          : row,
  ];

  /// The ids to write, in the backup's order: checked rows' chosen candidates.
  static List<MediaId> trackIdsToWrite(List<ReviewRow> rows) {
    final sorted = [...rows]..sort((a, b) => a.position.compareTo(b.position));
    return [
      for (final row in sorted)
        if (row.checked) ?row.choice?.id,
    ];
  }

  static ReviewSummary summary(List<ReviewRow> rows) => ReviewSummary(
    matched: rows.where((r) => r.status == ReviewStatus.matched).length,
    toCheck: rows.where((r) => r.status == ReviewStatus.uncertain).length,
    notFound: rows.where((r) => r.status == ReviewStatus.notFound).length,
    picked: rows.where((r) => r.status == ReviewStatus.picked).length,
  );
}
