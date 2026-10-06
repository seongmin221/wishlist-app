import SwiftUI

#if DEBUG

// C1 데모 화면(debug 빌드의 탭 첫 화면에서만 보인다). 내용은 Android `feature/demo`와 같다.

struct DemoPurpose {
    let id: String
    let name: String
    let color: WLPurposeColor
    let candidates: Int
}

struct DemoProduct {
    let id: String
    let brand: String
    let name: String
    let price: Decimal
    let currency: String
    let category: String
    let purpose: DemoPurpose?
    let tint: WLPurposeColor
    /// 사진 세로/가로 비.
    let photoRatio: CGFloat
}

struct DemoChip {
    let id: String
    let name: String
    let count: Int
}

/// 보드(FHomeL·FCategoryHomeL·FPurposeHomeL·FProductDetailL)의 예시 값과 넘침 확인용 긴 이름.
enum DemoContent {
    /// 넘침 확인용으로 가장 긴 목적 이름(보드 예시보다 길게). 도현 28·20 줄바꿈과 큰 글자 크기를 본다.
    static let longestPurposeName = "주말 캠핑용 가벼운 의자와 테이블 세트 고르기"

    static let purposes: [DemoPurpose] = [
        DemoPurpose(id: "commute", name: "출퇴근 헤드폰", color: .coral, candidates: 5),
        DemoPurpose(id: "trail", name: "가을 트레일 러닝", color: .mustard, candidates: 3),
        DemoPurpose(id: "office", name: "홈오피스 의자", color: .periwinkle, candidates: 2),
        DemoPurpose(id: "camping", name: "캠핑 첫 장비", color: .cyan, candidates: 4),
        DemoPurpose(id: "light", name: "거실 조명 바꾸기", color: .mint, candidates: 3),
        DemoPurpose(id: "gift", name: "엄마 생신 선물", color: .pink, candidates: 2),
        DemoPurpose(id: "longest", name: longestPurposeName, color: .mustard, candidates: 0),
    ]

    static let products: [DemoProduct] = [
        DemoProduct(id: "p1", brand: "소니", name: "WH-1000XM6", price: 549_000, currency: "KRW", category: "헤드폰",
                    purpose: purposes[0], tint: .coral, photoRatio: 1),
        DemoProduct(id: "p2", brand: "보스", name: "QuietComfort Ultra", price: 499_000, currency: "KRW", category: "헤드폰",
                    purpose: purposes[0], tint: .periwinkle, photoRatio: 1.25),
        DemoProduct(id: "p3", brand: "살로몬", name: "Speedcross 6", price: Decimal(string: "159.99")!, currency: "USD", category: "러닝화",
                    purpose: purposes[1], tint: .mustard, photoRatio: 1.25),
        DemoProduct(id: "p4", brand: "헬리녹스", name: "체어 원 라이트", price: 139_000, currency: "KRW", category: "캠핑 의자",
                    purpose: purposes[6], tint: .mint, photoRatio: 1),
        // 가장 긴 가격(보드 FPurposeAddCategoryFilterL). 가장 좁은 상품 카드에서도 한 줄이어야 한다(디자인 결정 2026-10-04).
        DemoProduct(id: "p5", brand: "뱅앤올룹슨", name: "Beoplay H95", price: 1_190_000, currency: "KRW", category: "헤드폰",
                    purpose: purposes[0], tint: .pink, photoRatio: 1.25),
    ]

    static let railCategories = ["패션·잡화", "뷰티·퍼스널케어", "디지털·IT", "가구·인테리어", "생활·주방·가전", "스포츠·아웃도어·여행"]

    static let chips: [DemoChip] = [
        DemoChip(id: "headphone", name: "헤드폰", count: 8), DemoChip(id: "keyboard", name: "키보드", count: 3),
        DemoChip(id: "camera", name: "카메라·액션캠", count: 3), DemoChip(id: "monitor", name: "모니터", count: 2),
        DemoChip(id: "mouse", name: "마우스·트랙패드", count: 2), DemoChip(id: "wearable", name: "웨어러블 기기", count: 2),
        DemoChip(id: "earphone", name: "이어폰", count: 1), DemoChip(id: "tablet", name: "태블릿", count: 1),
        DemoChip(id: "audio", name: "오디오 케이블·DAC", count: 2),
    ]

    static func product(_ id: String) -> DemoProduct? { products.first { $0.id == id } }
    static func chip(_ id: String) -> DemoChip? { chips.first { $0.id == id } }
    static func purpose(_ id: String) -> DemoPurpose? { purposes.first { $0.id == id } }
}

/// 데모 상세 id 규칙: `product:p1`, `chip:headphone`, `purpose:commute`.
enum DemoDestination: Hashable {
    case product(String), chip(String), purpose(String)
}

/// 사진 대신 쓰는 면(목적 색 + 헤드폰 선). 목적 색은 두 테마 같다. 그림이라 접근성 요소가 없다.
struct DemoPhoto: View {
    let tint: WLPurposeColor

