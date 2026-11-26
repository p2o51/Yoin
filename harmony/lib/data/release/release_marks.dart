/// The marks a release or a song carries next to its name (Android PR #343, owner 2026-11-25 / 26): the
/// Explicit "E" ([ContentRating]) and the release's kind ([ReleaseType]: EP / Single / Compilation), with the
/// pure rules every platform applies to them. A port of :shared
/// `com.gpo.yoin.shared.release.ReleaseMarks.kt` (facade `YoinReleaseMarks`); it answers exactly as
/// shared/src/commonTest/resources/golden/release-marks.json says (test/data/release/release_marks_golden_test.dart).
/// Fix a rule on the Kotlin side first, regenerate the vectors there, then follow here.
library;

import 'dart:math' as math;
import 'dart:typed_data';

import '../model/model.dart';

/// Whether a song or a release is marked explicit, as its service says. Nullable, it is a tri-state:
/// [explicit], [clean], or null = unknown (the service didn't say). Only [explicit] is ever shown (the
/// "E" after the title, docs/design.md); [clean] is kept so a clean edition is known as such.
///
/// - Apple Music: `attributes.contentRating`, "explicit" or "clean"; absent is unknown.
/// - Subsonic / OpenSubsonic: `explicitStatus` on songs and albums ("explicit" / "clean"; "" or absent is
///   unknown — plain Subsonic servers never send it; Navidrome fills it from ITUNESADVISORY).
/// - Spotify (no Spotify provider on HarmonyOS; kept so the golden vectors replay whole): a track's
///   `explicit`; an album from its tracks ([fromTracks]).
enum ContentRating {
  explicit,
  clean;

  /// The stored name (caches, snapshots, the player's item): Kotlin's, so every platform's files agree.
  String get storageName => switch (this) {
    ContentRating.explicit => 'Explicit',
    ContentRating.clean => 'Clean',
  };

  /// Apple Music's `contentRating` ("explicit" / "clean"); anything else, or none, is unknown.
  static ContentRating? fromAppleMusic(String? value) => switch (value?.trim().toLowerCase()) {
    'explicit' => ContentRating.explicit,
    'clean' => ContentRating.clean,
    _ => null,
  };

  /// OpenSubsonic's `explicitStatus` ("explicit" / "clean"); "" or none is unknown.
  static ContentRating? fromOpenSubsonic(String? value) => fromAppleMusic(value);

  /// Spotify's `explicit` flag; null (not sent) is unknown.
  static ContentRating? fromExplicitFlag(bool? explicit) => switch (explicit) {
    true => ContentRating.explicit,
    false => ContentRating.clean,
    null => null,
  };

  /// A release's rating from its tracks' ([ratings]): explicit as soon as one is; clean only when
  /// [complete] (every track of the release is in [ratings]) and each is known clean; else unknown.
  static ContentRating? fromTracks(List<ContentRating?> ratings, {required bool complete}) {
    if (ratings.contains(ContentRating.explicit)) return ContentRating.explicit;
    if (complete && ratings.isNotEmpty && ratings.every((r) => r == ContentRating.clean)) return ContentRating.clean;
    return null;
  }

  /// A stored name ([storageName]); unknown for null or a name this build doesn't know.
  static ContentRating? fromStorage(String? name) {
    for (final r in ContentRating.values) {
      if (r.storageName == name) return r;
    }
    return null;
  }
}

/// The release-kind filter of the artist page's discography (All / Albums / Singles & EPs / Compilations),
/// shared with Library's Albums view (owner 2026-11-26: "EP 和专辑能不能试着分开？"). Kinds come from the
/// service only ([ReleaseType]); an unknown kind is only under [all].
enum ReleaseKindFilter {
  all,
  albums,
  singlesAndEps,
  compilations;

  bool accepts(ReleaseType? type) => switch (this) {
    ReleaseKindFilter.all => true,
    ReleaseKindFilter.albums => type == ReleaseType.album,
    ReleaseKindFilter.singlesAndEps => type == ReleaseType.single || type == ReleaseType.ep,
    ReleaseKindFilter.compilations => type == ReleaseType.compilation,
  };

  /// The filters worth showing for [types]: none unless the list holds at least two kinds, else All first.
  static List<ReleaseKindFilter> offeredFor(Iterable<ReleaseType?> types) {
    final kinds = [
      for (final f in ReleaseKindFilter.values.skip(1))
        if (types.any(f.accepts)) f,
    ];
    return kinds.length >= 2 ? [ReleaseKindFilter.all, ...kinds] : const [];
  }
}

/// Where a mark sits on a cover's band ([ReleaseMarks.coverBand]).
enum CoverBandRole { kind, version, explicit }

/// A mark to place on the band: its natural (unconstrained) width and its height, in logical pixels.
final class CoverBandItem {
  const CoverBandItem(this.width, this.height);
  final int width;
  final int height;
}

