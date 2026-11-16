import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../home/home_grid_pool_store.dart';
import '../shell/app_messages.dart';
import 'playlist_writes.dart';

/// A playlist of one account ([PlaylistWrites.accountNow]).
@immutable
final class PlaylistDeletionKey {
  const PlaylistDeletionKey(this.account, this.id);
  final String? account;
  final MediaId id;

  @override
  bool operator ==(Object other) => other is PlaylistDeletionKey && other.account == account && other.id == id;

  @override
  int get hashCode => Object.hash(account, id);
}

/// A deletion the service took; [seq] orders them.
@immutable
final class PlaylistDeletionDone {
  const PlaylistDeletionDone(this.seq, this.key);
  final int seq;
  final PlaylistDeletionKey key;
}

/// Playlists on their way out ([pending]: off the lists until the service
/// answers) and the last ones it took ([done], oldest first). One value, so a
/// list sees a deletion land and its playlist leave [pending] together
/// (Android PlaylistDeletions.State).
@immutable
final class PlaylistDeletionState {
  const PlaylistDeletionState({this.pending = const {}, this.done = const []});

  final Set<PlaylistDeletionKey> pending;
  final List<PlaylistDeletionDone> done;

  int get lastSeq => done.isEmpty ? 0 : done.last.seq;

  /// The deletions taken since [seq], a list's last look.
  List<PlaylistDeletionKey> doneAfter(int seq) => [
    for (final d in done)
      if (d.seq > seq) d.key,
  ];

  /// [account]'s playlists on their way out.
  Set<MediaId> pendingFor(String? account) => {
    for (final k in pending)
      if (k.account == account) k.id,
  };

  /// [playlists] as a list shows them (Android LibraryViewModel
  /// visiblePlaylists): without [account]'s playlists on their way out, nor
  /// the ones it deleted (a read that started before the deletion may still
  /// list them). The same list when none goes.
  List<Playlist> visible(List<Playlist> playlists, String? account) {
    final hidden = {
      ...pendingFor(account),
      for (final d in done)
        if (d.key.account == account) d.key.id,
    };
    if (hidden.isEmpty || !playlists.any((p) => hidden.contains(p.id))) return playlists;
    return [
      for (final p in playlists)
        if (!hidden.contains(p.id)) p,
    ];
  }
}

/// What a deletion says when the service answers, in the app's language,
/// taken as Delete was confirmed (the page is gone by then).
typedef PlaylistDeletionLines = ({String deleted, String failed});

/// Deleting a playlist (Android PlaylistDeletions, as iOS's
/// PlaylistActions.delete): the page closes at once and the playlist leaves
/// the Library's lists at once ([PlaylistDeletionState.pending]); the service
/// is told after, here, which outlives the page. Taken: the lists drop it for
/// good ([PlaylistDeletionState.done]) and the snackbar says so. Refused (or no
/// answer): it comes back where it was and the snackbar says it could not be
/// deleted.
///
/// A deletion belongs to the account it was made on. One whose turn comes
/// after a switch is not sent: the same id on the new account is another
/// playlist. Deletions go out one after another.
class PlaylistDeletions extends Notifier<PlaylistDeletionState> {
  /// Deletions [PlaylistDeletionState.done] remembers; a list looks far more often than that many land.
  static const doneKept = 32;

  int _seq = 0;
  Future<void> _tail = Future.value();

  @override
  PlaylistDeletionState build() {
    // Kept current, so a deletion knows its account the moment it is made.
    ref.listen(playlistAccountProvider, (_, _) {});
    return const PlaylistDeletionState();
  }

  /// Takes [id] off the lists now and asks the account in use to delete it.
  void delete(MediaId id, PlaylistDeletionLines lines) {
    final writes = ref.read(playlistWritesProvider);
    final key = PlaylistDeletionKey(writes.accountNow(), id);
    if (state.pending.contains(key)) return;
    state = PlaylistDeletionState(pending: {...state.pending, key}, done: state.done);
    final post = ref.read(appMessageSinkProvider);
    final sent = _tail.then((_) => _send(writes, key));
    _tail = sent.then((_) {});
    sent.then((ok) {
      if (!ref.mounted) return;
      final pending = {...state.pending}..remove(key);
      if (ok) {
        final done = [...state.done, PlaylistDeletionDone(++_seq, key)];
        state = PlaylistDeletionState(
          pending: pending,
          done: done.length > doneKept ? done.sublist(done.length - doneKept) : done,
        );
        post(lines.deleted);
      } else {
        state = PlaylistDeletionState(pending: pending, done: state.done);
        post(lines.failed);
      }
    });
  }

  Future<bool> _send(PlaylistWrites writes, PlaylistDeletionKey key) async {
    try {
      if (!await writes.isAccountInUse(key.account)) return false;
      await writes.delete(key.id);
    } catch (_) {
      return false;
    }
    await _forgetStored(writes, key);
    return true;
  }

  /// A deleted playlist leaves the rows Home reads it from (Android
  /// forgetStoredPlaylist): its Activities row and its place in Jump Back
  /// In's stored pools, so Home's next read doesn't bring it back. Best
  /// effort: the service no longer has it, and Home drops it from the feed
  /// up anyway (HomeViewModel).
  Future<void> _forgetStored(PlaylistWrites writes, PlaylistDeletionKey key) async {
    try {
      // The account the deletion was made on, even if another is in use by now.
      final log = await writes.activityLogOf(key.account);
      await log?.forget(ActivityEntityType.playlist, key.id.rawId);
    } catch (_) {}
    final account = key.account;
    if (account == null) return;
    try {
      await ref.read(homeGridPoolStoreProvider).forgetPlaylist(account, key.id.provider, key.id.rawId);
    } catch (_) {}
  }
}

final playlistDeletionsProvider = NotifierProvider<PlaylistDeletions, PlaylistDeletionState>(PlaylistDeletions.new);
