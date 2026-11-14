/// Yoin's voice on a Memory and the card's excerpt (Android ui/memories/copy
/// MemoryVoice, MemoryExcerpt), ported from the approved prototype
/// (docs/handoff/memories-showcase/twostate4.html: `writesIn`, `signals`,
/// `narrative` ①–④, `excerptCands`).
///
/// The rule for every line Yoin writes on a memory: second person, past
/// tense, one or two sentences, built only from local signals. It never
/// judges the music, never quotes the review or the notes, never names the
/// mechanism. The question follows from the fact before it. The templates
/// are Android's own prose, word for word.
library;

import 'package:flutter/foundation.dart';

import 'memory_copy.dart';

/// The language Yoin's own prose is written in.
enum MemoryProseLanguage { en, zh }

/// Where the card's title came from. The serif belongs to titles someone
/// wrote: Yoin's [ai] title and the [user]'s own. Without either, the
/// album's name stands in ([album]).
enum MemoryTitleKind { ai, album, user }

/// A fact Yoin has already said on this card.
enum MemoryFact { plays, range, coverage, notes, top, score }

@immutable
final class MemoryCopyTrack {
  const MemoryCopyTrack({required this.number, required this.title, this.rating});
  final int number;
  final String title;

  /// Null when unrated.
  final double? rating;
}

/// A note the user wrote: on a song ([track] set) or on the album.
@immutable
final class MemoryCopyNote {
  const MemoryCopyNote({required this.text, required this.writtenOn, this.track, this.positionMs});
  final String text;
  final MemoryDay writtenOn;
  final MemoryCopyTrack? track;
  final int? positionMs;
}

/// Play history in Yoin only (never visits).
@immutable
final class MemoryListening {
  const MemoryListening({required this.plays, required this.firstHeard, required this.lastHeard});
  final int plays;
  final MemoryDay firstHeard;
  final MemoryDay lastHeard;
}

/// Everything Yoin's copy may look at for one album memory.
@immutable
final class MemoryCopyInput {
  const MemoryCopyInput({
    required this.albumName,
    this.aiTitle,
    this.review,
    this.reviewWrittenOn,
    this.notes = const [],
    this.tracks = const [],
    this.ratedTracks = 0,
    this.totalTracks = 0,
    this.albumScore,
    this.listening,
  });

  final String albumName;
  final String? aiTitle;
  final String? review;
  final MemoryDay? reviewWrittenOn;

  /// Oldest first.
  final List<MemoryCopyNote> notes;

  /// Album order.
  final List<MemoryCopyTrack> tracks;
  final int ratedTracks;
  final int totalTracks;

  /// The user's album rating; null unless the card shows an album score.
  final double? albumScore;
  final MemoryListening? listening;
}

/// Yoin's words on one card; [narration] / [question] only without a review.
@immutable
final class MemoryVoiceCopy {
  const MemoryVoiceCopy({
    required this.language,
    required this.titleKind,
    required this.title,
    this.narration,
    this.question,
    this.said = const {},
  });
  final MemoryProseLanguage language;
  final MemoryTitleKind titleKind;
  final String title;
  final String? narration;
  final String? question;
  final Set<MemoryFact> said;
}

@immutable
final class _Signals {
  const _Signals({
    required this.notes,
    required this.coverage,
    required this.noteDays,
    required this.latest,
    required this.seasons,
    required this.top,
    required this.agoDays,
  });
  final int notes;
  final double coverage;
  final int? noteDays;
  final MemoryCopyNote? latest;
  final int seasons;
  final MemoryCopyTrack? top;
  final int? agoDays;
}

final class _Told {
  const _Told(this.narration, this.question, this.uses);
  final String? narration;
  final String question;
  final Set<MemoryFact> uses;
}

