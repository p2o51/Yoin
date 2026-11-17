import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../library/playlist_deletions.dart';
import '../library/playlist_renames.dart';
import '../library/playlist_writes.dart';
import '../shell/app_messages.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/error_diagnostics.dart';
import '../ui/component/error_strings.dart';
import '../ui/component/user_message.dart';
import 'detail_cache.dart';

/// One playlist row (Android PlaylistSong.rowKey): its key stays this entry's
/// across reads and removals, and a song in the playlist twice has one per
/// entry. The list keys its rows by it (so a removed row leaves and the rest
/// move up) and [PlaylistDetailNotifier.removeTrack] takes it.
final class PlaylistEntry {
  const PlaylistEntry(this.rowKey, this.track);
  final String rowKey;
  final Track track;
}

sealed class PlaylistDetailState {
  const PlaylistDetailState();
}

final class PlaylistDetailLoading extends PlaylistDetailState {
  const PlaylistDetailLoading();
}

final class PlaylistDetailError extends PlaylistDetailState {
  const PlaylistDetailError({required this.notFound, this.cause, this.diagnostics});

  /// The server has no such playlist (vs. a failed read).
  final bool notFound;

  /// What the failed read threw: the page says its line (`userMessageText`).
  final Object? cause;

  /// The scrubbed report Copy details copies ([diagnosticReport]), taken when it failed.
  final String? diagnostics;
}

final class PlaylistDetailContent extends PlaylistDetailState {
  /// [entries] default to one row per song of [playlist], keyed by position.
  PlaylistDetailContent(this.playlist, {List<PlaylistEntry>? entries, this.canRemoveSongs = false})
    : entries = entries ?? [for (final (i, t) in playlist.tracks.indexed) PlaylistEntry('row-$i', t)];

  /// The playlist as the page shows it: the name on the page (a rename the
  /// service hasn't answered yet included), the count and length without the
  /// rows on their way out, and [Playlist.tracks] = the rows' songs.
  final Playlist playlist;

  /// The rows, in the service's order, without the ones on their way out.
  final List<PlaylistEntry> entries;

  /// A song's sheet offers "Remove from playlist": the account may edit this
  /// playlist on a service that writes playlists (Android canRemoveSongs).
  final bool canRemoveSongs;

  List<Track> get tracks => playlist.tracks;

  /// songCount, else the rows.
  int get trackCount => playlist.songCount ?? tracks.length;
  int get totalDurationSec => playlist.durationSec ?? tracks.fold(0, (sum, t) => sum + (t.durationSec ?? 0));

  /// The hero cover: the playlist's own cover id (Subsonic maps it to the
  /// playlist id), else the first track's cover or album id; none (the
  /// placeholder) for an empty playlist.
  CoverRef? get heroCover => playlistHeroCover(playlist);

  /// The cover the play context carries.
  CoverRef? get contextCover => heroCover;
}

/// The cover a playlist shows everywhere (Library row and cell, detail hero).
/// A playlist known to be empty has none: the server's art for it is a blank
/// (black) square, so it gets the placeholder with the playlist symbol, as
/// Android's artwork does when there is nothing to show.
CoverRef? playlistHeroCover(Playlist playlist) {
  if (playlist.songCount == 0 && playlist.tracks.isEmpty) return null;
  if (playlist.coverArt case final cover?) return cover;
  final first = playlist.tracks.firstOrNull;
  if (first == null) return null;
  return first.coverArt ?? (first.albumId == null ? null : CoverSourceRelative(first.albumId!.rawId));
}

/// Up to two covers to fan behind the hero (Android playlistSleeveCovers):
/// the rows' own album covers in playlist order, distinct, not the hero's.
List<CoverRef> playlistSleeveCovers(Playlist playlist, {int max = 2}) {
  final hero = playlistHeroCover(playlist);
  final out = <CoverRef>[];
  for (final t in playlist.tracks) {
    final cover = t.coverArt ?? (t.albumId == null ? null : CoverSourceRelative(t.albumId!.rawId));
    if (cover == null || cover == hero || out.contains(cover)) continue;
    out.add(cover);
    if (out.length == max) break;
  }
  return out;
}

