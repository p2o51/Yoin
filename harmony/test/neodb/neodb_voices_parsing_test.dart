import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/neodb/neodb_models.dart';
import 'package:yoin/data/neodb/neodb_voices.dart';

CatalogItem _item(String uuid, String title, {List<String> artist = const [], int? count}) =>
    CatalogItem(uuid: uuid, title: title, artist: artist, ratingCount: count);

NeoDbPost _post(
  String id,
  List<Map<String, Object?>> related, {
  Map<String, Object?>? account,
  bool sensitive = false,
  String? spoiler,
}) => NeoDbPost.fromJson({
  'id': id,
  'url': 'https://neodb.social/@a/$id',
  'created_at': '2026-11-09T10:00:00Z',
  'sensitive': sensitive,
  'spoiler_text': ?spoiler,
  'account': account ?? {'acct': 'ann@neodb.social', 'username': 'ann', 'display_name': 'Ann'},
  'ext_neodb': {'relatedWith': related},
});

void main() {
  group('pickAlbumItem', () {
    test('should_pickMostRatedExactTitle_when_severalEditionsMatch', () {
      final picked = NeoDbVoicesParsing.pickAlbumItem('OK Computer', 'Radiohead', [
        _item('a', 'OK Computer', artist: ['Radiohead'], count: 4),
        _item('b', 'OK Computer', artist: ['Radiohead'], count: 510),
      ]);
      expect(picked?.uuid, 'b');
    });

    test('should_returnNull_when_onlyOtherArtistsShareTheTitle', () {
      final picked = NeoDbVoicesParsing.pickAlbumItem('Museum', 'OWIS', [
        _item('jazz', 'Museum', artist: ['Some Jazz Trio'], count: 40),
        _item('x', 'Museum of Love', artist: ['OWIS']),
      ]);
      expect(picked, isNull);
    });

    test('should_acceptEdition_when_artistAgrees', () {
      final picked = NeoDbVoicesParsing.pickAlbumItem('Blonde', 'Frank Ocean', [
        _item('d', 'Blonde Deluxe Edition', artist: ['Frank Ocean']),
      ]);
      expect(picked?.uuid, 'd');
    });

    test('should_rejectLongerTitle_when_addedWordsAreNotAnEdition', () {
      expect(
        NeoDbVoicesParsing.pickAlbumItem('范特西', '周杰伦', [
          _item('p', '范特西plus', artist: ['周杰伦']),
          _item('y', '依然范特西', artist: ['周杰伦']),
        ]),
        isNull,
      );
      expect(
        NeoDbVoicesParsing.pickAlbumItem('Luv Is Rage', 'Lil Uzi Vert', [
          _item('2', 'Luv Is Rage 2', artist: ['Lil Uzi Vert']),
        ]),
        isNull,
      );
    });

    test('should_matchExactTitle_when_artistUnknownOnEitherSide', () {
      final picked = NeoDbVoicesParsing.pickAlbumItem('Blue', null, [
        _item('b', 'Blue (Remastered)', artist: ['Joni']),
      ]);
      expect(picked?.uuid, 'b');
    });

    test('should_splitArtists_when_albumNamesSeveral', () {
      final picked = NeoDbVoicesParsing.pickAlbumItem('Watch the Throne', 'JAY-Z & Kanye West', [
        _item('w', 'Watch the Throne', artist: ['Kanye West']),
      ]);
      expect(picked?.uuid, 'w');
    });
  });

  group('normalizeTitle', () {
    test('should_dropCasePunctuationAndTrailingBrackets_when_normalizing', () {
      expect(NeoDbVoicesParsing.normalizeTitle('  OK Computer (Remastered) [Deluxe] '), 'okcomputer');
      expect(NeoDbVoicesParsing.normalizeTitle('天空【限定盘】'), '天空');
    });

    test('should_keepSigns_when_titleIsOnlySigns', () {
      expect(NeoDbVoicesParsing.normalizeTitle('÷'), '÷');
      expect(NeoDbVoicesParsing.normalizeTitle('( )'), '()');
    });
  });

  group('voice', () {
    test('should_readCommentAndRating_when_postIsShortComment', () {
      final v = NeoDbVoicesParsing.voice(
        _post('1', [
          {'type': 'Comment', 'content': '  Lovely.  '},
          {'type': 'Rating', 'value': 8},
        ]),
      )!;
      expect(v.text, 'Lovely.');
      expect(v.rating, 8);
      expect(v.reviewTitle, isNull);
      expect(v.author, 'Ann');
      expect(v.otherInstance, isNull, reason: 'neodb.social is the page\'s own instance');
      expect(v.folded, isFalse);
      expect(v.createdAtMillis, DateTime.utc(2026, 11, 9, 10).millisecondsSinceEpoch);
    });

    test('should_readReviewTitleAndOpening_when_postIsReview', () {
      final v = NeoDbVoicesParsing.voice(
        _post(
          '2',
          [
            {
              'type': 'Review',
              'name': 'A night drive',
              'content': '# Big\n\n**Bold** [link](https://x) ![img](y)',
              'href': 'https://neodb.social/review/2',
            },
          ],
          account: {'acct': 'bob@mastodon.social', 'username': 'bob'},
        ),
      )!;
      expect(v.reviewTitle, 'A night drive');
      expect(v.text, 'Big Bold link');
      expect(v.url, 'https://neodb.social/review/2');
      expect(v.author, 'bob');
      expect(v.otherInstance, 'mastodon.social');
      expect(v.isLong, isTrue);
    });

    test('should_fold_when_writerMarkedItSensitiveOrWarned', () {
      final warned = NeoDbVoicesParsing.voice(
        _post('3', [
          {'type': 'Comment', 'content': 'spoilers'},
        ], spoiler: 'Plot'),
      )!;
      expect(warned.folded, isTrue);
      expect(warned.foldNote, 'Plot');
      final sensitive = NeoDbVoicesParsing.voice(
        _post('4', [
          {'type': 'Comment', 'content': 'x'},
        ], sensitive: true),
      )!;
      expect(sensitive.folded, isTrue);
      expect(sensitive.foldNote, isNull);
    });

    test('should_skipPost_when_itCarriesNoWords', () {
      expect(
        NeoDbVoicesParsing.voice(
          _post('5', [
            {'type': 'Rating', 'value': 6},
          ]),
        ),
        isNull,
      );
    });

    test('should_dropRating_when_outOfRange', () {
      final v = NeoDbVoicesParsing.voice(
        _post('6', [
          {'type': 'Comment', 'content': 'ok'},
          {'type': 'Rating', 'value': 0},
        ]),
      )!;
      expect(v.rating, isNull);
    });
  });

  test('should_keepOneVoicePerId_when_pageRepeatsAPost', () {
    final page = NeoDbVoicesParsing.page(
      1,
      NeoDbPostPage(
        data: [
          _post('1', [
            {'type': 'Comment', 'content': 'a'},
          ]),
          _post('1', [
            {'type': 'Comment', 'content': 'a again'},
          ]),
        ],
        pages: 3,
        count: 41,
      ),
    );
    expect(page.voices, hasLength(1));
    expect(page.pages, 3);
    expect(page.count, 41);
  });

  test('should_cutReviewAtSentence_when_openingIsLong', () {
    final long = '${'This is a sentence. ' * 8}and then it keeps going without a stop for quite a while longer';
    final opening = NeoDbVoicesParsing.reviewOpening(long);
    expect(opening.length, lessThanOrEqualTo(180));
    expect(opening.endsWith('.'), isTrue);
    final noStop = 'x' * 300;
    expect(NeoDbVoicesParsing.reviewOpening(noStop), '${'x' * 180}…');
  });

  test('should_readSpreadAndLinks_when_itemHasThem', () {
    final voices = NeoDbVoicesParsing.albumVoices(
      'https://neodb.social/',
      CatalogItem.fromJson({
        'uuid': 'u1',
        'title': 'OK Computer',
        'url': '/album/u1',
        'rating': 9.1,
        'rating_count': 510,
        'rating_distribution': [1, 2, 7, 30, 60],
        'external_resources': [
          {'url': 'https://music.douban.com/subject/1/'},
          {'url': 'https://www.discogs.com/release/9-x'},
          {'url': 'https://www.discogs.com/master/21491-Radiohead-OK-Computer'},
          {'url': 'https://musicbrainz.org/release-group/b1392450-e666-3926-a536-22c65f834433'},
        ],
      }),
    )!;
    expect(voices.itemUrl, 'https://neodb.social/album/u1');
    expect(voices.distribution, [1, 2, 7, 30, 60]);
    expect(voices.doubanUrl, 'https://music.douban.com/subject/1/');
    expect(voices.discogsUrl, contains('/master/'));
    expect(NeoDbVoicesParsing.discogsId(voices.discogsUrl!), ('master', 21491));
    expect(NeoDbVoicesParsing.musicBrainzId(voices.musicBrainzUrl!), (
      'release-group',
      'b1392450-e666-3926-a536-22c65f834433',
    ));
  });

  test('should_dropDistribution_when_notFiveBuckets', () {
    final voices = NeoDbVoicesParsing.albumVoices(
      'https://neodb.social',
      const CatalogItem(uuid: 'u', title: 't', ratingDistribution: [1, 2]),
    )!;
    expect(voices.distribution, isEmpty);
    expect(voices.itemUrl, 'https://neodb.social/album/u');
  });

  test('should_buildSearchLinks_when_albumAndArtistGiven', () {
    expect(NeoDbVoicesParsing.query(' Blue ', ' '), 'Blue');
    expect(
      NeoDbVoicesParsing.rymSearchUrl('OK Computer', 'Radiohead'),
      'https://rateyourmusic.com/search?searchtype=l&searchterm=OK+Computer+Radiohead',
    );
    expect(NeoDbVoicesParsing.aotySearchUrl('Blue', null), 'https://www.albumoftheyear.org/search/albums/?q=Blue');
    expect(
      NeoDbVoicesParsing.neoDbSearchUrl('https://x.org/', 'Blue', 'Joni'),
      'https://x.org/search?c=music&q=Blue+Joni',
    );
  });

  test('should_openOnlyWebLinks_when_askedToOpen', () {
    expect(NeoDbVoicesParsing.isWebLink('https://neodb.social'), isTrue);
    expect(NeoDbVoicesParsing.isWebLink('HTTP://a.b'), isTrue);
    expect(NeoDbVoicesParsing.isWebLink('intent://x'), isFalse);
    expect(NeoDbVoicesParsing.isWebLink('javascript:alert(1)'), isFalse);
  });
}
