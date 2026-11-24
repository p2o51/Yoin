/// The readable/ part of a backup (:shared ReadableExport.kt): per account a
/// folder, per album one Markdown file (album → its songs with scores and
/// timestamped notes, the review, album notes, titles), songs whose album is
/// unknown in one more file, and readable/global/ for the playlist backups.
/// Pure apart from [ReadableNames]; never read back.
library;

import 'package:flutter/foundation.dart';

import '../backup/playlist_backup_models.dart';
import '../sync/adapters/sync_projections.dart';
import '../sync/sync_contracts.dart';
import 'backup_files.dart';
import 'backup_format.dart';

/// One text file of a backup: its path inside the zip and its UTF-8 text.
@immutable
final class BackupTextFile {
  const BackupTextFile(this.path, this.text);
  final String path;
  final String text;
}

@immutable
final class ReadableAlbum {
  const ReadableAlbum(this.name, this.artist);
  final String name;
  final String? artist;
}

@immutable
final class ReadableTrack {
  const ReadableTrack(this.title, this.artist, this.albumId);
  final String title;
  final String? artist;
  final String? albumId;
}

/// Names for the readable part. Records carry ids, not names; the app looks
/// them up in what it already has (its own plays), never on the network.
/// [localProfileId] is null for accounts the device only knows from Drive.
abstract class ReadableNames {
  const ReadableNames();

  static const ReadableNames none = _NoNames();

  Future<ReadableAlbum?> album(String? localProfileId, String provider, String albumId);

  Future<ReadableTrack?> track(String? localProfileId, String provider, String trackId);
}

final class _NoNames extends ReadableNames {
  const _NoNames();

  @override
  Future<ReadableAlbum?> album(String? localProfileId, String provider, String albumId) async => null;

  @override
  Future<ReadableTrack?> track(String? localProfileId, String provider, String trackId) async => null;
}

/// Wording of the readable Markdown. "Memories" stays English in both (docs/i18n/zh-glossary.md).
@immutable
final class ReadableLabels {
  const ReadableLabels({
    required this.account,
    required this.rating,
    required this.yourTitle,
    required this.aiTitle,
    required this.review,
    required this.albumNotes,
    required this.songs,
    required this.notes,
    required this.unknownAlbum,
    required this.unknownSong,
    required this.songsWithoutAlbum,
    required this.songsWithoutAlbumFile,
    required this.playlistBackups,
    required this.playlistBackupsFile,
    required this.tracksCount,
    required this.lastBackedUp,
    required this.from,
    required this.notRated,
    required this.writtenAt,
  });

  final String account;
  final String rating;
  final String yourTitle;
  final String aiTitle;
  final String review;
  final String albumNotes;
  final String songs;
  final String notes;
  final String unknownAlbum;
  final String unknownSong;
  final String songsWithoutAlbum;
  final String songsWithoutAlbumFile;
  final String playlistBackups;
  final String playlistBackupsFile;
  final String tracksCount;
  final String lastBackedUp;
  final String from;
  final String notRated;
  final String writtenAt;

  static const english = ReadableLabels(
    account: 'Account',
    rating: 'Rating',
    yourTitle: 'Your title',
    aiTitle: "Yoin's title",
    review: 'Review',
    albumNotes: 'Album notes',
    songs: 'Songs',
    notes: 'Notes',
    unknownAlbum: 'Album',
    unknownSong: 'Song',
    songsWithoutAlbum: "Songs whose album Yoin doesn't know",
    songsWithoutAlbumFile: '_songs-without-album',
    playlistBackups: 'Playlist backups',
    playlistBackupsFile: 'playlist-backups',
    tracksCount: 'songs',
    lastBackedUp: 'Last backed up',
    from: 'From',
    notRated: 'not rated',
    writtenAt: 'Written',
  );

  static const chinese = ReadableLabels(
    account: '账号',
    rating: '评分',
    yourTitle: '你起的标题',
    aiTitle: 'Yoin 的标题',
    review: '乐评',
    albumNotes: '专辑笔记',
    songs: '歌曲',
    notes: '笔记',
    unknownAlbum: '专辑',
    unknownSong: '歌曲',
    songsWithoutAlbum: '还不知道专辑的歌曲',
    songsWithoutAlbumFile: '_未归入专辑的歌曲',
    playlistBackups: '歌单备份',
    playlistBackupsFile: '歌单备份',
    tracksCount: '首',
    lastBackedUp: '最后备份',
    from: '来自',
    notRated: '未评分',
    writtenAt: '写于',
  );

