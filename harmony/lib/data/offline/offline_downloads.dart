/// The device's downloads (Android data/offline/OfflineDownloads.kt): the
/// songs the listener keeps, in the app's files folder where nothing evicts
/// them, with an index (`index.json`) that lists them with no network.
///
/// Android runs this through Media3's DownloadManager; here it is plain Dart
/// over `dart:io` and `package:http`, so it runs the same on HarmonyOS and on
/// the desktop build. A download is kept under the song and its account (one
/// account's songs never stand for another's) and holds no credential: its
/// address is signed with the active account only as it opens, and a song of
/// an account that isn't active waits for it.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../../platform/network/network_bearer.dart';
import '../../platform/storage/app_files.dart';
import '../../platform/storage/stores.dart';
import '../model/model.dart';
import '../source/music_source.dart';
import 'offline_model.dart';

/// The active account as downloads need it: whose songs run now, and how to
/// sign their addresses. Null from [OfflineDownloads.account] when the active
/// account can't download (Apple Music, or none).
@immutable
final class OfflineAccount {
  const OfflineAccount({required this.profileId, required this.provider, required this.downloads});
  final String profileId;

  /// [MediaId.provider] of the account's songs.
  final String provider;
  final MusicDownloads downloads;
}

/// The stored [DownloadSettings], for this device (storage and data use are
/// the device's, not the account's; Android DownloadSettingsStore).
final class DownloadSettingsStore {
  const DownloadSettingsStore(this._values);

  final KeyValueStore _values;

  static const qualityKey = 'download_quality';
  static const mobileDataKey = 'download_over_mobile_data';

  Future<DownloadSettings> read() async => DownloadSettings(
    quality: DownloadQuality.fromKey(await _values.read(qualityKey)) ?? DownloadSettings.defaultQuality,
    overMobileData: await _values.read(mobileDataKey) == 'true',
  );

  Future<void> write(DownloadSettings settings) async {
    await _values.write(qualityKey, settings.quality.key);
    await _values.write(mobileDataKey, '${settings.overMobileData}');
  }
}

/// Everything the downloads know now, every account's.
@immutable
final class OfflineState {
  OfflineState({
    this.songs = const [],
    this.activeProfileId,
    this.settings = const DownloadSettings(),
    this.ready = false,
    this.available = true,
  });

  final List<OfflineSong> songs;
  final String? activeProfileId;
  final DownloadSettings settings;

  /// The index has been read (until then the lists are empty, not "nothing downloaded").
  final bool ready;

  /// This build keeps downloads (it has a files folder); known once [ready].
  /// Without one the Download rows stay out rather than do nothing.
  final bool available;

  /// The active account's downloads.
  late final OfflineLibrary library = OfflineLibrary([
    for (final s in songs)
      if (s.profileId == activeProfileId) s,
  ]);

  /// Every account's downloads on disk, bytes.
  int get downloadBytes => songs.fold(0, (sum, s) => sum + s.bytes);
}

class OfflineDownloads extends ChangeNotifier {
  OfflineDownloads({
    required AppFilesHost files,
    required this.account,
    required this.bearer,
    KeyValueStore? Function()? settingsValues,
    http.Client? client,
    this.maxParallel = 2,
    this.maxRetries = 5,
    this.retryDelay = const Duration(seconds: 5),
    this.connectTimeout = const Duration(seconds: 30),
    this.idleTimeout = const Duration(seconds: 30),
    DateTime Function()? now,
  }) : _files = files,
       _settingsValues = settingsValues,
       _client = client ?? http.Client(),
       _now = now ?? DateTime.now;

  final AppFilesHost _files;
  final KeyValueStore? Function()? _settingsValues;
  final http.Client _client;
  final DateTime Function() _now;

  /// The active account, read as each download opens.
  final Future<OfflineAccount?> Function() account;

  /// What the default network runs over now.
  final Future<NetworkBearer> Function() bearer;

  /// Songs downloading at once (Android maxParallelDownloads).
  final int maxParallel;

