/// Home's feed per account (Android data/home/HomeSnapshotStore.kt, P2 PR3,
/// owner Q14a): the last feed Home showed, kept so a cold start paints it at
/// once — before the account's source is up, and with no network — instead of
/// a spinner; the fresh load then replaces it block by block.
///
/// One versioned JSON file per profile under `<filesDir>/home_snapshot/`. It
/// holds only what the feed shows, covers as CoverRef storage keys (a URL that
/// carries credentials is never written), and records the provider so a
/// reader can drop a feed that isn't of the account's source.
///
/// Nothing is read until Home asks ([read]). Writes are conflated per profile
/// and capped ([save]): a burst settles for [settleMs] into one write, and
/// after a write the next waits at least [minWriteIntervalMs]. A write lands
/// whole or not at all (temp file + rename). An unreadable file — corrupt,
/// another format version, another profile's — is deleted and read as none,
/// never thrown.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../offline/offline_providers.dart';
import '../../platform/storage/app_files.dart';

/// A network block of Home's feed (Android HomeNetworkBlock). Activities and
/// Jump Back In are local on HarmonyOS (the activity log and the persisted
/// pools), so only these three can be unread.
enum HomeNetworkBlock {
  recentlyAdded,
  recentlyPlayed,
  playlists;

  /// Every block a feed built from nothing lacks.
  static Set<HomeNetworkBlock> unreadFromNothing() => values.toSet();
}

/// A feed read back from the store: whose it is and when it was written.
@immutable
final class HomeSnapshot {
  const HomeSnapshot({required this.profileId, required this.provider, required this.savedAt, required this.feed});

  final String profileId;
  final String provider;
  final int savedAt;

  /// The feed as written (`home/home_snapshot_mapping.dart` reads it).
  final Map<String, Object?> feed;
}

/// The feed keys each block owns (`home_snapshot_mapping.dart` writes them).
abstract final class HomeFeedKeys {
  static const recentlyAddedTracks = 'addedTracks';
  static const recentlyAddedAlbums = 'addedAlbums';
  static const recentlyPlayed = 'played';
  static const playlists = 'playlists';

  static List<String> of(HomeNetworkBlock block) => switch (block) {
    HomeNetworkBlock.recentlyAdded => const [recentlyAddedTracks, recentlyAddedAlbums],
    HomeNetworkBlock.recentlyPlayed => const [recentlyPlayed],
    HomeNetworkBlock.playlists => const [playlists],
  };
}

/// [feed] with each of [blocks] taken from [previous] (the file it replaces),
/// as far as that has them: a feed short of what was read before never
/// replaces it (a slow network, a server down, credentials refused).
Map<String, Object?> keepingFrom(
  Map<String, Object?> feed,
  Map<String, Object?>? previous,
  Set<HomeNetworkBlock> blocks,
) {
  if (previous == null || blocks.isEmpty) return feed;
  final kept = {...feed};
  for (final block in blocks) {
    for (final key in HomeFeedKeys.of(block)) {
      if (previous.containsKey(key)) {
        kept[key] = previous[key];
      } else {
        kept.remove(key);
      }
    }
  }
  return kept;
}

/// A cover storage key as the snapshot may keep it: an id the source resolves,
/// or a URL with no auth parameter (a resolved Subsonic cover's u, t, s, p,
/// apiKey …). Null otherwise: that cover shows its placeholder until the
/// fresh feed.
String? credentialFreeCoverKey(String? key) {
  if (key == null || key.trim().isEmpty) return null;
  final lower = key.toLowerCase();
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) return key;
  final q = key.indexOf('?');
  if (q < 0) return key;
  var query = key.substring(q + 1);
  final hash = query.indexOf('#');
  if (hash >= 0) query = query.substring(0, hash);
  if (query.isEmpty) return key;
  final names = [for (final p in query.split('&')) p.split('=').first.toLowerCase()];
  return names.any(_credentialParameters.contains) ? null : key;
}

