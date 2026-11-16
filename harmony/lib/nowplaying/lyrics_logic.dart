import 'package:flutter/foundation.dart';

import '../data/model/model.dart';

/// One line as Now Playing shows it; [startMs] is null for unsynced text.
@immutable
final class LyricRow {
  const LyricRow(this.text, {this.startMs, this.translation});

  final String text;
  final int? startMs;

  /// The line's translation (Android LyricLine.translation): the lyrics
  /// source's own, or Gemini's; null until the Translate key finds one.
  final String? translation;

  @override
  bool operator ==(Object other) =>
      other is LyricRow && other.text == text && other.startMs == startMs && other.translation == translation;

  @override
  int get hashCode => Object.hash(text, startMs, translation);
}

/// A song's lyrics, ready for the window and the full list.
@immutable
final class LyricSheet {
  const LyricSheet(this.rows);

  static const empty = LyricSheet([]);

  final List<LyricRow> rows;

  bool get isEmpty => rows.isEmpty;

  /// Any line has a timestamp (synced lyrics never scroll by hand in the window).
  bool get isSynced => rows.any((r) => r.startMs != null);

  @override
  bool operator ==(Object other) => other is LyricSheet && listEquals(other.rows, rows);

  @override
  int get hashCode => Object.hashAll(rows);
}

/// Source lyrics → rows, credits stripped (SubsonicMappers toLyrics +
/// LyricCreditsExtractor): synced lines sorted by start, unsynced text
/// split on newlines into untimed rows.
LyricSheet lyricSheetOf(Lyrics? lyrics) {
  switch (lyrics) {
    case null:
      return LyricSheet.empty;
    case SyncedLyrics(:final lines):
      final sorted = [...lines]..sort((a, b) => a.startMs.compareTo(b.startMs));
      final removed = LyricCredits.scan([for (final l in sorted) l.text]);
      final kept = [
        for (var i = 0; i < sorted.length; i++)
          if (!removed.contains(i)) LyricRow(sorted[i].text, startMs: sorted[i].startMs),
      ];
      if (kept.every((r) => r.text.trim().isEmpty)) return LyricSheet.empty;
      return LyricSheet(kept);
    case UnsyncedLyrics(:final text):
      final all = text.split('\n');
      final textIndices = [
        for (var i = 0; i < all.length; i++)
          if (all[i].trim().isNotEmpty) i,
      ];
      final removedText = LyricCredits.scan([for (final i in textIndices) all[i].trim()]);
      final removed = {for (final i in removedText) textIndices[i]};
      final rows = [
        for (var i = 0; i < all.length; i++)
          if (!removed.contains(i) && all[i].trim().isNotEmpty) LyricRow(all[i].trim()),
      ];
      return LyricSheet(rows);
  }
}

/// The active row: the last timed row with start ≤ [positionMs] (rows are
/// ascending; the scan stops at the first later start; untimed rows are
/// skipped). -1 when none has started.
int activeLyricIndex(List<LyricRow> rows, int positionMs) {
  var active = -1;
  for (var i = 0; i < rows.length; i++) {
    final start = rows[i].startMs;
    if (start == null) continue;
    if (start > positionMs) break;
    active = i;
  }
  return active;
}

/// Takes production credits out of lyrics (Android data/lyrics/LyricCreditsExtractor.kt).
/// Only the head and tail blocks are scanned, each stopping at the first
/// non-credit line; a block counts with a headline role (作词 / 作曲 / 编曲 /
/// 制作人), three or more roles, or (tail) when the head counted; nothing is
/// taken if fewer than two lines would remain.
abstract final class LyricCredits {
  static const int _minLinesLeft = 2;
  static const int _minRoles = 3;
  static const int _maxCjkRole = 12;
  static const int _maxLatinWords = 7;
  static const int _maxTitleLine = 160;

  /// Indices of [texts] to drop (empty = keep everything).
  static Set<int> scan(List<String> texts) {
    if (texts.isEmpty) return const {};
    final kinds = texts.map(_classify).toList();
    for (var i = 0; i < kinds.length; i++) {
      if (kinds[i] is _Continuation) {
        final above = i > 0 ? kinds[i - 1] : null;
        if (above is! _Credit && above is! _Continuation) kinds[i] = const _Lyric();
      }
    }
    final removed = <int>{};
    final head = _block(kinds, [for (var i = 0; i < texts.length; i++) i], head: true);
    final headOk = head.isNotEmpty && _counts(head, kinds, extra: false);
    if (headOk) removed.addAll(head);
    final tail = _block(kinds, [
      for (var i = texts.length - 1; i >= 0; i--) i,
    ], head: false).where((i) => !removed.contains(i)).toList();
    if (tail.isNotEmpty && _counts(tail, kinds, extra: headOk)) removed.addAll(tail);
    if (headOk) removed.addAll(_middleRuns(kinds, removed));
    if (removed.isEmpty || texts.length - removed.length < _minLinesLeft) return const {};
    return removed;
  }

