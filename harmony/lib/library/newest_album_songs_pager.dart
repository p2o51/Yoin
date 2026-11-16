import '../data/model/model.dart';
import '../data/source/music_source.dart';
import 'library_sort.dart';

/// Songs for a server with no song list (Subsonic): the songs of albums,
/// newest album first, each album in its page order (Android
/// NewestAlbumSongsPager).
///
/// Each [next] reads one page of [pageAlbums] albums starting [overlap]
/// albums before where the last page ended (an album deleted meanwhile
/// never makes it skip one), opens the new ones at most [inFlight] at a
/// time and returns their songs. A song is listed once however many albums
/// hold it. A page with no new songs reads on, so an empty result means the
/// end. A failed read leaves the pager where it was (a retry re-reads the
/// same albums); an album that fails twice in a row is left out.
/// Library › Songs read a page at a time: [next] gives the next songs (empty
/// at the end), [ended] says nothing more follows.
abstract interface class LibrarySongsPager {
  bool get ended;
  Future<List<Track>> next();
}

/// Songs for a service whose library lists songs of its own (Android
/// readLibrarySongs, Apple Music): its first [size], read at once, in title
/// order (Android songsByTitle: Apple sends them in two A–Z runs).
final class ServiceLibrarySongsPager implements LibrarySongsPager {
  ServiceLibrarySongsPager(this._collection, {this.size = 500});

  final MusicLibraryCollection _collection;
  final int size;
  bool _ended = false;

  @override
  bool get ended => _ended;

  @override
  Future<List<Track>> next() async {
    if (_ended) return const [];
    final songs = await _collection.librarySongs(size: size);
    _ended = true;
    return librarySongsByTitle(songs);
  }
}

final class NewestAlbumSongsPager implements LibrarySongsPager {
  NewestAlbumSongsPager(
    this._library, {
    Future<Album?> Function(MediaId id)? openAlbum,
    this.pageAlbums = 10,
    this.overlap = 5,
    this.inFlight = 3,
  }) : _openAlbum = openAlbum ?? _library.getAlbum;

  final MusicLibrary _library;
  final Future<Album?> Function(MediaId id) _openAlbum;
  final int pageAlbums;
  final int overlap;
  final int inFlight;

  /// Albums consumed so far (the next page starts [overlap] before this).
  int _consumed = 0;
  final _heldAlbums = <MediaId>{};
  MediaId? _lastAlbum;
  final _seenSongs = <MediaId>{};
  final _failures = <MediaId, int>{};
  bool _ended = false;

  @override
  bool get ended => _ended;

  @override
  Future<List<Track>> next() async {
    while (!_ended) {
      final from = _consumed - overlap < 0 ? 0 : _consumed - overlap;
      final size = pageAlbums + (_consumed - from);
      final page = await _library.getAlbumList(AlbumListType.newest, size: size, offset: from);
      if (page.isEmpty) {
        _ended = true;
        break;
      }
      // The new albums are those after the last one already held.
      final lastIndex = _lastAlbum == null ? -1 : page.indexWhere((a) => a.id == _lastAlbum);
      final fresh = [
        for (final (i, a) in page.indexed)
          if (i > lastIndex && !_heldAlbums.contains(a.id)) a,
      ];
      if (fresh.isEmpty) {
        // The server ignores the offset, or the list ended inside the overlap.
        _ended = true;
        break;
      }
      final opened = await _openAll(fresh);
      // Committed only now: a failure above leaves the pager where it was.
      final songs = <Track>[];
      for (final album in opened) {
        for (final track in album?.tracks ?? const <Track>[]) {
          if (_seenSongs.add(track.id)) songs.add(track);
        }
      }
      for (final a in fresh) {
        _heldAlbums.add(a.id);
      }
      _lastAlbum = fresh.last.id;
      _consumed = from + page.indexWhere((a) => a.id == _lastAlbum) + 1;
      if (page.length < size) _ended = true;
      if (songs.isNotEmpty) return songs;
    }
    return const [];
  }

  /// Opens [albums] (in order, [inFlight] at a time). An album that has
  /// failed twice in a row contributes nothing; any other failure throws.
  Future<List<Album?>> _openAll(List<Album> albums) async {
    final results = List<Album?>.filled(albums.length, null);
    Object? error;
    var nextIndex = 0;
    Future<void> worker() async {
      while (nextIndex < albums.length) {
        final i = nextIndex++;
        final id = albums[i].id;
        try {
          results[i] = await _openAlbum(id);
          _failures.remove(id);
        } catch (e) {
          final count = (_failures[id] ?? 0) + 1;
          _failures[id] = count;
          if (count < 2) error ??= e;
        }
      }
    }

    await Future.wait([for (var w = 0; w < inFlight; w++) worker()]);
    if (error != null) throw error!;
    return results;
  }
}
