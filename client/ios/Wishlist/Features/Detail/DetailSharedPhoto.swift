import Shared
import SwiftUI

/// Rules of the photo slot of a detail opened from a list card (C5 spec §4, Android `DetailPhoto.kt`).
enum DetailSharedSlot {
    /// One slot view from the first frame (item still loading) to the shown product: the push measures the
    /// target probe right away, and the slot must stay the same view through the flight. A failed load
    /// without an item, processing and gone show no photo.
    static func keeps(photoEntry: Bool, hasItem: Bool, hasError: Bool, kind: DetailKind?) -> Bool {
        guard photoEntry else { return false }
        guard hasItem else { return !hasError }
        return kind == .ready || kind == .incomplete
    }

    /// The detail-size photo once drawn, else the card photo held at the push (`PhotoHandoff`), else the
    /// biggest memory-cached photo of the address (`bestCached`), else nil (the placeholder).
    static func shown(loaded: UIImage?, held: UIImage?, cached: () -> UIImage?) -> UIImage? {
        loaded ?? held ?? cached()
    }

    /// Whether the held card photo still stands for the item: while the item is still loading (its address is
    /// not known yet) or when the item's photo is the address the card drew. A refresh that removes or replaces
    /// the photo — also after the detail photo failed and never let the handoff go — drops it (Android `heldPhotoFits`).
    static func heldFits(itemLoaded: Bool, heldURL: URL?, url: URL?) -> Bool {
        !itemLoaded || (url != nil && heldURL == url)
    }

    /// The loaded detail photo, if it was loaded from `url` (the item's photo now). A refresh that removes or
    /// replaces the photo drops it: the slot falls back to the held photo, the cache or the placeholder, and
    /// going back shrinks what the detail shows now (Android `drawnPhotoFor`).
    static func loaded(for url: URL?, _ loaded: (url: URL, image: UIImage)?) -> UIImage? {
        guard let url, let loaded, loaded.url == url else { return nil }
        return loaded.image
    }
}

/// The slot: a 1:1 card-color frame (r20) with the photo fitted inside the 56 inset. The fitted photo
/// rectangle is the shared element's target (`WLSharedPhotoTarget`, corner 0 at rest like the board's
/// FProductDetail; the flight goes from the card's 20 to 0). It draws the held card photo from the first
/// frame and swaps in the detail-size photo when it comes; the held photo is let go once that photo is drawn
/// and the push motion ended. Without any photo the whole frame is the placeholder and that grows instead.
struct DetailSharedPhoto: View {
    let imageUrl: String?
    /// False while the item is still loading (no address yet): the held card photo is drawn meanwhile.
    var itemLoaded = true

    @Environment(\.wlNavMotion) private var motion
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.displayScale) private var scale
    @Environment(\.wlColors) private var c
    @State private var loaded: (url: URL, image: UIImage)?

    private static let inset: CGFloat = 56

    var body: some View {
        let url = remotePhotoURL(imageUrl)
        let holding = entryID.flatMap { motion.handoff.forward($0) }
        let heldURL = entryID.flatMap { motion.handoff.sourceURL($0) }
        let held = holding.flatMap { DetailSharedSlot.heldFits(itemLoaded: itemLoaded, heldURL: heldURL, url: url) ? $0 : nil }
        let shown = DetailSharedSlot.shown(loaded: DetailSharedSlot.loaded(for: url, loaded), held: held) { url.flatMap { RemoteImageLoader.shared.bestCached(for: $0) } }
        let animating = entryID.map { motion.channels($0).animating } ?? false
        DetailPhotoFrame {
            GeometryReader { geo in
                let box = CGSize(width: max(0, geo.size.width - 2 * Self.inset), height: max(0, geo.size.height - 2 * Self.inset))
                Group {
                    if let shown, shown.size.width > 0, shown.size.height > 0 {
                        let fitted = Self.fit(shown.size, in: box)
                        // Rebuilt by the flight layer outside this view: it draws the captured `shown` value.
                        WLSharedPhotoTarget(corner: 0) {
                            Image(uiImage: shown).resizable().scaledToFit()
                        }
                        .frame(width: fitted.width, height: fitted.height)
                    } else {
                        WLSharedPhotoTarget(corner: 0) { DetailPhotoPlaceholder() }
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .task(id: DetailPhotoKey(url: url, width: Int(box.width * scale), height: Int(box.height * scale))) {
                    guard let url, box.width > 0, box.height > 0 else { return }
                    if let image = await RemoteImageLoader.shared.image(for: url, frame: box, scale: scale, fill: false), !Task.isCancelled {
                        loaded = (url, image)
                        if let entryID { motion.handoff.detailDrawn(entryID) }
                    }
                }
            }
        }
        .accessibilityHidden(true)
        // A photo of another address is not kept (it is no longer drawn or handed back).
        .onChange(of: url) { _, now in
            if let current = loaded, current.url != now { loaded = nil }
        }
        // A card photo of another address is let go (it would otherwise stay until the detail closes).
        .onChange(of: holding != nil && held == nil, initial: true) { _, mismatch in
            if mismatch, let entryID { motion.handoff.letGo(entryID) }
        }
        .onChange(of: animating, initial: true) { _, now in
            if !now, let entryID { motion.handoff.motionEnded(entryID) }
        }
    }

    /// `size` scaled to fit `box` (ratio kept).
    static func fit(_ size: CGSize, in box: CGSize) -> CGSize {
        let k = min(box.width / size.width, box.height / size.height)
        return CGSize(width: size.width * k, height: size.height * k)
    }
}

private struct DetailPhotoKey: Hashable {
    let url: URL?
    let width: Int
    let height: Int
}
