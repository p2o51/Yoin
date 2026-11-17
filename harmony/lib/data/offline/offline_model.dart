/// Offline downloads (Android data/offline/OfflineModel.kt; owner 2026-11-04):
/// a song, an album or a playlist kept on the device until the listener
/// removes it, played without a network. Subsonic only (Apple Music doesn't
/// let an app store its songs).
///
/// Everything here is plain Dart: what to download, at what quality, and how
/// the downloads read as albums and playlists. [OfflineDownloads] fetches the
/// files and keeps the index.
library;

import 'package:flutter/foundation.dart';

import '../../playback/track_codec.dart';
import '../model/model.dart';

/// What a downloaded song is stored as (Settings › Downloads, like Spotify's
/// Download quality). [original] is the file as it is on the server; the rest
/// ask the server for an mp3 at no more than [maxBitRateKbps], as streaming
/// does (constant bit rate, so a stored transcode seeks).
enum DownloadQuality {
  original('original', null),
  kbps320('320', 320),
  kbps192('192', 192),
  kbps128('128', 128);

  const DownloadQuality(this.key, this.maxBitRateKbps);

  /// Stored value (Android's keys).
  final String key;
  final int? maxBitRateKbps;

  /// Like streaming's: a cbr mp3 is what seeks with one request.
  static const transcodeFormat = 'mp3';

  /// The server's transcode format for this quality; null for the file as it is.
  String? get format => maxBitRateKbps == null ? null : transcodeFormat;

  static DownloadQuality? fromKey(String? key) => values.where((q) => q.key == key).firstOrNull;
}

/// Settings › Downloads. The defaults follow Spotify's: a high quality you can
/// change, and nothing downloads over mobile data until the listener allows it.
@immutable
final class DownloadSettings {
  const DownloadSettings({this.quality = defaultQuality, this.overMobileData = false});

  static const defaultQuality = DownloadQuality.kbps320;

  final DownloadQuality quality;
  final bool overMobileData;

  DownloadSettings copyWith({DownloadQuality? quality, bool? overMobileData}) =>
      DownloadSettings(quality: quality ?? this.quality, overMobileData: overMobileData ?? this.overMobileData);

  @override
  bool operator ==(Object other) =>
      other is DownloadSettings && other.quality == quality && other.overMobileData == overMobileData;

  @override
  int get hashCode => Object.hash(quality, overMobileData);
}

enum OfflineCollectionKind { album, playlist, song }

/// Where a downloaded song came from: the album or playlist the listener
/// downloaded, or the song alone.
@immutable
final class OfflineCollection {
  const OfflineCollection({required this.kind, required this.id, required this.title, this.subtitle, this.cover});

  final OfflineCollectionKind kind;

  /// The album's or playlist's [MediaId] string; the song's own for [OfflineCollectionKind.song].
  final String id;
  final String title;
  final String? subtitle;

  /// A [CoverRef.storageKey]; never a signed URL.
  final String? cover;

  /// What a download is held under: one entry per album, playlist, or lone song.
  String get key => '${kind.name}:$id';

  /// An album page's download (albumOfflineCollection).
  factory OfflineCollection.album(Album album) => OfflineCollection(
    kind: OfflineCollectionKind.album,
    id: album.id.toString(),
    title: album.name,
    subtitle: album.artist,
    cover: _storable(album.coverArt),
  );

  /// A playlist page's download (playlistOfflineCollection).
  factory OfflineCollection.playlist(Playlist playlist, {CoverRef? cover}) => OfflineCollection(
    kind: OfflineCollectionKind.playlist,
    id: playlist.id.toString(),
    title: playlist.name,
    subtitle: playlist.owner,
    cover: _storable(cover ?? playlist.coverArt),
  );

  /// A song downloaded on its own, from its sheet (songOfflineCollection).
  factory OfflineCollection.song(Track track) => OfflineCollection(
    kind: OfflineCollectionKind.song,
    id: track.id.toString(),
    title: track.title ?? '',
    subtitle: track.artist,
    cover: _storable(track.coverArt),
  );

  /// A cover the index may keep: an id, never a URL (a signed one holds a credential).
  static String? _storable(CoverRef? cover) => switch (cover) {
    CoverSourceRelative(:final coverArtId) => coverArtId,
    _ => null,
  };

  Map<String, Object?> toJson() => {
    'kind': kind.name,
    'id': id,
    'title': title,
    if (subtitle != null) 'subtitle': subtitle,
    if (cover != null) 'cover': cover,
  };

