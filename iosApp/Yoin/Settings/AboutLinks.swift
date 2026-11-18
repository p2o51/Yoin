import Foundation

/// Settings › About: Yoin's public pages and the version line (Android YoinLinks, SettingsScreen About).
enum AboutLinks {
    static let website = URL(string: "https://yoin.gojyuplus.com/")!
    static let docs = URL(string: "https://yoin.gojyuplus.com/docs")!
    /// The website's Simplified Chinese twins (the privacy policy is English only).
    static let websiteZh = URL(string: "https://yoin.gojyuplus.com/zh/")!
    static let docsZh = URL(string: "https://yoin.gojyuplus.com/zh/docs")!
    static let privacyPolicy = URL(string: "https://yoin.gojyuplus.com/privacy")!
    /// The public Notion form that files into the bug tracker; anyone with the link can answer.
    static let feedback = URL(string: "https://conscious-snapper-b16.notion.site/3f5d1115e921807aaa7be5c39223f00f?pvs=105")!

    /// The website's home page in the app's language ([language] is a language code, "zh" for Chinese).
    static func website(language: String) -> URL { language == "zh" ? websiteZh : website }

    /// The user docs in the app's language.
    static func docs(language: String) -> URL { language == "zh" ? docsZh : docs }

    /// The app's own language code (what the UI shows in), "en" when unknown.
    static var currentLanguage: String { Bundle.main.preferredLocalizations.first.map { String($0.prefix(2)) } ?? "en" }

    /// "1.2 (15)" from the bundle's marketing version and build number; either alone when the other is missing.
    static func versionName(_ info: [String: Any]? = Bundle.main.infoDictionary) -> String {
        let short = info?["CFBundleShortVersionString"] as? String
        let build = info?["CFBundleVersion"] as? String
        switch (short, build) {
        case let (s?, b?) where s != b: return "\(s) (\(b))"
        case let (s?, _): return s
        case let (_, b?): return b
        default: return ""
        }
    }
}