abstract final class MemoryVoice {
  static const _zhNum = ['零', '一', '两', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二'];
  static const _enNum = [
    'zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', //
  ];

  /// Which script the user writes in. One Han character carries about as
  /// much as two latin letters. Any kana or hangul means Japanese or Korean,
  /// which have no templates: the app language. Nothing written: the app
  /// language.
  static MemoryProseLanguage writesIn(
    String? review,
    List<String> noteTexts, {
    MemoryProseLanguage appLanguage = MemoryProseLanguage.en,
  }) {
    var han = 0;
    var latin = 0;
    for (final text in [review ?? '', ...noteTexts]) {
      for (final c in text.codeUnits) {
        if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF)) return appLanguage;
        if (c >= 0x3400 && c <= 0x9FFF) {
          han++;
        } else if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A)) {
          latin++;
        }
      }
    }
    if (han == 0 && latin == 0) return appLanguage;
    return han * 2 >= latin ? MemoryProseLanguage.zh : MemoryProseLanguage.en;
  }

  /// Yoin's title, narration and question, in the language the user writes in.
  static MemoryVoiceCopy compose(
    MemoryCopyInput input,
    MemoryDay today, {
    MemoryProseLanguage appLanguage = MemoryProseLanguage.en,
  }) =>
      composeIn(input, today, writesIn(input.review, [for (final n in input.notes) n.text], appLanguage: appLanguage));

  static MemoryVoiceCopy composeIn(MemoryCopyInput input, MemoryDay today, MemoryProseLanguage language) {
    final aiTitle = (input.aiTitle?.trim().isEmpty ?? true) ? null : input.aiTitle;
    String? narration;
    String? question;
    var said = <MemoryFact>{};
    if (input.review == null || input.review!.trim().isEmpty) {
      final told = _narrative(input, _signals(input, today), language, today);
      narration = told.narration;
      question = told.question;
      said = told.uses;
    }
    return MemoryVoiceCopy(
      language: language,
      titleKind: aiTitle != null ? MemoryTitleKind.ai : MemoryTitleKind.album,
      title: aiTitle ?? input.albumName,
      narration: narration,
      question: question,
      said: said,
    );
  }

  /// A small count in words: 两 for two in Chinese, "two" in English;
  /// numerals past twelve.
  static String num(int n, MemoryProseLanguage language, {bool capitalize = false}) {
    if (language == MemoryProseLanguage.zh) return n >= 0 && n < _zhNum.length ? _zhNum[n] : '$n';
    final word = n >= 0 && n < _enNum.length ? _enNum[n] : '$n';
    return capitalize && word.isNotEmpty ? word[0].toUpperCase() + word.substring(1) : word;
  }

  /// Narration then question as one paragraph: Chinese joins without a space.
  static String? paragraph(String? narration, String? question, MemoryProseLanguage language) {
    final parts = [
      if (narration != null && narration.trim().isNotEmpty) narration,
      if (question != null && question.trim().isNotEmpty) question,
    ];
    if (parts.isEmpty) return null;
    return parts.join(language == MemoryProseLanguage.zh ? '' : ' ');
  }

  static _Signals _signals(MemoryCopyInput input, MemoryDay today) {
    final dated = [...input.notes];
    _stableSortBy(dated, (n) => n.writtenOn.millisecondsSinceEpoch);
    final listening = input.listening;
    MemoryCopyTrack? top;
    for (final t in input.tracks) {
      if (t.rating == null) continue;
      if (top == null || t.rating! > top.rating!) top = t;
    }
    return _Signals(
      notes: input.notes.length,
      coverage: input.totalTracks > 0 ? input.ratedTracks / input.totalTracks : 0,
      noteDays: dated.isEmpty ? null : MemoryDates.daysBetween(dated.first.writtenOn, dated.last.writtenOn) + 1,
      latest: dated.isEmpty ? null : dated.last,
      seasons: listening == null
          ? 0
          : (MemoryDates.daysBetween(listening.firstHeard, listening.lastHeard) / 91.0 + 0.5).floor(),
      top: top,
      agoDays: listening == null ? null : MemoryDates.daysBetween(listening.lastHeard, today),
    );
  }

  /// "February" / "二月"; a first play in another year carries it.
  static String _since(MemoryListening listening, MemoryDay today, MemoryProseLanguage language) {
    final first = listening.firstHeard;
    final otherYear = first.year != today.year;
    final zh = language == MemoryProseLanguage.zh;
    final month = MemoryDates.monthName(first.month, zh: zh);
    return zh ? '${otherYear ? '${first.year}年' : ''}$month' : '$month${otherYear ? ' ${first.year}' : ''}';
  }

  /// "two days ago" / "前天"; a week or more back, the date itself.
  static String _ago(MemoryListening listening, _Signals signals, MemoryDay today, MemoryProseLanguage language) {
    final n = signals.agoDays ?? MemoryDates.daysBetween(listening.lastHeard, today);
    if (language == MemoryProseLanguage.zh) {
      if (n <= 0) return '今天';
      if (n == 1) return '昨天';
      if (n == 2) return '前天';
      if (n < 7) return '${_zhNum[n]}天前';
      return MemoryDates.dayZh(listening.lastHeard, today);
    }
    if (n <= 0) return 'today';
    if (n == 1) return 'yesterday';
    if (n < 7) return '${_enNum[n]} days ago';
    return 'on ${MemoryDates.day(listening.lastHeard, today)}';
  }

  static _Told _narrative(MemoryCopyInput input, _Signals signals, MemoryProseLanguage language, MemoryDay today) {
    final zh = language == MemoryProseLanguage.zh;
    final listening = input.listening;
    final noteDays = signals.noteDays;
    // ① notes, nothing rated
    if (signals.notes >= 2 && noteDays != null && input.ratedTracks == 0 && input.albumScore == null) {
      final track = signals.latest?.track?.title;
      final narration = zh
          ? '${num(noteDays, language)}天里记了${num(signals.notes, language)}条笔记，'
                '${track != null ? '最近一条写在《$track》。' : '最近一条写给整张专辑。'}'
          : '${num(signals.notes, language, capitalize: true)} notes in ${num(noteDays, language)} '
                'day${noteDays > 1 ? 's' : ''}; '
                '${track != null ? 'the latest was on $track.' : 'the latest was about the whole album.'}';
      return _Told(narration, zh ? '合起来看，这张专辑你会怎么说？' : 'Put together, what would you say about the album?', {
        MemoryFact.notes,
      });
    }
    // ② rated track by track
    final top = signals.top;
    if (signals.coverage >= 0.6 && top != null) {
      final topScore = MemoryScores.text(top.rating ?? 0);
      final topLine = zh
          ? '最高分是《${top.title}》的 $topScore。'
          : '${top.title} scored highest, ${_article(topScore)} $topScore.';
      final question = zh ? '那整张专辑呢？' : 'And the album as a whole?';
      if (signals.seasons >= 2) {
        return _Told(
          zh
              ? '你跨了${num(signals.seasons, language)}个季节回来听，$topLine'
              : 'You came back across ${num(signals.seasons, language)} seasons. $topLine',
          question,
          {MemoryFact.range, MemoryFact.top},
        );
      }
      if (listening != null) {
        return _Told(
          zh
              ? '听了 ${listening.plays} 遍，$topLine'
              : '${_playsEn(listening.plays)} in, ${top.title} is still your top track at $topScore.',
          question,
          {MemoryFact.plays, MemoryFact.top},
        );
      }
      return _Told(topLine, question, {MemoryFact.top});
    }
    // ③ an album score, no words yet
    final albumScore = input.albumScore;
    if (albumScore != null) {
      final scoreText = MemoryScores.text(albumScore);
      final question = zh
          ? '你给了 $scoreText 分，是哪里打动了你？'
          : 'You gave it ${_article(scoreText)} $scoreText. What earned it?';
      if (listening == null) return _Told(null, question, {MemoryFact.score});
      final ago = _ago(listening, signals, today, language);
      final since = _since(listening, today, language);
      return _Told(
        zh
            ? '$since以来听了 ${listening.plays} 遍，最近一次是$ago。'
            : '${_playsEn(listening.plays)} since $since, the last one $ago.',
        question,
        {MemoryFact.plays, MemoryFact.range, MemoryFact.score},
      );
    }
    // ④ fallback
    return _Told(null, zh ? '这张专辑，留下了什么？' : 'What stays with you from this one?', const {});
  }

  // English takes "an" before eight.
  static String _article(String scoreText) => scoreText.startsWith('8') ? 'an' : 'a';

  static String _playsEn(int plays) => plays == 1 ? '1 play' : '$plays plays';
}

