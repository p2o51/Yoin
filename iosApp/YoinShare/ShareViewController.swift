import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Open in Yoin" in the share sheet (docs/open-in-yoin.md; Android OpenInShareActivity). Tiny on purpose and
/// public API only (owner 2026-11-25: it must pass App Review). It takes the shared link or text, keeps it in
/// the App Group (OpenInInbox) and says so with a small confirmation and Done (ShareConfirmation). Yoin opens
/// a music link in the account on its service (or searches the account in use), or puts other text into
/// Search, the next time it comes to the front (within ten minutes). A share extension can't open its app: Apple documents
/// `NSExtensionContext.open` for Today and iMessage extensions only. Nothing is sent anywhere from here.
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
            guard let text = await Self.sharedText(items) else {
                // Nothing Yoin can take (no link, no text): the share ends without a word.
                extensionContext?.completeRequest(returningItems: nil)
                return
            }
            OpenInInbox.put(text)
            OpenInInbox.postShared()
            showConfirmation()
        }
    }

    private func showConfirmation() {
        let host = UIHostingController(rootView: ShareConfirmation { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        })
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
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
}