  /// Times a dropped download starts again before it counts as failed (Media3 minRetryCount).
  final int maxRetries;

  /// How long a download waiting for the network (or Wi‑Fi) waits before it looks again.
  final Duration retryDelay;
  final Duration connectTimeout;
  final Duration idleTimeout;

  /// The folder under the app's files folder (Android DOWNLOAD_CACHE_DIR).
  static const folderName = 'offline_media';
  static const indexName = 'index.json';
  static const indexVersion = 1;

  /// Progress is redrawn at most this often (Android reads it each second).
  static const progressInterval = Duration(milliseconds: 500);

  OfflineState _state = OfflineState();
  OfflineState get state => _state;

  Directory? _dir;
  Future<void>? _starting;
  bool _disposed = false;
  int _seq = 0;
  int _jobSeq = 0;
  final _jobs = <String, _Job>{};

  /// Transient failures per song so far (a dropped connection, the app
  /// frozen in the background) and when it may try again.
  final _attempts = <String, int>{};
  final _notBefore = <String, DateTime>{};
  Timer? _retry;
  bool _pumping = false;
  bool _pumpAgain = false;
  Future<void> _saving = Future.value();
  DateTime _lastProgress = DateTime.fromMillisecondsSinceEpoch(0);

  /// Reads the settings and the index, then carries on with what was left
  /// queued. Safe to call again.
  Future<void> start() => _starting ??= _start();

  Future<void> _start() async {
    var settings = const DownloadSettings();
    try {
      final values = _settingsValues?.call();
      if (values != null) settings = await DownloadSettingsStore(values).read();
    } catch (e) {
      debugPrint('YoinOffline: settings unreadable: $e');
    }
    Directory? dir;
    try {
      final base = await _files.filesDirectory();
      if (base != null) {
        dir = Directory('${base.path}${Platform.pathSeparator}$folderName');
        await dir.create(recursive: true);
      }
    } catch (e) {
      debugPrint('YoinOffline: no folder: $e');
      dir = null;
    }
    if (_disposed) return;
    _dir = dir;
    if (dir != null) await _sweep(dir, (name) => name.endsWith(_partSuffix));
    final songs = dir == null ? <OfflineSong>[] : await _readIndex(dir);
    _seq = songs.fold(0, (m, s) => s.order >= m ? s.order + 1 : m);
    _set(
      OfflineState(
        songs: songs,
        activeProfileId: _state.activeProfileId,
        settings: settings,
        ready: true,
        available: dir != null,
      ),
    );
    _pump();
  }

  /// Whether this build keeps downloads at all (it has a files folder).
  bool get available => _dir != null;

  /// The account whose downloads [state.library] lists; another account's downloads wait.
  void setActiveProfile(String? profileId) {
    if (profileId == _state.activeProfileId) return;
    // A running download of the account going away stops and waits for it
    // (Android STOP_OTHER_PROFILE): it starts over when that account is back.
    final stopped = <String, OfflineSong>{};
    for (final s in _state.songs) {
      if (s.profileId == profileId || s.state != OfflineSongState.downloading) continue;
      _jobs.remove(s.key)?.cancel();
      stopped[s.key] = s.copyWith(state: OfflineSongState.waiting, bytes: 0, clearTotal: true);
    }
    _set(_copy(activeProfileId: profileId));
    if (stopped.isNotEmpty) _replace(stopped);
    _pump();
  }

  Future<void> setQuality(DownloadQuality quality) => _saveSettings(_state.settings.copyWith(quality: quality));

  Future<void> setOverMobileData(bool allowed) async {
    await _saveSettings(_state.settings.copyWith(overMobileData: allowed));
    _pump();
  }

  Future<void> _saveSettings(DownloadSettings settings) async {
    if (settings == _state.settings) return;
    _set(_copy(settings: settings));
    try {
      final values = _settingsValues?.call();
      if (values != null) await DownloadSettingsStore(values).write(settings);
    } catch (e) {
      debugPrint('YoinOffline: settings not saved: $e');
    }
  }

