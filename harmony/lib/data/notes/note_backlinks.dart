/// 被提及 (Android data/notes/NoteBacklinks.kt): for an album, a track or an
/// artist, every song note, album note and album review of the same account
/// that mentions it, newest first. A mention on the target's own provider
/// counts by id (and, on Spotify / Apple Music, by name when the id differs);
/// one on another provider by name ([MentionMatcher]). A track mention counts
/// toward its album; writings on the album itself (its review, its album
/// notes, its tracks' notes) are left out — they are already on that page.
library;

import 'package:flutter/foundation.dart';

import '../memory/memory_store.dart';
import '../memory/track_album_links.dart';
import '../model/model.dart';
import 'mention_matcher.dart';
import 'note_markup.dart';

/// What a 被提及 section is for.
sealed class BacklinkTarget {
  const BacklinkTarget();

  MediaId get id;
}

/// [tracks]: the album's tracks, so a mention of one of them counts toward the album.
final class BacklinkAlbum extends BacklinkTarget {
  const BacklinkAlbum(this.id, {required this.title, this.artist, this.tracks = const []});

  @override
  final MediaId id;
  final String title;
  final String? artist;
  final List<BacklinkTrack> tracks;
}

final class BacklinkTrackTarget extends BacklinkTarget {
  const BacklinkTrackTarget(this.id, {required this.title, this.artist, this.album});

  @override
  final MediaId id;
  final String title;
  final String? artist;
  final String? album;
}

final class BacklinkArtist extends BacklinkTarget {
  const BacklinkArtist(this.id, {required this.name});

  @override
  final MediaId id;
  final String name;
}

@immutable
final class BacklinkTrack {
  const BacklinkTrack(this.id, {required this.title, this.artist});

  final MediaId id;
  final String title;
  final String? artist;
}

/// Where a backlink was written. Tapping it opens that item's scrapbook.
sealed class BacklinkSource {
  const BacklinkSource();

  /// The source's cover as Yoin's plays stored it (a CoverRef storage key); null when no play did.
  String? get coverKey;

  /// A stable order key for writings at the same moment (Android's `source.toString()`).
  String get sortKey;
}

/// [albumId]: the album the track belongs to, when Yoin knows it (a play or a track→album link).
final class BacklinkSongNote extends BacklinkSource {
  const BacklinkSongNote({
    required this.noteId,
    required this.trackId,
    required this.trackTitle,
    required this.trackArtist,
    this.positionMs,
    this.albumId,
    this.coverKey,
  });

  final String noteId;
  final MediaId trackId;
  final String trackTitle;
  final String trackArtist;
  final int? positionMs;
  final MediaId? albumId;
  @override
  final String? coverKey;

  @override
  String get sortKey => 'SongNote($noteId)';
}

final class BacklinkAlbumNote extends BacklinkSource {
  const BacklinkAlbumNote({
    required this.noteId,
    required this.albumId,
    required this.albumName,
    required this.albumArtist,
    this.coverKey,
  });

  final String noteId;
  final MediaId albumId;
  final String albumName;
  final String albumArtist;
  @override
  final String? coverKey;

  @override
  String get sortKey => 'AlbumNote($noteId)';
}

/// [albumName] / [albumArtist]: as Yoin's plays or album notes know them; null when neither does.
final class BacklinkReview extends BacklinkSource {
  const BacklinkReview({required this.albumId, this.albumName, this.albumArtist, this.coverKey});

  final MediaId albumId;
  final String? albumName;
  final String? albumArtist;
  @override
  final String? coverKey;

  @override
  String get sortKey => 'Review($albumId)';
}

/// One writing as the backlink query reads it.
@immutable
final class BacklinkWriting {
  const BacklinkWriting(this.source, this.text, this.at);

  final BacklinkSource source;
  final String text;
  final int at;
}

/// One 被提及 clipping: the writing, a one-line [snippet] (plain words, " · "
/// between lines) and when it was written. [mentions]: the mentions in it that
/// point at the target; [marks]: where their names sit in [snippet] (what the
/// clipping marks, end exclusive).
@immutable
final class NoteBacklink {
  const NoteBacklink({
    required this.source,
    required this.text,
    required this.snippet,
    required this.at,
    required this.mentions,
    this.marks = const [],
  });

  final BacklinkSource source;
  final String text;
  final String snippet;
  final int at;
  final List<NoteMention> mentions;
  final List<(int, int)> marks;
}

/// The writings among [writings] that mention [target], newest first. Pure.
List<NoteBacklink> findBacklinks(List<BacklinkWriting> writings, BacklinkTarget target) {
  final out = <NoteBacklink>[];
  for (final writing in writings) {
    if (_owns(target, writing.source)) continue;
    final hits = NoteMarkup.mentions(writing.text).where((m) => isMentionedBy(target, m)).toList();
    if (hits.isEmpty) continue;
    final snippet = NoteMarkup.snippetWithMentions(writing.text);
    out.add(
      NoteBacklink(
        source: writing.source,
        text: writing.text,
        snippet: snippet.text,
        at: writing.at,
        mentions: hits,
        marks: [
          for (final span in snippet.mentions)
            if (isMentionedBy(target, span.mention)) (span.start, span.end),
        ],
      ),
    );
  }
  out.sort((a, b) {
    final at = b.at.compareTo(a.at);
    return at != 0 ? at : a.source.sortKey.compareTo(b.source.sortKey);
  });
  return out;
}

/// Services whose same-service mentions with another id are also matched by
/// name (catalogue vs library ids, reissues). A Subsonic id is the server's own.
const Set<String> backlinkNameMatchedServices = {MediaId.providerSpotify, MediaId.providerAppleMusic};

