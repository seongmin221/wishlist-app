import SwiftUI

#if DEBUG

// C1 데모 화면(debug 빌드의 탭 첫 화면에서만 보인다). 값은 보드(FCategoryHomeL·FCategoryListL·FProductDetailL·
// FPurposeHomeL·FPurposeDetailL) 스크립트의 예시 데이터다. 내용은 Android `feature/demo`와 같다.

/// 보드 목적 아이콘(24 격자 선 아이콘).
enum DemoIcon: Hashable {
    case music, star, book, tent, home, gift, plane, heart
    case archive, external, plus, back, more, chevronRight, purposeTab, pending
}

struct DemoPurpose: Hashable {
    let id: String
    let name: String
    let color: WLPurposeColor
    let icon: DemoIcon
    let candidates: Int
    /// 목적 카드 펼침 메타("후보 5 · 어제 후보 추가").
    let meta: String
    /// 목적 상세 설명(없으면 줄을 그리지 않는다).
    var description: String?
}

/// 사진 대신 쓰는 그림의 면·선 색. UI 토큰이 아니라 상품 사진(이미지 데이터)의 견본이라 보드 사진 색을 그대로 쓰고,
/// 두 테마가 같다(보드 다크 화면도 같은 사진 색이다). Android `DemoPhotoLook`과 같은 값이다.
enum DemoPhotoArt: Hashable {
    case white, light, brown, green

    var face: Color {
        switch self {
        case .white: Color(wlHex: 0xFFFFFF)
        case .light: Color(wlHex: 0xE9E8E4)
        case .brown: Color(wlHex: 0x7A6B5B)
        case .green: Color(wlHex: 0x3F4B44)
        }
    }

    var stroke: Color {
        switch self {
        case .white: Color(wlHex: 0x9A9A96)
        case .light: Color(wlHex: 0x7C7B77)
        case .brown: Color(wlHex: 0xE9DFD2)
        case .green: Color(wlHex: 0xC9D3CB)
        }
    }

    var fill: Color {
        switch self {
        case .white: Color(wlHex: 0xD8D8D4)
        case .light: Color(wlHex: 0xBDBCB7)
        case .brown: Color(wlHex: 0xA99683)
        case .green: Color(wlHex: 0x7F8E84)
        }
    }
}

struct DemoProduct: Hashable {
    let id: String
    let brand: String
    let name: String
    let price: String
    let currency: String
    let top: String
    let category: String
    let purpose: DemoPurpose?
    let art: DemoPhotoArt
    /// 사진 세로/가로 비(1:1 → 1, 4:5 → 1.25, 3:4 → 4/3).
    let photoRatio: CGFloat
    /// 카드 메타 줄("출퇴근 헤드폰 · 2일 전 확인").
    let meta: String
    /// 목록 카드 메타 줄 앞에 목적 점을 그리는지(목적 상세의 후보 카드는 점이 없다).
    var showsDot = true
    /// 분류·목적 미확정 표시.
    var pending = false

    var fullName: String { "\(brand) \(name)" }
}

struct DemoChip: Hashable {
    let name: String
    let count: Int
}

struct DemoTopCategory: Hashable {
    let name: String
    let types: [DemoChip]

    /// 레일 글자: 가운뎃점 뒤에 zero-width space를 넣어 어절·가운뎃점 뒤에서만 줄을 바꾼다(보드 `label`과 같다).
    /// UILabel은 가운뎃점 앞에서도 끊으므로("취미·문화 / ·컬렉터블") 앞에는 word joiner를 넣어 막는다.
    var railLabel: String { name.replacingOccurrences(of: "·", with: "\u{2060}·\u{200B}") }
}

enum DemoContent {
    /// 넘침 확인용으로 가장 긴 목적 이름(보드 예시보다 길게). 도현 28·20 줄바꿈과 큰 글자 크기를 본다. 홈 데모에서만 쓴다.
    static let longestPurposeName = "주말 캠핑용 가벼운 의자와 테이블 세트 고르기"

    static let longestPurpose = DemoPurpose(id: "longest", name: longestPurposeName, color: .mustard, icon: .tent,
                                            candidates: 0, meta: "1달 전 만듦")