/// What the page's edits say on the snackbar, in the app's language, taken
/// where the edit was made: the answer can come after the page has closed.
final class PlaylistEditLines {
  const PlaylistEditLines(this.strings, this.errors);

  static const en = PlaylistEditLines(ComponentStrings.en, ErrorStrings.en);

  final ComponentStrings strings;
  final ErrorStrings errors;

  /// What a refusal says: the service's own words or the app's line for the
  /// failure, else [fallback] (Android `it.message ?: fallback`).
  String failure(Object? error, String fallback) => userMessageOf(error)?.text(errors) ?? fallback;
}

/// The Playlist page (Android PlaylistDetailViewModel). Rename, Delete and
/// Remove from playlist are optimistic, as iOS's PlaylistActions: the page
/// shows the change at once and the service is told after; a refusal puts
/// back what it changed and the snackbar says so. Each edit belongs to the
/// account it was made on and is not sent once another one is in use.
class PlaylistDetailNotifier extends Notifier<PlaylistDetailState> {
  PlaylistDetailNotifier(this.playlistId);

  final MediaId playlistId;
  int _generation = 0;

  // The playlist as the service last gave it, and its entries in the
  // service's order (the ones on their way out included).
  Playlist? _lastRead;
  List<PlaylistEntry> _entries = const [];
  int _nextRowKey = 0;

  // Rows taken off the page while their removal is out; one that fails comes
  // back where it was.
  final _removing = <String>{};

  // One removal at a time: each finds its position in the playlist as the
  // one before left it.
  Future<void> _removals = Future.value();

  // Renames reach the service one after another, in the order they were
  // made. Each ends with the name the service then holds, which is what the
  // next one goes back to if it is refused.
  Future<String?>? _renaming;

  // Renames made and not yet answered. While any is out, a read of the
  // playlist keeps the name on the page: it is the latest one asked for.
  int _renamesOut = 0;

  bool _deleted = false;

  @override
  PlaylistDetailState build() {
    // A write anywhere (Add to playlist from a row's sheet, a rename here): the page re-reads in place.
    ref.listen(playlistsRevisionProvider, (_, _) {
      // Not once Delete has closed the page: a read then would find the
      // playlist gone and put "not found" on the page on its way out.
      if (!_deleted && state is PlaylistDetailContent) _load(ref.read(detailCacheProvider));
    });
    // Kept current, so an edit knows its account the moment it is made.
    ref.listen(playlistAccountProvider, (_, _) {});
    _load(ref.watch(detailCacheProvider));
    return const PlaylistDetailLoading();
  }

  PlaylistWrites get _writes => ref.read(playlistWritesProvider);

  /// Rename (Android rename): the page wears the new name at once and the
  /// service is told after. If it refuses, the name it still holds comes back
  /// and the snackbar says so; a name put on the page since (a later rename)
  /// is left alone. A blank or unchanged name sends nothing.
  void rename(String name, PlaylistEditLines lines) {
    final trimmed = name.trim();
    if (trimmed.isEmpty || _deleted) return;
    final before = _shownName;
    if (before == null || trimmed == before) return;
    _showName(trimmed);
    // With a rename still out, the name on the page is that one's, not the service's.
    final earlier = _renamesOut > 0 ? _renaming : null;
    final writes = _writes;
    final account = writes.accountNow();
    final post = ref.read(appMessageSinkProvider);
    final renames = ref.read(playlistRenamesProvider.notifier);
    _renamesOut++;
    // The page already wears the name, so the write goes on if the page closes under it.
    _renaming = () async {
      final held = earlier != null ? await earlier : before;
      Object? refusal;
      var ok = false;
      try {
        if (await writes.isAccountInUse(account)) {
          await writes.rename(playlistId, trimmed);
          ok = true;
        }
      } catch (e) {
        refusal = e;
      }
      _renamesOut--;
      if (ok) {
        post(lines.strings.playlistRenamed);
        // Home's rows that keep a name of their own (Activities, Jump Back In) take this one.
        unawaited(renames.renamed(account, playlistId, trimmed));
        return trimmed;
      }
      // Only while this name is still the one on the page: a later rename is not undone.
      if (held != null && _shownName == trimmed) _showName(held);
      post(lines.failure(refusal, lines.strings.playlistErrorRename));
      return held;
    }();
  }

