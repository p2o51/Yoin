/// Where playlist backups live on HarmonyOS (Android PlaylistBackupStore +
/// the `playlist_backups` Room table). A backup is large (every track's
/// metadata, a few earlier versions), so each one is its own JSON file under
/// the app's files folder (`playlist_backups/`), written atomically, rather
/// than a value in the Preferences file every settings write would rewrite.
///
/// Backups are global, not per account (as Android): one is restored into
/// any profile, and each row says which local profile follows it
/// ([PlaylistBackupRow.sourceProfileId], never synced).
///
/// Every write goes through here, so [changes] tells cloud sync and the
/// screens about each change once.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

import '../../platform/storage/app_files.dart';
import 'playlist_backup_models.dart';

/// The rows' persistence: one text per backup id.
abstract interface class PlaylistBackupStorage {
  /// Every stored text (any order); unreadable entries are skipped.
  Future<List<String>> readAll();

  /// Stores [json] for [id]; null deletes.
  Future<void> write(String id, String? json);
}

/// One file per backup in `<filesDir>/playlist_backups/`, named by a hash of
/// its id (ids come from other devices too, so they never name a path). A
/// build that keeps no files (tests, previews) keeps them in memory.
final class DirectoryPlaylistBackupStorage implements PlaylistBackupStorage {
  DirectoryPlaylistBackupStorage(this._host);

  final AppFilesHost _host;
  final _memory = InMemoryPlaylistBackupStorage();
  static const folderName = 'playlist_backups';

  Future<Directory?> _folder() async {
    final root = await _host.filesDirectory();
    if (root == null) return null;
    return Directory('${root.path}${Platform.pathSeparator}$folderName');
  }

  static String fileNameFor(String id) => '${sha256.convert(utf8.encode(id)).toString().substring(0, 40)}.json';

  @override
  Future<List<String>> readAll() async {
    final folder = await _folder();
    if (folder == null) return _memory.readAll();
    if (!await folder.exists()) return const [];
    final texts = <String>[];
    await for (final entry in folder.list()) {
      if (entry is! File || !entry.path.endsWith('.json')) continue;
      try {
        texts.add(await entry.readAsString());
      } catch (e) {
        debugPrint('playlist backup unreadable (${e.runtimeType})');
      }
    }
    return texts;
  }

  @override
  Future<void> write(String id, String? json) async {
    final folder = await _folder();
    if (folder == null) return _memory.write(id, json);
    final file = File('${folder.path}${Platform.pathSeparator}${fileNameFor(id)}');
    if (json == null) {
      if (await file.exists()) await file.delete();
      return;
    }
    await folder.create(recursive: true);
    final temp = File('${file.path}.tmp');
    await temp.writeAsString(json, flush: true);
    await temp.rename(file.path);
  }
}

/// Kept in memory only (tests and previews; a build that keeps no files).
final class InMemoryPlaylistBackupStorage implements PlaylistBackupStorage {
  final Map<String, String> texts = {};

  @override
  Future<List<String>> readAll() async => texts.values.toList();

  @override
  Future<void> write(String id, String? json) async {
    if (json == null) {
      texts.remove(id);
    } else {
      texts[id] = json;
    }
  }
}

