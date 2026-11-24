/// What a note's mention points at for the account in use (Android
/// data/notes/MentionResolver.kt, owner 2026-11-22), locally (no service is
/// asked):
///  1. by id: a mention on the account's own provider is that item;
///  2. by name: any other provider's mention (a Spotify mention read on
///     Subsonic) is matched by title + artist (+ album for a track) against
///     the tracks and albums this account knows ([MentionMatcher]): its plays,
///     notes and track → album answers. An artist has no local record to
///     match, so it stays unresolved;
///  3. otherwise unresolved: drawn as plain text in the text's own colour, not
///     tappable.
library;

import 'package:flutter/foundation.dart';

import '../memory/memory_store.dart';
import '../memory/track_album_links.dart';
import '../model/model.dart';
import 'mention_matcher.dart';
import 'note_markup.dart';

/// The account a mention is read under: its profile, and the provider its ids belong to.
@immutable
final class MentionAccount {
  const MentionAccount(this.profileId, this.provider);

  final String profileId;
  final String provider;

  @override
  bool operator ==(Object other) =>
      other is MentionAccount && other.profileId == profileId && other.provider == provider;

  @override
  int get hashCode => Object.hash(profileId, provider);
}

enum MentionVia {
  /// The mention's own id (same provider as the account).
  id,

  /// Matched by name against what the account knows locally.
  name,
}

sealed class MentionResolution {
  const MentionResolution(this.mention);

  final NoteMention mention;
}

/// [id] is an item of the account in use.
final class MentionResolved extends MentionResolution {
  const MentionResolved(super.mention, this.id, this.via);

  final MediaId id;
  final MentionVia via;
}

/// Nothing here answers to it.
final class MentionUnresolved extends MentionResolution {
  const MentionUnresolved(super.mention);
}

/// One local name a mention can be matched against.
@immutable
final class MentionNameRow {
  const MentionNameRow(this.id, this.title, {this.artist, this.album});

  final MediaId id;
  final String title;
  final String? artist;
  final String? album;
}

/// The names an account knows locally.
abstract interface class MentionNameLookup {
  Future<List<MentionNameRow>> tracks();

  Future<List<MentionNameRow>> albums();
}

/// Names from the account's Memory rows: songs played in Yoin, songs with
/// notes, songs whose album was looked up, and albums played in Yoin.
final class MemoryMentionNameLookup implements MentionNameLookup {
  const MemoryMentionNameLookup(this.store, {this.links});

  final MemoryStore store;
  final TrackAlbumLinkStore? links;

  @override
  Future<List<MentionNameRow>> tracks() async => [
    for (final t in await store.trackPlays())
      if (t.title.trim().isNotEmpty) MentionNameRow(t.trackId, t.title, artist: t.artist, album: t.album),
    for (final n in await store.songNotes())
      if (n.title.trim().isNotEmpty) MentionNameRow(n.trackId, n.title, artist: n.artist),
    for (final l in (await links?.links())?.values ?? const <TrackAlbumLink>[])
      if (l.title.trim().isNotEmpty) MentionNameRow(l.trackId, l.title, artist: l.artist, album: l.albumName),
  ];

  @override
  Future<List<MentionNameRow>> albums() async => [
    for (final a in await store.albumPlays())
      if (a.albumName.trim().isNotEmpty) MentionNameRow(a.albumId, a.albumName, artist: a.artistName),
  ];
}

final class LocalMentionResolver {
  const LocalMentionResolver({required this.account, required this.names});

  final MentionAccount? account;
  final MentionNameLookup? names;

  Future<MentionResolution> resolve(NoteMention mention) async {
    final current = account;
    if (current == null) return MentionUnresolved(mention);
    if (mention.provider == current.provider) {
      return MentionResolved(mention, MediaId(current.provider, mention.id), MentionVia.id);
    }
    MediaId? found;
    try {
      found = await _byName(mention, current.provider);
    } catch (_) {
      found = null;
    }
    return found == null ? MentionUnresolved(mention) : MentionResolved(mention, found, MentionVia.name);
  }

  Future<MediaId?> _byName(NoteMention mention, String provider) async {
    final lookup = names;
    if (lookup == null) return null;
    bool mine(MentionNameRow row) => row.id.provider == provider;
    switch (mention.kind) {
      case MentionKind.track:
        final rows = (await lookup.tracks())
            .where((row) => mine(row) && MentionMatcher.namesMatchTrack(mention, row.title, row.artist, row.album))
            .toList();
        if (rows.isEmpty) return null;
        // A row that names the same album beats one that doesn't say.
        final named = _isBlank(mention.album) ? null : rows.where((row) => !_isBlank(row.album)).firstOrNull;
        return (named ?? rows.first).id;
      case MentionKind.album:
        return (await lookup.albums())
            .where((row) => mine(row) && MentionMatcher.namesMatchAlbum(mention, row.title, row.artist))
            .firstOrNull
            ?.id;
      case MentionKind.artist:
        return null;
    }
  }

  static bool _isBlank(String? s) => s == null || isBlank(s);
}