  static List<int> _block(List<_Kind> kinds, List<int> order, {required bool head}) {
    final hit = <int>[];
    for (final i in order) {
      final kind = kinds[i];
      if (kind is _Lyric || (kind is _TitleLine && !head)) break;
      hit.add(i);
    }
    return hit;
  }

  static List<int> _middleRuns(List<_Kind> kinds, Set<int> removed) {
    final taken = <int>[];
    var run = <int>[];
    void close() {
      final roles = run.where((i) {
        final k = kinds[i];
        return k is _Credit && k.names.isNotEmpty;
      }).length;
      if (roles >= _minRoles) taken.addAll(run);
      run = [];
    }

    for (var i = 0; i < kinds.length; i++) {
      final kind = kinds[i];
      final creditLine =
          kind is _Credit || kind is _Notice || kind is _Continuation || (kind is _Junk && run.isNotEmpty);
      if (!removed.contains(i) && creditLine) {
        run.add(i);
      } else {
        close();
      }
    }
    close();
    return taken;
  }

  static bool _counts(List<int> run, List<_Kind> kinds, {required bool extra}) {
    final credits = run.map((i) => kinds[i]).whereType<_Credit>().where((c) => c.names.isNotEmpty).toList();
    return extra || credits.any((c) => c.headline) || credits.length >= _minRoles;
  }

  static _Kind _classify(String raw) {
    final text = raw.trim();
    final kind = _classifyLine(text);
    if (kind is _Lyric && raw.length <= _maxTitleLine && _titleLine.hasMatch(text)) return const _TitleLine();
    return kind;
  }

  static _Kind _classifyLine(String text) {
    if (text.isEmpty || _junk.hasMatch(text)) return const _Junk();
    if (_code.hasMatch(text)) return _Credit(text, headline: false);
    if (_continuation.hasMatch(text) && (text.length > 20 || text.contains('/'))) return const _Continuation();

    final match = _credit.firstMatch(text);
    if (match == null) {
      final dash = _dashCredit.firstMatch(text);
      if (dash != null) {
        final key = dash.group(1)!.trim();
        if (!_hasCjk.hasMatch(key) && _isLatinRole(key)) {
          return _Credit(dash.group(2)!.trim(), headline: _isHeadline(key));
        }
      }
      if (_prose.hasMatch(text)) return _Credit(text, headline: false);
      if (_notice.hasMatch(text)) return const _Notice();
      return const _Lyric();
    }
    final written = match.group(1)!.trim();
    final names = match.group(2)!.trim();
    if (names.isNotEmpty && _sentenceEnd.hasMatch(names)) return const _Lyric();

    final bilingual = _bilingualKey.firstMatch(written);
    final key = bilingual?.group(1)?.trim() ?? written;
    final english = bilingual?.group(2)?.trim();

    if (_isHeadline(key) || (english != null && _isHeadline(english))) return _Credit(names, headline: true);
    if (_noticeRole.hasMatch(key)) return const _Notice();
    final isRole = _hasCjk.hasMatch(key)
        ? (key.length <= _maxCjkRole && _cjkRole.hasMatch(key)) || (english != null && _isLatinRole(english))
        : _isLatinRole(key);
    return isRole ? _Credit(names, headline: false) : const _Lyric();
  }

  static bool _isHeadline(String key) => _headlines.any((p) => p.hasMatch(key));

  static bool _isLatinRole(String key) {
    final words = key.toLowerCase().split(_nonWord).where((w) => w.isNotEmpty).toList();
    return words.isNotEmpty && words.length <= _maxLatinWords && words.any(_latinRoles.contains);
  }