    /// 보드 FPurposeHomeL `ps` 7개(최근 활동순).
    static let purposes: [DemoPurpose] = [
        DemoPurpose(id: "commute", name: "출퇴근 헤드폰", color: .coral, icon: .music, candidates: 5,
                    meta: "후보 5 · 어제 후보 추가", description: "지하철에서 쓸 노이즈 캔슬링 헤드폰"),
        DemoPurpose(id: "trail", name: "가을 트레일 러닝", color: .mustard, icon: .star, candidates: 3, meta: "후보 3 · 3일 전 후보 추가"),
        DemoPurpose(id: "office", name: "홈오피스 의자", color: .periwinkle, icon: .book, candidates: 2, meta: "후보 2 · 1주 전 후보 추가"),
        DemoPurpose(id: "camping", name: "캠핑 첫 장비", color: .cyan, icon: .tent, candidates: 4, meta: "후보 4 · 2주 전 후보 추가"),
        DemoPurpose(id: "light", name: "거실 조명 바꾸기", color: .mint, icon: .home, candidates: 3, meta: "후보 3 · 3주 전 후보 추가"),
        DemoPurpose(id: "gift", name: "엄마 생신 선물", color: .pink, icon: .gift, candidates: 2, meta: "후보 2 · 1달 전 후보 추가"),
        DemoPurpose(id: "carrier", name: "여행 캐리어", color: .mustard, icon: .plane, candidates: 0, meta: "1달 전 만듦",
                    description: "다음 달 출장 때 쓸 기내용 캐리어"),
    ]

    private static var commute: DemoPurpose { purposes[0] }

