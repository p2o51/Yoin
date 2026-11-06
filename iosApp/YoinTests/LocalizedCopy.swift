import Foundation

/// The app's own string for [key], in the language the tests run in (`-testLanguage`): copy assertions
/// hold in English and in Chinese. Looked up in the app bundle (the test host), not the test bundle.
func loc(_ key: String.LocalizationValue) -> String { String(localized: key, bundle: .main) }