  /// Chinese for a "zh" language code (any region or script), English otherwise.
  static ReadableLabels forLanguage(String language) =>
      language.split('-').first.split('_').first.toLowerCase() == 'zh' ? chinese : english;
}

@immutable
final class _TrackKey {
  const _TrackKey(this.provider, this.trackId);
  final String provider;
  final String trackId;

  @override
  bool operator ==(Object other) => other is _TrackKey && other.provider == provider && other.trackId == trackId;

  @override
  int get hashCode => Object.hash(provider, trackId);
}

@immutable
final class _AlbumKey {
  const _AlbumKey(this.provider, this.albumId);
  final String provider;
  final String albumId;

  @override
  bool operator ==(Object other) => other is _AlbumKey && other.provider == provider && other.albumId == albumId;

  @override
  int get hashCode => Object.hash(provider, albumId);
}

final class _Song {
  _Song(this.key);
  final _TrackKey key;
  String? title;
  String? artist;
  double? rating;
  int? ratedAt;
  final notes = <JsonMap>[];
}

final class _AlbumPage {
  _AlbumPage(this.key);
  final _AlbumKey key;
  double? rating;
  String? review;
  int? reviewAt;
  String? userTitle;
  String? aiTitle;
  String? noteName;
  String? noteArtist;
  final albumNotes = <JsonMap>[];
  final songs = <_TrackKey, _Song>{};

  bool get isEmpty =>
      rating == null && review == null && userTitle == null && aiTitle == null && albumNotes.isEmpty && songs.isEmpty;
}

abstract final class ReadableExport {
  static const _entityAlbum = 'album';

  /// [accounts] as [BackupContent.accounts] lists them; each gets [BackupAccount.readableDir] filled in.
  static Future<(List<BackupTextFile>, List<BackupAccount>)> render({
    required List<BackupAccount> accounts,
    required Map<String, String> localProfiles,
    required List<WireRecord> records,
    required ReadableNames names,
    required ReadableLabels labels,
    required BackupZone zone,
  }) async {
    final files = <BackupTextFile>[];
    final usedDirs = <String>{};
    final live = [
      for (final r in records)
        if (!r.deleted && r.payload != null) r,
    ];
    final byScope = <String, List<WireRecord>>{};
    for (final r in live) {
      (byScope[r.scope] ??= []).add(r);
    }
    String dated(int ms) => BackupDates.dateTime(ms, zone);
    final updated = <BackupAccount>[];
    for (final account in accounts) {
      final scoped = [
        ...?byScope[account.scope],
        for (final r in live)
          if (r.kind == SyncKinds.account && r.key == account.scope) r,
      ];
      final pages = await _pagesOf(localProfiles[account.scope], scoped, names);
      if (pages.isEmpty) {
        updated.add(account);
        continue;
      }
      final dir = _unique(sanitize(account.displayName.trim().isEmpty ? account.scope : account.displayName), usedDirs);
      final usedFiles = <String>{};
      for (final page in pages) {
        final profileId = localProfiles[account.scope];
        final known = page.key.albumId.isEmpty
            ? null
            : await names.album(profileId, page.key.provider, page.key.albumId);
        final title = known?.name ?? _nonBlank(page.noteName);
        final artist = known?.artist ?? _nonBlank(page.noteArtist);
        final String fileName;
        if (page.key.albumId.isEmpty) {
          fileName = labels.songsWithoutAlbumFile;
        } else if (title != null) {
          fileName = [?artist, title].join(' - ');
        } else {
          fileName = '${labels.unknownAlbum} ${page.key.albumId}';
        }
        final text = _albumMarkdown(page, title, artist, account, labels, dated);
        files.add(BackupTextFile('${BackupFormat.readableDir}$dir/${_unique(sanitize(fileName), usedFiles)}.md', text));
      }
      updated.add(account.copyWith(readableDir: '${BackupFormat.readableDir}$dir/'));
    }
    final playlists = _playlistMarkdown(
      [
        for (final r in live)
          if (r.kind == SyncKinds.playlistBackup) r,
      ],
      labels,
      dated,
    );
    if (playlists != null) {
      files.add(
        BackupTextFile('${BackupFormat.readableGlobalDir}${sanitize(labels.playlistBackupsFile)}.md', playlists),
      );
    }
    return (files, updated);
  }