/// Type size tier of a card excerpt, by UTF-16 length.
enum MemoryExcerptSize { short, medium, long }

/// Which voice the card is quoting.
enum MemoryExcerptKind { review, note }

/// The signature under a quote: a small label ([kind]) and an optional
/// detail (a date, or a song title and anchor). There is no "in Diary".
@immutable
final class MemoryExcerptAttribution {
  const MemoryExcerptAttribution({required this.kind, this.detail});
  final MemoryExcerptKind kind;
  final String? detail;

  @override
  bool operator ==(Object other) => other is MemoryExcerptAttribution && other.kind == kind && other.detail == detail;

  @override
  int get hashCode => Object.hash(kind, detail);
}

/// One candidate for the card's excerpt slot: the card shows the first that
/// fits; [text] null is the last resort, the attribution alone.
@immutable
final class MemoryExcerptCandidate {
  const MemoryExcerptCandidate(this.text, this.attribution);
  final String? text;
  final MemoryExcerptAttribution attribution;

  bool get attributionOnly => text == null;

  MemoryExcerptSize get size {
    final t = text;
    if (t == null || t.length > 60) return MemoryExcerptSize.long;
    return t.length <= 16 ? MemoryExcerptSize.short : MemoryExcerptSize.medium;
  }

  @override
  bool operator ==(Object other) =>
      other is MemoryExcerptCandidate && other.text == text && other.attribution == attribution;

  @override
  int get hashCode => Object.hash(text, attribution);
}

