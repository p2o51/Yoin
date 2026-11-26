/// The search surface's type chips (#Yoin:#18; :shared search/SearchFilter.kt,
/// ported against memory-search.json): which it offers, and what each lists
/// of the service's rows. Memories lists the ledger's hits
/// ([SearchPageLayout.memories]), never the service's rows.
library;

import 'search_page.dart';

/// What the search surface lists: every type ([all]), one type alone, or
/// [memories]: the listener's own notes and memories the query met. Declared
/// in chip order.
enum SearchFilter { all, songs, albums, memories, artists, playlists }

/// Most rows of each type the All list shows. The remote searches return
/// fewer (Spotify 10, Subsonic 20, Apple Music 25 a type); a type filter
/// shows all of its type.
const int searchAllRowsPerType = 40;

/// The type chips offered. Capability alone decides, never whether the
/// current results hold that type, so the row doesn't reshuffle as results
/// land. Playlists need [canSearchPlaylists] (not Subsonic, whose search3 has
/// no playlists), and inside the saved library ([savedLibrary]) the library
/// must hold playlists ([canReadPlaylists]).
List<SearchFilter> searchFilters({
  required bool canSearchPlaylists,
  required bool canReadPlaylists,
  required bool savedLibrary,
}) => [
  for (final f in SearchFilter.values)
    if (f != SearchFilter.playlists || (canSearchPlaylists && (!savedLibrary || canReadPlaylists))) f,
];

extension SearchFilterSections on SearchFilter {
  /// The one section this filter lists; null for [SearchFilter.all] and [SearchFilter.memories].
  SearchSection? get section => switch (this) {
    SearchFilter.all || SearchFilter.memories => null,
    SearchFilter.songs => SearchSection.songs,
    SearchFilter.albums => SearchSection.albums,
    SearchFilter.artists => SearchSection.artists,
    SearchFilter.playlists => SearchSection.playlists,
  };

  /// How many of the service's [available] rows of [section] this filter
  /// lists, from the top. Switching fetches nothing: a type shows what the
  /// mixed search already returned. All keeps at most [searchAllRowsPerType]
  /// of each type; Memories lists none of them.
  int shownRows(SearchSection section, int available) => switch (this) {
    SearchFilter.all => available < searchAllRowsPerType ? available : searchAllRowsPerType,
    SearchFilter.memories => 0,
    _ => this.section == section ? available : 0,
  };
}

/// The chip that lists [section] alone (a section's Show all).
SearchFilter searchFilterFor(SearchSection section) => switch (section) {
  SearchSection.songs => SearchFilter.songs,
  SearchSection.albums => SearchFilter.albums,
  SearchSection.artists => SearchFilter.artists,
  SearchSection.playlists => SearchFilter.playlists,
};
