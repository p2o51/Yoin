import XCTest
@testable import Yoin

/// Settings › About: the public pages and the version line (D-F7, D-U5).
final class AboutLinksTests: XCTestCase {
    func test_should_matchAndroidUrls() {
        XCTAssertEqual(AboutLinks.website.absoluteString, "https://yoin.gojyuplus.com/")
        XCTAssertEqual(AboutLinks.docs.absoluteString, "https://yoin.gojyuplus.com/docs")
        XCTAssertEqual(AboutLinks.privacyPolicy.absoluteString, "https://yoin.gojyuplus.com/privacy")
        XCTAssertEqual(AboutLinks.feedback.absoluteString,
                       "https://conscious-snapper-b16.notion.site/3f5d1115e921807aaa7be5c39223f00f?pvs=105")
    }

    func test_should_pickTheChineseSite_when_theLanguageIsChinese() {
        XCTAssertEqual(AboutLinks.website(language: "zh").absoluteString, "https://yoin.gojyuplus.com/zh/")
        XCTAssertEqual(AboutLinks.docs(language: "zh").absoluteString, "https://yoin.gojyuplus.com/zh/docs")
        XCTAssertEqual(AboutLinks.website(language: "en"), AboutLinks.website)
        XCTAssertEqual(AboutLinks.docs(language: "en"), AboutLinks.docs)
    }

    func test_should_formatTheVersion_fromTheBundleKeys() {
        XCTAssertEqual(AboutLinks.versionName(["CFBundleShortVersionString": "0.6", "CFBundleVersion": "15"]), "0.6 (15)")
        XCTAssertEqual(AboutLinks.versionName(["CFBundleShortVersionString": "0.6"]), "0.6")
        XCTAssertEqual(AboutLinks.versionName(["CFBundleShortVersionString": "3", "CFBundleVersion": "3"]), "3")
        XCTAssertEqual(AboutLinks.versionName([:]), "")
        XCTAssertFalse(AboutLinks.versionName().isEmpty, "the test host carries a version")
    }

    func test_should_labelTheVersionRow_inTheAppLanguage() {
        XCTAssertEqual(loc("Version \(AboutLinks.versionName())"), String(localized: "Version \(AboutLinks.versionName())"))
        XCTAssertTrue(loc("Version \(AboutLinks.versionName())").contains(AboutLinks.versionName()))
    }
}
