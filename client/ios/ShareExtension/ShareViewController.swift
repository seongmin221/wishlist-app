import OSLog
import SwiftUI
import UIKit

/// The share extension (C3-D1 C안): extracts the first link, writes one inbox file into the app
/// group, shows the result card (C3-D9 iOS) and closes itself. It never sends anything
/// (`DisabledShareDirectSender`) and does not link Shared; the app imports the file on its next
/// launch or foreground.
///
/// Presentation: iOS 26 shows the extension in its own opaque system sheet whatever we ask for, so the
/// view paints the board's "other app" backdrop (`ShareBackdrop`, Ruling 17) and draws only the card
/// at its bottom. `completeRequest` is called only after the view appeared and the card's down motion
/// finished — called from `viewDidLoad` the request is ignored and the sheet stays open (Task 0).
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
        // Painted from the first frame, so the sheet never shows the system background while sliding up.
        view.backgroundColor = ShareBackdrop.uiColor
        let host = UIHostingController(rootView: WLTheme { ShareCardHost(model: self.model) })
        host.view.backgroundColor = ShareBackdrop.uiColor
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
        guard case .link(let url) = await ShareInput.extraction(from: items) else { return .invalid }
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
