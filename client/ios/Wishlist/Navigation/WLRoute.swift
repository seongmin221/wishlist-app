import Foundation

/// 하단 탭 3개(디자인 결정 2026-10-01). 순서가 탭 바 칸 순서다(Android `WLTab`과 같다).
enum WLTab: String, CaseIterable, Identifiable {
    case home, category, purpose

    var id: String { rawValue }

    var label: String {
        switch self {
        case .home: "홈"
        case .category: "카테고리"
        case .purpose: "목적"
        }
    }
}

/// 탭 스택에 쌓이는 화면. C1에서는 탭 첫 화면과 데모 상세만 있다(Android `WLRoute`와 같다).
enum WLRoute: Hashable {
    case tabRoot(WLTab)
    /// 데모 상세(debug 빌드에서만 진입). `hasPhoto`면 사진 공유 요소로, 아니면 누른 면(자리 표시)이 커지며 열린다
    /// (motion.md 2절의 두 방식). `id` 앞머리로 상품·칩 목록·목적 상세를 고른다(`DemoIds`).
    case demoDetail(id: String, hasPhoto: Bool)

    /// 이 화면이 맨 위일 때 탭 바를 보이는지.
    var showsTabBar: Bool {
        switch self {
        case .tabRoot: true
        case .demoDetail: false
        }
    }

    var isTabRoot: Bool {
        if case .tabRoot = self { return true }
        return false
    }
}

/// 화면 이동 방식. 경로가 정한다(motion.md 2절).
enum WLPushStyle { case photo, surface }

extension WLRoute {
    var pushStyle: WLPushStyle {
        switch self {
        case .demoDetail(_, let hasPhoto): hasPhoto ? .photo : .surface
        case .tabRoot: .surface
        }
    }
}