const _credentialParameters = {'u', 'p', 't', 's', 'apikey', 'api_key', 'token', 'access_token', 'password'};

final class _PendingWrite {
  _PendingWrite(this.provider, this.unread, this.feed);
  final String provider;
  final Set<HomeNetworkBlock> unread;
  final Map<String, Object?> Function() feed;
}

final class HomeSnapshotStore {
  HomeSnapshotStore(
    this._directory, {
    int Function()? clock,
    this.settleMs = defaultSettleMs,
    this.minWriteIntervalMs = defaultMinWriteIntervalMs,
  }) : _clock = clock ?? _now;

  /// The file format. Bump on any change an older reader can't take as-is; old files are then dropped.
  static const formatVersion = 1;
  static const directoryName = 'home_snapshot';
  static const defaultSettleMs = 2000;
  static const defaultMinWriteIntervalMs = 30000;

  static int _now() => DateTime.now().millisecondsSinceEpoch;

  /// The app's files folder (null: this build keeps no files; the store is off).
  final Future<Directory?> Function() _directory;
  final int Function() _clock;
  final int settleMs;
  final int minWriteIntervalMs;

  final Map<String, _PendingWrite> _pending = {};
  final Set<String> _writing = {};
  final Map<String, Future<void>> _drains = {};

  /// The feed each profile's file holds as of this process's last write: an equal feed isn't written again.
  final Map<String, (String, String)> _written = {};

  /// Deleted profiles: a publish already on its way can't write their snapshot back.
  final Set<String> _deleted = {};

  Future<void> _fileLock = Future.value();

  Future<T> _locked<T>(Future<T> Function() body) {
    final run = _fileLock.then((_) => body());
    _fileLock = run.then((_) {}, onError: (Object _) {});
    return run;
  }

  /// [profileId]'s snapshot, or null when there is none or it can't be read (then it is deleted).
  Future<HomeSnapshot?> read(String profileId) => _locked(() async {
    final file = await _fileFor(profileId);
    if (file == null) return null;
    String text;
    try {
      if (!await file.exists()) return null;
      text = await file.readAsString();
    } on Object {
      return null;
    }
    final snapshot = _decode(text, profileId);
    if (snapshot == null) {
      try {
        await file.delete();
      } on Object {
        // gone already
      }
    }
    return snapshot;
  });

  /// Queues [profileId]'s feed for writing, built by [feed] when the write
  /// runs. Conflated: the latest call wins. [unread]: blocks no read has
  /// filled in this feed yet — each keeps what the file on disk has, when
  /// that is the same provider's.
  void save(
    String profileId,
    String provider, {
    Set<HomeNetworkBlock> unread = const {},
    required Map<String, Object?> Function() feed,
  }) {
    if (_deleted.contains(profileId)) return;
    _pending[profileId] = _PendingWrite(provider, unread, feed);
    if (_writing.contains(profileId)) return;
    _writing.add(profileId);
    final drain = _drain(profileId);
    _drains[profileId] = drain;
    unawaited(
      drain.whenComplete(() {
        if (identical(_drains[profileId], drain)) _drains.remove(profileId);
      }),
    );
  }

  /// Completes once every queued write has landed (tests).
  @visibleForTesting
  Future<void> whenIdle() async {
    while (_drains.isNotEmpty) {
      await Future.wait([..._drains.values]);
    }
  }

  /// Drops [profileId]'s snapshot and any write still queued for it (the profile is being deleted).
  Future<void> delete(String profileId) async {
    _deleted.add(profileId);
    _pending.remove(profileId);
    _written.remove(profileId);
    await _locked(() async {
      final file = await _fileFor(profileId);
      if (file == null) return;
      for (final f in [file, File('${file.path}.tmp')]) {
        try {
          if (await f.exists()) await f.delete();
        } on Object {
          // gone already
        }
      }
    });
  }

