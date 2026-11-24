/// The mentions in drawn notes for the account in use (Android
/// ui/notes/NoteMentions.kt + NoteNavigation.kt): whether each resolves
/// ([NoteMentions.isResolved], read in build: an answer that lands later
/// redraws) and where a tap goes ([NoteMentions.open]). A mention on the
/// account's own provider resolves at once; another provider's is matched by
/// name in the background. Unresolved ones draw as plain text and take no tap
/// (owner 2026-11-22). The answers are the account's: a switch starts over.
library;

import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/memory/track_album_links.dart';
import '../data/model/model.dart';
import '../data/notes/mention_resolver.dart';
import '../data/notes/note_markup.dart';
import '../data/source/music_source.dart';
import '../detail/detail_routes.dart';
import '../detail/scrapbook_focus.dart';
import '../theme/tokens.dart';

/// Where a mention takes the reader.
sealed class NoteDestination {
  const NoteDestination();
}

/// An album's scrapbook (page 2); [focusTrack] brings one of its songs into view there.
final class NoteAlbumScrapbook extends NoteDestination {
  const NoteAlbumScrapbook(this.albumId, {this.focusTrack});

  final MediaId albumId;
  final MediaId? focusTrack;
}

final class NoteArtist extends NoteDestination {
  const NoteArtist(this.artistId, {this.name});

  final MediaId artistId;
  final String? name;
}

/// A surface that has to get out of the way before a detail page opens (Now
/// Playing closes itself, then opens on the app navigator): it hands [leave]
/// the page to open. Without one, the page opens from the tapped text.
class NoteMentionLeave extends InheritedWidget {
  const NoteMentionLeave({super.key, required this.leave, required super.child});

  final void Function(void Function(BuildContext navigator) open) leave;

  static void Function(void Function(BuildContext navigator) open)? maybeOf(BuildContext context) =>
      context.getInheritedWidgetOfExactType<NoteMentionLeave>()?.leave;

  @override
  bool updateShouldNotify(NoteMentionLeave oldWidget) => false;
}

/// Answers by mention for the account in use: true = resolved (tappable, in the mention colour).
class NoteMentions extends Notifier<Map<MentionRef, bool>> {
  /// How long after opening a page another tap is taken as the same one.
  static const openCooldown = Duration(seconds: 1);

  final Set<MentionRef> _asked = {};
  final Map<MentionRef, String?> _artworks = {};
  final Set<MentionRef> _artworkAsked = {};
  int _generation = 0;
  bool _opening = false;
  DateTime? _openedAt;

  @override
  Map<MentionRef, bool> build() {
    // A new account (or its source landing) starts over.
    ref.watch(activeSourceProvider);
    _asked.clear();
    _artworks.clear();
    _artworkAsked.clear();
    _generation++;
    return const {};
  }

  /// The cover (or artist picture) a drawn mention shows before its name.
  /// Read in build like [isResolved]: null until it is known (asked for once
  /// in the background, a landing answer redraws), and for one with none.
  String? artworkUrl(NoteMention mention) {
    if (_artworks.containsKey(mention.ref)) return _artworks[mention.ref];
    if (_artworkAsked.add(mention.ref)) {
      final generation = _generation;
      unawaited(
        Future.microtask(() async {
          String? url;
          try {
            url = await _artworkOf(mention);
          } on Object {
            url = null;
          }
          if (!ref.mounted || generation != _generation) return;
          _artworks[mention.ref] = url;
          // A new map: the drawn notes redraw with the cover.
          if (url != null) state = {...state};
        }),
      );
    }
    return null;
  }

  /// A resolved mention's artwork: a song's cover from Yoin's plays, else the
  /// service; an album's from its plays, else the service; an artist's picture.
  Future<String?> _artworkOf(NoteMention mention) async {
    final answer = await (await _resolver()).resolve(mention);
    if (answer is! MentionResolved) return null;
    final source = await ref.read(activeSourceProvider.future);
    if (source == null) return null;
    final store = await ref.read(memoryStoreProvider.future);
    final id = answer.id;
    CoverRef? cover;
    switch (mention.kind) {
      case MentionKind.track:
        for (final t in await store?.trackPlays() ?? const <TrackPlays>[]) {
          if (t.trackId == id) cover = CoverRef.fromStorageKey(t.coverArtKey);
        }
        cover ??= (await source.trackLookup?.getTrack(id))?.coverArt;
      case MentionKind.album:
        cover = CoverRef.fromStorageKey((await store?.playsOf(id))?.coverArtKey);
        cover ??= (await source.library.getAlbum(id))?.coverArt;
      case MentionKind.artist:
        cover = (await source.library.getArtist(id))?.coverArt;
    }
    if (cover == null) return null;
    return source.resolveCoverUrl(cover, size: NoteEditorTokens.coverSize);
  }

