import Shared
import SwiftUI

/// 아직 서버에 가지 않은 링크의 상세(D4, Android `LocalSubmissionScreen`): 분석 중 틀 + 대기 타일(홈 줄과 같은 대기 이유,
/// Ruling 7), 제목 host, 저장 줄, 원본 보기, ⋯에는 삭제만(보내는 중이거나 지우는 중이면 비활성, D17). 삭제는 D8 확인창 뒤에
/// 부른다. 새로고침은 없다(coordinator view 관찰).
///
/// 끝(outcome): 전송되어 서버 상품이 되면 이 칸을 상품 상세로 교체(`replaceTop`, 전환 중이면 끝난 뒤), 삭제·이유 모를 사라짐은
/// 닫기, 서버가 이미 삭제한 상품이면(D19) "삭제된 상품이에요" + 닫기.
struct LocalSubmissionScreen: View {
    let submissionId: String

    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlColors) private var c
    // Asked once per view: a retired id builds and closes a throwaway owner on every request.
    @State private var owner: LocalSubmissionPresenterOwner?

    var body: some View {
        ZStack {
            if let owner, let entryID {
                LocalSubmissionContent(submissionId: submissionId, entryID: entryID, owner: owner)
            } else {
                c.background.ignoresSafeArea()
            }
        }
        .onAppear {
            if owner == nil, let entryID { owner = owners.localDetail(entryID) }
        }
    }
}

/// The stack change an outcome asks for (the task key: it runs again only when the outcome changes).
private enum LocalExit: Hashable {
    case stay
    case moved(String)
    case close
}

private struct LocalSubmissionContent: View {
    let submissionId: String
    let entryID: Int
    let owner: LocalSubmissionPresenterOwner

    @Environment(\.wlNavigator) private var nav
    @Environment(\.colorScheme) private var scheme
    @Environment(\.wlColors) private var c
    @State private var notice: BriefNotice?

    var body: some View {
        let row = owner.row
        let removed = owner.outcome is LocalDetailOutcomeRemovedOnServer
        DetailScaffold(
            onBack: { nav.pop() },
            originalUrl: removed ? nil : row?.sourceUrl,
            notice: notice
        ) {
            if row != nil, !removed, owner.outcome == nil {
                LocalMoreButton(owner: owner)
            }
        } content: {
            if removed {
                DetailStatusBlock(message: DetailLine("detail.not.found").resolve(), buttonText: DetailLine("detail.close").resolve()) { nav.pop() }
            } else if let row {
                DetailWaitingFrame(
                    icon: row.status == .localOnly ? .clock : .sorting,
                    tile: .pending(scheme == .dark),
                    caption: DetailLine(DetailText.localTileKey(row.status)).resolve()
                )
                DetailWaitingBody(title: row.host) {
                    WLText(DetailText.localSavedText(row.savedAt).resolve(), DetailStyles.caption, color: c.textSecondary)
                }
            } else {
                DetailWaitingFrame(icon: .clock, tile: .pending(scheme == .dark), caption: "")
            }
        }
        .onAppear { owner.loadOnce(submissionId: submissionId) }
        // "방금" → "1분 전" while the screen is shown.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                owner.tick()
            }
        }
        .task(id: exit) {
            switch exit {
            case .stay: break
            case .moved(let itemId): await nav.whenSettled(entryID: entryID) { nav.replaceTop(AppDestination.item(itemId).route) }
            case .close: await nav.whenSettled(entryID: entryID) { nav.pop() }
            }
        }
        // Each failed delete shows the line again (counted on the owner; the flag is cleared by the next view).
        .onChange(of: owner.deleteFailureNotices) { _, serial in
            notice = BriefNotice(serial: serial, text: DetailLine("local.delete.failed").resolve())
        }
    }

    private var exit: LocalExit {
        guard let outcome = owner.outcome else { return .stay }
        switch onEnum(of: outcome) {
        case .movedTo(let moved): return .moved(moved.itemId)
        case .deleted, .gone: return .close
        case .removedOnServer: return .stay
        }
    }
}

/// ⋯ → 메뉴 "삭제"(보내는 중·지우는 중이면 흐림) → D8 확인창.
private struct LocalMoreButton: View {
    let owner: LocalSubmissionPresenterOwner

    @Environment(\.overlayHostState) private var overlay
    @State private var anchor = WLAnchor()

    var body: some View {
        WLCircleButton(.more, label: DetailLine("detail.more").resolve()) {
            guard let overlay else { return }
            let item = WLMenuItem(
                text: DetailLine("local.delete").resolve(),
                icon: AnyView(WLIcon(.trash, size: 18)),
                enabled: owner.canDelete && !owner.deleting
            ) {
                // D17: a link that started sending cannot be deleted (it becomes the item detail soon).
                if owner.canDelete, let dialog = deleteDialog() { overlay.showDialog(dialog) }
            }
            overlay.showMenu(anchor: anchor.frame, items: [item])
        }
        .wlAnchor(anchor)
    }

    private func deleteDialog() -> WLDialogSpec? {
        guard let row = owner.row else { return nil }
        let owner = owner
        return WLDialogSpec(
            title: DetailLine("local.delete.title").resolve(),
            bullets: [DetailLine("local.delete.line.unsent").resolve(), DetailLine("webview.clear.line.irreversible").resolve()],
            cancelText: DetailLine("dialog.cancel").resolve(),
            confirmText: DetailLine("local.delete").resolve(),
            confirmKind: .danger,
            target: WLDialogTarget(text: DetailText.deleteTargetText(row.host).resolve(), thumbnail: AnyView(DeleteThumbnail())),
            onConfirm: { owner.delete() }
        )
    }
}

/// 확인창 썸네일 자리: 44 모서리 10, 시트색 위 대기 아이콘(사진이 아직 없다).
private struct DeleteThumbnail: View {
    @Environment(\.wlColors) private var c

    var body: some View {
        WLIconTile(size: 44, radius: WishlistTokens.Radius.xs, color: c.sheet) { WLIcon(.clock, color: c.textSecondary) }
            .accessibilityHidden(true)
    }
}
