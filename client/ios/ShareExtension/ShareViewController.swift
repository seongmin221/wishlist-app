import UIKit

final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        // C3 Task 0 spike — remove after verification
        let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.wishlist")
        if let container {
            let stamp = ISO8601DateFormatter().string(from: Date())
            try? stamp.write(to: container.appendingPathComponent("spike.txt"), atomically: true, encoding: .utf8)
        }
        NSLog("C3 spike: share extension app group container = %@", container?.path ?? "nil")
        // End C3 Task 0 spike
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }
}