    /// 보드 FCategoryListL `items` 8개(디지털·IT > 헤드폰).
    static let headphones: [DemoProduct] = [
        DemoProduct(id: "h1", brand: "소니", name: "WH-1000XM6", price: "549000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .white, photoRatio: 1, meta: "출퇴근 헤드폰 · 2일 전 확인"),
        DemoProduct(id: "h2", brand: "보스", name: "QuietComfort Ultra", price: "499000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .brown, photoRatio: 1.25, meta: "출퇴근 헤드폰 · 2일 전 확인"),
        DemoProduct(id: "h3", brand: "젠하이저", name: "MOMENTUM 4", price: "389000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: nil, art: .light, photoRatio: 4.0 / 3, meta: "목적 미지정 · 5일 전 확인"),
        DemoProduct(id: "h4", brand: "애플", name: "AirPods Max", price: "769000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .white, photoRatio: 1, meta: "출퇴근 헤드폰 · 1주 전 확인"),
        DemoProduct(id: "h5", brand: "마샬", name: "MAJOR V", price: "229000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: nil, art: .green, photoRatio: 1.25, meta: "목적 미지정 · 1주 전 확인", pending: true),
        // 가장 긴 가격(보드 FPurposeAddCategoryFilterL). 가장 좁은 상품 카드에서도 한 줄이어야 한다(디자인 결정 2026-10-04).
        DemoProduct(id: "h6", brand: "뱅앤올룹슨", name: "Beoplay H95", price: "1190000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: nil, art: .light, photoRatio: 4.0 / 3, meta: "목적 미지정 · 2주 전 확인"),
        DemoProduct(id: "h7", brand: "소니", name: "ULT WEAR", price: "279000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .white, photoRatio: 1, meta: "출퇴근 헤드폰 · 2주 전 확인"),
        DemoProduct(id: "h8", brand: "오디오테크니카", name: "ATH-M50x", price: "219000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: nil, art: .brown, photoRatio: 1.25, meta: "목적 미지정 · 3주 전 확인"),
    ]

    /// 보드 FPurposeDetailL `items` 5개(출퇴근 헤드폰 후보). 메타가 판매처라 목록과 따로 둔다.
    static let candidates: [DemoProduct] = [
        DemoProduct(id: "c1", brand: "소니", name: "WH-1000XM6", price: "549000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .white, photoRatio: 1, meta: "무신사 · 2일 전 확인", showsDot: false),
        DemoProduct(id: "c2", brand: "보스", name: "QuietComfort Ultra", price: "499000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .brown, photoRatio: 1.25, meta: "보스 공식몰 · 2일 전 확인", showsDot: false),
        DemoProduct(id: "c3", brand: "애플", name: "AirPods Max", price: "769000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .light, photoRatio: 4.0 / 3, meta: "애플 · 1주 전 확인", showsDot: false),
        DemoProduct(id: "c4", brand: "소니", name: "ULT WEAR", price: "279000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .white, photoRatio: 1, meta: "11번가 · 2주 전 확인", showsDot: false),
        DemoProduct(id: "c5", brand: "마샬", name: "MAJOR V", price: "229000", currency: "KRW", top: "디지털·IT", category: "헤드폰",
                    purpose: commute, art: .green, photoRatio: 1.25, meta: "29CM · 1주 전 확인", showsDot: false, pending: true),
    ]

    /// 홈 데모의 사진 카드. USD 소수 가격과 가장 긴 목적 이름·가격을 함께 본다.
    static let homeProducts: [DemoProduct] = [
        headphones[0], headphones[1],
        DemoProduct(id: "p3", brand: "살로몬", name: "Speedcross 6", price: "159.99", currency: "USD", top: "스포츠·아웃도어·여행",
                    category: "러닝 용품", purpose: purposes[1], art: .light, photoRatio: 1.25, meta: "가을 트레일 러닝 · 3일 전 확인"),
        DemoProduct(id: "p4", brand: "헬리녹스", name: "체어 원 라이트", price: "139000", currency: "KRW", top: "스포츠·아웃도어·여행",
                    category: "캠핑 용품", purpose: longestPurpose, art: .green, photoRatio: 1, meta: "\(longestPurposeName) · 1주 전 확인"),
        headphones[5],
    ]

    /// 보드 FCategoryHomeL `home` 8개 상위와 세부 유형(합계 상품 69개).
    static let categories: [DemoTopCategory] = [
        DemoTopCategory(name: "패션·잡화", types: [
            DemoChip(name: "신발", count: 5), DemoChip(name: "아우터", count: 3), DemoChip(name: "가방", count: 3),
            DemoChip(name: "상의", count: 2), DemoChip(name: "패션 소품", count: 2),
        ]),
        DemoTopCategory(name: "뷰티·퍼스널케어", types: [DemoChip(name: "스킨케어", count: 2), DemoChip(name: "향수", count: 2)]),
        DemoTopCategory(name: "디지털·IT", types: [
            DemoChip(name: "헤드폰", count: 8), DemoChip(name: "키보드", count: 3), DemoChip(name: "카메라·액션캠", count: 3),
            DemoChip(name: "모니터", count: 2), DemoChip(name: "마우스·트랙패드", count: 2), DemoChip(name: "웨어러블 기기", count: 2),
            DemoChip(name: "이어폰", count: 1), DemoChip(name: "태블릿", count: 1), DemoChip(name: "오디오 케이블·DAC", count: 2),
        ]),
        DemoTopCategory(name: "가구·인테리어", types: [
            DemoChip(name: "조명", count: 3), DemoChip(name: "의자", count: 2), DemoChip(name: "책상·테이블", count: 2),
        ]),
        DemoTopCategory(name: "생활·주방·가전", types: [
            DemoChip(name: "주방 가전", count: 3), DemoChip(name: "공기·온습도 관리", count: 2), DemoChip(name: "조리 도구", count: 1),
        ]),
        DemoTopCategory(name: "스포츠·아웃도어·여행", types: [
            DemoChip(name: "캠핑 용품", count: 4), DemoChip(name: "러닝 용품", count: 2), DemoChip(name: "여행 가방·캐리어", count: 2),
            DemoChip(name: "백패킹 소품", count: 1),
        ]),
        DemoTopCategory(name: "취미·문화·컬렉터블", types: [
            DemoChip(name: "피규어·컬렉터블", count: 2), DemoChip(name: "보드게임·퍼즐", count: 1), DemoChip(name: "레고", count: 0),
        ]),
        DemoTopCategory(name: "건강·웰빙", types: [DemoChip(name: "수면·회복 용품", count: 1)]),
    ]

    static var productCount: Int { categories.flatMap(\.types).map(\.count).reduce(0, +) }

    static func product(_ id: String) -> DemoProduct? {
        (headphones + candidates + homeProducts).first { $0.id == id }
    }

    static func chip(top: String, name: String) -> DemoChip? {
        categories.first { $0.name == top }?.types.first { $0.name == name }
    }

    static func purpose(_ id: String) -> DemoPurpose? { (purposes + [longestPurpose]).first { $0.id == id } }

    /// 목적 상세의 후보(데모: 보드 5개 중 앞에서 개수만큼).
    static func candidates(for purpose: DemoPurpose) -> [DemoProduct] { Array(candidates.prefix(purpose.candidates)) }
}

/// 데모 목적지. 종류마다 탭 바·이동 방식이 다르다(보드: 세부 유형 목록·목적 상세는 탭 바 보임, 상품 상세는 숨김).
enum DemoDestination: Hashable {
    case product(String)
    case categoryList(top: String, chip: String)
    case purpose(String)

    var route: WLRoute {
        switch self {
        case .product: WLRoute(destination: self, showsTabBar: false, pushStyle: .photo)
        case .categoryList: WLRoute(destination: self, showsTabBar: true, pushStyle: .slide)
        case .purpose: WLRoute(destination: self, showsTabBar: true, pushStyle: .slide)
        }
    }
}

/// 사진 대신 쓰는 그림(보드 헤드폰 svg, viewBox 48). 사진처럼 크기만 바뀌고 모양은 같다. 그림이라 접근성 요소가 없다.
struct DemoPhoto: View {
    let art: DemoPhotoArt
    /// 짧은 변 대비 그림 크기(보드 목록 카드 56%).
    var iconScale: CGFloat = 0.56

    var body: some View {
        Canvas { ctx, size in
            let w = min(size.width, size.height) * iconScale
            let k = w / 48
            ctx.translateBy(x: (size.width - w) / 2, y: (size.height - w) / 2)
            ctx.scaleBy(x: k, y: k)
            let line = StrokeStyle(lineWidth: 3, lineCap: .round)
            var band = Path()
            band.move(to: CGPoint(x: 10, y: 30))
            band.addLine(to: CGPoint(x: 10, y: 24))
            band.addArc(center: CGPoint(x: 24, y: 24), radius: 14, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false)
            band.addLine(to: CGPoint(x: 38, y: 30))
            ctx.stroke(band, with: .color(art.stroke), style: line)
            for x in [7.0, 33.0] {
                let cup = Path(roundedRect: CGRect(x: x, y: 28, width: 8, height: 12), cornerRadius: 3)
                ctx.fill(cup, with: .color(art.fill))
                ctx.stroke(cup, with: .color(art.stroke), style: line)
            }
        }
        .background(art.face)
        .accessibilityHidden(true)
    }
}

/// 보드 24 격자 선 아이콘. svg처럼 선 굵기도 격자 단위다. 뒤로 화살표만 둥근 끝·이음(보드 `M15 5l-7 7 7 7`, 명세 6절).
struct DemoLineIcon: View {
    let icon: DemoIcon
    var size: CGFloat = 20
    var lineWidth: CGFloat = 1.8
    var color: Color?

    @Environment(\.wlColors) private var c

    var body: some View {
        let color = color ?? c.text
        Canvas { ctx, sz in
            let k = sz.width / 24
            ctx.scaleBy(x: k, y: k)
            let style = icon == .back
                ? StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
                : StrokeStyle(lineWidth: lineWidth)
            ctx.stroke(icon.path, with: .color(color), style: style)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

extension DemoIcon {
    private static func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

    private static func poly(_ points: [CGPoint], closed: Bool = false) -> Path {
        var path = Path()
        path.addLines(points)
        if closed { path.closeSubpath() }
        return path
    }

    /// 보드 svg path를 그대로 옮긴 선(viewBox 24).
    var path: Path {
        let p = Self.p
        var path = Path()
        switch self {
        case .music:
            path.addPath(Self.poly([p(9, 18), p(9, 6), p(19, 4), p(19, 16)]))
            path.addEllipse(in: CGRect(x: 5, y: 16, width: 4, height: 4))
            path.addEllipse(in: CGRect(x: 15, y: 14, width: 4, height: 4))
        case .star:
            path = Self.poly([p(12, 4), p(14.4, 9), p(20, 9.8), p(16, 13.7), p(17, 19.2), p(12, 16.5),
                              p(7, 19.2), p(8, 13.7), p(4, 9.8), p(9.6, 9)], closed: true)
        case .book:
            path.move(to: p(5, 4))
            path.addLine(to: p(15, 4))
            path.addArc(tangent1End: p(18, 4), tangent2End: p(18, 7), radius: 3)
            path.addLine(to: p(18, 20))
            path.addLine(to: p(8, 20))
            path.addArc(tangent1End: p(5, 20), tangent2End: p(5, 17), radius: 3)
            path.closeSubpath()
        case .tent:
            path.addPath(Self.poly([p(3, 20), p(12, 5), p(21, 20)], closed: true))
            path.addPath(Self.poly([p(12, 5), p(12, 20)]))
        case .home:
            path = Self.poly([p(4, 11), p(12, 4), p(20, 11), p(20, 20), p(4, 20)], closed: true)
        case .gift:
            path.addRoundedRect(in: CGRect(x: 4, y: 9, width: 16, height: 11), cornerSize: CGSize(width: 1, height: 1))
            path.addPath(Self.poly([p(12, 9), p(12, 20)]))
            path.addPath(Self.poly([p(4, 13), p(20, 13)]))
            path.move(to: p(12, 9))
            path.addCurve(to: p(7, 9), control1: p(10, 5), control2: p(6, 6))
            path.move(to: p(12, 9))
            path.addCurve(to: p(17, 9), control1: p(14, 5), control2: p(18, 6))
        case .plane:
            path = Self.poly([p(3, 13), p(21, 6), p(14, 24), p(11, 16)], closed: true)
        case .heart:
            // M12 20s-7-4.5-7-10 a4 4 0 0 1 7-2.6 A4 4 0 0 1 19 10 c0 5.5-7 10-7 10z (호의 중심은 계산 값)
            path.move(to: p(12, 20))
            path.addCurve(to: p(5, 10), control1: p(12, 20), control2: p(5, 15.5))
            path.addArc(center: p(9, 10.045), radius: 4, startAngle: .degrees(180.64), endAngle: .degrees(318.6), clockwise: false)
            path.addArc(center: p(15, 10.045), radius: 4, startAngle: .degrees(221.4), endAngle: .degrees(359.36), clockwise: false)
            path.addCurve(to: p(12, 20), control1: p(19, 15.5), control2: p(12, 20))
            path.closeSubpath()
        case .archive:
            path.addRoundedRect(in: CGRect(x: 3, y: 4, width: 18, height: 5), cornerSize: CGSize(width: 1.5, height: 1.5))
            path.move(to: p(5, 9))
            path.addLine(to: p(5, 19))
            path.addArc(tangent1End: p(5, 20), tangent2End: p(6, 20), radius: 1)
            path.addLine(to: p(18, 20))
            path.addArc(tangent1End: p(19, 20), tangent2End: p(19, 19), radius: 1)
            path.addLine(to: p(19, 9))
            path.addPath(Self.poly([p(10, 13), p(14, 13)]))
        case .external:
            path.addPath(Self.poly([p(14, 4), p(20, 4), p(20, 10)]))
            path.addPath(Self.poly([p(20, 4), p(11, 13)]))
            path.move(to: p(18, 14))
            path.addLine(to: p(18, 19))
            path.addArc(tangent1End: p(18, 20), tangent2End: p(17, 20), radius: 1)
            path.addLine(to: p(5, 20))
            path.addArc(tangent1End: p(4, 20), tangent2End: p(4, 19), radius: 1)
            path.addLine(to: p(4, 7))
            path.addArc(tangent1End: p(4, 6), tangent2End: p(5, 6), radius: 1)
            path.addLine(to: p(10, 6))
        case .plus:
            path.addPath(Self.poly([p(12, 5), p(12, 19)]))
            path.addPath(Self.poly([p(5, 12), p(19, 12)]))
        case .back:
            path = Self.poly([p(15, 5), p(8, 12), p(15, 19)])
        case .more:
            for x in [5.0, 12.0, 19.0] { path.addEllipse(in: CGRect(x: x - 1.5, y: 10.5, width: 3, height: 3)) }
        case .chevronRight:
            path = Self.poly([p(9, 5), p(16, 12), p(9, 19)])
        case .purposeTab:
            path.addRoundedRect(in: CGRect(x: 4, y: 8, width: 16, height: 12), cornerSize: CGSize(width: 3, height: 3))
            path.addPath(Self.poly([p(7, 5), p(17, 5)]))
        case .pending:
            path.addPath(Self.poly([p(12, 4), p(12, 20)]))
            path.addPath(Self.poly([p(4, 12), p(20, 12)]))
            path.addPath(Self.poly([p(6.3, 6.3), p(17.7, 17.7)]))
            path.addPath(Self.poly([p(17.7, 6.3), p(6.3, 17.7)]))
        }
        return path
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

/// 보드 뒤로 화살표(20, 선 1.8, 둥근 끝).
struct BackChevron: View {
    var body: some View { DemoLineIcon(icon: .back) }
}

/// 보드 ⋯(원 3개 r1.5, 선 1.8).
struct MoreDots: View {
    var body: some View { DemoLineIcon(icon: .more) }
}

/// 목적 아이콘 타일(카드색 면 + 선 아이콘). 보드 목적 카드·접힌 띠 36/12·18, 목적 상세 머리 44/14·22.
struct DemoPurposeTile: View {
    let icon: DemoIcon
    var size: CGFloat = 36
    var radius: CGFloat = 12
    var iconSize: CGFloat = 18

    @Environment(\.wlColors) private var c

    var body: some View {
        WLIconTile(size: size, radius: radius, color: c.card) { DemoLineIcon(icon: icon, size: iconSize) }
            .accessibilityHidden(true)
    }
}

extension WLTextStyle {
    /// 보드 13/500 보조 글(부제·섹션 제목·저장 날짜).
    static let demoCaption = WLTextStyle.label.resized(13)
}

/// 탭 첫 화면 머리(명세 7): 도현 28 제목이 위쪽 바(`WLTopBar`, 안전 영역 아래 6부터 높이 56)의 세로 가운데에 선다
/// (제목 윗변 = safeTop + 20). trailing(홈 데모 ⋯)은 바의 trailing 자리. 부제 13/500은 바 바로 아래다.
/// 호출하는 쪽이 안전 영역 안에 두고 좌우 화면 여백을 준다.
struct DemoTabHeader<Trailing: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WLTopBar(sidePadding: 0) {
                EmptyView()
            } center: {
                WLText(title, .display28, maxLines: 1)
                    .accessibilityAddTraits(.isHeader)
            } trailing: {
                trailing()
            }
            WLText(subtitle, .demoCaption, color: c.textSecondary, maxLines: 1)
        }
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

/// 사진 없는 칩 → 세부 유형 목록(가로 밀기).
struct DemoListChip: View {
    let top: String
    let chip: DemoChip
    let sourceKey: String

    @Environment(\.wlNavigator) private var nav

    var body: some View {
        WLChip(text: chip.name, count: chip.count) {
            nav.push(DemoDestination.categoryList(top: top, chip: chip.name).route, sourceKey: sourceKey)
        }
    }
}

/// 2열 사진 카드(보드 FCategoryListL·FPurposeDetailL): 사진(모서리 20) + 8 + 이름 14/1.35 · 4 · 가격 18/700 · 4 · 메타 12/500.
/// 카드 전체를 누르면 상품 상세로 간다(사진 공유 요소 이동).
struct DemoProductCard: View {
    let product: DemoProduct
    let sourceKey: String

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        Button {
            nav.push(DemoDestination.product(product.id).route, sourceKey: sourceKey)
        } label: {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
                WLSharedPhotoSource(key: sourceKey) {
                    DemoPhoto(art: product.art)
                        .aspectRatio(1 / product.photoRatio, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                }
                .overlay(alignment: .topLeading) {
                    if product.pending {
                        WLIconTile(size: 32, radius: WishlistTokens.Radius.xs, color: WishlistTokens.Light.card) {
                            DemoLineIcon(icon: .pending, size: 16, lineWidth: 2, color: WishlistTokens.Light.text)
                        }
                        .padding(WishlistTokens.Space.s8)
                        .accessibilityLabel("분류·목적 미확정")
                    }
                }
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                    WLText(product.fullName, .body)
                    PriceText(amountText: product.price, currency: product.currency)
                    HStack(spacing: 6) {
                        if product.showsDot, let purpose = product.purpose { PurposeDot(color: purpose.color) }
                        WLText(product.meta, .label, color: c.textSecondary, maxLines: 1)
                    }
                }
                .padding(.horizontal, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

#endif