/// One placed mark: [x] from the band's start edge (mirror it for RTL), [y] from the top, at [width].
final class CoverBandPlaced {
  const CoverBandPlaced(this.role, this.x, this.y, this.width, this.height);
  final CoverBandRole role;
  final int x;
  final int y;
  final int width;
  final int height;
}

/// The marks the band shows, start to end (kind, version, E), and the band's height.
final class CoverBandLayout {
  const CoverBandLayout(this.band, this.start, this.placed);
  final int band;
  final int start;
  final List<CoverBandPlaced> placed;
}

/// The pure rules behind the marks (Kotlin ReleaseMarkRules / YoinReleaseMarks).
abstract final class ReleaseMarks {
  // ── the E's geometry (Android ExplicitBadge.kt) ────────────────────────────────────────────────────

  /// Google Sans Flex's cap height (OS/2 sCapHeight 1432 / 2000 em): the badge's side on every title but
  /// Now Playing's.
  static const double capHeightEm = 0.716;

  /// Google Sans Flex's x-height (OS/2 sxHeight 1023 / 2000 em): Now Playing's (hero) badge's side.
  static const double xHeightEm = 0.5115;

  /// The knocked-out E's own cap height, as a share of the square.
  static const double letterShare = 0.58;

  /// The square's corner, as a share of its side.
  static const double cornerShare = 0.2;

  /// The square is never smaller than this, so the E still reads on small text.
  static const double minSideDp = 9;

  /// A hero badge is never taller than this, whatever the title's size.
  static const double heroMaxSideDp = 18;

  /// Space between a title's last glyph and the badge, as the note marker's in a list row.
  static const double gapDp = 6;

  /// On a cover's band the E is set as if for a 17sp label: about the tags' cap height, a touch larger.
  static const double bandBadgeTextSp = 17;

  /// How far in from the shape's start the band's row may begin when it needs more than the cover's width.
  static const double bandInsetFraction = 0.08;

  /// Below this a version tag on the band is dropped rather than cut to an ellipsis.
  static const double bandMinTagWidthDp = 40;

  /// Space between two marks on the band.
  static const double bandGapDp = 4;

  /// Space kept free at the band's end for the memory sticker on the cover's top-end corner.
  static const double bandStickerRoomDp = 14;

  /// The square's side for a title of [fontSize]: its cap height, at least [minSide]; [hero] (Now Playing's
  /// big titles): its x-height instead, at most [heroMaxSide] (and still at least [minSide]).
  static double badgeSide(
    double fontSize, {
    bool hero = false,
    double minSide = minSideDp,
    double heroMaxSide = heroMaxSideDp,
  }) {
    if (hero) return math.max(math.min(_f(fontSize * _f(xHeightEm)), heroMaxSide), minSide);
    return math.max(_f(fontSize * _f(capHeightEm)), minSide);
  }

  /// Space between the title and the badge: [minGap], growing with large type (a third of [fontSize]); a
  /// [hero] badge sits half its own side away instead (at least [minGap]). Whole pixels, half up.
  static int badgeGap(
    double fontSize, {
    bool hero = false,
    double minSide = minSideDp,
    double heroMaxSide = heroMaxSideDp,
    double minGap = gapDp,
  }) {
    final grown = hero
        ? _f(badgeSide(fontSize, hero: true, minSide: minSide, heroMaxSide: heroMaxSide) * 0.5)
        : _f(fontSize * _f(0.33));
    return _roundToInt(math.max(grown, minGap));
  }

  /// Whether the badge may follow a title in a [maxWidth] box, leaving the title [titleRoom]: the title keeps
  /// at least half the box, or all of itself if it is shorter ([naturalTitle]).
  static bool explicitBadgeFits(int maxWidth, int titleRoom, int naturalTitle) =>
      titleRoom >= math.min(naturalTitle, maxWidth ~/ 2);

  // ── release kinds ───────────────────────────────────────────────────────────────────────────────────

  /// Spotify `album_type` (no Spotify on HarmonyOS; kept for the golden replay): a "single" of 4+ tracks is an EP.
  static ReleaseType? spotifyReleaseType(String? albumType, int? totalTracks) => switch (albumType?.toLowerCase()) {
    'album' => ReleaseType.album,
    'compilation' => ReleaseType.compilation,
    'single' => (totalTracks ?? 0) >= 4 ? ReleaseType.ep : ReleaseType.single,
    _ => null,
  };

  /// Whether [loadedTracks] are a release's whole tracklist (Spotify; kept for the golden replay).
  static bool tracklistComplete(int loadedTracks, int? totalTracks, int? pageTotal) {
    final total = totalTracks ?? pageTotal;
    return total != null && loadedTracks >= total;
  }

  /// OpenSubsonic `releaseTypes`: the first recognised value wins.
  static ReleaseType? subsonicReleaseType(Iterable<String> releaseTypes) {
    for (final type in releaseTypes) {
      final kind = switch (type.trim().toLowerCase()) {
        'album' => ReleaseType.album,
        'ep' => ReleaseType.ep,
        'single' => ReleaseType.single,
        'compilation' => ReleaseType.compilation,
        _ => null,
      };
      if (kind != null) return kind;
    }
    return null;
  }

