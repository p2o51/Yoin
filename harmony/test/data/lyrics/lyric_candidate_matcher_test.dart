import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/lyrics/lyric_candidate_matcher.dart';

/// Android LyricCandidateMatcherTest, case for case: candidate lists are live
/// QQ / NetEase answers (2026-10-05) for the names Apple Music's Japanese
/// storefront reports.
void main() {
  final matcher = LyricCandidateMatcher(fold: (s) => s);
  String fakeFold(String value) => value.replaceAll('愛', '爱').replaceAll('楊', '杨').replaceAll('艷', '艳');

  LyricCandidate c(String title, List<String> artists, {String? album, int? seconds}) =>
      LyricCandidate(title: title, artists: artists, album: album, durationMs: seconds == null ? null : seconds * 1000);

  test('should_matchSimplifiedCandidate_when_requestIsTraditionalChinese', () {
    final folding = LyricCandidateMatcher(fold: fakeFold);
    final candidates = [
      c('雨爱', ['杨丞琳']),
      c('雨爱 (DJ 阿若版)', ['杨丞琳']),
      c('雨爱', ['张德伊玲']),
    ];
    expect(folding.pick(candidates, '雨愛', '楊丞琳'), 0);
  });

  test('should_preferPlainTitle_when_decoratedVersionComesFirst', () {
    expect(
      matcher.pick(
        [
          c('晴天 (Live)', ['周杰伦']),
          c('晴天', ['周杰伦']),
        ],
        '晴天',
        '周杰伦',
      ),
      1,
    );
  });

  test('should_acceptDecoratedTitle_when_noPlainVersionExists', () {
    expect(
      matcher.pick(
        [
          c('Detour (Explicit)', ['Kim Petras']),
        ],
        'Detour',
        'Kim Petras',
      ),
      0,
    );
  });

  test('should_matchArtist_when_latinWordsOverlapAcrossScripts', () {
    expect(
      matcher.pick(
        [
          c('party 4 u', ['Charli xcx']),
        ],
        'party 4 u',
        'チャーリーxcx',
      ),
      0,
    );
  });

  test('should_matchArtist_when_requestNamesSeveralArtists', () {
    final candidates = [
      c('In My Bag', ['Someone Else']),
      c('In My Bag (Explicit)', ['FLO', 'GloRilla']),
    ];
    expect(matcher.pick(candidates, 'In My Bag', 'FLO & GloRilla'), 1);
  });

  test('should_rejectAll_when_comparableArtistDiffers', () {
    final candidates = [
      c('Stupid Love', ['Jason Derulo']),
      c('Stupid Love', ['Dan + Shay']),
    ];
    expect(matcher.pick(candidates, 'Stupid Love', 'Lady Gaga'), isNull);
  });

  test('should_skipWrongArtistAtTop_when_rightArtistIsFurtherDown', () {
    final candidates = [
      c('Stupid Love', ['Jason Derulo']),
      c('stupid love', ['米卡']),
      c('Stupid Love', ['Lady Gaga']),
    ];
    expect(matcher.pick(candidates, 'Stupid Love', 'Lady Gaga'), 2);
  });

  test('should_acceptUnverifiableArtist_when_everyTitleMatchIsBySameArtist', () {
    final candidates = [
      c('my tears ricochet', ['Taylor Swift']),
      c('my tears ricochet (the long pond studio sessions)', ['Taylor Swift']),
      c('cardigan', ['Taylor Swift']),
    ];
    expect(matcher.pick(candidates, 'my tears ricochet', 'テイラー・スウィフト'), 0);
  });

  test('should_rejectUnverifiableArtist_when_titleMatchesSeveralArtists', () {
    final candidates = [
      c('Stupid Love', ['Jason Derulo']),
      c('stupid love', ['米卡']),
      c('Stupid Love', ['Lady Gaga']),
    ];
    expect(matcher.pick(candidates, 'Stupid Love', 'レディー・ガガ'), isNull);
  });

  test('should_acceptDominantPerformer_when_unverifiableArtistHasMostSameTitledCuts', () {
    final folding = LyricCandidateMatcher(fold: fakeFold);
    final candidates = [
      c('痛快的哀艳 (苏打绿版)', ['苏打绿']),
      c('痛快的哀艳 (Live)', ['苏诗丁']),
      c('痛快的哀艳 (Endless Story Live)', ['苏打绿']),
      c('痛快的哀艳', ['苏打绿']),
      c('痛快的哀艳 (二十年一刻Live)', ['苏打绿']),
      c('四季未了 (Live)', ['苏打绿']),
      c('痛快的哀艳 (Live)', ['苏打绿']),
      c('痛快的哀艳 (Remix)', ['金鸿煊']),
      c('痛快的哀艳', ['泪零Namida']),
      c('痛快的哀艳', ['比利凯']),
    ];
    expect(folding.pick(candidates, '痛快的哀艷', 'ソーダグリーン'), 3);
  });

  test('should_pickStudioCut_when_dominantPerformerAlsoHasTaylorsVersion', () {
    final candidates = [
      c('I Knew You Were Trouble', ['Taylor Swift']),
      c('I Knew You Were Trouble (Taylor’s Version)', ['Taylor Swift']),
      c('I Knew You Were Trouble', ['Party Hit Kings']),
      c('I Knew You Were Trouble (Live from the BRITs 2013)', ['Taylor Swift']),
      c('I Knew You Were Trouble (Fish Fugue Remix)', ['Fish Fugue', 'Taylor Swift']),
    ];
    expect(matcher.pick(candidates, 'I Knew You Were Trouble', 'テイラー・スウィフト'), 0);
  });

  test('should_returnNull_when_topRankedPerformerHasOnlyOneCut', () {
    final candidates = [
      c('What Was That', ['Lorde']),
      c('What Was That', ['Chartwave Collective']),
      c('What Was That (Live)', ['Chartwave Collective']),
    ];
    expect(matcher.pick(candidates, 'What Was That', 'ロード'), isNull);
  });

  test('should_notTreatKanaPrefix_as_sameArtist', () {
    expect(
      matcher.pick(
        [
          c('What Was That', ['ロードオブメジャー']),
        ],
        'What Was That',
        'ロード',
      ),
      isNull,
    );
  });

  test('should_notMatchShortLatinFragment_insideLongerName', () {
    final candidates = [
      c('After LIKE', ['Five Seconds']),
      c('After LIKE', ['IVE']),
    ];
    expect(matcher.pick(candidates, 'After LIKE', 'IVE'), 1);
  });

  test('should_returnNull_when_noTitleMatches', () {
    expect(
      matcher.pick(
        [
          c("Making A Life Outa Livin'(feat. Ms. Lex)", ['Murdoq']),
        ],
        'Making Out',
        'Lexie Liu',
      ),
      isNull,
    );
  });

  test('should_ignoreCaseWidthAndPunctuation_when_comparingTitles', () {
    expect(
      matcher.pick(
        [
          c('ＡＰＴ．', ['ROSÉ']),
        ],
        'APT.',
        'ROSÉ & Bruno Mars',
      ),
      0,
    );
  });

  test('should_skipInstrumentalCut_when_requestIsTheVocalVersion', () {
    final candidates = [
      c('晴天 (伴奏)', ['周杰伦']),
      c('Instant Crush (Inst.)', ['Daft Punk']),
      c('晴天 (Live)', ['周杰伦']),
    ];
    expect(matcher.pick(candidates, '晴天', '周杰伦'), 2);
    expect(matcher.pick(candidates, 'Instant Crush', 'Daft Punk'), isNull);
  });

  test('should_acceptInstrumentalCut_when_requestIsInstrumentalToo', () {
    expect(
      matcher.pick(
        [
          c('晴天 (伴奏)', ['周杰伦']),
        ],
        '晴天 (伴奏版)',
        '周杰伦',
      ),
      0,
    );
  });

  test('should_rankRemasterWithPlainTitle_when_bothNameTheStudioCut', () {
    final remasterFirst = [
      c('Hotel California (2013 Remaster)', ['Eagles']),
      c('Hotel California', ['Eagles']),
    ];
    final plainFirst = [
      c('Hotel California', ['Eagles']),
      c('Hotel California (1999 Remaster)', ['Eagles']),
    ];
    expect(matcher.pick(remasterFirst, 'Hotel California', 'Eagles'), 0);
    expect(matcher.pick(plainFirst, 'Hotel California', 'Eagles'), 0);
  });

  test('should_preferRemaster_when_liveCutRanksFirst', () {
    final candidates = [
      c('Hotel California (Live on MTV, 1994)', ['Eagles']),
      c('Hotel California (2013 Remaster)', ['Eagles']),
    ];
    expect(matcher.pick(candidates, 'Hotel California', 'Eagles'), 1);
  });

  test('should_preferRemaster_when_plainTitleComesFromUnpluggedAlbum', () {
    final candidates = [
      c('Hotel California', ['Eagles'], album: 'Unplugged 1994 - The Second Night'),
      c('Hotel California (2013 Remaster)', ['Eagles'], album: 'The Studio Albums 1972-1979 (2013 Remaster)'),
    ];
    expect(matcher.pick(candidates, 'Hotel California', 'Eagles'), 1);
  });

  test('should_pickStudioRemaster_when_netEaseListsLiveAndUnpluggedCuts', () {
    final candidates = [
      c('Hotel California (2013 Remaster)', ['Eagles'], album: 'The Studio Albums 1972-1979 (2013 Remaster)'),
      c('Hotel California (Live on MTV, 1994)', ['Eagles'], album: 'Hell Freezes Over'),
      c('Hotel California (1999 Remaster)', ['Eagles'], album: 'Selected Works 1972-1999'),
      c('Hotel California', ['Eagles'], album: 'Unplugged 1994 - The Second Night'),
      c('Hotel California (live)', ['Eagles'], album: 'Unplugged 1994 (live)'),
      c('Hotel California', ['Eagles'], album: 'The Summit, Houston, 1976 (Hd Remastered Edition)'),
    ];
    expect(matcher.pick(candidates, 'Hotel California', 'Eagles'), 0);
    expect(matcher.pick(candidates.skip(1).toList(), 'Hotel California', 'Eagles'), 1);
  });

  test('should_pickRemaster_when_netEaseListsAnInterviewAlbumEntry', () {
    final candidates = [
      c('Yesterday (Remastered)', ['The Beatles'], album: 'Help! (Remastered)'),
      c('Yesterday', ['The Beatles', 'Marty Murray'], album: 'Famous Interviews - The Beatles'),
      c('Yesterday (live)', ['The Beatles'], album: 'Live in Tokyo 1966'),
      c('Yesterday (Take 1 - Remastered)', ['The Beatles'], album: 'Anthology Highlights'),
      c('Yesterday (2023 Mix)', ['The Beatles'], album: 'The Beatles 1962 – 1966 (2023 Edition)'),
    ];
    expect(matcher.pick(candidates, 'Yesterday', 'The Beatles'), 0);
    expect(matcher.pick(candidates.take(2).toList().reversed.toList(), 'Yesterday', 'The Beatles'), 1);
  });

  test('should_rankInterviewAlbumBelowLiveCut_when_noStudioCutExists', () {
    final candidates = [
      c('Yesterday', ['The Beatles', 'Marty Murray'], album: 'Famous Interviews - The Beatles'),
      c('Yesterday (live)', ['The Beatles'], album: 'Live in Tokyo 1966'),
    ];
    expect(matcher.pick(candidates, 'Yesterday', 'The Beatles'), 1);
    expect(matcher.pick(candidates.take(1).toList(), 'Yesterday', 'The Beatles'), 0);
  });

  test('should_treatNoteAsOriginal_when_itOnlyNamesTheSameMaster', () {
    const sameMaster = [
      '(Remastered)',
      '(Remaster)',
      '(2013 Remaster)',
      '(Remastered 2009)',
      '- Remastered 2009',
      '- 2011 Remastered Version',
      '(Digitally Remastered)',
      '(40th Anniversary Remaster)',
      '(Hd Remastered Edition)',
      '(Mono)',
      '(Stereo)',
      '(Mono Version)',
      '(2009 Stereo Mix)',
      '(Mono / Remastered 2009)',
      '(Album Version)',
      '(LP Version)',
      '(Original Version)',
      '(Original Mix)',
      '(Explicit)',
      '[Explicit]',
      '(2019 リマスター)',
      '(アルバム・バージョン)',
    ];
    for (final note in sameMaster) {
      final candidates = [
        c('Placeholder Song (Live)', ['Band']),
        c('Placeholder Song $note', ['Band']),
      ];
      expect(matcher.pick(candidates, 'Placeholder Song', 'Band'), 1, reason: note);
    }
  });

  test('should_keepNoteDecorated_when_itNamesAnotherVersion', () {
    const otherVersions = [
      '(Live)',
      '(Unplugged)',
      '(Remix)',
      '(2023 Mix)',
      '(Single Version)',
      '(Single Edit)',
      '(Radio Edit)',
      '(Edit)',
      '(Clean)',
      '(Original)',
      '(Take 1 - Remastered)',
      '(Live / Remastered)',
      '(Taylor’s Version)',
      '(Acoustic)',
      '(Cover)',
      '(Sped Up)',
      '(Slowed)',
      '(Demo)',
      '(feat. Mono)',
    ];
    for (final note in otherVersions) {
      final candidates = [
        c('Placeholder Song $note', ['Band']),
        c('Placeholder Song', ['Band']),
      ];
      expect(matcher.pick(candidates, 'Placeholder Song', 'Band'), 1, reason: note);
    }
  });

  test('should_keepFeatClauseDecorated_when_featuredNameLooksLikeAMasterWord', () {
    final candidates = [
      c('Placeholder Song feat. Stereo', ['Band']),
      c('Placeholder Song', ['Band']),
    ];
    expect(matcher.pick(candidates, 'Placeholder Song', 'Band'), 1);
  });

  test('should_matchPlainCandidate_when_requestTitleCarriesRemaster', () {
    final candidates = [
      c('Yesterday (Live)', ['The Beatles']),
      c('Yesterday', ['The Beatles']),
    ];
    expect(matcher.pick(candidates, 'Yesterday - Remastered 2009', 'The Beatles'), 1);
    expect(matcher.pick(candidates, 'Yesterday (2015 Remaster)', 'The Beatles'), 1);
  });

  test('should_preferStudioAlbum_when_plainTitleComesFromConcertAlbum', () {
    final candidates = [
      c('晴天', ['周杰伦'], album: '周杰伦 2004 无与伦比 演唱会 Live CD'),
      c('晴天', ['周杰伦'], album: '叶惠美'),
    ];
    expect(matcher.pick(candidates, '晴天', '周杰伦'), 1);
  });

  test('should_notFlagStudioAlbum_when_liveIsJustAWordInItsName', () {
    final throughThis = [
      c('Placeholder Song', ['Band'], album: 'Live Through This'),
      c('Placeholder Song (Remastered)', ['Band'], album: 'Collection'),
    ];
    final mysteryTour = [
      c('Placeholder Song', ['Band'], album: 'Magical Mystery Tour'),
      c('Placeholder Song (Remastered 2009)', ['Band'], album: '1967-1970'),
    ];
    expect(matcher.pick(throughThis, 'Placeholder Song', 'Band'), 0);
    expect(matcher.pick(mysteryTour, 'Placeholder Song', 'Band'), 0);
  });

  test('should_flagLiveAlbum_when_liveOrTourIsFollowedByAPlaceOrYear', () {
    for (final album in ['Live in Tokyo 1966', 'After Hours (Live At SoFi Stadium)', 'idina: live', 'Tour 2019']) {
      final candidates = [
        c('Placeholder Song', ['Band'], album: album),
        c('Placeholder Song', ['Band'], album: 'Studio Album'),
      ];
      expect(matcher.pick(candidates, 'Placeholder Song', 'Band'), 1, reason: album);
    }
  });

  test('should_rankInstrumentalAlbumBelowLiveCut_when_titleLooksPlain', () {
    final candidates = [
      c('Placeholder Song', ['Band'], album: 'Placeholder Song (Instrumental)'),
      c('Placeholder Song (Live)', ['Band']),
    ];
    expect(matcher.pick(candidates, 'Placeholder Song', 'Band'), 1);
  });

  test('should_ignoreAlbum_when_requestItselfIsALiveCut', () {
    final candidates = [
      c('Hotel California (Live, 1994)', ['Eagles'], album: 'Hell Freezes Over'),
      c('Hotel California (Live)', ['Eagles'], album: 'Unplugged 1994 (live)'),
    ];
    expect(matcher.pick(candidates, 'Hotel California (Live)', 'Eagles'), 1);
  });

  test('should_preferSameLength_when_sameSongIsListedTwice', () {
    final candidates = [
      c('Pop Sound', ['Kim Petras'], seconds: 150),
      c('Pop Sound', ['Kim Petras'], seconds: 171),
    ];
    expect(matcher.pick(candidates, 'Pop Sound', 'Kim Petras', durationMs: 171172), 1);
    expect(matcher.pick(candidates, 'Pop Sound', 'Kim Petras'), 0, reason: 'no length: rank order');
  });

  test('should_keepRankOrder_when_candidateHasNoLength', () {
    final candidates = [
      c('Pop Sound', ['Kim Petras']),
      c('Pop Sound', ['Kim Petras'], seconds: 171),
    ];
    expect(matcher.pick(candidates, 'Pop Sound', 'Kim Petras', durationMs: 171172), 0);
  });

  test('should_returnNull_when_onlyLengthMatchesAnUnverifiableArtist', () {
    final candidates = [
      c('Pop Sound', ['Kim Petras'], seconds: 171),
      c('Pop Sound (Explicit)', ['Detour'], seconds: 169),
      c('Pop Sound', ['Losing N3mo'], seconds: 172),
      c('Pop Sound', ['gabe', 'JNTA'], seconds: 169),
    ];
    expect(matcher.pick(candidates, 'Pop Sound', 'キム・ペトラス', durationMs: 171172), isNull);
    expect(matcher.pick(candidates, 'Pop Sound', 'Kim Petras', durationMs: 171172), 0);
  });

  // Japanese Spotify's `摩天輪` / `ヒビ・ティエン` (244.8 s): nothing on QQ names Hebe Tien in a script that
  // can be compared, and Luo Baiji's 摩天轮 leads. Its length doesn't fit, so it mustn't stand in.
  test('should_returnNull_when_dominantUnverifiablePerformerIsOffLength', () {
    final candidates = [
      c('摩天轮', ['罗百吉'], seconds: 215),
      c('摩天轮 (DJ版)', ['罗百吉'], seconds: 230),
      c('摩天轮', ['某翻唱'], seconds: 245),
    ];
    expect(matcher.pick(candidates, '摩天轮', 'ヒビ・ティエン', durationMs: 244800), isNull);
    expect(matcher.pick(candidates, '摩天轮', 'ヒビ・ティエン'), 0);
  });

  test('should_keepDominantPerformersFittingCut_when_anotherOfTheirCutsIsOffLength', () {
    final candidates = [
      c('I Knew You Were Trouble', ['Taylor Swift'], seconds: 300),
      c('I Knew You Were Trouble', ['Taylor Swift'], seconds: 220),
      c('I Knew You Were Trouble', ['Party Hit Kings'], seconds: 219),
    ];
    expect(matcher.pick(candidates, 'I Knew You Were Trouble', 'テイラー・スウィフト', durationMs: 219000), 1);
  });

  test('should_notBacktrackForever_when_noteIsALongDigitRun', () {
    final candidates = [
      c('Placeholder Song (${'1' * 40}x)', ['Band']),
      c('Placeholder Song', ['Band']),
    ];
    final watch = Stopwatch()..start();
    expect(matcher.pick(candidates, 'Placeholder Song', 'Band'), 1);
    expect(watch.elapsedMilliseconds, lessThan(1000));
  });

  group('the default fold', () {
    test('should_foldTraditionalHan_when_tableHasIt', () {
      expect(traditionalToSimplified('雨愛'), '雨爱');
      expect(traditionalToSimplified('楊丞琳'), '杨丞琳');
      expect(traditionalToSimplified('痛快的哀艷'), '痛快的哀艳');
    });

    test('should_returnTextUnchanged_when_itHasNoHan', () {
      expect(traditionalToSimplified('テイラー・スウィフト'), 'テイラー・スウィフト');
      expect(traditionalToSimplified('ROSÉ'), 'ROSÉ');
    });

    test('should_matchTraditionalRequest_when_defaultFoldIsUsed', () {
      final candidates = [
        const LyricCandidate(title: '雨爱 (DJ 阿若版)', artists: ['杨丞琳']),
        const LyricCandidate(title: '雨爱', artists: ['杨丞琳']),
      ];
      expect(LyricCandidateMatcher().pick(candidates, '雨愛', '楊丞琳'), 1);
    });

    test('should_widenFullWidthAndHalfWidthKana_when_normalizing', () {
      expect(nfkcLite('ＡＰＴ．'), 'APT.');
      expect(nfkcLite('ｶﾞｷﾞ'), 'ガギ');
      expect(nfkcLite('ﬁne™'), 'fineTM');
    });
  });
}
