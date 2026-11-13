import 'package:flutter/foundation.dart';

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import 'home_selection.dart';

/// Home's state (Android HomeUiState): switched on exhaustively by the screen.
sealed class HomeUiState {
  const HomeUiState();
}

final class HomeLoading extends HomeUiState {
  const HomeLoading();
}

/// Only when the very first load throws with nothing up (a local storage
/// failure); network blocks never produce it.
final class HomeError extends HomeUiState {
  const HomeError(this.error);
  final Object error;
}

/// The hero album's footnote (year · N songs · M min · version).
@immutable
final class HeroFootnote {
  const HeroFootnote({this.year, this.songCount, this.minutes, this.version});

  final int? year;
  final int? songCount;
  final int? minutes;
  final String? version;

  bool get isEmpty => year == null && songCount == null && minutes == null;

  @override
  bool operator ==(Object other) =>
      other is HeroFootnote &&
      other.year == year &&
      other.songCount == songCount &&
      other.minutes == minutes &&
      other.version == version;

  @override
  int get hashCode => Object.hash(year, songCount, minutes, version);
}

/// What tapping a Home card does.
sealed class HomeTarget {
  const HomeTarget();
}

final class HomeOpenAlbum extends HomeTarget {
  const HomeOpenAlbum(this.album);
  final Album album;
}

final class HomeOpenPlaylist extends HomeTarget {
  const HomeOpenPlaylist(this.playlist);
  final Playlist playlist;
}

final class HomeOpenArtist extends HomeTarget {
  const HomeOpenArtist(this.id, this.name);
  final MediaId id;
  final String name;
}

/// Plays this one track at once (queue of 1, no navigation).
final class HomePlaySong extends HomeTarget {
  const HomePlaySong(this.track);
  final Track track;
}

/// Backdrop shape per entity (album Bun, song Circle, playlist Ghostish; artist bare circle).
enum HomeCardKind { album, song, playlist, artist }

/// One Jump Back In cover (HomeWidgetCard, covers only in v1).
@immutable
final class HomeWidgetCard {
  const HomeWidgetCard({
    required this.stableId,
    required this.kind,
    required this.title,
    required this.subtitle,
    required this.target,
    this.cover,
    this.version,
  });

  final String stableId;
  final HomeCardKind kind;
  final String title;

  /// Blank is allowed (hidden).
  final String subtitle;
  final CoverRef? cover;
  final String? version;
  final HomeTarget target;
}

/// One Activities card's data (buildActivityEntries).
@immutable
final class HomeActivityEntry {
  const HomeActivityEntry({
    required this.stableId,
    required this.entityType,
    required this.entityKey,
    required this.title,
    required this.subtitle,
    required this.timestampMs,
    required this.target,
    this.cover,
  });

  final String stableId;
  final ActivityEntityType entityType;

  /// `entityType:rawId`, the hero's identity.
  final String entityKey;
  final String title;

  /// null = the localized fallback ('Artist' / 'Recently active').
  final String? subtitle;
  final int timestampMs;
  final CoverRef? cover;
  final HomeTarget target;

  HomeCardKind get kind => switch (entityType) {
        ActivityEntityType.album => HomeCardKind.album,
        ActivityEntityType.artist => HomeCardKind.artist,
        ActivityEntityType.playlist => HomeCardKind.playlist,
        ActivityEntityType.song => HomeCardKind.song,
      };
}

/// The feed. Each network block owns its own fields; [activities] is live.
final class HomeContent extends HomeUiState {
  HomeContent({
    required this.scopeKey,
    required this.provider,
    this.accountTitle,
    this.activities = const [],
    this.activitiesAtMs = 0,
    this.heroFootnote,
    this.grid = const [],
    this.recentlyAddedTracks = const [],
    this.recentlyAddedAlbums = const [],
    this.recentlyPlayedAll = const [],
    this.playlists = const [],
  });

  /// `provider|profileId`; '' with no account.
  final String scopeKey;
  final String provider;

  /// The active account's name, null when no account is configured.
  final String? accountTitle;

  /// Collapsed newest-per-entity events (≤ 40), newest first.
  final List<ActivityEvent> activities;