  /// Read in build: never changes state synchronously; a missing answer is asked for in the background.
  bool isResolved(NoteMention mention) {
    final provider = ref.read(activeSourceProvider).value?.provider;
    if (provider == null) return false;
    if (mention.provider == provider) return true;
    final known = state[mention.ref];
    if (known != null) return known;
    if (_asked.add(mention.ref)) {
      final generation = _generation;
      unawaited(
        Future(() async {
          final answer = await (await _resolver()).resolve(mention);
          if (!ref.mounted || generation != _generation) return;
          state = {...state, mention.ref: answer is MentionResolved};
        }),
      );
    }
    return false;
  }

  /// Opens what [mention] names: an album's scrapbook, a song's album
  /// scrapbook on that song, an artist. One open at a time: a second tap while
  /// one is being looked up, or right after one opened, does nothing.
  Future<void> open(BuildContext context, NoteMention mention) async {
    final last = _openedAt;
    if (_opening || (last != null && DateTime.now().difference(last) < openCooldown)) return;
    _opening = true;
    try {
      final answer = await (await _resolver()).resolve(mention);
      if (answer is! MentionResolved) return;
      final target = await destinationOf(answer);
      if (target == null || !context.mounted || !ref.mounted) return;
      _openedAt = DateTime.now();
      final focus = ref.read(scrapbookFocusRequestProvider.notifier);
      void go(BuildContext navigator) {
        switch (target) {
          case NoteAlbumScrapbook(:final albumId, :final focusTrack):
            focus.request(focusTrack ?? albumId);
            DetailRoutes.openAlbum(navigator, albumId);
          case NoteArtist(:final artistId, :final name):
            DetailRoutes.openArtist(navigator, artistId, name: name);
        }
      }

      final leave = NoteMentionLeave.maybeOf(context);
      leave != null ? leave(go) : go(context);
    } finally {
      _opening = false;
    }
  }

  /// Where a resolved mention goes; null for a song whose album can't be found.
  Future<NoteDestination?> destinationOf(MentionResolved resolved) async => switch (resolved.mention.kind) {
    MentionKind.album => NoteAlbumScrapbook(resolved.id),
    MentionKind.track => switch (await _albumOf(resolved.id)) {
      final album? => NoteAlbumScrapbook(album, focusTrack: resolved.id),
      null => null,
    },
    MentionKind.artist => NoteArtist(resolved.id, name: resolved.mention.title),
  };

  Future<LocalMentionResolver> _resolver() async {
    final source = await ref.read(activeSourceProvider.future);
    final store = await ref.read(memoryStoreProvider.future);
    if (source == null || store == null) return const LocalMentionResolver(account: null, names: null);
    return LocalMentionResolver(
      account: MentionAccount(store.profileId, source.provider),
      names: MemoryMentionNameLookup(
        store,
        links: TrackAlbumLinkStore.shared(ref.read(keyValueStoreProvider), store.profileId),
      ),
    );
  }

  /// A song's album: what Yoin's plays, notes or album answers know, else the service.
  Future<MediaId?> _albumOf(MediaId trackId) async {
    MediaId? mine(MediaId? album) => album != null && album.provider == trackId.provider ? album : null;
    final store = await ref.read(memoryStoreProvider.future);
    if (store != null) {
      for (final t in await store.trackPlays()) {
        if (t.trackId == trackId && mine(t.albumId) != null) return t.albumId;
      }
      for (final n in await store.songNotes()) {
        if (n.trackId == trackId && mine(n.albumId) != null) return n.albumId;
      }
      final link = (await TrackAlbumLinkStore.shared(
        ref.read(keyValueStoreProvider),
        store.profileId,
      ).links())[trackId];
      if (mine(link?.albumId) case final album?) return album;
    }
    try {
      final source = await ref.read(activeSourceProvider.future);
      return mine((await source?.trackLookup?.getTrack(trackId))?.albumId);
    } catch (_) {
      return null;
    }
  }
}

final noteMentionsProvider = NotifierProvider<NoteMentions, Map<MentionRef, bool>>(NoteMentions.new);