  /// Apple Music has no release-type field: a compilation says `isCompilation`, a single `isSingle`, and an
  /// EP only by its [name] (" - EP"; a single's " - Single"). `isSingle` false and no EP name is an album;
  /// neither flag nor suffix is unknown. A library album passes its catalog album's flags (each flag: the
  /// resource's own when it has the key, else the catalog's).
  static ReleaseType? appleMusicReleaseType(String? name, {bool? isCompilation, bool? isSingle}) {
    if (isCompilation == true) return ReleaseType.compilation;
    if (isSingle == true || (name?.endsWith(' - Single') ?? false)) return ReleaseType.single;
    if (name?.endsWith(' - EP') ?? false) return ReleaseType.ep;
    if (isSingle == false) return ReleaseType.album;
    return null;
  }

  /// The kind worth a tag beside a cover or a row: an EP or a single (an album is the default).
  static bool showsKindTag(ReleaseType? type) => type == ReleaseType.ep || type == ReleaseType.single;

  /// The kind the album page's header names: the real one, Album when unknown.
  static ReleaseType headerKind(ReleaseType? type) => type ?? ReleaseType.album;

  /// [name] without Apple Music's " - EP" / " - Single" when the kind is shown beside it; unchanged otherwise
  /// (and when nothing would be left).
  static String nameWithoutKindSuffix(String name, ReleaseType? type) {
    final shown = switch (type) {
      ReleaseType.ep => _removeSuffix(name, ' - EP'),
      ReleaseType.single => _removeSuffix(name, ' - Single'),
      _ => name,
    };
    return shown.trim().isEmpty ? name : shown;
  }

  // ── the cover's band (Android CoverBandBadges) ─────────────────────────────────────────────────────

  /// The marks on the band of a [width] × [height] box whose cover sits in the bottom-end [coverFraction]:
  /// start-aligned with the cover's start edge, centred in the band above it, sliding toward the shape's
  /// top-start lobe (no further than [bandInsetFraction] of the width) only as far as the row needs, and
  /// stopping [stickerRoom] short of the end. When the band is short the marks are kept in the order E,
  /// kind, version; the version may be cut (never below [minTagWidth]), the others are whole or left out,
  /// and a mark taller than the band is left out. [gap] between marks.
  static CoverBandLayout coverBand({
    required int width,
    required int height,
    required double coverFraction,
    required int stickerRoom,
    required int gap,
    required int minTagWidth,
    CoverBandItem? kind,
    CoverBandItem? version,
    CoverBandItem? explicit,
  }) {
    final rest = _f(1 - _f(coverFraction));
    final band = _roundToInt(_f(height * rest));
    final coverStart = _roundToInt(_f(width * rest));
    final minStart = _roundToInt(_f(width * _f(bandInsetFraction)));
    final int end = width - math.max<int>(minStart, stickerRoom);
    var room = math.max<int>(end - minStart, 0);
    final items = {CoverBandRole.kind: kind, CoverBandRole.version: version, CoverBandRole.explicit: explicit};
    final widths = <CoverBandRole, int>{};
    for (final role in const [CoverBandRole.explicit, CoverBandRole.kind, CoverBandRole.version]) {
      final item = items[role];
      if (item == null) continue;
      final need = widths.isEmpty ? 0 : gap;
      final int left = room - need;
      final int? w = role == CoverBandRole.version
          ? (left >= math.min(item.width, minTagWidth) ? math.min(item.width, left) : null)
          : (item.width <= left ? item.width : null);
      if (w == null) continue;
      if (item.height > band) continue;
      widths[role] = w;
      room -= w + need;
    }
    final row = [
      for (final role in const [CoverBandRole.kind, CoverBandRole.version, CoverBandRole.explicit])
        if (widths.containsKey(role)) role,
    ];
    final int total = row.fold<int>(0, (s, r) => s + widths[r]!) + gap * math.max<int>(row.length - 1, 0);
    final int start = math.min<int>(math.max<int>(end - total, minStart), math.max<int>(coverStart, minStart));
    var x = start;
    final placed = <CoverBandPlaced>[];
    for (final role in row) {
      final w = widths[role]!;
      final h = items[role]!.height;
      placed.add(CoverBandPlaced(role, x, (band - h) ~/ 2, w, h));
      x += w + gap;
    }
    return CoverBandLayout(band, start, placed);
  }

  static String _removeSuffix(String s, String suffix) =>
      s.endsWith(suffix) ? s.substring(0, s.length - suffix.length) : s;

  static final Float32List _f32 = Float32List(1);

  /// [x] as a 32-bit float, the way Kotlin's Float arithmetic holds it (the vectors are Kotlin's).
  static double _f(double x) {
    _f32[0] = x;
    return _f32[0];
  }

  /// Kotlin's `roundToInt`: half up.
  static int _roundToInt(double x) => (x + 0.5).floor();
}
