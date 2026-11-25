/// Play from a note's second (#Yoin:#18; :shared search/NoteOpenTarget.kt,
/// ported against memory-search.json), as the Memories diary and the album
/// scrapbook do it: a note tile on a song the service returned plays that
/// song and, once it is current and prepared, seeks to where the note was
/// written; a note on a song that isn't there opens its album's scrapbook at
/// the song, in the note's own account. The screen plays, seeks, switches
/// accounts and navigates.
library;

import 'memory_search.dart';

enum NoteOpenAction {
  /// Play [NoteOpenTarget.song] (the returned song), then seek to [NoteOpenTarget.seekToMs].
  playSong,

  /// Open [NoteOpenTarget.album]'s scrapbook in [NoteOpenTarget.profileId]'s account, at [NoteOpenTarget.song].
  openAlbum,

  /// Nothing to open: the song isn't returned and its album is unknown.
  none,
}

/// A song or album by provider and raw id.
typedef NoteOpenRef = ({String provider, String rawId});

final class NoteOpenTarget {
  const NoteOpenTarget({
    required this.action,
    required this.song,
    required this.album,
    required this.profileId,
    required this.switchAccount,
    required this.seekToMs,
    required this.subject,
  });

  final NoteOpenAction action;

  /// playSong: the song to play. openAlbum: the song to focus on the scrapbook, when the note knows it.
  final NoteOpenRef? song;

  /// openAlbum: the album whose scrapbook opens.
  final NoteOpenRef? album;

  /// The note's account.
  final String profileId;

  /// openAlbum: the note is another account's; switch to it first (when that account still exists).
  final bool switchAccount;

  /// playSong: where to seek once the song is current (ms); null plays it from the start.
  final int? seekToMs;

  /// openAlbum: what an account-switch prompt names: the song, else the album.
  final String? subject;
}

/// Where opening [note] leads. [returnedSong] = the service's song the note's
/// tile is on (it joined that tile), null for a note standing alone.
/// [activeProfileId] = the account in use.
NoteOpenTarget noteOpenTarget(NoteSearchHit note, NoteOpenRef? returnedSong, String? activeProfileId) {
  if (returnedSong != null) {
    final position = note.positionMs;
    return NoteOpenTarget(
      action: NoteOpenAction.playSong,
      song: returnedSong,
      album: null,
      profileId: note.profileId,
      switchAccount: false,
      seekToMs: position != null && position > 0 ? position : null,
      subject: null,
    );
  }
  final album = note.album;
  if (album == null) {
    return NoteOpenTarget(
      action: NoteOpenAction.none,
      song: null,
      album: null,
      profileId: note.profileId,
      switchAccount: false,
      seekToMs: null,
      subject: null,
    );
  }
  final provider = note.provider;
  final songId = note.songId;
  return NoteOpenTarget(
    action: NoteOpenAction.openAlbum,
    song: provider != null && songId != null ? (provider: provider, rawId: songId) : null,
    album: (provider: album.provider, rawId: album.albumId),
    profileId: note.profileId,
    switchAccount: note.profileId != activeProfileId,
    seekToMs: null,
    subject: note.trackTitle ?? album.name,
  );
}

/// How long to wait for the song to become current before giving up on the
/// seek (a provider that never reports it).
const Duration noteSeekTimeout = Duration(milliseconds: 4000);

/// Whether the player is ready for the note's seek: [targetRawId] is current and its duration known.
bool noteSeekReady(String? currentRawId, int durationMs, String targetRawId) =>
    currentRawId == targetRawId && durationMs > 0;