  static final _credit = RegExp(r'^([^:：]{1,40}?)\s*[:：]\s*(.*)$');
  static final _dashCredit = RegExp(r'^([^-–]{1,40}?)\s+[-–]\s+(.+)$');
  static final _titleLine = RegExp(r'^.+\s[-–]\s.+$');
  static final _continuation = RegExp(r'^[(（].*[)）]$');
  static final _junk = RegExp(r'^[\s\-–—_~·•*.。,，♪]+$');
  static final _code = RegExp(r'^(isrc|upc|ean)\b', caseSensitive: false);
  static final _prose = RegExp(
    r'^(recorded|mixed|mastered|published|produced|engineered|written|performed|edited|'
    r'courtesy|licensed|distributed|administered|arranged|programmed)\b.*\b(at|by|of)\b',
    caseSensitive: false,
  );
  static final _notice = RegExp(r'(版权|版權|著作权|著作權|未经|未經|许可|許可|℗|©|copyright|all rights reserved)', caseSensitive: false);
  static final _noticeRole = RegExp(r'(版权|版權|出品|发行|發行|监制|監製|著作)');
  static final _sentenceEnd = RegExp(r'[。！？]$');
  static final _hasCjk = RegExp(r'[぀-ヿ㐀-鿿가-힯]');
  static final _bilingualKey = RegExp(r"^(.*[぀-ヿ㐀-鿿가-힯])\s*([A-Za-z][A-Za-z0-9 &.'/()-]*)$");
  static final _nonWord = RegExp(r'[^a-z&]+');

  static final _headlines = [
    RegExp(r'^(作词|作詞|词|詞|填词|填詞|lyricist|lyricists|lyrics|lyrics by|written by|words by|작사)$', caseSensitive: false),
    RegExp(r'^(作曲|曲|composer|composers|composed by|music by|작곡)$', caseSensitive: false),
    RegExp(r'^(编曲|編曲|arranger|arrangers|arranged by|arrangement|편곡)$', caseSensitive: false),
    RegExp(r'^(制作人|製作人|监制|監製|producer|producers|produced by|프로듀서|プロデューサー)$', caseSensitive: false),
  ];

  static final _cjkRole = RegExp(
    '(词|詞|曲|制作|製作|琴|筝|箏|琵琶|阮|笙|埙|吉他|贝斯|貝斯|鼓|箫|笛|胡|唢呐|键盘|鍵盤|弦乐|弦樂|和声|和聲|和音|合声|合唱|人声|人聲|配唱|'
    '采样|录音|錄音|混音|缩混|母带|母帶|工程|助理|编写|編寫|编程|演奏|乐队|樂隊|乐手|乐团|樂團|总监|監製|监制|'
    '统筹|統籌|企划|企劃|策划|协力|協力|出品|发行|發行|版权|版權|宣传|推广|视觉|封面|摄影|设计|後期|后期|谱|譜|贡献者|'
    '调声|調聲|调教|調教|调校|翻译|翻譯|经纪|經紀|编辑|編輯|剪辑|剪輯|绘|繪|美术|美術|动画|動畫|'
    'ギター|ベース|ドラム|ピアノ|コーラス|レコーディング|ミックス|マスタリング|プログラミング|'
    '믹싱|마스터링|녹음|기타|베이스|드럼|피아노|코러스|디렉팅|프로그래밍)',
  );

  static final Set<String> _latinRoles =
      ('by guitar guitars bass drums drum percussion keyboard keyboards keys synth synths synthesizer piano organ '
              'strings string violin viola cello horn horns trumpet sax saxophone flute choir harmony hormony vocal '
              'vocals background programming programing programmer engineer engineers engineering mixer mix mixing '
              'mixed master mastering mastered recording recorded studio studios personnel producer producers '
              'production produced arrange arranger arranged arrangement composer composed lyrics lyricist written '
              'directed director assistant coordinator coordination a&r timpani gong sample samples additional '
              'executive op sp publisher publishers publishing label loop loops pads pad programmed lead backing '
              'electric acoustic talkbox tenor alto baritone editing editor edited digital assisted engineered '
              'original writer writers sub musicians credits arragement arrangements orchestra orchestration '
              'conductor conducted brass trombone woodwinds clarinet harp banjo mandolin ukulele turntables '
              'scratches beats beat tuning tuner translator translation manager management supervisor illustration '
              'illustrator artwork animation animator video pv guzheng pipa erhu dizi xiao guqin suona')
          .split(' ')
          .toSet();
}

sealed class _Kind {
  const _Kind();
}

final class _Lyric extends _Kind {
  const _Lyric();
}

final class _Junk extends _Kind {
  const _Junk();
}

final class _TitleLine extends _Kind {
  const _TitleLine();
}

final class _Credit extends _Kind {
  const _Credit(this.names, {required this.headline});
  final String names;
  final bool headline;
}

final class _Notice extends _Kind {
  const _Notice();
}

final class _Continuation extends _Kind {
  const _Continuation();
}