  /// Delete (Android delete): hands the deletion to [PlaylistDeletions],
  /// which outlives the page, and says the page may close at once (true).
  /// The playlist leaves the Library meanwhile; the snackbar says how it
  /// went. A second Delete hands nothing over (false).
  bool delete(PlaylistDeletionLines lines) {
    if (_deleted) return false;
    _deleted = true;
    // A read still out lands on a closing page: dropped (it may find the playlist gone).
    _generation++;
    ref.read(playlistDeletionsProvider.notifier).delete(playlistId, lines);
    return true;
  }

  /// "Remove from playlist" on the row [rowKey] (Android removeTrack): that
  /// one entry, so a song in the playlist twice keeps its other row. The row
  /// leaves the page at once and comes back where it was if the service
  /// refuses, with the snackbar saying so. Nothing happens on a playlist the
  /// account can't edit ([PlaylistDetailContent.canRemoveSongs]).
  void removeTrack(String rowKey, PlaylistEditLines lines) {
    final content = state;
    if (content is! PlaylistDetailContent || !content.canRemoveSongs) return;
    if (!_entries.any((e) => e.rowKey == rowKey) || !_removing.add(rowKey)) return;
    _publish();
    final writes = _writes;
    final account = writes.accountNow();
    final post = ref.read(appMessageSinkProvider);
    final cache = ref.read(detailCacheProvider);
    // The row is already off the page, so the write goes on if the page closes under it.
    _removals = _removals
        .then((_) => _removeEntry(rowKey, account, writes, cache, post, lines))
        .catchError((Object _) {});
  }

  Future<void> _removeEntry(
    String rowKey,
    String? account,
    PlaylistWrites writes,
    DetailCache cache,
    void Function(String) post,
    PlaylistEditLines lines,
  ) async {
    final position = _entries.indexWhere((e) => e.rowKey == rowKey);
    if (position < 0) {
      // A read since the tap no longer has it: gone already.
      _removing.remove(rowKey);
      return;
    }
    final entry = _entries[position];
    try {
      // Another account since the tap: the playlist is not this one.
      if (!await writes.isAccountInUse(account)) {
        _removing.remove(rowKey);
        _publish();
        return;
      }
      await writes.remove(playlistId, [(track: entry.track.id, position: position)]);
    } catch (e) {
      _removing.remove(rowKey);
      _publish();
      post(lines.failure(e, lines.strings.playlistErrorRemove));
      return;
    }
    _entries = [
      for (final e in _entries)
        if (e.rowKey != rowKey) e,
    ];
    if (_lastRead case final read?) _lastRead = _without(read, entry.track);
    _removing.remove(rowKey);
    _publish();
    // Read again rather than trust the one row going: the positions are the
    // service's, another client may have edited the playlist, and a song
    // listed twice may have lost both entries. A read that fails leaves the
    // page as it is.
    try {
      final fresh = await cache.playlist(playlistId);
      if (fresh != null && ref.mounted) _show(fresh);
    } catch (_) {}
  }

  /// Moves the song at [from] to [to]: the page shows the new order at once,
  /// the old one again if the server refuses (returns false then). Not while
  /// a removal is out: the order it would send is not the service's.
  Future<bool> move(int from, int to) async {
    if (state is! PlaylistDetailContent || _removing.isNotEmpty) return false;
    final entries = [..._entries];
    if (from == to || from < 0 || to < 0 || from >= entries.length || to >= entries.length) return false;
    final before = _entries;
    entries.insert(to, entries.removeAt(from));
    _entries = entries;
    _publish();
    try {
      await _writes.reorder(playlistId, [for (final e in entries) e.track]);
      return true;
    } catch (_) {
      _entries = before;
      _publish();
      return false;
    }
  }