    var body: some View {
        Canvas { ctx, size in
            let line = WishlistTokens.Purpose.onPurpose.opacity(0.55)
            let w = min(size.width, size.height)
            let cx = size.width / 2
            let cy = size.height / 2
            let r = w * 0.26
            var arc = Path()
            arc.addArc(center: CGPoint(x: cx, y: cy + r * 0.1), radius: r, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false)
            ctx.stroke(arc, with: .color(line), style: StrokeStyle(lineWidth: w * 0.06, lineCap: .round))
            let cup = CGSize(width: w * 0.14, height: w * 0.24)
            for x in [cx - r - cup.width / 2, cx + r - cup.width / 2] {
                ctx.fill(Path(roundedRect: CGRect(origin: CGPoint(x: x, y: cy), size: cup), cornerRadius: cup.width / 2), with: .color(line))
            }
        }
        .background(tint.face)
        .accessibilityHidden(true)
    }
}

/// 화면 머리의 원형 버튼(44, 카드색).
struct DemoCircleButton<Icon: View>: View {
    let description: String
    let action: () -> Void
    @ViewBuilder let icon: () -> Icon

    @Environment(\.wlColors) private var c

    var body: some View {
        Button(action: action) {
            icon()
                .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                .background(c.card, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(description)
    }
}

struct BackChevron: View {
    @Environment(\.wlColors) private var c

    var body: some View {
        Path { p in
            p.move(to: CGPoint(x: 15 * 20 / 24, y: 5 * 20 / 24))
            p.addLine(to: CGPoint(x: 8 * 20 / 24, y: 12 * 20 / 24))
            p.addLine(to: CGPoint(x: 15 * 20 / 24, y: 19 * 20 / 24))
        }
        .stroke(c.text, style: StrokeStyle(lineWidth: 2 * 20 / 24, lineCap: .round, lineJoin: .round))
        .frame(width: 20, height: 20)
    }
}

struct MoreDots: View {
    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: 20 * 0.25 - 3.2) {
            ForEach(0..<3, id: \.self) { _ in Circle().fill(c.text).frame(width: 3.2, height: 3.2) }
        }
        .frame(width: 20, height: 20)
    }
}

/// 탭 첫 화면 머리: 도현 28 제목 + 보조 글.
struct DemoTabHeader<Trailing: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
                WLText(title, .display28)
                    .accessibilityAddTraits(.isHeader)
                WLText(subtitle, .label, color: c.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.top, WishlistTokens.Space.s24)
    }
}

extension DemoTabHeader where Trailing == EmptyView {
    init(title: String, subtitle: String) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

struct DemoSectionLabel: View {
    let text: String

    @Environment(\.wlColors) private var c

    var body: some View {
        WLText(text, .label, color: c.textSecondary)
            .padding(.top, WishlistTokens.Space.s32)
            .padding(.bottom, WishlistTokens.Space.s12)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 줄바꿈 가로 배치(Compose FlowRow). 데모의 칩 묶음용.
struct DemoFlowLayout: Layout {
    var spacing: CGFloat = WishlistTokens.Space.s8

    private func rows(_ width: CGFloat, _ subviews: Subviews) -> [[(Int, CGSize)]] {
        var rows: [[(Int, CGSize)]] = [[]]
        var x: CGFloat = 0
        for (i, sub) in subviews.enumerated() {
            let size = sub.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0 && x + size.width > width {
                rows.append([])
                x = 0
            }
            rows[rows.count - 1].append((i, size))
            x += size.width + spacing
        }
        return rows
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        let rs = rows(width, subviews)
        let height = rs.map { $0.map(\.1.height).max() ?? 0 }.reduce(0, +) + spacing * CGFloat(max(0, rs.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(bounds.width, subviews) {
            var x = bounds.minX
            let h = row.map(\.1.height).max() ?? 0
            for (i, size) in row {
                subviews[i].place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += h + spacing
        }
    }
}

func demoDeleteDialog(onConfirm: @escaping () -> Void = {}) -> WLDialogSpec {
    WLDialogSpec(
        title: "상품을 삭제할까요?",
        bullets: ["'\(DemoContent.longestPurposeName)' 목적의 비교 후보에서도 빠져요.", "삭제한 상품은 되돌릴 수 없어요."],
        cancelText: "취소",
        confirmText: "삭제",
        confirmKind: .danger,
        onConfirm: onConfirm
    )
}

func demoMenuItems(_ overlay: OverlayHostState) -> [WLMenuItem] {
    [
        WLMenuItem(text: "편집") {},
        WLMenuItem(text: "삭제") { overlay.showDialog(demoDeleteDialog()) },
    ]
}

/// 사진 없는 칩 → 목록(자리 표시 면 이동).
struct DemoSurfaceChip: View {
    let chip: DemoChip
    let sourceKey: String

    @Environment(\.wlNavigator) private var nav

    var body: some View {
        WLSharedSurfaceSource(key: sourceKey, fill: .chip, radius: .pill) {
            WLChip(text: chip.name, count: chip.count) {
                nav.push(.init(destination: DemoDestination.chip(chip.id), pushStyle: .surface), sourceKey: sourceKey)
            }
        }
    }
}

#endif