  /// Downloads [tracks] as [collection] (an album, a playlist, or one song)
  /// at the quality Settings › Downloads names. Songs already downloaded keep
  /// their file and quality and are now held by [collection] too; a song that
  /// failed tries again. Returns how many songs were queued: 0 when the
  /// active account can't download.
  Future<int> download(List<Track> tracks, OfflineCollection collection) async {
    await start();
    if (_dir == null) return 0;
    final account = await _safeAccount();
    if (account == null) return 0;
    final quality = _state.settings.quality;
    final byKey = {for (final s in _state.songs) s.key: s};
    final added = <String>{};
    var count = 0;
    for (final track in tracks) {
      if (track.id.provider != account.provider || track.id.rawId.isEmpty) continue;
      final key = OfflineSong.keyOf(account.profileId, track.id.toString());
      if (!added.add(key)) continue;
      final existing = byKey[key];
      if (existing != null) {
        var next = existing.withCollection(collection);
        if (existing.state == OfflineSongState.failed) {
          next = next.copyWith(state: OfflineSongState.queued, bytes: 0);
          _attempts.remove(key);
          _notBefore.remove(key);
        }
        byKey[key] = next;
      } else {
        byKey[key] = OfflineSong(
          profileId: account.profileId,
          songId: track.id.toString(),
          track: track,
          quality: quality,
          collections: [collection],
          order: _seq++,
          fileName: _fileNameOf(key, track, quality),
        );
      }
      count++;
    }
    if (count == 0) return 0;
    final order = [for (final s in _state.songs) s.key];
    final songs = [for (final key in order) byKey.remove(key)!, ...byKey.values];
    _set(_copy(songs: songs));
    _save();
    _pump();
    return count;
  }

  /// Removes [collectionKey] from the active account's downloads: its songs
  /// go unless another download still holds them.
  Future<void> remove(String collectionKey) async {
    await start();
    final profileId = _state.activeProfileId;
    if (profileId == null || !_holds(profileId, collectionKey)) return;
    final gone = <OfflineSong>[];
    final songs = <OfflineSong>[];
    for (final s in _state.songs) {
      if (s.profileId != profileId || !s.collections.any((c) => c.key == collectionKey)) {
        songs.add(s);
        continue;
      }
      final rest = s.withoutCollection(collectionKey);
      if (rest == null) {
        gone.add(s);
      } else {
        songs.add(rest);
      }
    }
    _set(_copy(songs: songs));
    for (final s in gone) {
      await _discard(s);
    }
    _save();
    _pump();
  }

  bool _holds(String profileId, String collectionKey) =>
      _state.songs.any((s) => s.profileId == profileId && s.collections.any((c) => c.key == collectionKey));

  /// Every account's downloads.
  Future<void> removeAll() async {
    await start();
    final songs = _state.songs;
    _set(_copy(songs: const []));
    for (final s in songs) {
      await _discard(s);
    }
    // Anything else in the folder too (a file a removal raced with): the
    // folder is the downloads', and every one of them is going.
    final dir = _dir;
    if (dir != null) await _sweep(dir, (name) => name != indexName && name != '$indexName.tmp');
    _save();
  }

  /// Whether a download queued now waits: mobile data isn't allowed and the
  /// network is mobile data (or there is none).
  Future<bool> waitsForWifi() async {
    if (_state.settings.overMobileData) return false;
    final now = await _safeBearer();
    return now == NetworkBearer.cellular || now == NetworkBearer.none;
  }

  /// The downloaded file [songId] plays from, as a `file://` address, when
  /// the active account has all of it; null otherwise (it streams).
  String? playableUri(MediaId songId) {
    final dir = _dir;
    final profileId = _state.activeProfileId;
    if (dir == null || profileId == null) return null;
    final key = OfflineSong.keyOf(profileId, songId.toString());
    for (final s in _state.songs) {
      if (s.key != key) continue;
      final name = s.fileName;
      if (s.state != OfflineSongState.done || name == null) return null;
      final file = File(_pathIn(dir, name));
      return file.existsSync() ? Uri.file(file.path).toString() : null;
    }
    return null;
  }