  /// "Now" when [activities] was derived: time-ago labels are computed against it, not ticking.
  final int activitiesAtMs;
  final HeroFootnote? heroFootnote;
  final List<HomeWidgetCard> grid;
  final List<Track> recentlyAddedTracks;
  final List<Album> recentlyAddedAlbums;

  /// The server's recent albums before the Activities dedupe.
  final List<Album> recentlyPlayedAll;
  final List<Playlist> playlists;

  bool get hasAccount => accountTitle != null;

  /// Bento entries (songs dropped), recomputed only when [activities] changes.
  late final List<HomeActivityEntry> activityEntries = buildActivityEntries(activities, provider: provider);

  /// Recently Played minus albums already shown by Activities.
  late final List<Album> recentlyPlayed = notShownIn(recentlyPlayedAll, activityAlbumIds(activityEntries));

  /// Something for the staged reveal to show (homeFeedHasSomethingToReveal).
  bool get hasSomethingToReveal =>
      activityEntries.isNotEmpty ||
      grid.isNotEmpty ||
      recentlyAddedTracks.isNotEmpty ||
      recentlyAddedAlbums.isNotEmpty ||
      recentlyPlayed.isNotEmpty ||
      playlists.isNotEmpty;

  HomeContent copyWith({
    String? accountTitle,
    List<ActivityEvent>? activities,
    int? activitiesAtMs,
    HeroFootnote? heroFootnote,
    bool clearHeroFootnote = false,
    List<HomeWidgetCard>? grid,
    List<Track>? recentlyAddedTracks,
    List<Album>? recentlyAddedAlbums,
    List<Album>? recentlyPlayedAll,
    List<Playlist>? playlists,
  }) =>
      HomeContent(
        scopeKey: scopeKey,
        provider: provider,
        accountTitle: accountTitle ?? this.accountTitle,
        activities: activities ?? this.activities,
        activitiesAtMs: activitiesAtMs ?? this.activitiesAtMs,
        heroFootnote: clearHeroFootnote ? null : (heroFootnote ?? this.heroFootnote),
        grid: grid ?? this.grid,
        recentlyAddedTracks: recentlyAddedTracks ?? this.recentlyAddedTracks,
        recentlyAddedAlbums: recentlyAddedAlbums ?? this.recentlyAddedAlbums,
        recentlyPlayedAll: recentlyPlayedAll ?? this.recentlyPlayedAll,
        playlists: playlists ?? this.playlists,
      );

  /// Same feed as [other] for publishing purposes (ids, titles and footnote);
  /// a splice that changes nothing publishes nothing.
  bool sameFeedAs(HomeContent other) =>
      other.scopeKey == scopeKey &&
      other.accountTitle == accountTitle &&
      other.heroFootnote == heroFootnote &&
      _sameEvents(other.activities, activities) &&
      listEquals([for (final c in other.grid) c.stableId], [for (final c in grid) c.stableId]) &&
      listEquals(_trackKeys(other.recentlyAddedTracks), _trackKeys(recentlyAddedTracks)) &&
      listEquals(_albumKeys(other.recentlyAddedAlbums), _albumKeys(recentlyAddedAlbums)) &&
      listEquals(_albumKeys(other.recentlyPlayedAll), _albumKeys(recentlyPlayedAll)) &&
      listEquals([for (final p in other.playlists) '${p.id}|${p.name}|${p.songCount}'],
          [for (final p in playlists) '${p.id}|${p.name}|${p.songCount}']);

  static bool _sameEvents(List<ActivityEvent> a, List<ActivityEvent> b) => listEquals(
        [for (final e in a) '${e.entityKey}|${e.actionType.name}|${e.timestampMs}|${e.title}'],
        [for (final e in b) '${e.entityKey}|${e.actionType.name}|${e.timestampMs}|${e.title}'],
      );

  static List<String> _trackKeys(List<Track> t) => [for (final x in t) '${x.id}|${x.title}'];
  static List<String> _albumKeys(List<Album> a) => [for (final x in a) '${x.id}|${x.name}'];
}
