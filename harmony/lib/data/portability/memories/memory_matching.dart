/// Finding each source song and album on the target (Android
/// MemoryTransfer.plan's matchTracks / matchAlbum): what the core leaves to
/// each platform. Songs: ISRC first, then title + artist + duration
/// ([TrackMatcher], the playlist transfer's rules), then the title alone.
/// Albums: the target album most of the album's matched songs are on, else an
/// album search by name + artist. Three outcomes: matched, uncertain (up to
/// [TransferRules.maxCandidates] to pick from) or not found.
library;

import 'package:flutter/foundation.dart';

import '../../model/model.dart';
import '../../transfer/track_matcher.dart';
import 'transfer_models.dart';
import 'transfer_rules.dart';

/// The target's catalogue as matching uses it (Android MemoryCatalog).
abstract interface class MemoryCatalog {
  /// The source song as its service describes it; null when unknown (no sign-in, cloud-only account).
  Future<Track?> describeSourceTrack(SourceRef ref);

  /// Target songs by ISRC, where the target can look ISRCs up.
  Future<Map<String, List<Track>>> tracksByIsrc(List<String> isrcs);

  /// An ISRC the source knows for [track] (a song it handed out), else null.
  String? isrcOf(Track track);

  Future<List<Track>> searchTracks(String query);

  Future<List<Album>> searchAlbums(String query);
}

/// One source song and where it goes.
@immutable
final class TrackMatch {
  const TrackMatch({
    required this.source,
    required this.sourceTrack,
    required this.status,
    this.candidates = const [],
    this.chosen,
  });

  final SourceRef source;

  /// What is known about the source song (title, artist…), when anything is.
  final Track? sourceTrack;
  final MatchStatus status;

  /// Best first; for [MatchStatus.uncertain] up to three.
  final List<Track> candidates;

  /// What will be written; null = this song's Memories are not copied.
  final Track? chosen;

  TrackMatch pick(Track? track) => TrackMatch(
    source: source,
    sourceTrack: sourceTrack,
    status: track == null ? MatchStatus.uncertain : MatchStatus.picked,
    candidates: candidates,
    chosen: track,
  );

  TrackPick toPick() => TrackPick(
    source,
    status,
    chosen == null
        ? null
        : TrackTarget(
            provider: chosen!.id.provider,
            id: chosen!.id.rawId,
            title: chosen!.title,
            artist: chosen!.artist,
            albumProvider: chosen!.albumId?.provider,
            albumId: chosen!.albumId?.rawId,
          ),
  );
}

/// One source album and where it goes.
@immutable
final class AlbumMatch {
  const AlbumMatch({
    required this.source,
    this.sourceAlbum,
    required this.status,
    this.candidates = const [],
    this.chosen,
  });

  final SourceRef source;
  final Album? sourceAlbum;
  final MatchStatus status;
  final List<Album> candidates;
  final Album? chosen;

  AlbumMatch pick(Album? album) => AlbumMatch(
    source: source,
    sourceAlbum: sourceAlbum,
    status: album == null ? MatchStatus.uncertain : MatchStatus.picked,
    candidates: candidates,
    chosen: album,
  );

  AlbumPick toPick() => AlbumPick(
    source,
    status,
    chosen == null
        ? null
        : AlbumTarget(provider: chosen!.id.provider, id: chosen!.id.rawId, name: chosen!.name, artist: chosen!.artist),
  );
}

/// What a source song needs matching with (the plan's list) plus the ledger's earlier answer.
@immutable
final class TrackToMatch {
  const TrackToMatch({required this.source, this.title, this.artist, this.album, this.albumId, this.durationSec});

  final SourceRef source;
  final String? title;
  final String? artist;
  final String? album;
  final String? albumId;
  final int? durationSec;
}

@immutable
final class AlbumToMatch {
  const AlbumToMatch({required this.source, this.name, this.artist});
  final SourceRef source;
  final String? name;
  final String? artist;
}

abstract final class MemoryMatching {
  static MediaId? _mediaId(String provider, String id) =>
      provider.isEmpty || provider.contains(':') || id.isEmpty ? null : MediaId(provider, id);

