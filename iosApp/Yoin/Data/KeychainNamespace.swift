import Foundation

/// Keychain service names. The Mac's QA copy (bundle id ending ".qa", ad-hoc signed, docs/mac.md) keeps its
/// own items under "<service>.qa": it never reads the real app's sign-ins (macOS would ask for the login
/// keychain password for them) and never writes over them.
enum KeychainNamespace {
    /// The Mac's QA copy: its re-signed builds can't read even their own items without a prompt, so its
    /// account list lives in its own defaults (fake-server sign-ins only).
    static var isQACopy: Bool {
        #if os(macOS)
        Bundle.main.bundleIdentifier?.hasSuffix(".qa") == true
        #else
        false
        #endif
    }

    static func service(_ name: String) -> String {
        #if os(macOS)
        if Bundle.main.bundleIdentifier?.hasSuffix(".qa") == true { return name + ".qa" }
        #endif
        return name
    }
}
