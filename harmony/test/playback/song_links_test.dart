import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/playback/song_links.dart';
import 'package:yoin/theme/playback_theme.dart';
import 'package:yoin/ui/component/track_menu_sheet.dart';

import '../support/nowplaying_fakes.dart';

/// A source that knows each song's album and artist (Subsonic getSong, Apple Music's songs with
/// albums,artists), counting the reads.
class _LinkedSource extends NowPlayingFakeSource implements MusicTrackLookup {
  _LinkedSource({this.fail = false, this.tag = ''});

  final bool fail;

  /// Told apart in the ids it answers with (another server of the same kind).
  final String tag;
  final List<String> reads = [];

  @override
  Future<Track?> getTrack(MediaId id) async {
    reads.add(id.rawId);
    if (fail) throw const SourceNetworkException('offline');
    return Track(
      id: id,
      title: 'Full ${id.rawId}',
      albumId: MediaId.subsonic('album-$tag${id.rawId}'),
      artistId: MediaId.subsonic('artist-$tag${id.rawId}'),
    );
  }
}

/// A song rebuilt from local history (a noted song, a Lyric Card): no artist id, no album id.
Track _slim(String raw) => Track(id: MediaId.subsonic(raw), title: 'Slim $raw', artist: 'Someone');

/// The account in use, switchable mid-test.
class _Account extends Notifier<MusicSource> {
  _Account(this._first);

  final MusicSource _first;

  @override
  MusicSource build() => _first;

  void use(MusicSource source) => state = source;
}

final _accountProvider = NotifierProvider<_Account, MusicSource>(() => throw UnimplementedError());