/// Card excerpt candidates, best first: whole sentences from the start of
/// the review's first paragraph; with no review, the first song note in
/// album order; then the shortest song note; then the attribution alone. A
/// Medium window caps the card as a teaser: at most two sentences and about
/// 60 weighted characters, the first sentence alone always allowed.
abstract final class MemoryExcerpt {
  static const mediumTeaserMaxSentences = 2;
  static const mediumTeaserMaxWeight = 60.0;

  static List<MemoryExcerptCandidate> candidates(
    MemoryCopyInput input, {
    required bool capped,
    required MemoryDay today,
    bool zhDates = false,
  }) {
    final songNotes = [
      for (final n in input.notes)
        if (n.track != null) n,
    ];
    final out = <MemoryExcerptCandidate>[];
    final MemoryExcerptAttribution lastAttribution;
    final review = (input.review?.trim().isEmpty ?? true) ? null : input.review!;
    if (review != null) {
      lastAttribution = reviewAttribution(input.reviewWrittenOn, today, zh: zhDates);
      final firstParagraph = review.split('\n').first;
      final sentences = MemorySentences.split(firstParagraph);
      final most = capped
          ? (sentences.length < mediumTeaserMaxSentences ? sentences.length : mediumTeaserMaxSentences)
          : sentences.length;
      for (var k = most; k >= 1; k--) {
        final text = MemorySentences.join(sentences.take(k).toList());
        if (!capped || k == 1 || MemorySentences.weightedLength(text) <= mediumTeaserMaxWeight) {
          out.add(MemoryExcerptCandidate(text, lastAttribution));
        }
      }
    } else if (songNotes.isNotEmpty) {
      final first = albumOrder(input.notes).firstWhere((n) => n.track != null);
      lastAttribution = noteAttribution(first, today, zh: zhDates);
      out.add(MemoryExcerptCandidate(first.text, lastAttribution));
    } else {
      return const [];
    }
    MemoryCopyNote? shortest;
    for (final n in songNotes) {
      if (shortest == null || n.text.length < shortest.text.length) shortest = n;
    }
    if (shortest != null) out.add(MemoryExcerptCandidate(shortest.text, noteAttribution(shortest, today, zh: zhDates)));
    out.add(MemoryExcerptCandidate(null, lastAttribution));
    return out;
  }

  /// Album notes first, then song notes by track number; within a song,
  /// anchored notes by position and unanchored ones after them.
  static List<MemoryCopyNote> albumOrder(List<MemoryCopyNote> notes) {
    final out = [...notes];
    int key(MemoryCopyNote n) => n.track?.number ?? -1;
    int pos(MemoryCopyNote n) => n.positionMs ?? (1 << 62);
    // Stable: ties keep input order.
    for (var i = 1; i < out.length; i++) {
      final item = out[i];
      var j = i - 1;
      while (j >= 0 && (key(out[j]) > key(item) || (key(out[j]) == key(item) && pos(out[j]) > pos(item)))) {
        out[j + 1] = out[j];
        j--;
      }
      out[j + 1] = item;
    }
    return out;
  }

  static MemoryExcerptAttribution reviewAttribution(MemoryDay? writtenOn, MemoryDay today, {bool zh = false}) =>
      MemoryExcerptAttribution(
        kind: MemoryExcerptKind.review,
        detail: writtenOn == null
            ? null
            : (zh ? MemoryDates.dayZh(writtenOn, today) : MemoryDates.day(writtenOn, today)),
      );

  /// A song note's detail is the title plus its anchor ("Satellite Hearts
  /// 2:21"); an album note's is the date. An unanchored song note carries no time.
  static MemoryExcerptAttribution noteAttribution(MemoryCopyNote note, MemoryDay today, {bool zh = false}) {
    final track = note.track;
    final detail = track == null
        ? (zh ? MemoryDates.dayZh(note.writtenOn, today) : MemoryDates.day(note.writtenOn, today))
        : '${track.title}${note.positionMs == null ? '' : ' ${anchorTime(note.positionMs!)}'}';
    return MemoryExcerptAttribution(kind: MemoryExcerptKind.note, detail: detail);
  }

  /// m:ss of a note's anchor, whole seconds.
  static String anchorTime(int positionMs) {
    final seconds = positionMs < 0 ? 0 : positionMs ~/ 1000;
    return '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';
  }
}

void _stableSortBy<T>(List<T> list, int Function(T) key) {
  for (var i = 1; i < list.length; i++) {
    final item = list[i];
    var j = i - 1;
    while (j >= 0 && key(list[j]) > key(item)) {
      list[j + 1] = list[j];
      j--;
    }
    list[j + 1] = item;
  }
}
