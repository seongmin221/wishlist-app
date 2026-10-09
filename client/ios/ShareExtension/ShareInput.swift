import Foundation
import UniformTypeIdentifiers

/// The shared item's link: a URL attachment first (Safari), else plain text (Notes, messengers).
/// Compiled into the extension and the test target (no Shared).
enum ShareInput {
    /// Loading an attachment is bounded; a provider that never answers counts as "no link".
    static let timeout: TimeInterval = 3

    static func extraction(from items: [NSExtensionItem]) async -> ShareExtraction {
        let providers = items.flatMap { $0.attachments ?? [] }
        let url = providers.first { $0.hasItemConformingToTypeIdentifier(UTType.url.identifier) }
        let plain = providers.first { $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) }
        let urlText: String? = if let url { await load(url, type: .url) } else { nil }
        return await decide(urlText: urlText, plainText: {
            guard let plain else { return nil }
            return await load(plain, type: .plainText)
        })
    }

    /// Which attachment decides the card. The URL attachment wins when it holds a web link (or a too-long
    /// one); otherwise (missing, unloadable, an app-scheme URL such as `musinsa://…`) the plain-text
    /// attachment is loaded and decides. `plainText` is only called when needed (each load can take up to
    /// [timeout]).
    static func decide(urlText: String?, plainText: () async -> String?) async -> ShareExtraction {
        let fromUrl = ShareTextExtractor.extract(urlText)
        if fromUrl != .noLink { return fromUrl }
        return ShareTextExtractor.extract(await plainText())
    }

    /// One loaded attachment value as text, for the type it was requested as. Only a `url` load reads
    /// `Data` as a URL representation; `URL(dataRepresentation:)` accepts any bytes and percent-encodes
    /// them, so plain text read that way turns its spaces into `%20` and the link runs past its end.
    static func string(_ item: NSSecureCoding, type: UTType) -> String? {
        switch item {
        case let url as URL: return url.absoluteString
        case let text as String: return text
        case let attributed as NSAttributedString: return attributed.string
        case let data as Data:
            if type.conforms(to: .url), let url = URL(dataRepresentation: data, relativeTo: nil) { return url.absoluteString }
            return String(data: data, encoding: .utf8)
        default: return nil
        }
    }

    private static func load(_ provider: NSItemProvider, type: UTType) async -> String? {
        await withCheckedContinuation { continuation in
            let once = ResumeOnce(continuation)
            provider.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
                once.resume(item.flatMap { string($0, type: type) })
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { once.resume(nil) }
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
