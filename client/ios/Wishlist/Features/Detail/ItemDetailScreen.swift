import Shared
import SwiftUI

/// 서버 상품 상세(C4 spec §3, Android `ItemDetailScreen`): 분석 중(FProductProcessing)·완료(FProductDetail)·정보 보완 필요를
/// 한 화면이 `DetailKinds`로 고른다. Presenter owner는 이 칸의 것(`WLEntryOwners`, pop·replaceTop·계정 떠남에 닫힌다)이고
/// 처음 한 번 `loadOnce`한다. 당겨서 새로고침(`refreshAndWait`)과 배경에서 돌아옴(`scenePhaseChanged`)이 `refresh()`다.
/// ⋯ 메뉴는 C8까지 없다(D2).
///
/// 상태: 항목 없이 로딩 → 틀만, 항목 없는 오류 → 문구 + 다시 시도(NOT_FOUND는 삭제됨 + 닫기), 항목 있는 오류 → 항목 유지 +
/// 짧은 안내. 항목·로딩·오류가 모두 없는 Initial(계정 세대가 바뀜)이나 로그인 없는 UNAUTHENTICATED(로그아웃 상태로 복원된
/// 스택)는 바로 닫는다(`ItemDetailPresenterOwner.shouldClose`, 전환 중이면 끝난 뒤).
struct ItemDetailScreen: View {
    let itemId: String

    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlColors) private var c
    // Asked once per view: a retired id builds and closes a throwaway owner on every request.
    @State private var owner: ItemDetailPresenterOwner?

    var body: some View {
        ZStack {
            if let owner, let entryID {
                ItemDetailContent(itemId: itemId, entryID: entryID, owner: owner)
            } else {
                c.background.ignoresSafeArea()
            }
        }
        .onAppear {
            if owner == nil, let entryID { owner = owners.itemDetail(entryID) }
        }
    }
}

private struct ItemDetailContent: View {
    let itemId: String
    let entryID: Int
    let owner: ItemDetailPresenterOwner

    @Environment(\.wlNavigator) private var nav
    @Environment(\.scenePhase) private var scenePhase
    @State private var notice: BriefNotice?

    var body: some View {
        let item = owner.item
        let error = owner.error
        let kind = item.map { DetailKinds.shared.of(item: $0) }
        let gone = kind?.kind == .gone
        DetailScaffold(
            onBack: { nav.pop() },
            originalUrl: gone ? nil : item?.sourceUrl,
            notice: notice,
            refresh: item != nil && !gone ? { await owner.refreshAndWait() } : nil
        ) {
            EmptyView()
        } content: {
            if item == nil, let error {
                if error.kind == .notFound {
                    DetailStatusBlock(message: DetailLine("detail.not.found").resolve(), buttonText: DetailLine("detail.close").resolve()) { nav.pop() }
                } else {
                    DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: DetailLine("detail.retry").resolve()) {
                        owner.retry()
                    }
                }
            } else if let item, let kind {
                switch kind.kind {
                case .gone:
                    DetailStatusBlock(message: DetailLine("detail.not.found").resolve(), buttonText: DetailLine("detail.close").resolve()) { nav.pop() }
                case .processing:
                    ProcessingContent(item: item)
                default:
                    ProductContent(item: item, noticeKey: kind.notice.map(DetailText.noticeKey))
                }
            } else {
                DetailPhotoFrame { EmptyView() }
            }
        }
        .onAppear { owner.loadOnce(id: itemId) }
        .task(id: owner.shouldClose) {
            if owner.shouldClose { await nav.whenSettled(entryID: entryID) { nav.pop() } }
        }
        .onChange(of: scenePhase) { _, phase in owner.scenePhaseChanged(phase) }
        // Each refresh that fails while an item is shown brings the short line back (counted on the owner).
        .onChange(of: owner.refreshNotices) { _, serial in
            if let error = owner.error { notice = BriefNotice(serial: serial, text: DetailLine(DetailText.errorKey(error)).resolve()) }
        }
    }
}

/// FProductProcessing: 높이 222 카드(모서리 28) 안 분석 중 타일 56 + 14 문구, 아래 제목 host·안내·저장 시점(간격 12).
private struct ProcessingContent: View {
    let item: WishlistItem

    @Environment(\.wlColors) private var c
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        DetailWaitingFrame(icon: .sorting, tile: .analyzing(scheme == .dark), caption: DetailLine("row.processing").resolve())
        DetailWaitingBody(title: DisplayFormat.shared.host(url: item.sourceUrl)) {
            WLText(DetailLine("detail.processing.note").resolve(), DetailStyles.note, color: c.textSecondary)
            SavedLine(item: item)
        }
    }
}

/// FProductDetail: 사진 → (안내) 브랜드·이름·가격 묶음 → 정보 카드 → 저장 시점(간격 20).
private struct ProductContent: View {
    let item: WishlistItem
    let noticeKey: String?

    @Environment(\.wlColors) private var c

