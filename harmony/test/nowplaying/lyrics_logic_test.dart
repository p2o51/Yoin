import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';

void main() {
  group('LyricCreditsExtractor', () {
    test('should_dropHeadAndTailCredits_when_neteaseStyleLrc', () {
      final texts = ['作词 : 唐恬', '作曲 : 钱雷', '编曲 : 钱雷', '都是勇敢的', '你额头的伤口', '她说：别走', '制作人 : 钱雷', '混音 : 赵靖'];
      expect(LyricCreditsExtractor.scan(texts)?.removed, {0, 1, 2, 6, 7});
    });

    test('should_keepSungLineWithColon_when_inTheMiddle', () {
      final texts = ['作词 : 唐恬', '第一句', '她说：别走', '最后一句'];
      expect(LyricCreditsExtractor.scan(texts)?.removed, {0});
    });

    test('should_takeNothing_when_noHeadlineRoleAndFewRoles', () {
      expect(LyricCreditsExtractor.scan(['Chorus: la la', 'line one', 'line two']), isNull);
    });

    test('should_takeNothing_when_lessThanTwoLinesWouldRemain', () {
      expect(LyricCreditsExtractor.scan(['作词 : 唐恬', '作曲 : 钱雷', '唯一一句']), isNull);
    });

    test('should_dropQqTitleLine_when_inHeadBlock', () {
      final texts = ['孤勇者 - 陈奕迅', '词：唐恬', '曲：钱雷', '都是勇敢的', '你额头的伤口'];
      expect(LyricCreditsExtractor.scan(texts)?.removed, {0, 1, 2});
    });

    test('should_recogniseEnglishRoles_when_threeOrMore', () {
      final texts = ['Bass Guitar: David', 'Drums: Ann', 'Mixed by: Bob', 'first line', 'second line'];
      expect(LyricCreditsExtractor.scan(texts)?.removed, {0, 1, 2});
    });
  });

  // Android LyricCreditsExtractorTest: shapes taken from real NetEase and QQ files (sung lines shortened).
  group('credits kept with the sheet', () {
    LyricSheet sheet(String raw) => lyricSheetOf(LrcParser.parse(raw));
    List<String> texts(String raw) => [for (final r in sheet(raw).rows) r.text];

    test('should_moveNeteaseHeadCreditsAndNotices_when_theyOpenTheFile', () {
      final lyrics = sheet('''
[00:00.000] 作词 : 唐恬
[00:00.463] 作曲 : 钱雷
[00:00.926] 编曲 : 钱雷
[00:01.389] 制作人 : 钱雷
[00:01.852] 吉他 : 高飞
[00:03.704] 混音 : 周天澈
[00:04.630]词版权管理方：北京梦织音传媒有限公司
[00:05.000]联合出品：拳头游戏/腾讯游戏/腾讯视频
[00:22.290]都 是勇敢的
[00:36.960]都 不必隐藏''');
      expect([for (final r in lyrics.rows) r.text], ['都 是勇敢的', '都 不必隐藏']);
      final credits = lyrics.credits!;
      expect(credits.namesFor(LyricCreditRole.lyricist), '唐恬');
      expect(credits.namesFor(LyricCreditRole.composer), '钱雷');
      expect(credits.namesFor(LyricCreditRole.arranger), '钱雷');
      expect(credits.namesFor(LyricCreditRole.producer), '钱雷');
      expect(credits.others, const [LyricCredit('吉他', '高飞'), LyricCredit('混音', '周天澈')]);
      expect(credits.notices, ['词版权管理方：北京梦织音传媒有限公司', '联合出品：拳头游戏/腾讯游戏/腾讯视频']);
    });

    test('should_moveTheTailBlockToo_when_theHeadHadCredits', () {
      const raw = '''
[00:00.000] 作词 : 李荣浩
[00:01.000] 作曲 : 李荣浩
[00:22.837]山隔壁还是山
[03:28.781]麻雀也有明天
[03:34.635]
[04:07.757] 吉他 : 李荣浩
[04:10.253] 音乐制作助理 : 青格乐
[04:12.333] 母带后期处理录音室 : Studio21A''';
      expect(texts(raw), ['山隔壁还是山', '麻雀也有明天']);
      expect(sheet(raw).credits!.others, hasLength(3));
    });

    test('should_dropQqTitleLine_when_creditsFollowIt', () {
      const raw = '''
[ti:Lemon]
[00:00.00]Lemon - 米津玄師 (よねづ けんし)
[00:00.53]词：米津玄師
[00:01.06]曲：米津玄師
[00:01.54]夢ならば
[00:02.88]どれほどよかったでしょう''';
      expect(texts(raw), ['夢ならば', 'どれほどよかったでしょう']);
      expect(sheet(raw).credits!.namesFor(LyricCreditRole.lyricist), '米津玄師');
    });

    test('should_readEnglishRoles_when_qqListsTheStudioCredits', () {
      const raw = '''
[00:00.00]Dynamite - BTS (防弹少年团)
[00:00.01]Lyrics by：David Stewart/Jessica Agombar
[00:00.02]Composed by：David Stewart/Jessica Agombar
[00:00.05]Bass Guitar：David Stewart
[00:00.08]Pads：David Stewart
[00:00.13]Recording Engineer - Pdogg @ Dogg Bounce
[00:05.30]'Cause ah ah I'm in the stars tonight
[00:09.10]So watch me bring the fire''';
      expect(texts(raw), ["'Cause ah ah I'm in the stars tonight", 'So watch me bring the fire']);
      expect([for (final c in sheet(raw).credits!.others) c.role], ['Bass Guitar', 'Pads', 'Recording Engineer']);
    });

    test('should_takeFreeFormStudioLines_when_theTailListsThem', () {
      const raw = '''
[00:00.000] 作词 : Freddie Mercury
[00:00.136]Is this the real life
[05:42.946]Anyway the wind blows
[05:48.500]Lead & Backing Vocals : Freddie Mercury
[05:50.500]Drums, Timpani & Gong : Roger Taylor
[05:51.500]Recorded at Rockfield Studio, Roundhouse
[05:52.000]ISRC TW-A45-99-62502''';
      expect(texts(raw), ['Is this the real life', 'Anyway the wind blows']);
      expect(
        [
          for (final c in sheet(raw).credits!.others)
            if (c.role.isEmpty) c.names,
        ],
        ['Recorded at Rockfield Studio, Roundhouse', 'ISRC TW-A45-99-62502'],
      );
    });

    test('should_keepDialogue_when_itFollowsTheCredits', () {
      const raw = '''
[00:00.000] 作词 : 刘弢
[00:00.239] 作曲 : 杨绍昆
[00:00.479]天笑：好好好
[00:03.739]龙宝：哦.....欸？哪首
[00:06.499]小马/雀阳：一个短篇''';
      expect(texts(raw), ['天笑：好好好', '龙宝：哦.....欸？哪首', '小马/雀阳：一个短篇']);
    });

    test('should_leaveLyricsAlone_when_colonsAreSung', () {
      const raw = '''
[00:10.00]她说：别走
[00:13.00]我说：好
[00:16.00]Chorus: 一起唱
[01:00.00]作词：你的名字在信封上
[02:40.00]Rap: 最后一段
[02:48.00]谢谢收听''';
      expect(texts(raw), hasLength(6));
      expect(sheet(raw).credits, isNull);
    });

    test('should_keepSingerMarkers_when_theyAreLyricsNotCredits', () {
      const raw = '''
[00:00.00]词：唐恬
[00:01.00]曲：钱雷
[00:10.00]宿涵：
[00:12.00]第一句
[00:20.00]合：
[00:22.00]第二句''';
      expect(texts(raw), ['宿涵：', '第一句', '合：', '第二句']);
    });

    test('should_extractFromPlainText_when_lyricsAreUnsynced', () {
      final lyrics = lyricSheetOf(LrcParser.parse('作词：A\n作曲：B\n\nFirst line\nSecond line'));
      expect([for (final r in lyrics.rows) r.text], ['First line', 'Second line']);
      expect(lyrics.credits!.namesFor(LyricCreditRole.lyricist), 'A');
    });

    test('should_readHeadlineRoles_when_creditsUseADash', () {
      const raw = '[00:00.00]Lyrics by - Alice\n[00:01.00]Composed by - Bob\n[00:05.00]One\n[00:06.00]Two';
      expect(texts(raw), ['One', 'Two']);
      expect(sheet(raw).credits!.namesFor(LyricCreditRole.lyricist), 'Alice');
      expect(sheet(raw).credits!.namesFor(LyricCreditRole.composer), 'Bob');
    });

    test('should_keepAParenthesisedSungLine_when_noCreditSitsRightAboveIt', () {
      const raw = '[00:00.00]作词：A\n[00:05.00]One\n[00:06.00]Two\n[00:07.00](Never gonna give you up)';
      expect(texts(raw), ['One', 'Two', '(Never gonna give you up)']);
      expect(sheet(raw).credits!.namesFor(LyricCreditRole.lyricist), 'A');
    });

    test('should_joinAContinuation_when_itFollowsACredit', () {
      const raw = '[00:00.00]作词：A\n[00:00.50](with the help of many friends)\n[00:05.00]One\n[00:06.00]Two';
      expect(texts(raw), ['One', 'Two']);
      expect(sheet(raw).credits!.namesFor(LyricCreditRole.lyricist), 'A (with the help of many friends)');
    });

    test('should_readBilingualRoles_when_vsingerWritesThemInChineseAndEnglish', () {
      const raw = '''
[00:00.000] 作词 Lyricist : 石井 伸昂 Nobutaka Ishii
[00:00.251] 中文翻译 Translator : haTami
[00:01.004] 调声 Tuning : 周小蚕
[00:01.500] 古筝 Guzheng : 猛虎蔷薇
[00:12.000]霞む瞳に
[00:15.000]降り注ぐ
[03:43.719] 出品人 Publisher : 徐博(波吉)
[03:44.034] 录音版权Sub Publishing : 上海哔哩哔哩科技有限公司/Vsinger
[03:44.400] 艺人经纪 Manager: 刘伯澍@太合音乐''';
      expect(texts(raw), ['霞む瞳に', '降り注ぐ']);
      final credits = sheet(raw).credits!;
      expect(credits.namesFor(LyricCreditRole.lyricist), '石井 伸昂 Nobutaka Ishii');
      expect([for (final c in credits.others) c.role], ['中文翻译', '调声', '古筝', '艺人经纪']);
    });

    test('should_takeACreditRunAfterTheIntro_when_theHeadCounted', () {
      const raw = '''
[00:00.00] 作词 : 唯Tu
[00:01.00] 作曲 : 芹菜猪肉大馄饨
[00:03.32] 回忆像是阴霾中的烟火
[00:07.80] 凝住眼泪才能看清轮廓
[00:29.79] 编曲 Arrange: HYPER SLASH超级斩
[00:31.12] 调声 Tuning: 流绪
[00:32.47] 吉他 Guitar: 文件夹
[00:45.78] 我像个贝壳一样怕触碰
[00:50.46] 小心翼翼地将心珍藏着''';
      expect(texts(raw), ['回忆像是阴霾中的烟火', '凝住眼泪才能看清轮廓', '我像个贝壳一样怕触碰', '小心翼翼地将心珍藏着']);
      expect(sheet(raw).credits!.namesFor(LyricCreditRole.arranger), 'HYPER SLASH超级斩');
    });

    test('should_mergeNamesAcrossLines_when_aHeadlineRoleRepeats', () {
      const credits = LyricCredits([
        LyricCredit('作词', 'A', headline: LyricCreditRole.lyricist),
        LyricCredit('Lyrics by', 'B', headline: LyricCreditRole.lyricist),
        LyricCredit('词', 'A', headline: LyricCreditRole.lyricist),
        LyricCredit('吉他', 'C'),
      ]);
      expect(credits.namesFor(LyricCreditRole.lyricist), 'A / B');
      expect(credits.namesFor(LyricCreditRole.producer), isNull);
      expect(credits.others, const [LyricCredit('吉他', 'C')]);
    });
  });

  group('lyricSheetOf', () {
    test('should_sortAndStripCredits_when_synced', () {
      const lyrics = SyncedLyrics([
        LyricLine(5000, 'second'),
        LyricLine(0, '作词 : 唐恬'),
        LyricLine(1000, 'first'),
        LyricLine(0, '作曲 : 钱雷'),
      ]);
      final sheet = lyricSheetOf(lyrics);
      expect(sheet.rows.map((r) => r.text), ['first', 'second']);
      expect(sheet.isSynced, isTrue);
    });

    test('should_splitIntoUntimedRows_when_unsynced', () {
      final sheet = lyricSheetOf(const UnsyncedLyrics('a\n\nb\nc'));
      expect(sheet.rows.map((r) => r.text), ['a', 'b', 'c']);
      expect(sheet.isSynced, isFalse);
    });

    test('should_beEmpty_when_noLyrics', () {
      expect(lyricSheetOf(null).isEmpty, isTrue);
    });
  });

  group('activeLyricIndex', () {
    const rows = [LyricRow('a', startMs: 1000), LyricRow('b', startMs: 2000), LyricRow('c', startMs: 3000)];

    test('should_beMinusOne_when_beforeFirstLine', () => expect(activeLyricIndex(rows, 500), -1));
    test('should_pickLastStarted_when_between', () => expect(activeLyricIndex(rows, 2500), 1));
    test('should_pickLast_when_pastEnd', () => expect(activeLyricIndex(rows, 99999), 2));
    test('should_skipUntimed_when_mixed', () {
      expect(activeLyricIndex(const [LyricRow('x'), LyricRow('y', startMs: 0)], 10), 1);
    });
  });

  group('formatSystemLrc', () {
    test('should_formatTimedRowsWithCrLf_when_synced', () {
      const sheet = LyricSheet([
        LyricRow('intro'),
        LyricRow('第一句', startMs: 1250),
        LyricRow('second\nline', startMs: 65430),
      ]);
      expect(formatSystemLrc(sheet), '[00:01.25]第一句\r\n[01:05.43]second line');
    });

    test('should_returnNull_when_unsyncedOrEmpty', () {
      expect(formatSystemLrc(LyricSheet.empty), isNull);
      expect(formatSystemLrc(const LyricSheet([LyricRow('plain only'), LyricRow('   ', startMs: 500)])), isNull);
    });

    test('should_truncateCleanlyAtByteLimit_when_lrcExceeds40KiB', () {
      final rows = [
        for (var i = 0; i < 600; i++) LyricRow('中文歌词第$i行测试长度填充字符ABCDEFGHIJKLMNOPQRSTUVWXYZ', startMs: i * 1000),
      ];
      final lrc = formatSystemLrc(LyricSheet(rows));
      expect(lrc, isNotNull);
      expect(utf8.encode(lrc!).length, lessThanOrEqualTo(harmonyMaxSystemLyricBytes));
      expect(lrc.endsWith('\r\n'), isFalse);
      expect(lrc.split('\r\n').last, startsWith('['));
    });
  });
}