/// Whether [mention] points at [target]: by id on the target's provider, by name on any other.
bool isMentionedBy(BacklinkTarget target, NoteMention mention) {
  final id = target.id;
  final sameProvider = mention.provider == id.provider;
  final idMatches =
      sameProvider &&
      switch (target) {
        BacklinkAlbum(:final tracks) => switch (mention.kind) {
          MentionKind.album => mention.id == id.rawId,
          MentionKind.track => tracks.any((t) => t.id.provider == mention.provider && t.id.rawId == mention.id),
          MentionKind.artist => false,
        },
        BacklinkTrackTarget() => mention.kind == MentionKind.track && mention.id == id.rawId,
        BacklinkArtist() => mention.kind == MentionKind.artist && mention.id == id.rawId,
      };
  if (idMatches) return true;
  if (sameProvider && !backlinkNameMatchedServices.contains(id.provider)) return false;
  return switch (target) {
    BacklinkAlbum(:final title, :final artist, :final tracks) => switch (mention.kind) {
      MentionKind.album => MentionMatcher.namesMatchAlbum(mention, title, artist),
      MentionKind.track =>
        MentionMatcher.trackMentionOnAlbum(mention, title, artist) ||
            tracks.any((t) => MentionMatcher.namesMatchTrack(mention, t.title, t.artist ?? artist, title)),
      MentionKind.artist => false,
    },
    BacklinkTrackTarget(:final title, :final artist, :final album) => MentionMatcher.namesMatchTrack(
      mention,
      title,
      artist,
      album,
    ),
    BacklinkArtist(:final name) => MentionMatcher.namesMatchArtist(mention, name),
  };
}

/// A writing that already sits on the target's own page.
bool _owns(BacklinkTarget target, BacklinkSource source) => switch (target) {
  BacklinkAlbum(:final id, :final tracks) => switch (source) {
    BacklinkAlbumNote(:final albumId) => albumId == id,
    BacklinkReview(:final albumId) => albumId == id,
    BacklinkSongNote(:final albumId, :final trackId) => albumId == id || tracks.any((t) => t.id == trackId),
  },
  BacklinkTrackTarget(:final id) => source is BacklinkSongNote && source.trackId == id,
  BacklinkArtist() => false,
};

/// The account's writings that hold a mention (Android backlinkWritings over
/// NoteMentionDao): song notes, album notes and reviews, blank ones dropped,
/// each with the cover, album and names Yoin's plays (and track→album links) know.
Future<List<BacklinkWriting>> backlinkWritings(MemoryStore store, {TrackAlbumLinkStore? links}) async {
  final trackPlays = {for (final t in await store.trackPlays()) t.trackId: t};
  final albumPlays = {for (final a in await store.albumPlays()) a.albumId: a};
  final albumNotes = await store.allAlbumNotes();
  final linked = links == null ? const <MediaId, TrackAlbumLink>{} : await links.links();
  String? nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
  final out = <BacklinkWriting>[];
  for (final note in await store.songNotes()) {
    if (note.content.trim().isEmpty || !NoteMarkup.hasMentions(note.content)) continue;
    final play = trackPlays[note.trackId];
    final albumId = note.albumId ?? play?.albumId ?? linked[note.trackId]?.albumId;
    out.add(
      BacklinkWriting(
        BacklinkSongNote(
          noteId: note.id,
          trackId: note.trackId,
          trackTitle: nonBlank(note.title) ?? play?.title ?? '',
          trackArtist: nonBlank(note.artist) ?? play?.artist ?? '',
          positionMs: note.positionMs,
          albumId: albumId,
          coverKey: play?.coverArtKey ?? (albumId == null ? null : albumPlays[albumId]?.coverArtKey),
        ),
        note.content,
        note.createdAt,
      ),
    );
  }
  for (final note in albumNotes) {
    if (note.content.trim().isEmpty || !NoteMarkup.hasMentions(note.content)) continue;
    final play = albumPlays[note.albumId];
    out.add(
      BacklinkWriting(
        BacklinkAlbumNote(
          noteId: note.id,
          albumId: note.albumId,
          albumName: nonBlank(note.albumName) ?? play?.albumName ?? '',
          albumArtist: nonBlank(note.artist) ?? play?.artistName ?? '',
          coverKey: play?.coverArtKey,
        ),
        note.content,
        note.createdAt,
      ),
    );
  }
  for (final row in (await store.albumRatings()).values) {
    final review = row.review;
    if (review == null || review.trim().isEmpty || !NoteMarkup.hasMentions(review)) continue;
    final play = albumPlays[row.albumId];
    TrackPlays? track;
    for (final t in trackPlays.values) {
      if (t.albumId == row.albumId && t.album.trim().isNotEmpty) track = t;
    }
    AlbumNote? named;
    for (final n in albumNotes) {
      if (n.albumId == row.albumId && n.albumName.trim().isNotEmpty) named = n;
    }
    out.add(
      BacklinkWriting(
        BacklinkReview(
          albumId: row.albumId,
          albumName: nonBlank(play?.albumName) ?? nonBlank(track?.album) ?? nonBlank(named?.albumName),
          albumArtist: nonBlank(play?.artistName) ?? nonBlank(track?.artist) ?? nonBlank(named?.artist),
          coverKey: play?.coverArtKey ?? track?.coverArtKey,
        ),
        review,
        row.reviewUpdatedAt ?? row.updatedAt,
      ),
    );
  }
  return out;
}