  static Future<List<_AlbumPage>> _pagesOf(String? profileId, List<WireRecord> records, ReadableNames names) async {
    final pages = <_AlbumKey, _AlbumPage>{};
    _AlbumPage page(_AlbumKey key) => pages.putIfAbsent(key, () => _AlbumPage(key));
    final links = <_TrackKey, String>{};
    for (final record in records) {
      if (record.kind != BackupKinds.trackAlbumLink) continue;
      final payload = record.payload!;
      final provider = requiredString(payload, BackupFields.provider);
      final trackId = requiredString(payload, BackupFields.trackId);
      if (provider == null || trackId == null) continue;
      final albumId = _nonBlank(requiredString(payload, BackupFields.albumId));
      if (albumId != null) links[_TrackKey(provider, trackId)] = albumId;
    }
    final songs = <_TrackKey, _Song>{};
    for (final record in stableSorted(records, (a, b) => a.ts.compareTo(b.ts))) {
      final payload = record.payload!;
      switch (record.kind) {
        case SyncKinds.songNote:
          final provider = requiredString(payload, 'provider');
          final trackId = requiredString(payload, 'trackId');
          if (provider == null || trackId == null) continue;
          final key = _TrackKey(provider, trackId);
          final song = songs.putIfAbsent(key, () => _Song(key));
          song.title ??= _nonBlank(requiredString(payload, 'title'));
          song.artist ??= _nonBlank(requiredString(payload, 'artist'));
          song.notes.add(payload);
        case SyncKinds.trackRating:
          final provider = requiredString(payload, 'provider');
          final songId = requiredString(payload, 'songId');
          if (provider == null || songId == null) continue;
          final wire = _rating(payload, 'rating');
          if (wire == null) continue;
          final zero = payload['zero'] == true || payload['zero'] == 'true';
          if (wire == 0 && !zero) continue;
          final key = _TrackKey(provider, songId);
          songs.putIfAbsent(key, () => _Song(key))
            ..rating = wire
            ..ratedAt = record.ts;
        case SyncKinds.albumRating:
          final key = _albumKey(record);
          final value = _rating(payload, 'rating');
          if (key != null && value != null && value > 0) page(key).rating = value;
        case SyncKinds.albumReview:
          final key = _albumKey(record);
          final review = _nonBlank(requiredString(payload, 'review'));
          if (key != null && review != null) {
            page(key)
              ..review = review
              ..reviewAt = record.ts;
          }
        case SyncKinds.albumTitle:
          final key = _albumKey(record);
          final title = _nonBlank(requiredString(payload, 'title'));
          if (key != null && title != null) page(key).userTitle = title;
        case SyncKinds.memoryTitle:
          final parts = record.key.split(SyncFormat.keySeparator);
          if (parts.length == 3 && parts[1] == _entityAlbum) {
            final title = _nonBlank(requiredString(payload, 'title'));
            if (title != null) page(_AlbumKey(parts[0], parts[2])).aiTitle = title;
          }
        case SyncKinds.albumNote:
          final provider = requiredString(payload, BackupFields.provider);
          final albumId = requiredString(payload, BackupFields.albumId);
          if (provider == null || albumId == null) continue;
          final p = page(_AlbumKey(provider, albumId));
          p.albumNotes.add(payload);
          p.noteName ??= requiredString(payload, BackupFields.albumName);
          p.noteArtist ??= requiredString(payload, BackupFields.artist);
      }
    }
    final loose = _AlbumPage(const _AlbumKey('', ''));
    for (final MapEntry(:key, value: song) in songs.entries) {
      final known = await names.track(profileId, key.provider, key.trackId);
      song.title ??= known?.title;
      song.artist ??= known?.artist;
      final albumId = links[key] ?? _nonBlank(known?.albumId);
      final target = albumId == null ? loose : page(_AlbumKey(key.provider, albumId));
      target.songs[key] = song;
    }
    return [
      for (final p in pages.values)
        if (!p.isEmpty) p,
      if (loose.songs.isNotEmpty) loose,
    ];
  }

