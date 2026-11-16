/// Playlist backup and restore (Android data/backup/PlaylistBackupService.kt;
/// docs/playlist-transfer.md §7). Back up: the active profile's playlist,
/// read whole, saved as a [PlaylistBackupRecord]. Restore, in two steps with
/// the user's review between them: [PlaylistBackupService.match] the
/// backup's tracks on a target profile, then [PlaylistBackupService.create]
/// a playlist there from the rows the review left checked, recording the
/// result in the backup's history. Sources for other profiles are built per
/// step and disposed after it; the active profile never changes.
library;

import 'dart:math';

import 'package:flutter/foundation.dart';

import '../model/model.dart';
import '../profile/profile.dart';
import '../source/music_source.dart';
import '../transfer/playlist_transfer.dart';
import '../transfer/restore_review.dart';
import 'playlist_backup_models.dart';
import 'playlist_backup_store.dart';

/// The profiles a backup reads from and restores into; the app's profile
/// repository and source factory, a fake in tests.
abstract interface class BackupProfiles {
  Future<List<Profile>> profiles();

  Future<Profile?> activeProfile();

  /// [block] with profile [profileId]'s source; null when the profile is gone or can't be signed in.
  Future<T?> withSource<T>(String profileId, Future<T> Function(MusicSource source) block);

  /// Which account [profile] is, the same on every device (cloud sync's
  /// account keys); null when not known.
  Future<String?> accountKey(Profile profile);
}

/// What following one backup found.
enum FollowResult { changed, unchanged, gone, skipped }

@immutable
final class FollowSummary {
  const FollowSummary({required this.checked, required this.changed, required this.gone});
  final int checked;
  final int changed;
  final int gone;

  @override
  bool operator ==(Object other) =>
      other is FollowSummary && other.checked == checked && other.changed == changed && other.gone == gone;

  @override
  int get hashCode => Object.hash(checked, changed, gone);

  @override
  String toString() => 'FollowSummary(checked $checked, changed $changed, gone $gone)';
}

/// A profile a backup can be restored into.
@immutable
final class RestoreTarget {
  const RestoreTarget({required this.profileId, required this.displayName, required this.provider});

  final String profileId;
  final String displayName;
  final String provider;

  @override
  bool operator ==(Object other) =>
      other is RestoreTarget &&
      other.profileId == profileId &&
      other.displayName == displayName &&
      other.provider == provider;

  @override
  int get hashCode => Object.hash(profileId, displayName, provider);
}

/// What a restore wrote, and the history entry it left on the backup.
@immutable
final class RestoreOutcome {
  const RestoreOutcome(this.record, this.write);
  final PlaylistRestoreRecord record;
  final PlaylistWrite write;
}

/// The backup's playlist couldn't be read: no such playlist (or none the account can read).
final class PlaylistBackupUnavailable implements Exception {
  const PlaylistBackupUnavailable(this.reason);
  final String reason;

  @override
  String toString() => 'PlaylistBackupUnavailable($reason)';
}

class PlaylistBackupService {
  PlaylistBackupService({
    required BackupProfiles profiles,
    required PlaylistBackupStore store,
    PlaylistTransfer transfer = const PlaylistTransfer(),
    int Function()? clock,
    String Function()? newId,
    this.onPlaylistCreated,
  }) : _profiles = profiles,
       _store = store,
       _transfer = transfer,
       _clock = clock ?? (() => DateTime.now().millisecondsSinceEpoch),
       _newId = newId ?? _uuid;

  final BackupProfiles _profiles;
  final PlaylistBackupStore _store;
  final PlaylistTransfer _transfer;
  final int Function() _clock;
  final String Function() _newId;

  /// A restore made a playlist on profile [profileId] (the Library re-reads it).
  final void Function(String profileId)? onPlaylistCreated;

  /// How often a followed playlist is read again (on app start or return, never in the background).
  static const int followIntervalMs = 6 * 60 * 60 * 1000;

  /// Services that take a new playlist with songs (Android Capability.PLAYLIST_IMPORT;
  /// here [Capability.playlistAdd]): Subsonic and Apple Music.
  static const Set<String> restoreProviders = {MediaId.providerSubsonic, MediaId.providerAppleMusic};