  Future<void> retry() async {
    state = const PlaylistDetailLoading();
    await _load(ref.read(detailCacheProvider));
  }

  Future<void> _load(DetailCache cache) async {
    final gen = ++_generation;
    try {
      final playlist = await cache.playlist(playlistId);
      if (!ref.mounted || gen != _generation) return;
      if (playlist == null) {
        state = const PlaylistDetailError(notFound: true);
      } else {
        _show(playlist);
      }
    } catch (e) {
      if (!ref.mounted || gen != _generation) return;
      // A read again under a page already up (after an edit) leaves it as it is.
      if (state is PlaylistDetailContent) return;
      state = PlaylistDetailError(notFound: false, cause: e, diagnostics: diagnosticReport(e));
    }
  }

  /// Takes [read] as the playlist. An entry keeps the row key it had (by
  /// song, in order, so the two entries of a song listed twice stay apart);
  /// a new one gets a new key.
  void _show(Playlist read) {
    final known = <MediaId, List<String>>{};
    for (final e in _entries) {
      (known[e.track.id] ??= []).add(e.rowKey);
    }
    _entries = [
      for (final track in read.tracks)
        PlaylistEntry(switch (known[track.id]) {
          final keys? when keys.isNotEmpty => keys.removeAt(0),
          _ => 'row-${_nextRowKey++}',
        }, track),
    ];
    // A removal out whose row the read no longer lists is gone already.
    _removing.retainAll({for (final e in _entries) e.rowKey});
    _lastRead = read;
    _publish();
  }

  /// Publishes the playlist as the page shows it: without the rows whose
  /// removal is out, and with the name of a rename still out.
  void _publish() {
    final read = _lastRead;
    if (read == null || !ref.mounted) return;
    final shown = <PlaylistEntry>[];
    var playlist = read;
    for (final e in _entries) {
      if (_removing.contains(e.rowKey)) {
        // The count and length say what the rows show.
        playlist = _without(playlist, e.track);
      } else {
        shown.add(e);
      }
    }
    final name = _renamesOut > 0 ? (_shownName ?? read.name) : read.name;
    state = PlaylistDetailContent(
      _copy(playlist, name: name, tracks: [for (final e in shown) e.track]),
      entries: shown,
      canRemoveSongs: read.canWrite && playlistAbilities(ref.read(activeSourceProvider).value).canEdit,
    );
  }

  String? get _shownName {
    if (!ref.mounted) return null;
    final content = state;
    return content is PlaylistDetailContent ? content.playlist.name : null;
  }

  void _showName(String name) {
    if (!ref.mounted) return;
    final content = state;
    if (content is! PlaylistDetailContent) return;
    state = PlaylistDetailContent(
      _copy(content.playlist, name: name),
      entries: content.entries,
      canRemoveSongs: content.canRemoveSongs,
    );
  }

  /// [p]'s count and length with one entry of [track] gone.
  static Playlist _without(Playlist p, Track track) => _copy(
    p,
    songCount: p.songCount == null ? null : math.max(0, p.songCount! - 1),
    durationSec: p.durationSec == null ? null : math.max(0, p.durationSec! - (track.durationSec ?? 0)),
  );

  static Playlist _copy(Playlist p, {String? name, int? songCount, int? durationSec, List<Track>? tracks}) => Playlist(
    id: p.id,
    name: name ?? p.name,
    owner: p.owner,
    coverArt: p.coverArt,
    songCount: songCount ?? p.songCount,
    durationSec: durationSec ?? p.durationSec,
    tracks: tracks ?? p.tracks,
    canWrite: p.canWrite,
    comment: p.comment,
    ownedByMe: p.ownedByMe,
    libraryAddedAt: p.libraryAddedAt,
  );
}

final playlistDetailProvider = NotifierProvider.autoDispose
    .family<PlaylistDetailNotifier, PlaylistDetailState, MediaId>(PlaylistDetailNotifier.new);