  static _AlbumKey? _albumKey(WireRecord record) {
    final parts = record.key.split(SyncFormat.keySeparator);
    return parts.length == 2 ? _AlbumKey(parts[0], parts[1]) : null;
  }

  static String _albumMarkdown(
    _AlbumPage page,
    String? title,
    String? artist,
    BackupAccount account,
    ReadableLabels labels,
    String Function(int) dated,
  ) {
    final out = StringBuffer();
    void line([String text = '']) => out.write('$text\n');
    if (page.key.albumId.isEmpty) {
      line('# ${labels.songsWithoutAlbum}');
    } else {
      line('# ${page.userTitle ?? title ?? '${labels.unknownAlbum} ${page.key.albumId}'}');
      if (page.userTitle != null && title != null) line('_${title}_');
      if (artist != null) line(artist);
    }
    line();
    line('- ${labels.account}: ${account.displayName} (${account.provider})');
    if (page.rating != null) line('- ${labels.rating}: ${score(page.rating!)} / 10');
    if (page.userTitle != null) line('- ${labels.yourTitle}: ${page.userTitle}');
    if (page.aiTitle != null) line('- ${labels.aiTitle}: ${page.aiTitle}');
    if (page.review != null) {
      line();
      line('## ${labels.review}');
      line();
      line(page.review!.trim());
      if (page.reviewAt != null) {
        line();
        line('_${labels.writtenAt} ${dated(page.reviewAt!)}_');
      }
    }
    if (page.albumNotes.isNotEmpty) {
      line();
      line('## ${labels.albumNotes}');
      for (final note in stableSorted(
        page.albumNotes,
        (a, b) =>
            (requiredLong(a, BackupFields.createdAt) ?? 0).compareTo(requiredLong(b, BackupFields.createdAt) ?? 0),
      )) {
        line();
        line('### ${dated(requiredLong(note, BackupFields.createdAt) ?? 0)}');
        line();
        line((requiredString(note, BackupFields.content) ?? '').trim());
      }
    }
    if (page.songs.isNotEmpty) {
      line();
      line('## ${labels.songs}');
      for (final song in stableSorted(
        page.songs.values,
        (a, b) => (a.title ?? a.key.trackId).toLowerCase().compareTo((b.title ?? b.key.trackId).toLowerCase()),
      )) {
        line();
        final name = song.title ?? '${labels.unknownSong} ${song.key.trackId}';
        line('### $name${song.artist == null ? '' : ' — ${song.artist}'}');
        line();
        line('- ${labels.rating}: ${song.rating == null ? labels.notRated : '${score(song.rating!)} / 10'}');
        if (song.notes.isNotEmpty) {
          line('- ${labels.notes}:');
          for (final note in stableSorted(
            song.notes,
            (a, b) => (requiredLong(a, 'createdAt') ?? 0).compareTo(requiredLong(b, 'createdAt') ?? 0),
          )) {
            final at = dated(requiredLong(note, 'createdAt') ?? 0);
            final position = optionalLong(note, 'positionMs');
            final positionText = position is FieldOk<int?> && position.value != null
                ? ' · ${clock(position.value!)}'
                : '';
            line('  - $at$positionText');
            for (final l in _lines((requiredString(note, 'content') ?? '').trim())) {
              line('    > $l');
            }
          }
        }
      }
    }
    return out.toString();
  }

