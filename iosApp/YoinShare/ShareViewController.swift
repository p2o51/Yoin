import UIKit
import UniformTypeIdentifiers

/// "Open in Yoin" in the share sheet (docs/open-in-yoin.md; Android OpenInShareActivity). Tiny on purpose:
/// no window of its own. It takes the shared link or text, leaves it in the App Group for a moment
/// (OpenInInbox) and opens Yoin with `yoin://open-in`, which opens a music link in the account on its
/// service (or searches the account in use) and puts any other text into Search. Nothing is sent anywhere
/// from here. Yoin never registers for Spotify or Apple Music web links: it only gets what the user sends.
final class ShareViewController: UIViewController {
    private var handled = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !handled else { return }
        handled = true
        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        Task { @MainActor in
            if let text = await Self.sharedText(items) {
                OpenInInbox.put(text)
                openYoin()
            }
            extensionContext?.completeRequest(returningItems: nil)
        }
    }

    /// The shared URL, else the shared text, else the item's own text (some apps put the title there and
    /// the link alone in the attachment; the link wins).
    static func sharedText(_ items: [NSExtensionItem]) async -> String? {
        let providers = items.flatMap { $0.attachments ?? [] }
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let url = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL, !url.isFileURL {
                return url.absoluteString
            }
        }
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let text = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String,
               !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return text
            }
        }
        return items.compactMap { $0.attributedContentText?.string }
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// A share extension can't call `UIApplication.open`; the app object up the responder chain still takes
    /// `openURL:options:completionHandler:`. Where it doesn't, the text waits in the App Group and Yoin takes
    /// it the next time it comes to the front (within ten minutes).
    private func openYoin() {
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let r = responder {
            if r is UIApplication, r.responds(to: selector) {
                typealias Open = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
                let open = unsafeBitCast(r.method(for: selector), to: Open.self)
                open(r, selector, OpenInInbox.url as NSURL, NSDictionary(), nil)
                return
            }
            responder = r.next
        }
    }
}
