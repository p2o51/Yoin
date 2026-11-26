/// A release's kind and the kind filter as words (Android component/ReleaseKind.kt): the artist page's
/// strings, shared with Library's Albums filter, the album header and the cover band's tag.
library;

import '../data/model/model.dart';
import '../data/release/release_marks.dart';
import 'detail_strings.dart';

/// "All" / "Albums" / "Singles & EPs" / "Compilations" (releaseKindFilterLabel).
String releaseKindFilterLabel(DetailStrings s, ReleaseKindFilter filter) => switch (filter) {
  ReleaseKindFilter.all => s.filterAll,
  ReleaseKindFilter.albums => s.filterAlbums,
  ReleaseKindFilter.singlesAndEps => s.filterSingles,
  ReleaseKindFilter.compilations => s.filterCompilations,
};

/// "Album" / "EP" / "Single" / "Compilation" (releaseKindLabel).
String releaseKindLabel(DetailStrings s, ReleaseType type) => switch (type) {
  ReleaseType.album => s.kindAlbum,
  ReleaseType.ep => s.kindEp,
  ReleaseType.single => s.kindSingle,
  ReleaseType.compilation => s.kindCompilation,
};

/// The tag a cover or a row wears for its kind: "EP" / "Single", else null (coverKindTag).
String? releaseKindTag(DetailStrings s, ReleaseType? type) =>
    ReleaseMarks.showsKindTag(type) ? releaseKindLabel(s, type!) : null;