  /// [tracks] on the target, in order; [onEach] after each song.
  static Future<List<TrackMatch>> matchTracks(
    List<TrackToMatch> tracks,
    MemoryCatalog? catalog, {
    void Function()? onEach,
  }) async {
    final out = <TrackMatch>[];
    final sources = <TrackToMatch, Track?>{};
    for (final t in tracks) {
      sources[t] = await _sourceTrack(t, catalog);
    }
    final isrcs = {
      for (final track in sources.values)
        if (track != null && catalog?.isrcOf(track) != null) catalog!.isrcOf(track)!,
    }.toList();
    var byIsrc = const <String, List<Track>>{};
    if (catalog != null && isrcs.isNotEmpty) byIsrc = await _safely(const {}, () => catalog.tracksByIsrc(isrcs));
    final searches = <String, List<Track>>{};
    Future<List<Track>> search(String query) async => searches[query] ??= catalog == null
        ? const []
        : await _safely(const <Track>[], () => catalog.searchTracks(query));

    for (final MapEntry(key: item, value: track) in sources.entries) {
      if (track == null || (track.title?.trim().isEmpty ?? true) || catalog == null) {
        out.add(TrackMatch(source: item.source, sourceTrack: track, status: MatchStatus.unmatched));
        onEach?.call();
        continue;
      }
      final isrc = catalog.isrcOf(track);
      final hits = isrc == null ? null : byIsrc[isrc];
      final isrcDecision = hits == null ? null : TrackMatcher.chooseByIsrc(track, hits);
      MatchDecision decision;
      if (isrcDecision is MatchMatched) {
        decision = isrcDecision;
      } else {
        final query = TrackMatcher.searchQuery(track);
        decision = query == null ? const MatchNotFound() : TrackMatcher.choose(track, await search(query));
        final titleOnly = TrackMatcher.titleQuery(track);
        if (decision is MatchNotFound && titleOnly != null && titleOnly != query) {
          decision = TrackMatcher.choose(track, await search(titleOnly));
        }
      }
      out.add(switch (decision) {
        MatchMatched(:final track) => TrackMatch(
          source: item.source,
          sourceTrack: sources[item],
          status: MatchStatus.matched,
          candidates: [track],
          chosen: track,
        ),
        MatchAmbiguous(:final candidates) when candidates.isNotEmpty => TrackMatch(
          source: item.source,
          sourceTrack: track,
          status: MatchStatus.uncertain,
          candidates: candidates.take(TransferRules.maxCandidates).toList(),
        ),
        _ => TrackMatch(source: item.source, sourceTrack: track, status: MatchStatus.unmatched),
      });
      onEach?.call();
    }
    return out;
  }

  /// What is known about a source song: local facts, a note's title, else the source service's description.
  static Future<Track?> _sourceTrack(TrackToMatch t, MemoryCatalog? catalog) async {
    final id = _mediaId(t.source.provider, t.source.id);
    if (id == null) return null;
    if (t.title != null && t.title!.trim().isNotEmpty) {
      return Track(
        id: id,
        title: t.title,
        artist: t.artist,
        album: t.album,
        albumId: t.albumId == null ? null : _mediaId(t.source.provider, t.albumId!),
        durationSec: t.durationSec,
      );
    }
    if (catalog == null) return null;
    return _safely<Track?>(null, () => catalog.describeSourceTrack(t.source));
  }

