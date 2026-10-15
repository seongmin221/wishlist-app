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
}

/// The slot: a 1:1 card-color frame (r20) with the photo fitted inside the 56 inset. The fitted photo
/// rectangle is the shared element's target (`WLSharedPhotoTarget`, corner 0 at rest like the board's
/// FProductDetail; the flight goes from the card's 20 to 0). It draws the held card photo from the first
/// frame and swaps in the detail-size photo when it comes; the held photo is let go once that photo is drawn
/// and the push motion ended. Without any photo the whole frame is the placeholder and that grows instead.
struct DetailSharedPhoto: View {
    let imageUrl: String?

    @Environment(\.wlNavMotion) private var motion
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.displayScale) private var scale
    @Environment(\.wlColors) private var c
    @State private var loaded: UIImage?

    private static let inset: CGFloat = 56

    var body: some View {
        let url = remotePhotoURL(imageUrl)
        let held = entryID.flatMap { motion.handoff.forward($0) }
        let shown = DetailSharedSlot.shown(loaded: loaded, held: held) { url.flatMap { RemoteImageLoader.shared.bestCached(for: $0) } }
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
                        loaded = image
                        if let entryID { motion.handoff.detailDrawn(entryID) }
                    }
                }
            }
        }
        .accessibilityHidden(true)
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