  /// The quality [songId]'s finished download was kept at; null when the
  /// active account has no finished download of it.
  DownloadQuality? playableQuality(MediaId songId) {
    final profileId = _state.activeProfileId;
    if (profileId == null) return null;
    final key = OfflineSong.keyOf(profileId, songId.toString());
    for (final s in _state.songs) {
      if (s.key == key) return s.state == OfflineSongState.done ? s.quality : null;
    }
    return null;
  }

  /// A downloaded album as its page needs it with no network: the songs as
  /// they were downloaded, in disc and track order. Null when the active
  /// account didn't download [id].
  Album? offlineAlbum(MediaId id, {String? profileId}) {
    final key = OfflineCollection(kind: OfflineCollectionKind.album, id: id.toString(), title: '').key;
    final library = _libraryOf(profileId);
    final collection = library.collectionOf(key);
    if (collection == null) return null;
    final tracks = [for (final s in library.songsOf(key)) s.track]
      ..sort((a, b) {
        final disc = (a.discNumber ?? 1).compareTo(b.discNumber ?? 1);
        return disc != 0 ? disc : (a.trackNumber ?? 0).compareTo(b.trackNumber ?? 0);
      });
    final first = tracks.firstOrNull;
    return Album(
      id: id,
      name: collection.title,
      artist: collection.subtitle,
      artistId: first?.artistId,
      coverArt: CoverRef.fromStorageKey(collection.cover),
      songCount: tracks.length,
      durationSec: tracks.fold<int>(0, (sum, t) => sum + (t.durationSec ?? 0)),
      year: first?.year,
      tracks: tracks,
    );
  }

  /// [profileId]'s downloads (the active account's when null): a page read
  /// as the app starts may come before the active account is followed.
  OfflineLibrary _libraryOf(String? profileId) => profileId == null || profileId == _state.activeProfileId
      ? _state.library
      : OfflineLibrary([
          for (final s in _state.songs)
            if (s.profileId == profileId) s,
        ]);

  /// A downloaded playlist with no network, its songs in the order they came.
  Playlist? offlinePlaylist(MediaId id, {String? profileId}) {
    final key = OfflineCollection(kind: OfflineCollectionKind.playlist, id: id.toString(), title: '').key;
    final library = _libraryOf(profileId);
    final collection = library.collectionOf(key);
    if (collection == null) return null;
    final tracks = [for (final s in library.songsOf(key)) s.track];
    return Playlist(
      id: id,
      name: collection.title,
      owner: collection.subtitle,
      coverArt: CoverRef.fromStorageKey(collection.cover),
      songCount: tracks.length,
      durationSec: tracks.fold<int>(0, (sum, t) => sum + (t.durationSec ?? 0)),
      tracks: tracks,
    );
  }

  @override
  void dispose() {
    _disposed = true;
    _retry?.cancel();
    for (final job in _jobs.values) {
      job.cancel();
    }
    _jobs.clear();
    _client.close();
    super.dispose();
  }

  // ── Running downloads ─────────────────────────────────────────────

  void _pump() {
    if (_disposed || _state.ready == false) return;
    unawaited(_pumpNow());
  }

  Future<void> _pumpNow() async {
    if (_pumping) {
      _pumpAgain = true;
      return;
    }
    _pumping = true;
    try {
      do {
        _pumpAgain = false;
        await _startEligible();
      } while (_pumpAgain && !_disposed);
    } finally {
      _pumping = false;
    }
  }

