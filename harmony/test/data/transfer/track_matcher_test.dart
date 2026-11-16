import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/transfer/track_matcher.dart';

Track _track(String id, String title, String artist, int? durationSec, {String? album}) =>
    Track(id: MediaId('test', id), title: title, artist: artist, durationSec: durationSec, album: album);

void main() {
  test('should_match_when_titleArtistAndDurationAgree', () {
    final source = _track('s', 'Bohemian Rhapsody - Remastered 2011', 'Queen', 354);
    final candidate = _track('t', 'Bohemian Rhapsody', 'Queen', 355);
    final decision = TrackMatcher.choose(source, [candidate]);
    expect(decision, isA<MatchMatched>());
    expect((decision as MatchMatched).track, same(candidate));
    expect(decision.method, MatchMethod.search);
  });

  test('should_ignoreFeatureCredits_when_comparingTitlesAndArtists', () {
    final source = _track('s', 'Stay (with Justin Bieber)', 'The Kid LAROI, Justin Bieber', 141);
    final candidate = _track('t', 'STAY', 'The Kid LAROI & Justin Bieber', 142);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchMatched>());
  });

  test('should_preferTheClosestDuration_when_twoCandidatesScoreAlike', () {
    final source = _track('s', 'Lemon', 'Kenshi Yonezu', 255);
    final far = _track('far', 'Lemon', 'Kenshi Yonezu', 258);
    final near = _track('near', 'Lemon', 'Kenshi Yonezu', 255);
    final decision = TrackMatcher.choose(source, [far, near]) as MatchMatched;
    expect(decision.track.id.rawId, 'near');
  });

  test('should_beUnsure_when_durationIsBeyondTheTolerance', () {
    final source = _track('s', 'Hey Jude', 'The Beatles', 431);
    final candidate = _track('t', 'Hey Jude', 'The Beatles', 424);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchAmbiguous>());
  });

  test('should_rejectCandidate_when_durationIsFarOff', () {
    final source = _track('s', 'Hey Jude', 'The Beatles', 431);
    final candidate = _track('t', 'Hey Jude', 'The Beatles', 200);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchNotFound>());
  });

  test('should_notMatch_when_artistDiffers', () {
    final source = _track('s', 'Hallelujah', 'Jeff Buckley', 414);
    final cover = _track('t', 'Hallelujah', 'Pentatonix', 414);
    expect(TrackMatcher.choose(source, [cover]), isA<MatchNotFound>());
  });

  test('should_keepLiveAndRemixApart_when_titlesDifferOnlyByThem', () {
    final source = _track('s', 'Yellow', 'Coldplay', 266);
    final live = _track('t', 'Yellow - Live in Buenos Aires', 'Coldplay', 266);
    expect(TrackMatcher.choose(source, [live]), isNot(isA<MatchMatched>()));
  });

  test('should_matchCjkTitles_when_widthAndCaseDiffer', () {
    final source = _track('s', '夜に駆ける', 'YOASOBI', 261);
    final candidate = _track('t', '夜に駆ける', 'Yoasobi', 261);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchMatched>());
  });

  test('should_foldAccentsAndFullWidth_when_normalizing', () {
    expect(TrackMatcher.normalizeTitle('Café Ｂｌｕｅ'), 'cafe blue');
    expect(TrackMatcher.normalizeArtist('The Beyoncé'), 'beyonce');
    final source = _track('s', 'Déjà Vu', 'Beyoncé', 240);
    final candidate = _track('t', 'Deja Vu', 'Beyonce', 240);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchMatched>());
  });

  test('should_match_when_durationIsUnknownButTitleAndArtistAreExact', () {
    final source = _track('s', 'Creep', 'Radiohead', null);
    final candidate = _track('t', 'Creep', 'Radiohead', 238);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchMatched>());
  });

  test('should_returnNotFound_when_thereAreNoCandidates', () {
    expect(TrackMatcher.choose(_track('s', 'Creep', 'Radiohead', 238), const []), isA<MatchNotFound>());
  });

  test('should_preferTheSameAlbum_when_isrcNamesSeveralTracks', () {
    final source = _track('s', 'Entropy', 'Beach Bunny', 221, album: 'Emotional Creature');
    final compilation = _track('c', 'Entropy', 'Beach Bunny', 221, album: 'Indie Hits 2022');
    final original = _track('o', 'Entropy', 'Beach Bunny', 221, album: 'Emotional Creature');
    final decision = TrackMatcher.chooseByIsrc(source, [compilation, original]) as MatchMatched;
    expect(decision.track, same(original));
    expect(decision.method, MatchMethod.isrc);
  });

  test('should_skipIsrcCandidate_when_itsDurationIsFarOff', () {
    final source = _track('s', 'Entropy', 'Beach Bunny', 221);
    final otherEdit = _track('x', 'Entropy', 'Beach Bunny', 400);
    expect(TrackMatcher.chooseByIsrc(source, [otherEdit]), isA<MatchNotFound>());
  });

  test('should_searchBareTitleAndLeadArtist_when_buildingQuery', () {
    final source = _track('s', 'Peaches (feat. Daniel Caesar & Giveon)', 'Justin Bieber, Daniel Caesar', 198);
    expect(TrackMatcher.searchQuery(source), 'Peaches Justin Bieber');
    expect(TrackMatcher.titleQuery(source), 'Peaches');
  });

  test('should_askTheUser_when_theCandidateIsALiveTake', () {
    final source = _track('s', 'BTTB — Track 4', 'Big Thief', 60);
    final live = _track('t', 'BTTB — Track 4 (Live)', 'Big Thief', 60);
    final decision = TrackMatcher.choose(source, [live]);
    expect(decision, isA<MatchAmbiguous>());
    expect((decision as MatchAmbiguous).candidates, [live]);
  });

  test('should_match_when_bothSidesAreTheSameRemix', () {
    final source = _track('s', 'Midnight City - Eric Prydz Remix', 'M83', 300);
    final candidate = _track('t', 'Midnight City (Eric Prydz Remix)', 'M83', 301);
    expect(TrackMatcher.choose(source, [candidate]), isA<MatchMatched>());
  });

  test('should_measureLevenshtein_when_comparingStrings', () {
    expect(TrackMatcher.similarity('kitten', 'sitting'), closeTo(1 - 3 / 7, 1e-9));
    expect(TrackMatcher.similarity('', ''), 1);
  });
}