Future<void> _settle() async {
  for (var i = 0; i < 6; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  group('SongLinksResolver', () {
    test('should_returnOwnLinksWithoutAsking_when_songHasBoth', () async {
      final source = _LinkedSource();
      final full = Track(id: MediaId.subsonic('s'), albumId: MediaId.subsonic('al'), artistId: MediaId.subsonic('ar'));
      final links = await SongLinksResolver(source).linksFor(full);
      expect(links, SongLinks(albumId: MediaId.subsonic('al'), artistId: MediaId.subsonic('ar')));
      expect(source.reads, isEmpty);
    });

    test('should_fillWhatIsMissingOnce_when_songCameWithoutLinks', () async {
      final source = _LinkedSource();
      final resolver = SongLinksResolver(source);
      final own = Track(id: MediaId.subsonic('s1'), albumId: MediaId.subsonic('mine'));
      final links = await resolver.linksFor(own);
      expect(links.albumId, MediaId.subsonic('mine'), reason: 'its own album is kept');
      expect(links.artistId, MediaId.subsonic('artist-s1'));
      await resolver.linksFor(_slim('s1'));
      expect(source.reads, ['s1'], reason: 'asked once a song');
    });

    test('should_notAsk_when_songIsAnotherServices', () async {
      final source = _LinkedSource();
      final apple = Track(id: MediaId(MediaId.providerAppleMusic, '123'));
      final links = await SongLinksResolver(source).linksFor(apple);
      expect(links, const SongLinks());
      expect(source.reads, isEmpty);
    });

    test('should_askAgainLater_when_readFails', () async {
      final source = _LinkedSource(fail: true);
      final resolver = SongLinksResolver(source);
      expect(await resolver.linksFor(_slim('s2')), const SongLinks());
      await _settle();
      await resolver.linksFor(_slim('s2'));
      expect(source.reads, ['s2', 's2']);
    });

    test('should_keepTrackItself_when_linksAddNothing', () {
      final track = _slim('s3');
      expect(identical(const SongLinks().applyTo(track), track), isTrue);
      final filled = SongLinks(artistId: MediaId.subsonic('a')).applyTo(track);
      expect(filled.artistId, MediaId.subsonic('a'));
      expect(filled.title, 'Slim s3');
      expect(filled.artistCredits.single.id, MediaId.subsonic('a'), reason: 'the artist name now opens a page');
    });
  });

  group('Now Playing', () {
    test('should_openArtistAndAlbum_when_songFromHomeCardCameWithout', () async {
      final source = _LinkedSource();
      final controller = NowPlayingFakeController(playingState([_slim('n1')]));
      final c = ProviderContainer(
        overrides: nowPlayingOverrides(controller: controller, source: source),
      );
      addTearDown(c.dispose);
      c.listen(nowPlayingViewModelProvider, (_, _) {});
      await c.read(activeSourceProvider.future);
      await _settle();
      final s = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
      expect(s.track.artistId, MediaId.subsonic('artist-n1'));
      expect(s.track.albumId, MediaId.subsonic('album-n1'));
      expect(s.track.title, 'Slim n1', reason: 'only the links change, not what the card showed');
      expect(source.reads, ['n1']);

      // A rebuild (pause) keeps the same filled-in song: the state stays equal where nothing else changed.
      final before = s.track;
      controller.emit(playingState([_slim('n1')], playWhenReady: false).copyWith(queue: controller.state.queue));
      await _settle();
      final after = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
      expect(identical(after.track, before), isTrue);
      expect(source.reads, ['n1'], reason: 'asked once per song');
    });

    test('should_askTheNewAccount_when_accountChangesUnderTheSameSongId', () async {
      final a = _LinkedSource(tag: 'a-');
      final b = _LinkedSource(tag: 'b-');
      final controller = NowPlayingFakeController(playingState([_slim('n2')]));
      final c = ProviderContainer(
        overrides: [
          playbackControllerProvider.overrideWith(() => controller),
          coverColorLoaderProvider.overrideWithValue(NowPlayingFakeColorLoader()),
          _accountProvider.overrideWith(() => _Account(a)),
          activeSourceProvider.overrideWith((ref) async => ref.watch(_accountProvider)),
        ],
      );
      addTearDown(c.dispose);
      c.listen(nowPlayingViewModelProvider, (_, _) {});
      await c.read(activeSourceProvider.future);
      await _settle();
      expect((c.read(nowPlayingViewModelProvider) as NowPlayingContent).track.artistId, MediaId.subsonic('artist-a-n2'));

      // Another server of the same kind, where raw id n2 is another song.
      c.read(_accountProvider.notifier).use(b);
      await c.read(activeSourceProvider.future);
      await _settle();
      expect(
        (c.read(nowPlayingViewModelProvider) as NowPlayingContent).track.artistId,
        MediaId.subsonic('artist-b-n2'),
        reason: "the first account's answer doesn't carry over",
      );
      expect(b.reads, ['n2']);
    });

    test('should_leaveSongAlone_when_itCameWithBoth', () async {
      final source = _LinkedSource();
      final full = Track(id: MediaId.subsonic('f1'), albumId: MediaId.subsonic('al'), artistId: MediaId.subsonic('ar'));
      final controller = NowPlayingFakeController(playingState([full]));
      final c = ProviderContainer(
        overrides: nowPlayingOverrides(controller: controller, source: source),
      );
      addTearDown(c.dispose);
      c.listen(nowPlayingViewModelProvider, (_, _) {});
      await c.read(activeSourceProvider.future);
      await _settle();
      expect(identical((c.read(nowPlayingViewModelProvider) as NowPlayingContent).track, full), isTrue);
      expect(source.reads, isEmpty);
    });
  });

  group('the song menu', () {
    testWidgets('should_offerGoToArtistAndAlbum_when_songCameWithoutThem', (tester) async {
      final source = _LinkedSource();
      final controller = NowPlayingFakeController(playingState(nowPlayingTracks(1)));
      final visits = <String>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: nowPlayingOverrides(controller: controller, source: source),
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => showTrackMenuSheet(
                      context,
                      track: _slim('m1'),
                      onGoToAlbum: (id) => visits.add('album:${id.rawId}'),
                      onGoToArtist: (id) => visits.add('artist:${id.rawId}'),
                      onMessage: (_) {},
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      expect(find.text('Go to album'), findsOneWidget);
      expect(find.text('Go to artist'), findsOneWidget);
      await tester.ensureVisible(find.text('Go to artist'));
      await tester.tap(find.text('Go to artist'));
      await tester.pumpAndSettle();
      expect(visits, ['artist:artist-m1']);
    });
  });
}
