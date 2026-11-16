/// Yoin's public pages, opened in the browser from Settings › Help and about
/// (Android ui/settings/YoinLinks.kt).
abstract final class YoinLinks {
  static const website = 'https://yoin.gojyuplus.com/';
  static const docs = 'https://yoin.gojyuplus.com/docs';

  // The website's Simplified Chinese twins (the privacy policy is English only).
  static const websiteZh = 'https://yoin.gojyuplus.com/zh/';
  static const docsZh = 'https://yoin.gojyuplus.com/zh/docs';

  static const privacyPolicy = 'https://yoin.gojyuplus.com/privacy';

  // The public Notion form that files into "Bug 反馈、收集"; anyone with the link can answer.
  static const feedbackForm = 'https://conscious-snapper-b16.notion.site/3f5d1115e921807aaa7be5c39223f00f?pvs=105';

  /// The website's home page in the app's [language].
  static String websiteFor(String language) => language == 'zh' ? websiteZh : website;

  /// The user docs in the app's [language].
  static String docsFor(String language) => language == 'zh' ? docsZh : docs;
}
