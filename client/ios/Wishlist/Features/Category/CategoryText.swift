import Foundation
import Shared

/// The category list a chip opens (`AppDestination.categoryList`): what the chip already knew, so the
/// header draws before any request. Plain Swift values (Hashable) rather than the Kotlin hint.
struct CategoryListTarget: Hashable {
    let categoryId: String
    let name: String
    let parentId: String
    let parentName: String
    let isCustom: Bool

    init(categoryId: String, name: String, parentId: String, parentName: String, isCustom: Bool) {
        self.categoryId = categoryId
        self.name = name
        self.parentId = parentId
        self.parentName = parentName
        self.isCustom = isCustom
    }

    init(entry: CategoryEntry, parentName: String) {
        self.init(categoryId: entry.id, name: entry.name, parentId: entry.parentId, parentName: parentName,
                  isCustom: entry.kind == .custom)
    }

    var hint: CategoryListHint {
        CategoryListHint(categoryId: categoryId, name: name, parentId: parentId, parentName: parentName,
                         kind: isCustom ? .custom : .`public`)
    }
}

/// Maps the shared category values to the 문구 표 keys (Android `CategoryText`; pure).
enum CategoryText {
    /// "상품 N개" under the tab title.
    static func total(_ count: Int32) -> DetailLine { DetailLine("category.total", [.int(count)]) }

    /// The list header's second line: the parent, plus " · 직접 만든 카테고리" for a custom one.
    static func listSubtitle(parentName: String, custom: Bool) -> DetailLine {
        custom ? DetailLine("category.list.custom.suffix", [.string(parentName)]) : DetailLine("raw", [.string(parentName)])
    }

    /// The card's last line (D8 + the null-time rule): "purpose · N일 전 확인", or the purpose part alone.
    static func cardMeta(_ line: CardMetaLine) -> String {
        let purpose: String
        if let named = line.purpose as? PurposeLabelNamed {
            purpose = named.name
        } else {
            purpose = String(localized: "category.card.no.purpose")
        }
        guard let checked = line.checked else { return purpose }
        let time = DetailLine("category.card.checked", [.text(HomeRowText.time(checked))]).resolve()
        return "\(purpose) · \(time)"
    }

    /// Whether the card shows a purpose dot (a named purpose), and its wire color key.
    static func cardDot(_ line: CardMetaLine) -> (shown: Bool, colorKey: String?) {
        guard let named = line.purpose as? PurposeLabelNamed else { return (false, nil) }
        return (true, named.colorKey)
    }

    static func fieldErrorKey(_ error: FieldError) -> String {
        switch error {
        case .duplicate: "category.form.err.duplicate"
        case .invalidChars: "category.form.err.chars"
        case .parent: "category.form.err.parent"
        }
    }

    static func messageKey(_ message: FormMessage) -> String {
        switch message {
        case .limitReached: "category.form.err.limit"
        case .rateLimited: "category.form.err.rate"
        case .saveFailed: "category.form.err.save"
        case .conflictLoaded: "category.form.conflict.loaded"
        case .conflictFailed: "category.form.conflict.failed"
        case .loadFailed: "detail.error.network"
        }
    }

    /// Messages that come with a "다시 시도" button (the form's data could not be read).
    static func messageRetries(_ message: FormMessage) -> Bool {
        message == .loadFailed || message == .conflictFailed
    }

    /// The rail label: lines break only after a middle dot or a space (decisions 2026-10-07).
    static func railLabel(_ name: String) -> String {
        // A word joiner between the other characters keeps them together; "·" and " " stay break points.
        var out = ""
        var previous: Character?
        for character in name {
            if let previous, previous != "·", previous != " ", character != " " { out.append("\u{2060}") }
            out.append(character)
            if character == "·" { out.append("\u{200B}") } // UILabel breaks here, and only here (besides spaces)
            previous = character
        }
        return out
    }
}

extension DetailLine {
    /// `raw` lines are the argument itself (no catalog lookup).
    func resolvedText(_ bundle: Bundle = .main) -> String {
        if key == "raw", case .string(let value)? = args.first { return value }
        return resolve(bundle)
    }
}