  static String? _playlistMarkdown(List<WireRecord> records, ReadableLabels labels, String Function(int) dated) {
    if (records.isEmpty) return null;
    final out = StringBuffer();
    void line([String text = '']) => out.write('$text\n');
    line('# ${labels.playlistBackups}');
    String? nameOf(WireRecord r) => r.payload == null ? null : requiredString(r.payload!, 'name')?.toLowerCase();
    for (final record in stableSorted(records, (a, b) => compareNullable(nameOf(a), nameOf(b)))) {
      final payload = record.payload;
      if (payload == null) continue;
      final contentText = requiredString(payload, 'content');
      PlaylistBackupContent? content;
      if (contentText != null) {
        try {
          content = PlaylistBackupCodec.decodeContent(contentText);
        } catch (_) {
          content = null;
        }
      }
      line();
      line('## ${requiredString(payload, 'name') ?? ''}');
      line();
      line(
        '- ${labels.from}: ${requiredString(payload, 'sourceProfileLabel') ?? ''} '
        '(${requiredString(payload, 'sourceProvider') ?? ''})',
      );
      if (content != null) line('- ${content.tracks.length} ${labels.tracksCount}');
      final lastBackedUp = requiredLong(payload, 'lastBackedUpAt');
      if (lastBackedUp != null) line('- ${labels.lastBackedUp}: ${dated(lastBackedUp)}');
      final description = content?.description;
      if (description != null && description.trim().isNotEmpty) {
        line();
        line(description.trim());
      }
      if (content != null && content.tracks.isNotEmpty) {
        line();
        for (final (index, track) in content.tracks.indexed) {
          final artists = track.artists.join(', ');
          final credit = artists.trim().isEmpty ? '' : ' — $artists';
          line('${index + 1}. ${track.title}$credit');
        }
      }
    }
    return out.toString();
  }

  /// Kotlin's `rating()`: a 0..10 JSON number, read as a Float.
  static double? _rating(JsonMap payload, String name) {
    final value = rating(payload, name);
    return value == null ? null : asFloat(value);
  }

  /// [value] rounded to a 32-bit float, as Kotlin holds a rating.
  static double asFloat(double value) => (Float32List(1)..[0] = value)[0];

  /// "8", "7.5": whole scores without a decimal, others to one decimal, rounded half up (Java's %.1f).
  static String score(double value) {
    final f = asFloat(value);
    if (f % 1 == 0) return f.toInt().toString();
    return _oneDecimal(f);
  }

  /// Java's `String.format(Locale.ROOT, "%.1f", value)`: the shortest decimal of the double, half up.
  static String _oneDecimal(double value) {
    final text = value.toString();
    final negative = text.startsWith('-');
    final body = negative ? text.substring(1) : text;
    if (body.contains('e') || body.contains('E') || body.split('').any((c) => c != '.' && !'0123456789'.contains(c))) {
      final tenths = (value.abs() * 10 + 0.5).floor();
      return '${negative && tenths != 0 ? '-' : ''}${tenths ~/ 10}.${tenths % 10}';
    }
    final dot = body.indexOf('.');
    final whole = dot < 0 ? body : body.substring(0, dot);
    final fraction = dot < 0 ? '' : body.substring(dot + 1);
    var tenths = int.parse(whole) * 10 + (fraction.isNotEmpty ? int.parse(fraction[0]) : 0);
    if (fraction.length > 1 && fraction.codeUnitAt(1) >= 0x35) tenths += 1;
    return '${negative ? '-' : ''}${tenths ~/ 10}.${tenths % 10}';
  }

  /// "m:ss" of a position in ms (minutes not wrapped into hours).
  static String clock(int ms) {
    final total = ms ~/ 1000;
    return '${total ~/ 60}:${(total % 60).toString().padLeft(2, '0')}';
  }

  /// A file or folder name that is safe on every file system and zip tool, at most 80 characters.
  static String sanitize(String name) {
    final out = StringBuffer();
    for (final unit in name.trim().codeUnits) {
      out.writeCharCode(unit < 0x20 || '/\\:*?"<>|'.codeUnits.contains(unit) ? 0x5f : unit);
    }
    var cleaned = _trimChar(out.toString().trim(), '.');
    if (cleaned.length > 80) cleaned = cleaned.substring(0, 80);
    cleaned = cleaned.trim();
    return cleaned.isEmpty ? '_' : cleaned;
  }

  static String _trimChar(String s, String c) {
    var start = 0;
    var end = s.length;
    while (start < end && s[start] == c) {
      start++;
    }
    while (end > start && s[end - 1] == c) {
      end--;
    }
    return s.substring(start, end);
  }

  static String _unique(String name, Set<String> used) {
    if (used.add(name.toLowerCase())) return name;
    var n = 2;
    while (!used.add('$name ($n)'.toLowerCase())) {
      n++;
    }
    return '$name ($n)';
  }

  /// Kotlin's String.lines(): split on \r\n, \n and \r.
  static List<String> _lines(String text) => text.split(RegExp('\r\n|\n|\r'));
}

String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
