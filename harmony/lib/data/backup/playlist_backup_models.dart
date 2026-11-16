/// Playlist backups (Android data/backup/PlaylistBackupModels.kt;
/// docs/playlist-transfer.md §7): a playlist kept as plain metadata, to be
/// restored into any profile later. Every JSON name is a wire format: the
/// same JSON lives in Android's Room and in the user's Drive (sync kind
/// `playlist_backup`), so the field names and their order are Android's
/// kotlinx output (`encodeDefaults`, `explicitNulls`).
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../model/model.dart';

/// What a backup holds besides its list columns: [PlaylistBackupRow.contentJson].
@immutable
final class PlaylistBackupContent {
  const PlaylistBackupContent({this.description, this.tracks = const [], this.unexported = const []});

  final String? description;
  final List<BackupTrack> tracks;
  final List<BackupUnexported> unexported;

  Map<String, Object?> toJson() => {
    'description': description,
    'tracks': [for (final t in tracks) t.toJson()],
    'unexported': [for (final u in unexported) u.toJson()],
  };

  /// Throws [FormatException] when [json] isn't a content object this app can read.
  static PlaylistBackupContent fromJson(Object? json) {
    if (json is! Map) throw const FormatException('content');
    return PlaylistBackupContent(
      description: _optString(json, 'description'),
      tracks: [for (final t in _list(json, 'tracks')) BackupTrack.fromJson(t)],
      unexported: [for (final u in _list(json, 'unexported')) BackupUnexported.fromJson(u)],
    );
  }

  @override
  bool operator ==(Object other) =>
      other is PlaylistBackupContent &&
      other.description == description &&
      listEquals(other.tracks, tracks) &&
      listEquals(other.unexported, unexported);

  @override
  int get hashCode => Object.hash(description, Object.hashAll(tracks), Object.hashAll(unexported));
}

/// One track of a backup, in playlist order. Metadata only: no audio, no cover.
@immutable
final class BackupTrack {
  const BackupTrack({
    required this.title,
    this.artists = const [],
    this.album,
    this.durationSec,
    this.isrc,
    required this.sourceTrackId,
  });

  final String title;
  final List<String> artists;
  final String? album;
  final int? durationSec;
  final String? isrc;

  /// The track's [MediaId] on the service it was backed up from (wire name `sourceId`).
  final String sourceTrackId;

  Map<String, Object?> toJson() => {
    'title': title,
    'artists': artists,
    'album': album,
    'durationSec': durationSec,
    'isrc': isrc,
    'sourceId': sourceTrackId,
  };

