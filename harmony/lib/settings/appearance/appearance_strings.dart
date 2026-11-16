import 'package:flutter/widgets.dart';

import '../../ui/component/seam/seam_style.dart';

/// Settings › Appearance (Scroll edge, Screen corners) and the landing's
/// edge and corner steps, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings_settings.xml, strings_scroll_edge.xml,
/// strings_screen_corners.xml and strings_landing.xml (key in the comment).
class AppearanceStrings {
  const AppearanceStrings._(this._zh);

  /// For tests and non-widget callers.
  const AppearanceStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  static AppearanceStrings of(BuildContext context) =>
      AppearanceStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get sectionAppearance => _zh ? '外观' : 'Appearance'; // settings_section_appearance

  // ── Scroll edge ─────────────────────────────────────────────────────
  String get scrollEdge => _zh ? '滚动边缘' : 'Scroll edge'; // settings_motion_scroll_edge
  String get scrollEdgeIntro => _zh
      ? '滚动内容与固定在页面顶部的栏（如资料库的筛选栏或专辑页顶栏）交界时的样式。'
      : "How scrolling content meets a bar fixed at the top of the page, like Library's filters or an album's header."; // settings_scroll_edge_intro
  String get scrollEdgeFoot => _zh
      ? '底栏与状态栏在每种样式下保持不变。'
      : 'The bottom bar and the status bar look the same in every style.'; // settings_scroll_edge_foot
  String get scrollEdgeDefault => _zh ? '默认' : 'Default'; // settings_scroll_edge_default

  /// The style's name (settings_motion_tide_line / _dots / _cookie_wave).
  String styleName(SeamTopStyle style) => switch (style) {
    SeamTopStyle.tide => _zh ? '潮线' : 'Tide line',
    SeamTopStyle.dots => _zh ? '网点' : 'Dots',
    SeamTopStyle.cookie => _zh ? '曲奇浪口' : 'Cookie wave',
  };

  /// The longer description (settings_scroll_edge_tide_desc / _dots_desc / _cookie_desc).
  String styleDescription(SeamTopStyle style) => switch (style) {
    SeamTopStyle.tide =>
      _zh
          ? '封面与文字沉入边缘的两道柔和波浪之下。快速滑动时依然保持平静。'
          : 'Covers and text sink under two soft waves at the edge. Stays calm even when you scroll fast.',
    SeamTopStyle.dots =>
      _zh
          ? '封面穿过顶栏下方时散成细密的网点。Yoin 最初的样式。'
          : "Covers break into a fine dot screen as they pass under the bar. Yoin's original look.",
    SeamTopStyle.cookie =>
      _zh
          ? '每张封面的上缘变为曲奇花边，离开时卷成柔和的软尖。'
          : "Each cover's top edge turns into a scalloped wave that rolls to a soft point as it leaves.",
  };

  /// One line on what the style does, for the landing's picker (landing_edge_tide_short / _dots_short / _cookie_short).
  String styleShort(SeamTopStyle style) => switch (style) {
    SeamTopStyle.tide => _zh ? '内容沉入两道柔和的波浪之下。' : 'Content sinks under two soft waves.',
    SeamTopStyle.dots => _zh ? '封面散成细密的网点。' : 'Covers break into fine dots.',
    SeamTopStyle.cookie => _zh ? '封面离开时，上缘化作曲奇花边。' : 'Covers leave with a scalloped edge.',
  };

  // ── Screen corners ──────────────────────────────────────────────────
  String get screenCorners => _zh ? '屏幕圆角' : 'Screen corners'; // settings_motion_screen_corners
  String get screenCornersFollow => _zh ? '跟随屏幕' : 'Match screen'; // screen_corners_follow
  String screenCornersValue(int dp) => '$dp dp'; // screen_corners_value
  String get screenCornersHint => _zh
      ? '拖动这条，让屏幕边缘那圈线贴住屏幕的圆角。Now Playing、回忆和返回手势都会用这个圆角。'
      : "Slide the bar until the line around the edge sits on your screen's corners. Now Playing, Memories and back gestures take this corner."; // screen_corners_hint
  String get screenCornersSliderCd => _zh ? '屏幕圆角半径' : 'Screen corner radius'; // screen_corners_slider_cd
  String get screenCornersPageHint =>
      _zh ? '拖动底下的条，直到四个角刚好看不到黑边。' : 'Slide the bar until no black shows in the corners.'; // screen_corners_page_hint
  String get exampleNowPlaying => _zh ? '打开 Now Playing' : 'Now Playing opening'; // screen_corners_example_now_playing
  String get exampleMemories => _zh ? '收起回忆' : 'Memories closing'; // screen_corners_example_memories
  String get exampleBack => _zh ? '返回手势' : 'Back gesture'; // screen_corners_example_back

  // ── The landing ─────────────────────────────────────────────────────
  String get landingEdgeTitle =>
      _zh ? '滚动时，内容怎样移入顶栏下方？' : 'When you scroll, how should things slip under the top bar?'; // landing_edge_title
  String get landingCornersTitle =>
      _zh ? '你的屏幕圆角有多圆？' : "How round are your screen's corners?"; // landing_corners_title

  /// Settings › Help and about › Welcome guide's summary (settings_about_welcome_guide_summary).
  String get welcomeGuideSummary => _zh ? '账号、首页与滚动边缘' : 'Accounts, Home and scroll edge';
}
