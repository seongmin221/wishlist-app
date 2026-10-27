import UIKit

/// 전환 중 사진 보유(C5 spec §4). 메모리 cache는 축출될 수 있어 key만으로는 전환 중 사진이 남는다는 보장이 없다.
/// 그래서 카드를 누르는 순간 카드가 그리고 있는 `UIImage`를 새 칸 id로 든다(메모리 전용). 상세 사진 자리는 이것 →
/// `RemoteImageLoader.bestCached(for:)` → 기본 placeholder 순으로 쓴다. 상세 크기 사진이 그려지고(`detailDrawn`) push
/// 모션이 끝나면(`motionEnded`) 놓는다. 칸이 스택에서 빠지면(pop 모션 뒤, 계정 떠남) `drop`이 놓는다.
///
/// 뒤로 갈 때 줄어드는 사진은 상세가 지금 그리는 사진이다(`WLSharedPhotoTarget`이 registry에 등록, 전환층이 고른다).
/// 셸(`WLNavMotion`)이 하나 갖는다. 메인 스레드에서만 쓴다.
final class PhotoHandoff {
    private struct Held {
        let image: UIImage
        /// The photo address the card drew (the detail uses the photo only while its item shows that address).
        let sourceURL: URL?
        var drawn = false
        var motionEnded = false
    }

    private var held: [Int: Held] = [:]

    func hold(_ entryID: Int, _ image: UIImage, sourceURL: URL? = nil) {
        held[entryID] = Held(image: image, sourceURL: sourceURL)
    }

    func forward(_ entryID: Int) -> UIImage? { held[entryID]?.image }

    func sourceURL(_ entryID: Int) -> URL? { held[entryID]?.sourceURL }

    /// Lets go of the held card photo at once (the item's photo is no longer the one the card drew).
    func letGo(_ entryID: Int) { held[entryID] = nil }

    func detailDrawn(_ entryID: Int) { update(entryID) { $0.drawn = true } }

    func motionEnded(_ entryID: Int) { update(entryID) { $0.motionEnded = true } }

    func drop(_ entryIDs: [Int]) {
        for id in entryIDs { held[id] = nil }
    }

    private func update(_ entryID: Int, _ change: (inout Held) -> Void) {
        guard var entry = held[entryID] else { return }
        change(&entry)
        held[entryID] = entry.drawn && entry.motionEnded ? nil : entry
    }
}

/// DEBUG 기기 확인용(C5 spec §4 "메모리 cache를 비운 상태에서 push·pop·끌어서 뒤로 취소"): launch 인자
/// `-wl.debug.clearImageMemory`가 있으면 사진 전환이 시작될 때(카드를 누를 때, 상세가 뒤로 떠나기 시작할 때) 로더의 메모리
/// cache를 비운다. release는 늘 꺼져 있다.
enum PhotoTransitionDebug {
    #if DEBUG
    static let clearImageMemory = ProcessInfo.processInfo.arguments.contains("-wl.debug.clearImageMemory")
    #else
    static let clearImageMemory = false
    #endif

    static func onPhotoTransitionStart() {
        if clearImageMemory { RemoteImageLoader.shared.clearMemory() }
    }
}