  Future<void> _startEligible() async {
    if (_dir == null) return;
    bool pending(OfflineSong s) =>
        (s.state == OfflineSongState.queued || s.state == OfflineSongState.waiting) && !_jobs.containsKey(s.key);
    if (!_state.songs.any(pending)) return;
    final account = await _safeAccount();
    final now = await _safeBearer();
    if (_disposed) return;
    final overMobile = _state.settings.overMobileData;
    final networkOk = now != NetworkBearer.none && (now != NetworkBearer.cellular || overMobile);
    var waitsForNetwork = false;
    final changes = <String, OfflineSong>{};
    final starts = <OfflineSong>[];
    for (final s in _state.songs) {
      if (!pending(s)) continue;
      final runnable = account != null && s.profileId == account.profileId;
      if (!runnable) {
        // Another account's song: it waits until that account is active.
        if (s.state != OfflineSongState.waiting) changes[s.key] = s.copyWith(state: OfflineSongState.waiting);
        continue;
      }
      final notBefore = _notBefore[s.key];
      if (!networkOk || (notBefore != null && _now().isBefore(notBefore))) {
        waitsForNetwork = true;
        if (s.state != OfflineSongState.waiting) changes[s.key] = s.copyWith(state: OfflineSongState.waiting);
        continue;
      }
      if (_jobs.length + starts.length >= maxParallel) {
        if (s.state != OfflineSongState.queued) changes[s.key] = s.copyWith(state: OfflineSongState.queued);
        continue;
      }
      starts.add(s);
    }
    if (changes.isNotEmpty) _replace(changes);
    for (final s in starts) {
      _begin(s, account!);
    }
    _retry?.cancel();
    _retry = waitsForNetwork ? Timer(retryDelay, _pump) : null;
  }

  void _begin(OfflineSong song, OfflineAccount account) {
    final dir = _dir;
    final id = MediaId.tryParse(song.songId);
    final uri = id == null
        ? null
        : account.downloads.downloadUri(id, maxBitRateKbps: song.quality.maxBitRateKbps, format: song.quality.format);
    final name = song.fileName ?? _fileNameOf(song.key, song.track, song.quality);
    if (dir == null || uri == null) {
      _replace({song.key: song.copyWith(state: OfflineSongState.failed)});
      _save();
      return;
    }
    final job = _Job(_jobSeq++);
    _jobs[song.key] = job;
    _replace({
      song.key: song.copyWith(state: OfflineSongState.downloading, bytes: 0, clearTotal: true, fileName: name),
    });
    unawaited(_run(song.key, uri, File(_pathIn(dir, name)), job));
  }

  Future<void> _run(String key, Uri uri, File target, _Job job) async {
    // Its own part file: a download removed and queued again never shares one
    // with the run still winding down.
    final part = File('${target.path}.${job.id}$_partSuffix');
    try {
      final response = await _client.send(http.Request('GET', uri)).timeout(connectTimeout);
      if (job.cancelled) {
        _drain(response);
        return;
      }
      final status = response.statusCode;
      if (status != 200) {
        _drain(response);
        // The server busy or failing tries again (Media3 retries any HTTP
        // error); anything else it says is a refusal.
        if (status >= 500 || status == 408 || status == 429) throw _DownloadDropped('HTTP $status');
        throw _DownloadRefused('HTTP $status');
      }
      // A Subsonic error comes back as a 200 with an XML or JSON body, never audio.
      final type = response.headers['content-type']?.toLowerCase() ?? '';
      if (type.contains('xml') || type.contains('json')) {
        _drain(response);
        throw const _DownloadRefused('server error');
      }
      final total = response.contentLength;
      final sink = part.openWrite();
      job.sink = sink;
      var bytes = 0;
      final done = Completer<void>();
      job.subscription = response.stream
          .timeout(idleTimeout)
          .listen(
            (chunk) {
              sink.add(chunk);
              bytes += chunk.length;
              _progress(key, bytes, total);
            },
            onError: (Object e) {
              if (!done.isCompleted) done.completeError(e);
            },
            onDone: () {
              if (!done.isCompleted) done.complete();
            },
            cancelOnError: true,
          );
      job.onCancel = () {
        if (!done.isCompleted) done.complete();
      };
      await done.future;
      await sink.close();
      if (job.cancelled) {
        await _delete(part);
        return;
      }
      if (bytes == 0) throw const _DownloadRefused('empty');
      await part.rename(target.path);
      _attempts.remove(key);
      _notBefore.remove(key);
      // Removed while the file moved into place: it goes with the song.
      if (!_finish(key, (s) => s.copyWith(state: OfflineSongState.done, bytes: bytes, totalBytes: bytes))) {
        await _delete(target);
      }
    } catch (e) {
      await job.sink?.close().catchError((_) {});
      await _delete(part);
      if (job.cancelled || _disposed) return;
      // The connection went (no network, the app frozen in the background):
      // it tries again, as Media3 retries a download, until it has failed
      // [maxRetries] times. The server saying no fails it at once.
      final transient =
          e is _DownloadDropped ||
          (e is! _DownloadRefused && (e is SocketException || e is TimeoutException || e is http.ClientException));
      final offline = transient && await _safeBearer() == NetworkBearer.none;
      final tries = transient && !offline ? (_attempts[key] ?? 0) + 1 : 0;
      final retries = offline || (transient && tries <= maxRetries);
      if (transient && !offline) {
        _attempts[key] = tries;
        _notBefore[key] = _now().add(retryDelay);
      }
      debugPrint('YoinOffline: download ${retries ? 'waits' : 'failed'}: $e');
      _finish(key, (s) => s.copyWith(state: retries ? OfflineSongState.waiting : OfflineSongState.failed, bytes: 0));
    } finally {
      if (identical(_jobs[key], job)) _jobs.remove(key);
      _pump();
    }
  }