  /// Starts [playlistId]'s backup on the active profile, or — when it already
  /// has one — backs it up again now. Read whole, afresh.
  Future<PlaylistBackupRecord> backUp(MediaId playlistId) async {
    final profile = await _profiles.activeProfile();
    if (profile == null) throw StateError('No active profile');
    final read = await _profiles.withSource(profile.id, (source) async {
      final playlist = await source.library.getPlaylist(playlistId);
      return playlist == null ? null : (playlist: playlist, isrcOf: source.trackCodes?.isrcOf);
    });
    if (read == null) throw const PlaylistBackupUnavailable('Playlist not found');
    final now = _clock();
    final account = await _profiles.accountKey(profile);
    final existing = await _backupOf(profile, account, read.playlist.id);
    final record = existing != null
        ? PlaylistSnapshots.refreshed(
            backup: existing,
            playlist: read.playlist,
            profileId: profile.id,
            profileLabel: profile.displayName,
            now: now,
            account: account,
            isrcOf: read.isrcOf,
          )
        : PlaylistSnapshots.backup(
            id: _newId(),
            playlist: read.playlist,
            provider: profile.provider,
            profileId: profile.id,
            profileLabel: profile.displayName,
            now: now,
            account: account,
            isrcOf: read.isrcOf,
          );
    await _store.save(record);
    return record;
  }

  /// [profile]'s own backup of [playlistId], if it has one: never another
  /// account's backup of a playlist with the same id (two Subsonic servers both have a "1").
  Future<PlaylistBackupRecord?> _backupOf(Profile profile, String? account, MediaId playlistId) async {
    final localIds = {for (final p in await _profiles.profiles()) p.id};
    return ownBackup(await _store.findBySource(profile.provider, playlistId.toString()), profile, account, localIds);
  }

  /// Follows every backup this device can follow that hasn't been checked
  /// for [interval]: each playlist read again and saved when it changed, or
  /// marked gone when its service no longer lists it. A profile that can't
  /// be reached (offline, signed out) is left for next time.
  Future<FollowSummary> followDue({int interval = followIntervalMs}) async {
    final now = _clock();
    final local = {for (final p in await _profiles.profiles()) p.id: p};
    final due = (await _store.getAll())
        .where((b) => b.following && local.containsKey(b.sourceProfileId) && now - b.lastBackedUpAt >= interval)
        .toList();
    var changed = 0;
    var gone = 0;
    final byProfile = <String, List<PlaylistBackupRecord>>{};
    for (final b in due) {
      (byProfile[b.sourceProfileId!] ??= []).add(b);
    }
    for (final entry in byProfile.entries) {
      final profile = local[entry.key]!;
      final account = await _profiles.accountKey(profile);
      List<FollowResult> results;
      try {
        results =
            await _profiles.withSource(entry.key, (source) async {
              final out = <FollowResult>[];
              for (final backup in entry.value) {
                out.add(await follow(backup, profile, source, account: account));
              }
              return out;
            }) ??
            const [];
      } catch (_) {
        results = const [];
      }
      changed += results.where((r) => r == FollowResult.changed).length;
      gone += results.where((r) => r == FollowResult.gone).length;
    }
    return FollowSummary(checked: due.length, changed: changed, gone: gone);
  }

  /// [backup] read again now, from this device's profile that follows it:
  /// Back up now on its page. Throws when no profile here can read it.
  Future<FollowResult> followNow(PlaylistBackupRecord backup) async {
    final profileId = backup.sourceProfileId;
    if (profileId == null) throw StateError('Not followed on this device');
    final profile = (await _profiles.profiles()).where((p) => p.id == profileId).firstOrNull;
    if (profile == null) throw StateError('Profile gone');
    final account = await _profiles.accountKey(profile);
    final result = await _profiles.withSource(profileId, (source) => follow(backup, profile, source, account: account));
    if (result == null) throw StateError('Profile unavailable');
    return result;
  }

  @visibleForTesting
  Future<FollowResult> follow(
    PlaylistBackupRecord backup,
    Profile profile,
    MusicSource source, {
    String? account,
  }) async {
    final id = MediaId.tryParse(backup.sourcePlaylistId);
    if (id == null) return FollowResult.skipped;
    // The profile is another account now (signed in elsewhere): its playlist of that id isn't this one.
    if (!_accountFits(backup, account)) return FollowResult.skipped;
    // Gone = the service answers with its list and the playlist isn't in it; a failed answer proves nothing.
    List<Playlist> listed;
    try {
      listed = await source.library.getPlaylists();
    } catch (_) {
      return FollowResult.skipped;
    }
    final now = _clock();
    if (!listed.any((p) => p.id == id)) {
      await _store.save(backup.copyWith(sourceGoneAt: now, updatedAt: max(now, backup.updatedAt + 1)));
      return FollowResult.gone;
    }
    Playlist? playlist;
    try {
      playlist = await source.library.getPlaylist(id);
    } catch (_) {
      playlist = null;
    }
    if (playlist == null) return FollowResult.skipped;
    final next = PlaylistSnapshots.refreshed(
      backup: backup,
      playlist: playlist,
      profileId: profile.id,
      profileLabel: profile.displayName,
      now: now,
      account: account,
      isrcOf: source.trackCodes?.isrcOf,
    );
    await _store.save(next);
    return next.lastChangedAt == now ? FollowResult.changed : FollowResult.unchanged;
  }