  static OfflineCollection? fromJson(Object? json) {
    if (json is! Map) return null;
    final kind = OfflineCollectionKind.values.asNameMap()[json['kind']];
    final id = json['id'];
    if (kind == null || id is! String || id.isEmpty) return null;
    return OfflineCollection(
      kind: kind,
      id: id,
      title: (json['title'] as String?) ?? '',
      subtitle: json['subtitle'] as String?,
      cover: json['cover'] as String?,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is OfflineCollection &&
      other.kind == kind &&
      other.id == id &&
      other.title == title &&
      other.subtitle == subtitle &&
      other.cover == cover;

  @override
  int get hashCode => Object.hash(kind, id, title, subtitle, cover);
}

enum OfflineSongState { queued, downloading, done, failed, waiting }

/// One downloaded or downloading song as the index has it (Android
/// OfflinePayload + the Media3 Download it rides on). It holds no credential:
/// the address is signed only when the download runs.
@immutable
final class OfflineSong {
  const OfflineSong({
    required this.profileId,
    required this.songId,
    required this.track,
    required this.quality,
    required this.collections,
    required this.order,
    this.state = OfflineSongState.queued,
    this.bytes = 0,
    this.totalBytes,
    this.fileName,
  });

  /// The account the song was downloaded from; another account's downloads wait.
  final String profileId;

  /// The song's [MediaId] string.
  final String songId;

  /// The song as it was downloaded, to list and play it with no network.
  final Track track;
  final DownloadQuality quality;

  /// Every album, playlist or lone-song download that holds this song; it goes when the last one does.
  final List<OfflineCollection> collections;

  /// When it was added (a running count), for listing newest first.
  final int order;
  final OfflineSongState state;

  /// Bytes on disk (so far, while it downloads).
  final int bytes;

  /// The whole file's size; null until known (a transcode has no length until it ends).
  final int? totalBytes;

  /// The file under the downloads folder; set once it is complete.
  final String? fileName;

  /// The index key: one song per account.
  String get key => keyOf(profileId, songId);

  static String keyOf(String profileId, String songId) => '$profileId/$songId';

  /// 0..100, or -1 when unknown.
  double get percent {
    if (state == OfflineSongState.done) return 100;
    final total = totalBytes;
    if (total == null || total <= 0) return -1;
    return (bytes * 100 / total).clamp(0, 100).toDouble();
  }

  OfflineSong copyWith({
    Track? track,
    List<OfflineCollection>? collections,
    OfflineSongState? state,
    int? bytes,
    int? totalBytes,
    bool clearTotal = false,
    String? fileName,
    bool clearFile = false,
  }) => OfflineSong(
    profileId: profileId,
    songId: songId,
    track: track ?? this.track,
    quality: quality,
    collections: collections ?? this.collections,
    order: order,
    state: state ?? this.state,
    bytes: bytes ?? this.bytes,
    totalBytes: clearTotal ? null : (totalBytes ?? this.totalBytes),
    fileName: clearFile ? null : (fileName ?? this.fileName),
  );

  /// This song also held by [collection] (its metadata refreshed).
  OfflineSong withCollection(OfflineCollection collection) =>
      copyWith(collections: [...collections.where((c) => c.key != collection.key), collection]);

  /// This song without [collectionKey]; null when nothing holds it any more.
  OfflineSong? withoutCollection(String collectionKey) {
    final rest = collections.where((c) => c.key != collectionKey).toList();
    return rest.isEmpty ? null : copyWith(collections: rest);
  }

  Map<String, Object?> toJson() => {
    'profileId': profileId,
    'songId': songId,
    'track': TrackCodec.encode(track),
    'quality': quality.key,
    'collections': [for (final c in collections) c.toJson()],
    'order': order,
    // A download cut off by the app going away starts over.
    'state': state == OfflineSongState.done || state == OfflineSongState.failed ? state.name : 'queued',
    'bytes': state == OfflineSongState.done ? bytes : 0,
    if (state == OfflineSongState.done && totalBytes != null) 'total': totalBytes,
    if (fileName != null) 'file': fileName,
  };

  static OfflineSong? fromJson(Object? json) {
    if (json is! Map) return null;
    final profileId = json['profileId'];
    final songId = json['songId'];
    final track = TrackCodec.decode(json['track'] as String?);
    if (profileId is! String || songId is! String || track == null) return null;
    final collections = [for (final c in (json['collections'] as List?) ?? const []) ?OfflineCollection.fromJson(c)];
    if (collections.isEmpty) return null;
    return OfflineSong(
      profileId: profileId,
      songId: songId,
      track: track,
      quality: DownloadQuality.fromKey(json['quality'] as String?) ?? DownloadSettings.defaultQuality,
      collections: collections,
      order: (json['order'] as num?)?.toInt() ?? 0,
      state: OfflineSongState.values.asNameMap()[json['state']] ?? OfflineSongState.queued,
      bytes: (json['bytes'] as num?)?.toInt() ?? 0,
      totalBytes: (json['total'] as num?)?.toInt(),
      fileName: json['file'] as String?,
    );
  }
}

/// What a page's Download row shows.
sealed class OfflineStatus {
  const OfflineStatus();
}

final class OfflineNone extends OfflineStatus {
  const OfflineNone();
  @override
  bool operator ==(Object other) => other is OfflineNone;
  @override
  int get hashCode => 0;
}

final class OfflineDownloading extends OfflineStatus {
  const OfflineDownloading(this.done, this.total);
  final int done;
  final int total;
  @override
  bool operator ==(Object other) => other is OfflineDownloading && other.done == done && other.total == total;
  @override
  int get hashCode => Object.hash(done, total);
}

final class OfflineDownloaded extends OfflineStatus {
  const OfflineDownloaded();
  @override
  bool operator ==(Object other) => other is OfflineDownloaded;
  @override
  int get hashCode => 1;
}

final class OfflineFailed extends OfflineStatus {
  const OfflineFailed();
  @override
  bool operator ==(Object other) => other is OfflineFailed;
  @override
  int get hashCode => 2;
}

/// An album, playlist or lone song as the listener downloaded it, with how far its songs are.
@immutable
final class OfflineEntry {
  const OfflineEntry({
    required this.collection,
    required this.total,
    required this.done,
    required this.failed,
    required this.bytes,
    required this.order,
  });

  final OfflineCollection collection;
  final int total;
  final int done;
  final int failed;
  final int bytes;

  /// The newest of its songs' addition order, for listing newest first.
  final int order;

  bool get complete => total > 0 && done == total;

  OfflineStatus get status {
    if (complete) return const OfflineDownloaded();
    if (failed > 0 && done + failed == total) return const OfflineFailed();
    return OfflineDownloading(done, total);
  }
}

/// One account's downloads, read as the albums, playlists and songs the listener downloaded.
@immutable
final class OfflineLibrary {
  OfflineLibrary([this.songs = const []]);

  /// In the order they were added.
  final List<OfflineSong> songs;

  /// Newest first.
  late final List<OfflineEntry> entries = () {
    final grouped = <String, List<OfflineSong>>{};
    final collections = <String, OfflineCollection>{};
    for (final song in songs) {
      for (final c in song.collections) {
        (grouped[c.key] ??= []).add(song);
        collections[c.key] = c;
      }
    }
    final list = [
      for (final MapEntry(:key, value: members) in grouped.entries)
        OfflineEntry(
          collection: collections[key]!,
          total: members.length,
          done: members.where((s) => s.state == OfflineSongState.done).length,
          failed: members.where((s) => s.state == OfflineSongState.failed).length,
          bytes: members.fold(0, (sum, s) => sum + s.bytes),
          order: members.map((s) => s.order).reduce((a, b) => a > b ? a : b),
        ),
    ];
    list.sort((a, b) => b.order.compareTo(a.order));
    return list;
  }();

  /// Bytes on disk for this account's downloads.
  int get bytes => songs.fold(0, (sum, s) => sum + s.bytes);

  OfflineStatus statusOf(String collectionKey) {
    for (final e in entries) {
      if (e.collection.key == collectionKey) return e.status;
    }
    return const OfflineNone();
  }

  /// Whether the song [songId] (a [MediaId] string) is fully on the device.
  bool isDownloaded(String songId) => songs.any((s) => s.songId == songId && s.state == OfflineSongState.done);

  List<OfflineSong> songsOf(String collectionKey) => [
    for (final s in songs)
      if (s.collections.any((c) => c.key == collectionKey)) s,
  ];

  OfflineCollection? collectionOf(String collectionKey) {
    for (final e in entries) {
      if (e.collection.key == collectionKey) return e.collection;
    }
    return null;
  }
}