    var body: some View {
        let host = DisplayFormat.shared.host(url: item.sourceUrl)
        // 2026-10-04: 1:1 칸(모서리 20) 안 여백 56에 사진을 원래 비율로(보드 FProductDetail). 자리표시는 칸 전체.
        DetailPhotoFrame { ProductPhoto(imageUrl: item.product.imageUrl, fill: false, inset: 56) }
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s20) {
            if let noticeKey { NoticeCard(text: DetailLine(noticeKey).resolve(), host: host) }
            VStack(alignment: .leading, spacing: 0) {
                if let brand = nonBlank(item.product.brand) {
                    WLText(brand, DetailStyles.brand, maxLines: 1)
                        .padding(.bottom, WishlistTokens.Space.s8)
                }
                if let name = nonBlank(item.product.name) {
                    WLText(name, DetailStyles.name).wlArrivalFocus()
                } else {
                    WLText(host, DetailStyles.name).wlArrivalFocus()
                    WLText(DetailLine("detail.name.empty").resolve(), DetailStyles.note, color: c.textSecondary)
                        .padding(.top, WishlistTokens.Space.s4)
                }
                if let price = item.product.price?.canonical, formatPrice(price, currency: item.product.currency) != nil {
                    PriceText(amountText: price, currency: item.product.currency, style: DetailStyles.price)
                        .padding(.top, 10)
                    if let checked = item.product.metadataCheckedAt {
                        DetailMinuteClock { now in
                            let time = DisplayFormat.shared.relative(from: checked, now: DetailClock.instant(now), utcOffsetSeconds: DetailClock.utcOffset)
                            WLText(DetailText.priceCheckedText(time).resolve(), DetailStyles.priceNote, color: c.textSecondary)
                        }
                        .padding(.top, WishlistTokens.Space.s4)
                    }
                }
            }
            InfoCard(item: item)
            SavedLine(item: item)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, WishlistTokens.Space.screenMargin)
        .padding(.top, WishlistTokens.Space.s20)
    }

    private func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }
}

/// 정보 보완 안내 한 줄(보드 FProductFill 카드): 실패색 경고 타일 44 + 15/700 문장 + 12 host. "다시 분석"은 C8(D10).
private struct NoticeCard: View {
    let text: String
    let host: String

    @Environment(\.wlColors) private var c
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let tile = DetailTile.failed(scheme == .dark)
        HStack(spacing: WishlistTokens.Space.s12) {
            WLIconTile(color: tile.surface) { WLIcon(.warning, color: tile.icon) }
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                WLText(text, DetailStyles.infoValue)
                WLText(host, .label, color: c.textSecondary, maxLines: 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(WishlistTokens.Space.s16)
        .background(c.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// 정보 카드(모서리 28, 위아래 4): 줄 최소 56·좌우 16, 라벨 폭 60 14 보조색, 값 15/700 오른쪽 정렬.
private struct InfoCard: View {
    let item: WishlistItem

    @Environment(\.wlColors) private var c

    var body: some View {
        WLCard {
            VStack(spacing: 0) {
                InfoRow(label: DetailLine("detail.category").resolve()) {
                    if let name = item.category.name, !name.trimmingCharacters(in: .whitespaces).isEmpty {
                        WLText(name, DetailStyles.infoValue, alignment: .trailing)
                    } else {
                        WLText(DetailLine("detail.category.empty").resolve(), DetailStyles.infoEmpty, color: c.textSecondary, maxLines: 1)
                    }
                }
                InfoRow(label: DetailLine("detail.purpose").resolve()) {
                    let purpose = item.purpose
                    if purpose.id != nil, let name = purpose.name, !name.trimmingCharacters(in: .whitespaces).isEmpty {
                        HStack(spacing: WishlistTokens.Space.s8) {
                            PurposeColorDot(colorKey: purpose.colorKey)
                            WLText(name, DetailStyles.infoValue, alignment: .trailing)
                        }
                    } else {
                        WLText(DetailLine("detail.purpose.none").resolve(), DetailStyles.infoEmpty, color: c.textSecondary, maxLines: 1)
                    }
                }
            }
            .padding(.vertical, WishlistTokens.Space.s4)
        }
    }
}

private struct InfoRow<Value: View>: View {
    let label: String
    @ViewBuilder let value: () -> Value

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s12) {
            WLText(label, .body, color: c.textSecondary, maxLines: 1).frame(width: 60, alignment: .leading)
            value().frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, WishlistTokens.Space.s16)
        .frame(minHeight: 56)
        .accessibilityElement(children: .combine)
    }
}

/// 목적 색 점 10: 아는 색은 그 토큰, 모르는 key는 중립(`textSecondary`).
private struct PurposeColorDot: View {
    let colorKey: String?

    @Environment(\.wlColors) private var c

    var body: some View {
        if let color = DetailText.purposeColor(colorKey) {
            PurposeDot(color: color)
        } else {
            Circle().fill(c.textSecondary).frame(width: 10, height: 10).accessibilityHidden(true)
        }
    }
}

/// "방금 저장"·"오늘 저장"·"M월 d일 저장"(1분마다 다시 잰다).
private struct SavedLine: View {
    let item: WishlistItem

    @Environment(\.wlColors) private var c

    var body: some View {
        DetailMinuteClock { now in
            let label = DisplayFormat.shared.saved(at: item.savedAt, now: DetailClock.instant(now), utcOffsetSeconds: DetailClock.utcOffset)
            WLText(DetailText.savedText(label).resolve(), DetailStyles.caption, color: c.textSecondary, maxLines: 1)
        }
    }
}
