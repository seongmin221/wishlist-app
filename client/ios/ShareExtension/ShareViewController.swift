import OSLog
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The share extension (C3-D1 C안): extracts the first link, writes one inbox file into the app
/// group, shows the result card (C3-D9 iOS) and closes itself. It never sends anything
/// (`DisabledShareDirectSender`) and does not link Shared; the app imports the file on its next
/// launch or foreground.
///
/// Presentation: the view is transparent and only the card is drawn. `completeRequest` is called
/// only after the view appeared and the card's down motion finished — called from `viewDidLoad`
/// the request is ignored and the sheet stays open (Task 0).
final class ShareViewController: UIViewController {
    private let model = ShareCardModel()
    private var appeared = false
    private var playing = false
    private let sender: ShareDirectSender = DisabledShareDirectSender()
    private static let log = Logger(subsystem: "app.wishlist.ios.share", category: "share")

    override init(nibName nibNameOrNil: String?, bundle nibBundleOrNil: Bundle?) {
        super.init(nibName: nibNameOrNil, bundle: nibBundleOrNil)
        // Ask the host for a full-screen presentation without its own background (the card is the UI).
        modalPresentationStyle = .overFullScreen
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        modalPresentationStyle = .overFullScreen
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        view.isOpaque = false
        let host = UIHostingController(rootView: WLTheme { ShareCardHost(model: self.model) })
        host.view.backgroundColor = .clear
        host.view.isOpaque = false
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)

        let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
        Task { @MainActor in
            let kind = await self.save(items)
            self.model.present(kind)
            self.playIfReady()
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared = true
        playIfReady()
    }

    private func playIfReady() {
        guard appeared, model.kind != nil, !playing else { return }
        playing = true
        Task { @MainActor in
            await model.play()
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }

    /// Reads the shared link, writes the inbox file and returns the card to show.
    private func save(_ items: [NSExtensionItem]) async -> ShareCardKind {
        let text = await ShareInput.text(from: items)
        guard case .link(let url) = ShareTextExtractor.extract(text) else { return .invalid }
        guard let directory = AppGroup.inboxDirectory() else {
            Self.log.error("share: no app group container (unsigned build?)")
            return .storeFailed
        }
        let binding = AppGroup.defaults()?.string(forKey: AppGroup.accountBindingKey)
        do {
            let record = try ShareInboxWriter(directory: directory).write(sourceUrl: url, accountBinding: binding)
            sender.send(record: record)
            return binding == nil ? .local : .savedOpenApp
        } catch {
            Self.log.error("share: inbox write failed: \(String(describing: error), privacy: .public)")
            return .storeFailed
        }
    }
}

/// The shared item's text: a URL attachment first (Safari), else plain text (Notes, messengers).
enum ShareInput {
    /// Loading an attachment is bounded; a provider that never answers counts as "no link".
    static let timeout: TimeInterval = 3

    static func text(from items: [NSExtensionItem]) async -> String? {
        let providers = items.flatMap { $0.attachments ?? [] }
        if let url = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.url.identifier) }),
           let value = await load(url, type: .url) {
            return value
        }
        if let plain = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) }),
           let value = await load(plain, type: .plainText) {
            return value
        }
        return nil
    }

    private static func load(_ provider: NSItemProvider, type: UTType) async -> String? {
        await withCheckedContinuation { continuation in
            let once = ResumeOnce(continuation)
            provider.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
                once.resume(item.flatMap(string))
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { once.resume(nil) }
        }
    }

    private static func string(_ item: NSSecureCoding) -> String? {
        switch item {
        case let url as URL: return url.absoluteString
        case let text as String: return text
        case let attributed as NSAttributedString: return attributed.string
        case let data as Data:
            if let url = URL(dataRepresentation: data, relativeTo: nil) { return url.absoluteString }
            return String(data: data, encoding: .utf8)
        default: return nil
        }
    }
}

/// Resumes a continuation with the first value only (the item provider's callback or the timeout).
private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<String?, Never>?

    init(_ continuation: CheckedContinuation<String?, Never>) {
        self.continuation = continuation
    }

    func resume(_ value: String?) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(returning: value)
    }
}
