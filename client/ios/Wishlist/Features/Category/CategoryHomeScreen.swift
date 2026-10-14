import Shared
import SwiftUI

/// 카테고리 탭 첫 화면(FCategoryHome, C5): 머리(도현 28 "카테고리" + "상품 N개") · 선 · 왼쪽 상위 레일 124 + 오른쪽 세로 페이저.
/// - 페이지 = 상위 하나(레이블 + 칩 + 점선 `+ 추가`). 끌어서 넘기면 가까운 페이지로 300 `emphasized` 스냅, 레일을 누르면 같은
///   모션으로 이동한다. 페이지가 화면보다 길면 그 페이지 안을 먼저 움직이고 끝에서 다음 페이지로 넘어간다(`CategoryPager`).
/// - 페이지가 바뀌면 Presenter에 알린다(이 계정의 마지막 상위로 기억).
/// - 로그인 전(D5): 로그인 안내 + 로그인 버튼. 빈 탭(D6): 빈 상태 + "새 카테고리 만들기". 첫 로딩은 머리만, 첫 로딩 실패는
///   "불러오지 못했어요 · 다시 시도". 새로고침 실패는 짧은 안내.
/// - 첫 페이지 위에서 아래로 당기면 새로고침, foreground 복귀도 새로고침. 현재 탭을 다시 누르면 첫 페이지로.
struct CategoryHomeScreen: View {
    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlNavigator) private var navigator
    @Environment(\.overlayHostState) private var overlay
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.wlColors) private var c
    @Environment(AccountPresenterOwner.self) private var account

    var body: some View {
        let owner = owners.categoryHome(entryID ?? -1)
        let state = owner.state
        VStack(alignment: .leading, spacing: 0) {
            CategoryTabHeader(caption: state.signedIn && !state.groups.isEmpty ? CategoryText.total(state.totalCount).resolve() : nil)
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .padding(.bottom, WishlistTokens.Space.s16)
            if !state.signedIn {
                Spacer(minLength: 0)
                CategorySignedOut()
                Spacer(minLength: 0)
            } else if state.loaded && state.groups.isEmpty && state.error == nil {
                Spacer(minLength: 0)
                CategoryEmptyTab { openCreate(owner: owner, parent: nil) }
                Spacer(minLength: 0)
            } else if state.groups.isEmpty, let error = state.error {
                Spacer(minLength: 0)
                DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "detail.retry")) {
                    owner.retry()
                }
                Spacer(minLength: 0)
            } else if !state.groups.isEmpty {
                Rectangle().fill(c.line).frame(height: 1)
                CategoryPager(owner: owner, state: state) { group in openCreate(owner: owner, parent: group) }
                    .ignoresSafeArea(.container, edges: .bottom)
            } else {
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(c.background)
        .overlay(alignment: .top) {
            DetailNoticeLine(notice: owner.refreshNotice)
                .padding(.top, WLTopBarMetrics.height + WishlistTokens.Space.s32)
        }
        .onAppear { owner.loadOnce() }
        .onChange(of: scenePhase) { _, phase in owner.scenePhaseChanged(phase) }
        .onChange(of: account.state.account?.accountId) { previous, next in
            // Leaving the account closes any open category sheet (spec §3 계정).
            if ContentView.shouldDropAccountScoped(previous: previous, next: next) { overlay?.dismissAll() }
        }
    }

    private func openCreate(owner: CategoryHomeOwner, parent: String?) {
        guard let overlay else { return }
        showCategoryFormSheet(overlay: overlay, owners: owners, start: { form in
            form.startCreate(parentId: parent, source: .tab, select: nil)
        }, onResult: { result in
            // D9: the sheet closes and the new category's group page shows its new chip.
            if let created = result as? FormResultCreated {
                owner.didCreate(in: created.detail.parentId)
            }
        })
    }
}

/// Tab title (도현 28, centered in the 56 bar) and the 13/500 caption under it (board FCategoryHome).
struct CategoryTabHeader: View {
    let caption: String?

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WLTopBar(sidePadding: 0) {
                EmptyView()
            } center: {
                WLText(String(localized: "category.title"), .display28, maxLines: 1)
                    .accessibilityAddTraits(.isHeader)
            } trailing: {
                EmptyView()
            }
            WLText(caption ?? " ", WLTextStyle.label.resized(13), color: c.textSecondary, maxLines: 1)
                .opacity(caption == nil ? 0 : 1)
        }
    }
}

/// D5: signed out. Category tile + "로그인하면 …" + 로그인(the pushed login route).
private struct CategorySignedOut: View {
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(spacing: WishlistTokens.Space.s16) {
            WLIconTile(size: 56, radius: WishlistTokens.Radius.m, color: c.card) { WLIcon(.apps, size: 24) }
                .accessibilityHidden(true)
            WLText(String(localized: "category.signed.out.body"), WLTextStyle.body.resized(15, lineHeight: 15 * 1.5), color: c.text, alignment: .center)
            WLButton(String(localized: "category.signed.out.action"), kind: .primary) {
                nav.push(AppDestination.login.route, sourceKey: "category/login")
            }
            .frame(maxWidth: 200)
        }
        .padding(.horizontal, WishlistTokens.Space.s32)
        .frame(maxWidth: .infinity)
    }
}

/// D6: nothing saved and no custom category. Empty state + secondary "새 카테고리 만들기".
private struct CategoryEmptyTab: View {
    let onCreate: () -> Void

    var body: some View {
        VStack(spacing: WishlistTokens.Space.s20) {
            EmptyState(title: String(localized: "category.empty.title"), description: String(localized: "category.empty.body")) {
                WLIcon(.apps, size: 24)
            }
            WLButton(String(localized: "category.empty.action"), kind: .secondary, action: onCreate)
                .frame(maxWidth: 240)
        }
    }
}

extension CategoryHomeOwner {
    /// D9 after a create: the pager moves to that group (and remembers it), then the list is read again.
    func didCreate(in parentId: String) {
        pendingGroup = parentId
        refresh()
    }
}
