import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../home/home_grid_pool_store.dart';
import 'playlist_writes.dart';

/// A rename the service took, on [account]'s playlist [id]; [seq] orders them.
@immutable
final class PlaylistRenamed {
  const PlaylistRenamed(this.seq, this.account, this.id, this.name);
  final int seq;
  final String? account;
  final MediaId id;
  final String name;
}

/// The last renames the service took, oldest first (Android
/// YoinRepository.playlistRenames). Home's rows keep a name of their own (the
/// Activities' history rows, Jump Back In's pool), so they take the new one
/// from here without reading Home again.
@immutable
final class PlaylistRenameState {
  const PlaylistRenameState([this.renamed = const []]);
  final List<PlaylistRenamed> renamed;

  int get lastSeq => renamed.isEmpty ? 0 : renamed.last.seq;

  /// The renames taken since [seq], a reader's last look.
  List<PlaylistRenamed> after(int seq) => [
    for (final r in renamed)
      if (r.seq > seq) r,
  ];
}

/// Renames of playlists made on their pages ([PlaylistDetailNotifier.rename]),
/// once the service has taken them.
class PlaylistRenames extends Notifier<PlaylistRenameState> {
  /// Renames [PlaylistRenameState.renamed] remembers; Home looks far more often than that many land.
  static const kept = 32;

  int _seq = 0;

  @override
  PlaylistRenameState build() => const PlaylistRenameState();

  /// [account]'s playlist [id] is called [name] now, on the service: its
  /// stored Activities rows and its Jump Back In pool row take the name
  /// (Android renameStoredPlaylist), then Home's feed up does.
  Future<void> renamed(String? account, MediaId id, String name) async {
    if (account != null) {
      try {
        final log = await ref.read(playlistWritesProvider).activityLogOf(account);
        await log?.retitle(ActivityEntityType.playlist, id.rawId, name);
      } catch (_) {}
      try {
        await ref.read(homeGridPoolStoreProvider).renamePlaylist(account, id.provider, id.rawId, name);
      } catch (_) {}
    }
    if (!ref.mounted) return;
    final all = [...state.renamed, PlaylistRenamed(++_seq, account, id, name)];
    state = PlaylistRenameState(all.length > kept ? all.sublist(all.length - kept) : all);
  }
}

final playlistRenamesProvider = NotifierProvider<PlaylistRenames, PlaylistRenameState>(PlaylistRenames.new);