  /// [album] on the target: by the songs already matched ([tracks], with what
  /// each source song's album is), else by search.
  static Future<AlbumMatch> matchAlbum(
    AlbumToMatch album,
    List<TrackMatch> tracks,
    String? Function(SourceRef track) sourceAlbumOf,
    MemoryCatalog? catalog,
  ) async {
    final sourceId = _mediaId(album.source.provider, album.source.id);
    final name = album.name?.trim().isNotEmpty ?? false ? album.name! : null;
    final sourceAlbum = name == null || sourceId == null ? null : Album(id: sourceId, name: name, artist: album.artist);
    // 1. The target album most of this album's matched songs are on.
    final votes = <MediaId, List<Track>>{};
    for (final match in tracks) {
      final chosen = match.chosen;
      if (chosen == null || !match.status.copies) continue;
      final sourceAlbumId = sourceAlbumOf(match.source) ?? match.sourceTrack?.albumId?.rawId;
      if (sourceAlbumId != album.source.id || match.source.provider != album.source.provider) continue;
      final targetAlbum = chosen.albumId;
      if (targetAlbum == null) continue;
      (votes[targetAlbum] ??= []).add(chosen);
    }
    if (votes.isNotEmpty) {
      final ranked = votes.entries.toList();
      // Stable: equal counts keep their first-seen order, as Kotlin's sortedByDescending.
      final indexed = [for (final (i, e) in ranked.indexed) (i, e)]
        ..sort((a, b) {
          final c = b.$2.value.length.compareTo(a.$2.value.length);
          return c != 0 ? c : a.$1.compareTo(b.$1);
        });
      final top = indexed.first.$2.value.length;
      final leaders = [
        for (final e in indexed)
          if (e.$2.value.length == top) e.$2,
      ];
      final candidates = [
        for (final e in leaders.take(TransferRules.maxCandidates))
          Album(id: e.key, name: e.value.first.album ?? '', artist: e.value.first.artist),
      ];
      return leaders.length == 1
          ? AlbumMatch(
              source: album.source,
              sourceAlbum: sourceAlbum,
              status: MatchStatus.matched,
              candidates: candidates,
              chosen: candidates.first,
            )
          : AlbumMatch(
              source: album.source,
              sourceAlbum: sourceAlbum,
              status: MatchStatus.uncertain,
              candidates: candidates,
            );
    }
    // 2. Album search by title + artist.
    if (catalog == null || name == null) {
      return AlbumMatch(source: album.source, sourceAlbum: sourceAlbum, status: MatchStatus.unmatched);
    }
    final stripped = TrackMatcher.stripDecorations(name);
    final firstArtist = album.artist == null ? null : TrackMatcher.splitArtists(album.artist!).firstOrNull;
    final query = [stripped.trim().isEmpty ? name : stripped, ?firstArtist].join(' ');
    final found = await _safely(const <Album>[], () => catalog.searchAlbums(query));
    final scored = [
      for (final a in found)
        if (_albumScore(name, album.artist, a) > 0) (a, _albumScore(name, album.artist, a)),
    ];
    final ordered = [for (final (i, s) in scored.indexed) (i, s)]
      ..sort((a, b) {
        final c = b.$2.$2.compareTo(a.$2.$2);
        return c != 0 ? c : a.$1.compareTo(b.$1);
      });
    if (ordered.isEmpty) {
      return AlbumMatch(source: album.source, sourceAlbum: sourceAlbum, status: MatchStatus.unmatched);
    }
    final best = ordered.first.$2;
    final sameVersion = setEquals(TrackMatcher.versionTags(name), TrackMatcher.versionTags(best.$1.name));
    if (best.$2 >= TrackMatcher.matchScore && sameVersion) {
      return AlbumMatch(
        source: album.source,
        sourceAlbum: sourceAlbum,
        status: MatchStatus.matched,
        candidates: [best.$1],
        chosen: best.$1,
      );
    }
    if (best.$2 >= TrackMatcher.ambiguousScore) {
      return AlbumMatch(
        source: album.source,
        sourceAlbum: sourceAlbum,
        status: MatchStatus.uncertain,
        candidates: [
          for (final e
              in ordered.takeWhile((e) => e.$2.$2 >= TrackMatcher.ambiguousScore).take(TransferRules.maxCandidates))
            e.$2.$1,
        ],
      );
    }
    return AlbumMatch(source: album.source, sourceAlbum: sourceAlbum, status: MatchStatus.unmatched);
  }

  static double _albumScore(String name, String? artist, Album candidate) {
    final title = TrackMatcher.titleSimilarity(name, candidate.name);
    if (title < 0.6) return 0;
    final credit =
        artist == null || artist.trim().isEmpty || candidate.artist == null || candidate.artist!.trim().isEmpty
        ? 0.6
        : TrackMatcher.artistSimilarity(artist, candidate.artist);
    if (credit < 0.5) return 0;
    return 0.6 * title + 0.4 * credit;
  }

  static Future<T> _safely<T>(T fallback, Future<T> Function() body) async {
    try {
      return await body();
    } catch (_) {
      return fallback;
    }
  }
}