class PlaylistBackupStore {
  PlaylistBackupStore(this._storage, {int Function()? clock})
    : _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch);

  final PlaylistBackupStorage _storage;
  final int Function() _clock;

  Map<String, PlaylistBackupRow>? _rows;
  Future<Map<String, PlaylistBackupRow>>? _loading;
  Future<void> _tail = Future.value();
  final _changes = StreamController<void>.broadcast();

  /// Fires after every write that landed (save, delete, restore history, sync).
  Stream<void> get changes => _changes.stream;

  Future<Map<String, PlaylistBackupRow>> _load() {
    final loaded = _rows;
    if (loaded != null) return Future.value(loaded);
    return _loading ??= () async {
      try {
        final rows = <String, PlaylistBackupRow>{};
        for (final text in await _storage.readAll()) {
          try {
            final row = PlaylistBackupRow.tryParse(jsonDecode(text));
            if (row != null) rows[row.id] = row;
          } catch (_) {}
        }
        return _rows = rows;
      } finally {
        _loading = null;
      }
    }();
  }

  /// Runs [body] after every earlier write (read-modify-write stays whole).
  Future<T> _serial<T>(Future<T> Function() body) {
    final run = _tail.then((_) => body());
    _tail = run.then((_) {}, onError: (Object _) {});
    return run;
  }

  Future<void> _put(PlaylistBackupRow row) async {
    final rows = await _load();
    await _storage.write(row.id, jsonEncode(row.toJson()));
    rows[row.id] = row;
    if (!_changes.isClosed) _changes.add(null);
  }

  Future<void> _remove(String id) async {
    final rows = await _load();
    await _storage.write(id, null);
    rows.remove(id);
    if (!_changes.isClosed) _changes.add(null);
  }

  // ---- rows (sync)

  /// Every stored row, ordered by id.
  Future<List<PlaylistBackupRow>> rows() async {
    final rows = (await _load()).values.toList()..sort((a, b) => a.id.compareTo(b.id));
    return rows;
  }

  Future<PlaylistBackupRow?> row(String id) async => (await _load())[id];

  Future<void> upsertRow(PlaylistBackupRow row) => _serial(() => _put(row));

  /// [body] runs alone against the store (sync's read-check-write).
  Future<T> transaction<T>(Future<T> Function() body) => _serial(body);

  /// Inside [transaction] only.
  Future<void> putRowUnlocked(PlaylistBackupRow row) => _put(row);

  /// Inside [transaction] only.
  Future<void> deleteUnlocked(String id) => _remove(id);

  // ---- records (the app)

  /// Every backup, newest change first; rows this app version can't read are left out.
  Future<List<PlaylistBackupRecord>> getAll() async {
    final rows = (await _load()).values.toList()
      ..sort((a, b) {
        final byChange = b.lastChangedAt.compareTo(a.lastChangedAt);
        return byChange != 0 ? byChange : a.id.compareTo(b.id);
      });
    return [for (final r in rows) ?PlaylistBackupCodec.fromRow(r)];
  }

  /// [getAll] now and after every change.
  Stream<List<PlaylistBackupRecord>> watchAll() async* {
    yield await getAll();
    await for (final _ in changes) {
      yield await getAll();
    }
  }

  Future<PlaylistBackupRecord?> get(String id) async {
    final row = (await _load())[id];
    return row == null ? null : PlaylistBackupCodec.fromRow(row);
  }

  /// [get] now and after every change; null once it is gone.
  Stream<PlaylistBackupRecord?> watch(String id) async* {
    yield await get(id);
    await for (final _ in changes) {
      yield await get(id);
    }
  }

  /// The backups of a playlist with id [sourcePlaylistId] on [sourceProvider], oldest first, from every
  /// account that has one; [PlaylistBackupService] picks the profile's own.
  Future<List<PlaylistBackupRecord>> findBySource(String sourceProvider, String sourcePlaylistId) async {
    final rows =
        (await _load()).values
            .where((r) => r.sourceProvider == sourceProvider && r.sourcePlaylistId == sourcePlaylistId)
            .toList()
          ..sort((a, b) {
            final byFirst = a.firstBackedUpAt.compareTo(b.firstBackedUpAt);
            return byFirst != 0 ? byFirst : a.id.compareTo(b.id);
          });
    return [for (final r in rows) ?PlaylistBackupCodec.fromRow(r)];
  }

  Future<void> save(PlaylistBackupRecord record) => _serial(() => _put(PlaylistBackupCodec.toRow(record)));

  Future<void> delete(String id) => _serial(() async {
    if ((await _load()).containsKey(id)) await _remove(id);
  });

  /// [record] added to the end of backup [id]'s restore history; false when the backup is gone.
  Future<bool> recordRestore(String id, PlaylistRestoreRecord record) => _serial(() async {
    final row = (await _load())[id];
    if (row == null) return false;
    List<PlaylistRestoreRecord> history;
    try {
      history = PlaylistBackupCodec.decodeRestores(row.restoresJson);
    } catch (_) {
      history = const [];
    }
    // updatedAt only moves forward, even under a clock that went back since the last change.
    final now = _clock();
    final updatedAt = now > row.updatedAt + 1 ? now : row.updatedAt + 1;
    await _put(row.withRestores(PlaylistBackupCodec.encodeRestores([...history, record]), updatedAt));
    return true;
  });

  void dispose() => _changes.close();
}