  static BackupTrack fromJson(Object? json) {
    if (json is! Map) throw const FormatException('track');
    return BackupTrack(
      title: _reqString(json, 'title'),
      artists: [for (final a in _list(json, 'artists')) a is String ? a : throw const FormatException('artist')],
      album: _optString(json, 'album'),
      durationSec: _optInt(json, 'durationSec'),
      isrc: _optString(json, 'isrc'),
      sourceTrackId: _reqString(json, 'sourceId'),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is BackupTrack &&
      other.title == title &&
      listEquals(other.artists, artists) &&
      other.album == album &&
      other.durationSec == durationSec &&
      other.isrc == isrc &&
      other.sourceTrackId == sourceTrackId;

  @override
  int get hashCode => Object.hash(title, Object.hashAll(artists), album, durationSec, isrc, sourceTrackId);
}

/// Why an entry of the playlist couldn't be backed up.
enum UnexportedReason {
  localFile('local_file'),
  episode('episode'),
  video('video'),
  unavailable('unavailable');

  const UnexportedReason(this.wireName);
  final String wireName;

  /// Unknown names read as [unavailable].
  static UnexportedReason fromWire(String name) =>
      values.where((r) => r.wireName == name).firstOrNull ?? UnexportedReason.unavailable;
}

/// An entry of the playlist that couldn't be backed up, and why.
@immutable
final class BackupUnexported {
  const BackupUnexported({required this.position, this.title, this.artist, required this.reason});

  /// The entry's place among all of the playlist's entries.
  final int position;
  final String? title;
  final String? artist;

  /// An [UnexportedReason.wireName]; unknown names read as [UnexportedReason.unavailable].
  final String reason;

  UnexportedReason get reasonKind => UnexportedReason.fromWire(reason);

  Map<String, Object?> toJson() => {'position': position, 'title': title, 'artist': artist, 'reason': reason};

  static BackupUnexported fromJson(Object? json) {
    if (json is! Map) throw const FormatException('unexported');
    final position = json['position'];
    if (position is! int) throw const FormatException('position');
    return BackupUnexported(
      position: position,
      title: _optString(json, 'title'),
      artist: _optString(json, 'artist'),
      reason: _reqString(json, 'reason'),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is BackupUnexported &&
      other.position == position &&
      other.title == title &&
      other.artist == artist &&
      other.reason == reason;

  @override
  int get hashCode => Object.hash(position, title, artist, reason);
}

/// An earlier version of a backed-up playlist: what it held until [until], when it changed.
@immutable
final class BackupVersion {
  const BackupVersion({required this.since, required this.until, required this.content});

  /// When this version began (the backup's lastChangedAt at the time).
  final int since;

  /// When the playlist changed away from it.
  final int until;
  final PlaylistBackupContent content;

  Map<String, Object?> toJson() => {'since': since, 'until': until, 'content': content.toJson()};

  static BackupVersion fromJson(Object? json) {
    if (json is! Map) throw const FormatException('version');
    final since = json['since'];
    final until = json['until'];
    if (since is! int || until is! int) throw const FormatException('version times');
    return BackupVersion(since: since, until: until, content: PlaylistBackupContent.fromJson(json['content']));
  }

  @override
  bool operator ==(Object other) =>
      other is BackupVersion && other.since == since && other.until == until && other.content == content;

  @override
  int get hashCode => Object.hash(since, until, content);
}

/// One restore of a backup: when, into which profile, and what came of it. Newest last in the stored list.
@immutable
final class PlaylistRestoreRecord {
  const PlaylistRestoreRecord({
    required this.at,
    required this.targetProvider,
    required this.targetLabel,
    required this.added,
    required this.skipped,
    required this.notFound,
    this.failed = false,
  });

  final int at;
  final String targetProvider;

  /// The target profile's name at the time.
  final String targetLabel;

  /// Songs written to the new playlist.
  final int added;

  /// Songs with a candidate the user left unchecked, or whose write failed.
  final int skipped;

  /// Songs the target had nothing for.
  final int notFound;

  /// No playlist was made.
  final bool failed;

  Map<String, Object?> toJson() => {
    'at': at,
    'targetProvider': targetProvider,
    'targetLabel': targetLabel,
    'added': added,
    'skipped': skipped,
    'notFound': notFound,
    'failed': failed,
  };

  static PlaylistRestoreRecord fromJson(Object? json) {
    if (json is! Map) throw const FormatException('restore');
    int req(String name) => json[name] is int ? json[name] as int : throw FormatException(name);
    final failed = json['failed'];
    if (failed != null && failed is! bool) throw const FormatException('failed');
    return PlaylistRestoreRecord(
      at: req('at'),
      targetProvider: _reqString(json, 'targetProvider'),
      targetLabel: _reqString(json, 'targetLabel'),
      added: req('added'),
      skipped: req('skipped'),
      notFound: req('notFound'),
      failed: failed as bool? ?? false,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is PlaylistRestoreRecord &&
      other.at == at &&
      other.targetProvider == targetProvider &&
      other.targetLabel == targetLabel &&
      other.added == added &&
      other.skipped == skipped &&
      other.notFound == notFound &&
      other.failed == failed;

  @override
  int get hashCode => Object.hash(at, targetProvider, targetLabel, added, skipped, notFound, failed);
}

/// A backup as stored (Android's `playlist_backups` row): the list columns and
/// the three JSON texts exactly as written, so sync hashes them alike on
/// every device and a newer device's extra fields survive.
@immutable
final class PlaylistBackupRow {
  const PlaylistBackupRow({
    required this.id,
    required this.name,
    required this.sourceProvider,
    required this.sourceProfileLabel,
    required this.sourcePlaylistId,
    this.sourceProfileId,
    this.sourceAccount,
    this.playlistCreatedAt,
    required this.firstBackedUpAt,
    required this.lastBackedUpAt,
    required this.lastChangedAt,
    this.sourceGoneAt,
    required this.trackCount,
    required this.unexportedCount,
    required this.contentJson,
    this.versionsJson = '[]',
    required this.restoresJson,
    required this.updatedAt,
  });

  final String id;
  final String name;
  final String sourceProvider;
  final String sourceProfileLabel;
  final String sourcePlaylistId;

  /// This device's profile that follows the playlist; never synced.
  final String? sourceProfileId;
  final String? sourceAccount;
  final String? playlistCreatedAt;
  final int firstBackedUpAt;
  final int lastBackedUpAt;
  final int lastChangedAt;
  final int? sourceGoneAt;
  final int trackCount;
  final int unexportedCount;
  final String contentJson;
  final String versionsJson;
  final String restoresJson;
  final int updatedAt;

  PlaylistBackupRow withRestores(String restoresJson, int updatedAt) => PlaylistBackupRow(
    id: id,
    name: name,
    sourceProvider: sourceProvider,
    sourceProfileLabel: sourceProfileLabel,
    sourcePlaylistId: sourcePlaylistId,
    sourceProfileId: sourceProfileId,
    sourceAccount: sourceAccount,
    playlistCreatedAt: playlistCreatedAt,
    firstBackedUpAt: firstBackedUpAt,
    lastBackedUpAt: lastBackedUpAt,
    lastChangedAt: lastChangedAt,
    sourceGoneAt: sourceGoneAt,
    trackCount: trackCount,
    unexportedCount: unexportedCount,
    contentJson: contentJson,
    versionsJson: versionsJson,
    restoresJson: restoresJson,
    updatedAt: updatedAt,
  );

  /// The file's JSON (HarmonyOS's own storage shape; not a wire format).
  Map<String, Object?> toJson() => {
    'id': id,
    'name': name,
    'sourceProvider': sourceProvider,
    'sourceProfileLabel': sourceProfileLabel,
    'sourcePlaylistId': sourcePlaylistId,
    'sourceProfileId': sourceProfileId,
    'sourceAccount': sourceAccount,
    'playlistCreatedAt': playlistCreatedAt,
    'firstBackedUpAt': firstBackedUpAt,
    'lastBackedUpAt': lastBackedUpAt,
    'lastChangedAt': lastChangedAt,
    'sourceGoneAt': sourceGoneAt,
    'trackCount': trackCount,
    'unexportedCount': unexportedCount,
    'contentJson': contentJson,
    'versionsJson': versionsJson,
    'restoresJson': restoresJson,
    'updatedAt': updatedAt,
  };

  /// null when [json] isn't a row (a damaged file is skipped, not fatal).
  static PlaylistBackupRow? tryParse(Object? json) {
    if (json is! Map) return null;
    String? s(String k) => json[k] is String ? json[k] as String : null;
    int? i(String k) => json[k] is int ? json[k] as int : null;
    final id = s('id');
    final name = s('name');
    final provider = s('sourceProvider');
    final label = s('sourceProfileLabel');
    final playlist = s('sourcePlaylistId');
    final first = i('firstBackedUpAt');
    final last = i('lastBackedUpAt');
    final changed = i('lastChangedAt');
    final content = s('contentJson');
    final restores = s('restoresJson');
    final updated = i('updatedAt');
    if (id == null ||
        name == null ||
        provider == null ||
        label == null ||
        playlist == null ||
        first == null ||
        last == null ||
        changed == null ||
        content == null ||
        restores == null ||
        updated == null) {
      return null;
    }
    return PlaylistBackupRow(
      id: id,
      name: name,
      sourceProvider: provider,
      sourceProfileLabel: label,
      sourcePlaylistId: playlist,
      sourceProfileId: s('sourceProfileId'),
      sourceAccount: s('sourceAccount'),
      playlistCreatedAt: s('playlistCreatedAt'),
      firstBackedUpAt: first,
      lastBackedUpAt: last,
      lastChangedAt: changed,
      sourceGoneAt: i('sourceGoneAt'),
      trackCount: i('trackCount') ?? 0,
      unexportedCount: i('unexportedCount') ?? 0,
      contentJson: content,
      versionsJson: s('versionsJson') ?? '[]',
      restoresJson: restores,
      updatedAt: updated,
    );
  }

  @override
  bool operator ==(Object other) => other is PlaylistBackupRow && mapEquals(other.toJson(), toJson());

  @override
  int get hashCode => Object.hashAll(toJson().values);
}

/// A backup as the app reads it: the row's columns with its JSON decoded.
@immutable
final class PlaylistBackupRecord {
  const PlaylistBackupRecord({
    required this.id,
    required this.name,
    required this.sourceProvider,
    required this.sourceProfileLabel,
    required this.sourcePlaylistId,
    required this.sourceProfileId,
    required this.playlistCreatedAt,
    required this.firstBackedUpAt,
    required this.lastBackedUpAt,
    required this.lastChangedAt,
    required this.sourceGoneAt,
    required this.content,
    required this.restores,
    required this.updatedAt,
    this.versions = const [],
    this.sourceAccount,
  });

  final String id;
  final String name;
  final String sourceProvider;
  final String sourceProfileLabel;
  final String sourcePlaylistId;

  /// This device's profile that follows the playlist; null when none here does.
  final String? sourceProfileId;

  /// The service's own date for the playlist (ISO-8601), when it gives one.
  final String? playlistCreatedAt;
  final int firstBackedUpAt;
  final int lastBackedUpAt;
  final int lastChangedAt;

  /// When the playlist was found gone; null while it is still followed.
  final int? sourceGoneAt;
  final PlaylistBackupContent content;
  final List<PlaylistRestoreRecord> restores;
  final int updatedAt;

  /// Earlier versions, newest first.
  final List<BackupVersion> versions;

  /// The account it came from, the same on every device; null when not known.
  final String? sourceAccount;

  int get trackCount => content.tracks.length;
  int get unexportedCount => content.unexported.length;

  /// Still backed up as it changes: the playlist is there.
  bool get following => sourceGoneAt == null;

  static const _keep = Object();

  PlaylistBackupRecord copyWith({
    String? name,
    String? sourceProfileLabel,
    Object? sourceProfileId = _keep,
    Object? playlistCreatedAt = _keep,
    int? lastBackedUpAt,
    int? lastChangedAt,
    Object? sourceGoneAt = _keep,
    PlaylistBackupContent? content,
    List<PlaylistRestoreRecord>? restores,
    int? updatedAt,
    List<BackupVersion>? versions,
    Object? sourceAccount = _keep,
  }) => PlaylistBackupRecord(
    id: id,
    name: name ?? this.name,
    sourceProvider: sourceProvider,
    sourceProfileLabel: sourceProfileLabel ?? this.sourceProfileLabel,
    sourcePlaylistId: sourcePlaylistId,
    sourceProfileId: identical(sourceProfileId, _keep) ? this.sourceProfileId : sourceProfileId as String?,
    playlistCreatedAt: identical(playlistCreatedAt, _keep) ? this.playlistCreatedAt : playlistCreatedAt as String?,
    firstBackedUpAt: firstBackedUpAt,
    lastBackedUpAt: lastBackedUpAt ?? this.lastBackedUpAt,
    lastChangedAt: lastChangedAt ?? this.lastChangedAt,
    sourceGoneAt: identical(sourceGoneAt, _keep) ? this.sourceGoneAt : sourceGoneAt as int?,
    content: content ?? this.content,
    restores: restores ?? this.restores,
    updatedAt: updatedAt ?? this.updatedAt,
    versions: versions ?? this.versions,
    sourceAccount: identical(sourceAccount, _keep) ? this.sourceAccount : sourceAccount as String?,
  );

  @override
  bool operator ==(Object other) =>
      other is PlaylistBackupRecord &&
      other.id == id &&
      other.name == name &&
      other.sourceProvider == sourceProvider &&
      other.sourceProfileLabel == sourceProfileLabel &&
      other.sourcePlaylistId == sourcePlaylistId &&
      other.sourceProfileId == sourceProfileId &&
      other.playlistCreatedAt == playlistCreatedAt &&
      other.firstBackedUpAt == firstBackedUpAt &&
      other.lastBackedUpAt == lastBackedUpAt &&
      other.lastChangedAt == lastChangedAt &&
      other.sourceGoneAt == sourceGoneAt &&
      other.content == content &&
      listEquals(other.restores, restores) &&
      other.updatedAt == updatedAt &&
      listEquals(other.versions, versions) &&
      other.sourceAccount == sourceAccount;

  @override
  int get hashCode => Object.hash(id, updatedAt, lastBackedUpAt, content);
}

/// Encodes and decodes the JSON texts as Android's kotlinx does (every field,
/// nulls written), so the same backup is the same bytes everywhere.
abstract final class PlaylistBackupCodec {
  static String encodeContent(PlaylistBackupContent content) => jsonEncode(content.toJson());

  static PlaylistBackupContent decodeContent(String text) => PlaylistBackupContent.fromJson(jsonDecode(text));

  static String encodeVersions(List<BackupVersion> versions) => jsonEncode([for (final v in versions) v.toJson()]);

  static List<BackupVersion> decodeVersions(String text) {
    final json = jsonDecode(text);
    if (json is! List) throw const FormatException('versions');
    return [for (final v in json) BackupVersion.fromJson(v)];
  }

  static String encodeRestores(List<PlaylistRestoreRecord> restores) =>
      jsonEncode([for (final r in restores) r.toJson()]);

  static List<PlaylistRestoreRecord> decodeRestores(String text) {
    final json = jsonDecode(text);
    if (json is! List) throw const FormatException('restores');
    return [for (final r in json) PlaylistRestoreRecord.fromJson(r)];
  }

  static PlaylistBackupRow toRow(PlaylistBackupRecord record) => PlaylistBackupRow(
    id: record.id,
    name: record.name,
    sourceProvider: record.sourceProvider,
    sourceProfileLabel: record.sourceProfileLabel,
    sourcePlaylistId: record.sourcePlaylistId,
    sourceProfileId: record.sourceProfileId,
    sourceAccount: record.sourceAccount,
    playlistCreatedAt: record.playlistCreatedAt,
    firstBackedUpAt: record.firstBackedUpAt,
    lastBackedUpAt: record.lastBackedUpAt,
    lastChangedAt: record.lastChangedAt,
    sourceGoneAt: record.sourceGoneAt,
    trackCount: record.trackCount,
    unexportedCount: record.unexportedCount,
    contentJson: encodeContent(record.content),
    versionsJson: encodeVersions(record.versions),
    restoresJson: encodeRestores(record.restores),
    updatedAt: record.updatedAt,
  );

  /// null when the row's JSON can't be read (a newer, incompatible shape).
  static PlaylistBackupRecord? fromRow(PlaylistBackupRow row) {
    try {
      return PlaylistBackupRecord(
        id: row.id,
        name: row.name,
        sourceProvider: row.sourceProvider,
        sourceProfileLabel: row.sourceProfileLabel,
        sourcePlaylistId: row.sourcePlaylistId,
        sourceProfileId: row.sourceProfileId,
        playlistCreatedAt: row.playlistCreatedAt,
        firstBackedUpAt: row.firstBackedUpAt,
        lastBackedUpAt: row.lastBackedUpAt,
        lastChangedAt: row.lastChangedAt,
        sourceGoneAt: row.sourceGoneAt,
        content: decodeContent(row.contentJson),
        restores: decodeRestores(row.restoresJson),
        updatedAt: row.updatedAt,
        versions: decodeVersions(row.versionsJson),
        sourceAccount: row.sourceAccount,
      );
    } catch (_) {
      return null;
    }
  }
}

/// Turns a playlist read from its service into a backup, and a backup back into tracks to match.
abstract final class PlaylistSnapshots {
  /// Versions a backup keeps, the current one included (owner 2026-10-19: only the latest few).
  static const int keepVersions = 5;

  /// The start of [playlist]'s backup, made [now]: read whole from the profile
  /// [profileId] ([profileLabel]) on [provider], account [account]. [isrcOf]
  /// gives a track's ISRC where its service knows one.
  static PlaylistBackupRecord backup({
    required String id,
    required Playlist playlist,
    required String provider,
    required String? profileId,
    required String profileLabel,
    required int now,
    String? account,
    String? Function(MediaId trackId)? isrcOf,
  }) => PlaylistBackupRecord(
    id: id,
    name: playlist.name,
    sourceProvider: provider,
    sourceProfileLabel: profileLabel,
    sourcePlaylistId: playlist.id.toString(),
    sourceProfileId: profileId,
    playlistCreatedAt: _nonBlank(playlist.libraryAddedAt),
    firstBackedUpAt: now,
    lastBackedUpAt: now,
    lastChangedAt: now,
    sourceGoneAt: null,
    content: content(playlist, isrcOf: isrcOf),
    restores: const [],
    updatedAt: now,
    sourceAccount: account,
  );

  /// [backup] brought up to date with [playlist] as read [now]: the contents
  /// and name replaced, [PlaylistBackupRecord.lastChangedAt] moved only when
  /// they differ, and following again if it had been found gone.
  static PlaylistBackupRecord refreshed({
    required PlaylistBackupRecord backup,
    required Playlist playlist,
    required String? profileId,
    required String profileLabel,
    required int now,
    String? account,
    String? Function(MediaId trackId)? isrcOf,
  }) {
    final next = content(playlist, isrcOf: isrcOf);
    final changed = next != backup.content || playlist.name != backup.name;
    // What it held until now becomes the newest earlier version (only when the songs changed).
    final versions = next != backup.content
        ? [
            BackupVersion(since: backup.lastChangedAt, until: now, content: backup.content),
            ...backup.versions,
          ].take(keepVersions - 1).toList()
        : backup.versions;
    return backup.copyWith(
      versions: versions,
      name: playlist.name,
      sourceProfileLabel: profileLabel,
      sourceProfileId: profileId ?? backup.sourceProfileId,
      // Backups from before accounts were kept pick theirs up here.
      sourceAccount: account ?? backup.sourceAccount,
      playlistCreatedAt: _nonBlank(playlist.libraryAddedAt) ?? backup.playlistCreatedAt,
      lastBackedUpAt: now,
      lastChangedAt: changed ? now : backup.lastChangedAt,
      sourceGoneAt: null,
      content: next,
      updatedAt: now > backup.updatedAt + 1 ? now : backup.updatedAt + 1,
    );
  }

  /// What [playlist] holds, as a backup keeps it. Every track keeps its
  /// title, credited artists, album, length, ISRC and id; a track with no
  /// title can't be matched anywhere, so it joins
  /// [PlaylistBackupContent.unexported] as unavailable.
  static PlaylistBackupContent content(Playlist playlist, {String? Function(MediaId trackId)? isrcOf}) {
    final tracks = <BackupTrack>[];
    final untitled = <BackupUnexported>[];
    for (final (index, track) in playlist.tracks.indexed) {
      final title = track.title?.trim() ?? '';
      if (title.isEmpty) {
        untitled.add(
          BackupUnexported(position: index, artist: track.artist, reason: UnexportedReason.unavailable.wireName),
        );
      } else {
        final artist = track.artist?.trim();
        tracks.add(
          BackupTrack(
            title: title,
            artists: artist == null || artist.isEmpty ? const [] : [artist],
            album: _nonBlank(track.album?.trim()),
            durationSec: (track.durationSec ?? 0) > 0 ? track.durationSec : null,
            isrc: _nonBlank(isrcOf?.call(track.id)?.trim()),
            sourceTrackId: track.id.toString(),
          ),
        );
      }
    }
    return PlaylistBackupContent(
      description: _nonBlank(playlist.comment?.trim()),
      tracks: tracks,
      unexported: untitled,
    );
  }

  /// [track] as a [Track] to match: the artists joined with ", " (the matcher splits credits on it).
  static Track toTrack(BackupTrack track) => Track(
    id: MediaId.tryParse(track.sourceTrackId) ?? MediaId(MediaId.providerSubsonic, track.sourceTrackId),
    title: track.title,
    artist: track.artists.isEmpty ? null : track.artists.join(', '),
    album: track.album,
    durationSec: track.durationSec,
  );

  static String? _nonBlank(String? text) => text == null || text.trim().isEmpty ? null : text;
}

T _req<T>(Map json, String name) => json[name] is T ? json[name] as T : throw FormatException(name);

String _reqString(Map json, String name) => _req<String>(json, name);

String? _optString(Map json, String name) {
  final v = json[name];
  if (v == null) return null;
  return v is String ? v : throw FormatException(name);
}

int? _optInt(Map json, String name) {
  final v = json[name];
  if (v == null) return null;
  return v is int ? v : throw FormatException(name);
}

List<Object?> _list(Map json, String name) {
  final v = json[name];
  if (v == null) return const [];
  return v is List ? v : throw FormatException(name);
}