  void _progress(String key, int bytes, int? total) {
    final index = _state.songs.indexWhere((s) => s.key == key);
    if (index < 0) return;
    final songs = [..._state.songs];
    songs[index] = songs[index].copyWith(bytes: bytes, totalBytes: total);
    _state = _copy(songs: songs);
    final now = _now();
    if (now.difference(_lastProgress) >= progressInterval) {
      _lastProgress = now;
      notifyListeners();
    }
  }

  /// Applies [change] to [key]'s song; false when it was removed meanwhile.
  bool _finish(String key, OfflineSong Function(OfflineSong) change) {
    if (_disposed) return true;
    final song = _state.songs.where((s) => s.key == key).firstOrNull;
    if (song == null) return false;
    _replace({key: change(song)});
    _save();
    return true;
  }

  /// Lets go of a response it won't read, so its connection closes.
  static void _drain(http.StreamedResponse response) {
    unawaited(response.stream.listen(null, onError: (_) {}, cancelOnError: true).cancel());
  }

  // ── Files and the index ───────────────────────────────────────────

  /// A file name for [key] that holds no id or credential: its hash, with the
  /// kind of file it will be.
  static String _fileNameOf(String key, Track track, DownloadQuality quality) {
    final suffix = quality.format ?? track.audioFormat?.suffix?.toLowerCase() ?? 'audio';
    final safe = RegExp(r'^[a-z0-9]{1,8}$').hasMatch(suffix) ? suffix : 'audio';
    return '${sha1.convert(utf8.encode(key))}.$safe';
  }

  static String _pathIn(Directory dir, String name) => '${dir.path}${Platform.pathSeparator}$name';

  Future<void> _discard(OfflineSong song) async {
    _jobs.remove(song.key)?.cancel();
    _attempts.remove(song.key);
    _notBefore.remove(song.key);
    final dir = _dir;
    final name = song.fileName;
    if (dir == null || name == null) return;
    await _delete(File(_pathIn(dir, name)));
  }

  /// Deletes the files in [dir] whose names [matches] (part files left by a
  /// run the app didn't outlive; everything, for Remove all).
  static Future<void> _sweep(Directory dir, bool Function(String name) matches) async {
    try {
      await for (final entity in dir.list()) {
        final name = entity.uri.pathSegments.lastWhere((s) => s.isNotEmpty, orElse: () => '');
        if (entity is File && matches(name)) await _delete(entity);
      }
    } catch (e) {
      debugPrint('YoinOffline: folder not swept: $e');
    }
  }