  Future<void> _drain(String profileId) async {
    try {
      while (true) {
        await Future<void>.delayed(Duration(milliseconds: settleMs));
        final next = _pending.remove(profileId);
        if (next == null) return;
        await _write(profileId, next);
        await Future<void>.delayed(Duration(milliseconds: minWriteIntervalMs));
        if (!_pending.containsKey(profileId)) return;
      }
    } finally {
      _writing.remove(profileId);
    }
  }

  Future<void> _write(String profileId, _PendingWrite next) async {
    try {
      final built = next.feed();
      final feed = next.unread.isEmpty
          ? built
          : keepingFrom(built, await _onDisk(profileId, next.provider), next.unread);
      final feedText = jsonEncode(feed);
      final last = _written[profileId];
      if (last != null && last.$1 == next.provider && last.$2 == feedText) return;
      final text = jsonEncode({
        'version': formatVersion,
        'profileId': profileId,
        'provider': next.provider,
        'savedAt': _clock(),
        'feed': feed,
      });
      await _locked(() async {
        if (_deleted.contains(profileId)) return;
        final file = await _fileFor(profileId, create: true);
        if (file == null) return;
        final temp = File('${file.path}.tmp');
        await temp.writeAsString(text, flush: true);
        await temp.rename(file.path);
        _written[profileId] = (next.provider, feedText);
      });
    } on Object {
      // Best effort: the next publish writes again, and a half-written file
      // can't be read (the rename is the commit).
    }
  }

  /// [profileId]'s feed on disk when it is [provider]'s.
  Future<Map<String, Object?>?> _onDisk(String profileId, String provider) async {
    final last = _written[profileId];
    if (last != null) {
      return last.$1 == provider ? (jsonDecode(last.$2) as Map).cast<String, Object?>() : null;
    }
    final snapshot = await read(profileId);
    return snapshot != null && snapshot.provider == provider ? snapshot.feed : null;
  }

  HomeSnapshot? _decode(String text, String profileId) {
    try {
      final raw = jsonDecode(text);
      if (raw is! Map || raw['version'] != formatVersion) return null;
      final provider = raw['provider'];
      final feed = raw['feed'];
      final savedAt = raw['savedAt'];
      if (raw['profileId'] != profileId || provider is! String || provider.trim().isEmpty) return null;
      if (feed is! Map || savedAt is! int) return null;
      return HomeSnapshot(
        profileId: profileId,
        provider: provider,
        savedAt: savedAt,
        feed: feed.cast<String, Object?>(),
      );
    } on Object {
      return null;
    }
  }

  Future<File?> _fileFor(String profileId, {bool create = false}) async {
    Directory? root;
    try {
      root = await _directory();
    } on Object {
      root = null;
    }
    if (root == null) return null;
    final dir = Directory('${root.path}/$directoryName');
    if (create) await dir.create(recursive: true);
    return File('${dir.path}/${fileStem(profileId)}.json');
  }

  static final _safeStem = RegExp(r'^[A-Za-z0-9_-]{1,64}$');

  /// A profile id as a file name: itself when it is a plain id, else a stable hash of it.
  @visibleForTesting
  static String fileStem(String profileId) {
    if (_safeStem.hasMatch(profileId)) return profileId;
    var hash = 0xcbf29ce484222325;
    for (final unit in utf8.encode(profileId)) {
      hash ^= unit;
      hash *= 0x100000001b3; // wraps at 64 bits
    }
    return 'p${(hash >>> 32).toRadixString(16).padLeft(8, '0')}${(hash & 0xFFFFFFFF).toRadixString(16).padLeft(8, '0')}';
  }
}

/// Home's snapshot per account (null where the build keeps no files: tests, previews).
final homeSnapshotStoreProvider = Provider<HomeSnapshotStore?>((ref) {
  final files = ref.watch(appFilesHostProvider);
  if (files is NoAppFilesHost) return null;
  return HomeSnapshotStore(files.filesDirectory);
});
