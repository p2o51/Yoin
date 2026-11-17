import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/detail_share.dart';
import 'package:yoin/detail/detail_strings.dart';
import 'package:yoin/platform/browser/url_opener.dart';
import 'package:yoin/platform/share/text_share.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_music_source.dart';
import '../support/link_fakes.dart';
import '../support/nowplaying_fakes.dart';

/// A source with links: [page] is every item's public page, [made] what
/// createShareLink answers (a throw when [refuse]).
class _LinkSource extends FakeMusicSource implements MusicMetadata, MusicLinks {
  _LinkSource({this.page, this.made, this.refuse = false, this.shares = true, this.gate});

  final String? page;
  final String? made;
  final bool refuse;
  final bool shares;
  final Completer<void>? gate;
  final List<(WebLinkKind, MediaId, String?)> requests = [];

  @override
  Set<Capability> get capabilities => {if (shares) Capability.shareLinks};

  @override
  MusicMetadata get metadata => this;

  @override
  Future<Lyrics?> getLyrics(Track track) async => null;

  @override
  Future<TrackNames?> alternateNames(Track track) async => null;

  @override
  Future<String?> webUrl(WebLinkKind kind, MediaId id) async => page;

  @override
  Future<String?> createShareLink(WebLinkKind kind, MediaId id, {String? description}) async {
    requests.add((kind, id, description));
    await gate?.future;
    if (refuse) throw const SourceServerException('Sharing is off', code: 0);
    return made;
  }
}

void main() {
  late RecordingTextShare share;
  late NowPlayingFakeController controller;
  late List<String> messages;

  ProviderContainer containerFor(MusicSource source) {
    share = RecordingTextShare();
    controller = NowPlayingFakeController(playingState(nowPlayingTracks(1)));
    messages = [];
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => source),
        textShareProvider.overrideWithValue(share),
        playbackControllerProvider.overrideWith(() => controller),
      ],
    );
    addTearDown(c.dispose);
    final sub = c.read(playbackControllerProvider.notifier).notices.listen((n) {
      if (n is TextNotice) messages.add(n.text);
    });
    addTearDown(sub.cancel);
    return c;
  }

  setUp(DetailShare.resetForTest);

  group('text', () {
    test('should_putLinkOnNextLine_when_thereIsOne', () {
      expect(detailShareText('Blue – Joni', 'https://s/1'), 'Blue – Joni\nhttps://s/1');
      expect(detailShareText('Blue – Joni', null), 'Blue – Joni');
    });

    test('should_nameTheService_when_linkIsItsPage', () {
      const strings = DetailStrings.en;
      expect(openInLabel('https://music.apple.com/us/album/1', strings), 'Open in Apple Music');
      expect(openInLabel('https://open.spotify.com/album/1', strings), 'Open in Spotify');
      expect(openInLabel('https://music.example.com/share/1', strings), isNull);
      expect(openInLabel(null, strings), isNull);
    });

    test('should_joinTitleAndArtist_when_sharingASong', () {
      expect(trackShareTitle(Track(id: MediaId.subsonic('s'), title: 'Lantern', artist: 'Mono')), 'Lantern — Mono');
      expect(trackShareTitle(Track(id: MediaId.subsonic('s'), title: 'Lantern')), 'Lantern');
    });
  });

  group('DetailShare', () {
    test('should_sendThePage_when_linkIsKnown', () async {
      final source = _LinkSource(made: 'https://never');
      final c = containerFor(source);
      await DetailShare.share(
        c,
        kind: WebLinkKind.album,
        id: MediaId.subsonic('al1'),
        title: 'Blue – Joni',
        webLink: 'https://music.apple.com/us/album/1',
        linkFailedMessage: 'failed',
      );
      expect(share.shared, [('Blue – Joni\nhttps://music.apple.com/us/album/1', 'Blue – Joni')]);
      expect(source.requests, isEmpty);
    });

    test('should_readThePage_when_linkHasNotResolvedYet', () async {
      final c = containerFor(_LinkSource(page: 'https://music.apple.com/us/album/2'));
      await DetailShare.share(
        c,
        kind: WebLinkKind.album,
        id: MediaId.subsonic('al1'),
        title: 'Blue',
        linkFailedMessage: 'x',
      );
      expect(share.shared.single.$1, 'Blue\nhttps://music.apple.com/us/album/2');
    });

    test('should_makeAShareLink_when_serverHasNoPublicPage', () async {
      final source = _LinkSource(made: 'https://music.example.com/share/abc');
      final c = containerFor(source);
      await DetailShare.share(
        c,
        kind: WebLinkKind.playlist,
        id: MediaId.subsonic('pl1'),
        title: 'Mix',
        linkFailedMessage: 'failed',
      );
      expect(source.requests.single, (WebLinkKind.playlist, MediaId.subsonic('pl1'), 'Mix'));
      expect(share.shared.single.$1, 'Mix\nhttps://music.example.com/share/abc');
      expect(messages, isEmpty);
    });

    test('should_sendTheNameAloneAndSaySo_when_serverRefuses', () async {
      final c = containerFor(_LinkSource(refuse: true));
      await DetailShare.share(
        c,
        kind: WebLinkKind.track,
        id: MediaId.subsonic('s1'),
        title: 'Song',
        linkFailedMessage: 'failed',
      );
      await Future<void>.delayed(Duration.zero);
      expect(share.shared.single.$1, 'Song');
      expect(messages, ['failed']);
    });

    test('should_sendTheNameAlone_when_serviceMakesNoLinks', () async {
      final source = _LinkSource(shares: false, made: 'https://never');
      final c = containerFor(source);
      await DetailShare.share(
        c,
        kind: WebLinkKind.album,
        id: MediaId.subsonic('al1'),
        title: 'Blue',
        linkFailedMessage: 'x',
      );
      expect(share.shared.single.$1, 'Blue');
      expect(source.requests, isEmpty);
    });

    test('should_ignoreSecondTap_when_linkIsStillBeingMade', () async {
      final gate = Completer<void>();
      final source = _LinkSource(made: 'https://music.example.com/share/1', gate: gate);
      final c = containerFor(source);
      Future<void> tap() => DetailShare.share(
        c,
        kind: WebLinkKind.album,
        id: MediaId.subsonic('al1'),
        title: 'Blue',
        linkFailedMessage: 'x',
      );
      final first = tap();
      await Future<void>.delayed(Duration.zero);
      await tap();
      gate.complete();
      await first;
      expect(source.requests, hasLength(1));
      expect(share.shared, hasLength(1));
      // Done: the next tap makes a new link.
      await tap();
      expect(source.requests, hasLength(2));
    });
  });

  group('links', () {
    test('should_resolvePublicPage_when_sourceHasOne', () async {
      final c = containerFor(_LinkSource(page: 'https://music.apple.com/us/album/9'));
      final key = (WebLinkKind.album, MediaId.subsonic('al1'));
      final sub = c.listen(detailWebLinkProvider(key), (_, _) {});
      addTearDown(sub.close);
      expect(await c.read(detailWebLinkProvider(key).future), 'https://music.apple.com/us/album/9');
    });

    test('should_openThePage_when_openInChosen', () async {
      final opener = RecordingUrlOpener();
      final c = ProviderContainer(overrides: [urlOpenerProvider.overrideWithValue(opener)]);
      addTearDown(c.dispose);
      await openDetailLink(c, 'https://music.apple.com/us/album/9');
      expect(opener.opened.single.toString(), 'https://music.apple.com/us/album/9');
    });
  });
}