  static const _partSuffix = '.part';

  static Future<void> _delete(File file) async {
    try {
      if (await file.exists()) await file.delete();
    } catch (_) {
      // Already gone, or held open: the next removal tries again.
    }
  }

  Future<List<OfflineSong>> _readIndex(Directory dir) async {
    final file = File(_pathIn(dir, indexName));
    try {
      if (!await file.exists()) return [];
      final raw = jsonDecode(await file.readAsString());
      if (raw is! Map || raw['songs'] is! List) return [];
      final songs = <OfflineSong>[];
      for (final item in raw['songs'] as List) {
        final song = OfflineSong.fromJson(item);
        if (song == null) continue;
        // A finished song whose file went away downloads again.
        final name = song.fileName;
        final missing =
            song.state == OfflineSongState.done && (name == null || !await File(_pathIn(dir, name)).exists());
        songs.add(missing ? song.copyWith(state: OfflineSongState.queued, bytes: 0, clearTotal: true) : song);
      }
      songs.sort((a, b) => a.order.compareTo(b.order));
      return songs;
    } catch (e) {
      debugPrint('YoinOffline: index unreadable: $e');
      return [];
    }
  }

  /// Writes the index (state changes only, never progress), one write at a
  /// time, replacing the file whole (temp file + rename).
  void _save() {
    final dir = _dir;
    if (dir == null || _disposed) return;
    final body = jsonEncode({
      'version': indexVersion,
      'songs': [for (final s in _state.songs) s.toJson()],
    });
    _saving = _saving.then((_) async {
      try {
        final tmp = File(_pathIn(dir, '$indexName.tmp'));
        await tmp.writeAsString(body, flush: true);
        await tmp.rename(_pathIn(dir, indexName));
      } catch (e) {
        debugPrint('YoinOffline: index not saved: $e');
      }
    });
  }

  /// The index's pending writes, for tests.
  @visibleForTesting
  Future<void> get saved => _saving;

  // ── State ─────────────────────────────────────────────────────────

  static const _keep = Object();

  OfflineState _copy({List<OfflineSong>? songs, Object? activeProfileId = _keep, DownloadSettings? settings}) =>
      OfflineState(
        songs: songs ?? _state.songs,
        activeProfileId: identical(activeProfileId, _keep) ? _state.activeProfileId : activeProfileId as String?,
        settings: settings ?? _state.settings,
        ready: _state.ready,
        available: _state.available,
      );

  void _replace(Map<String, OfflineSong> changes) {
    _set(_copy(songs: [for (final s in _state.songs) changes[s.key] ?? s]));
  }

  void _set(OfflineState next) {
    if (_disposed) return;
    _state = next;
    notifyListeners();
  }

  Future<OfflineAccount?> _safeAccount() async {
    try {
      return await account();
    } catch (_) {
      return null;
    }
  }

  Future<NetworkBearer> _safeBearer() async {
    try {
      return await bearer();
    } catch (_) {
      return NetworkBearer.other;
    }
  }
}

/// One running download: cancelled by a removal or an account switch.
final class _Job {
  _Job(this.id);

  /// Names its part file.
  final int id;
  bool cancelled = false;
  IOSink? sink;
  StreamSubscription<List<int>>? subscription;
  VoidCallback? onCancel;

  void cancel() {
    cancelled = true;
    unawaited(subscription?.cancel());
    onCancel?.call();
  }
}

/// The server busy or failing (5xx, 408, 429): tried again like a dropped connection.
final class _DownloadDropped implements Exception {
  const _DownloadDropped(this.reason);
  final String reason;
  @override
  String toString() => 'DownloadDropped($reason)';
}

final class _DownloadRefused implements Exception {
  const _DownloadRefused(this.reason);
  final String reason;
  @override
  String toString() => 'DownloadRefused($reason)';
}
