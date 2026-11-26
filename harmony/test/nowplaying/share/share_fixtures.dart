// What the share tests draw: Android's PreviewShareContent (ShareCards.kt) in
// Chinese, and the same shapes in English, with a demo cover painted locally.
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/nowplaying/share_content.dart';
import 'package:yoin/ui/component/artwork_image.dart';

/// [lang] 'zh' (CJK lyrics: all ten layouts offered) or 'en' (no 竖排).
ShareContent shareFixture({String lang = 'zh', bool cover = true, bool translation = false}) {
  final zh = lang == 'zh';
  return ShareContent(
    song: ShareSong(
      songId: 'preview',
      title: zh ? '他夏了夏天' : 'Northern Lights',
      artist: zh ? '苏打绿' : 'The Lanterns',
      coverArtUrl: cover ? DemoArtwork.url(2) : null,
      albumName: zh ? '夏/狂热' : 'Summer Fever',
      durationMs: 305000,
    ),
    lyrics: ShareLyrics(
      lines: zh
          ? [
              ShareLyricLine(
                startMs: 226800,
                text: '帶著笑容的睡意化成了彩虹',
                translation: translation ? 'Sleep with a smile' : null,
              ),
              ShareLyricLine(
                startMs: 230700,
                text: '在他夢中一口氣走上了星空',
                translation: translation ? 'Up to the stars' : null,
              ),
            ]
          : [
              ShareLyricLine(
                startMs: 226800,
                text: 'We kept the lights on all night long',
                translation: translation ? '我们整夜亮着灯' : null,
              ),
              ShareLyricLine(
                startMs: 230700,
                text: 'Till the morning found us singing',
                translation: translation ? '直到清晨发现我们在歌唱' : null,
              ),
            ],
      showTranslation: translation,
    ),
    about: ShareAbout(
      credits: zh
          ? const [
              ShareCredit('作词', '吴青峰'),
              ShareCredit('作曲', '吴青峰'),
              ShareCredit('制作人', '林暐哲', ai: true),
              ShareCredit('年份', '2009', fact: true),
            ]
          : const [
              ShareCredit('Lyricist', 'Mara Lin'),
              ShareCredit('Composer', 'Mara Lin'),
              ShareCredit('Producer', 'Theo Grant', ai: true),
              ShareCredit('Year', '2009', fact: true),
            ],
      heading: zh ? '关于' : 'About',
      paragraph: zh
          ? '一段占位的背景介绍：收录在哪张专辑、写的是什么、和谁有关。两三句话，由 AI 整理。'
          : 'A placeholder background note: which album it is on, what it is about and who it involves. Two or three sentences, gathered by AI.',
    ),
    note: ShareNote(
      noteId: 'n1',
      content: zh
          ? '最后一段鼓整个打开，**一口气走上了星空**。每次下班走出地铁，刚好听到这里。'
          : 'The last chorus opens all the way up — **straight up to the stars**. I hit this part every time I leave the subway after work.',
      positionMs: 226000,
      createdAt: DateTime(2026, 7, 18, 12).millisecondsSinceEpoch,
    ),
    aiProvider: AiProvider.gemini,
  );
}