  /// Every profile whose service takes a new playlist, the backup's own included.
  Future<List<RestoreTarget>> restoreTargets() async => restoreTargetsOf(await _profiles.profiles());

  /// The backup's tracks resolved on [target], in backup order.
  Future<List<TrackResolution>> match(
    PlaylistBackupRecord backup,
    RestoreTarget target, {
    void Function(int done, int total)? onProgress,
    void Function(TrackResolution resolution)? onResolved,
  }) async {
    final result = await _profiles.withSource(
      target.profileId,
      (source) => _transfer.resolve(
        [for (final t in backup.content.tracks) MatchableTrack(PlaylistSnapshots.toTrack(t), isrc: t.isrc)],
        source,
        onProgress: onProgress,
        onResolved: onResolved,
      ),
    );
    if (result == null) throw StateError('Profile unavailable');
    return result;
  }

  /// Songs on [target] for [query]: the review's search for a song the match didn't find.
  Future<List<Track>> search(RestoreTarget target, String query) async =>
      await _profiles.withSource(target.profileId, (source) async => (await source.library.search(query)).tracks) ??
      const [];

  /// A playlist on [target] named like the backup, holding [rows]' checked
  /// tracks in backup order, and the restore added to the backup's history
  /// (a failed write is recorded too).
  Future<RestoreOutcome> create(PlaylistBackupRecord backup, RestoreTarget target, List<ReviewRow> rows) async {
    final ids = RestoreReview.trackIdsToWrite(rows);
    PlaylistWrite write;
    try {
      write =
          await _profiles.withSource(target.profileId, (source) => _transfer.writeTracks(backup.name, ids, source)) ??
          PlaylistWrite(created: null, added: 0, requested: ids.length, failure: StateError('Profile unavailable'));
    } catch (failure) {
      write = PlaylistWrite(created: null, added: 0, requested: ids.length, failure: failure);
    }
    final record = restoreRecord(target, rows, write, _clock());
    await _store.recordRestore(backup.id, record);
    if (write.created != null) onPlaylistCreated?.call(target.profileId);
    return RestoreOutcome(record, write);
  }

  /// Which of [candidates] (backups of one playlist id on [profile]'s service, oldest first) is
  /// [profile]'s own, on account [account]; [localProfileIds] are this device's profiles.
  ///
  /// 1. One this profile follows here.
  /// 2. One no profile here follows, from the same account (backed up on another device).
  /// 3. One no profile here follows from before accounts were kept: only when it was backed up under
  ///    this profile's name, the one sign left that it is this account.
  ///
  /// A backup another profile here follows, or one from a different account, is never it.
  static PlaylistBackupRecord? ownBackup(
    List<PlaylistBackupRecord> candidates,
    Profile profile,
    String? account,
    Set<String> localProfileIds,
  ) {
    final fitting = candidates.where((b) => _accountFits(b, account)).toList();
    final followed = fitting.where((b) => b.sourceProfileId == profile.id).firstOrNull;
    if (followed != null) return followed;
    final unfollowed = fitting
        .where((b) => b.sourceProfileId == null || !localProfileIds.contains(b.sourceProfileId))
        .toList();
    if (account != null) {
      final same = unfollowed.where((b) => b.sourceAccount == account).firstOrNull;
      if (same != null) return same;
    }
    return unfollowed.where((b) => b.sourceAccount == null && b.sourceProfileLabel == profile.displayName).firstOrNull;
  }

  /// False only when both accounts are known and differ.
  static bool _accountFits(PlaylistBackupRecord backup, String? account) =>
      backup.sourceAccount == null || account == null || backup.sourceAccount == account;

  static List<RestoreTarget> restoreTargetsOf(List<Profile> profiles) => [
    for (final p in profiles)
      if (restoreProviders.contains(p.provider))
        RestoreTarget(profileId: p.id, displayName: p.displayName, provider: p.provider),
  ];

  /// What [write] made of [rows] on [target], as the backup's history keeps it.
  static PlaylistRestoreRecord restoreRecord(RestoreTarget target, List<ReviewRow> rows, PlaylistWrite write, int now) {
    final notFound = rows.where((r) => r.status == ReviewStatus.notFound).length;
    return PlaylistRestoreRecord(
      at: now,
      targetProvider: target.provider,
      targetLabel: target.displayName,
      added: write.added,
      skipped: max(0, rows.length - notFound - write.added),
      notFound: notFound,
      failed: write.created == null,
    );
  }

  static final Random _random = Random.secure();

  /// A random (version 4) UUID, as Android's UUID.randomUUID().
  static String _uuid() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0F) | 0x40;
    bytes[8] = (bytes[8] & 0x3F) | 0x80;
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
        '${hex.substring(16, 20)}-${hex.substring(20)}';
  }
}
