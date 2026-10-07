import SwiftUI

#if DEBUG

/// 카테고리 탭 데모(FCategoryHomeL, 사용자 결정 2026-10-07로 동작 변경): 왼쪽 상위 레일 + 오른쪽 세로 페이저.
/// - 오른쪽은 상위 하나가 한 페이지다(섹션 제목 + 칩 + 점선 `+ 추가` 하나). 아래로 스크롤하면 다음 상위, 위로 스크롤하면 이전
///   상위로 넘어가고, 손을 놓으면 가까운 페이지로 스냅한다(`.paging`). 첫·마지막 페이지 밖으로는 넘어가지 않는다.
/// - 페이지 높이 = 오른쪽 영역 높이. 내용은 위에 붙고 아래 여백은 탭 바 여백(보드 120) 이상이다. 큰 글자로 한 페이지가
///   넘치면 그 페이지 안에서 먼저 스크롤한다.
/// - 레일은 현재 페이지를 보이고, 누르면 그 페이지로 300 `emphasized`로 이동한다. 이동 중에는 목표를 선택 상태로 고정해
///   사이 페이지로 깜빡이지 않는다. Reduce Motion이면 바로 바꾼다.
/// - 현재 탭 재선택 → 첫 페이지. 마지막 페이지는 탭 전환·상세 다녀오기 뒤에도 남는다(스택 칸이 살아 있다).
/// - 칩 → 세부 유형 목록은 가로 밀기다.
struct DemoCategoryScreen: View {
    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var navigator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// 지금 페이지(`scrollPosition`). 사용자가 넘기면 바뀐다.
    @State private var page: Int? = 0
    /// 레일·탭 재선택으로 시작한 이동의 목표(이동 중 선택 고정).
    @State private var pinned: Int?
    @State private var pinSerial = 0

    private let categories = DemoContent.categories

    /// 보드 값: 레일 폭 124·padding 8/120, 페이지 padding 8/16/120/8.
    private enum Layout {
        static let railWidth: CGFloat = 124
        static let pageTop: CGFloat = 8
        static let bottomPadding: CGFloat = 120
    }

    private func railID(_ i: Int) -> String { "rail-\(i)" }

    private var selected: Int { pinned ?? page ?? 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DemoTabHeader(title: "카테고리", subtitle: "상품 \(DemoContent.productCount)개")
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .padding(.bottom, WishlistTokens.Space.s16)
            Rectangle().fill(c.line).frame(height: 1)
            HStack(alignment: .top, spacing: 0) {
                rail { go(to: $0) }
                pager
            }
            .ignoresSafeArea(.container, edges: .bottom)
        }
        .onChange(of: navigator.scrollToTopRequest) { _, request in
            guard request == .category else { return }
            go(to: 0)
            navigator.consumeScrollToTop(.category)
        }
    }

    // MARK: 레일

    private func rail(onPick: @escaping (Int) -> Void) -> some View {
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(categories.enumerated()), id: \.offset) { i, top in
                        railRow(top, on: i == selected) { onPick(i) }.id(railID(i))
                    }
                }
                .padding(.top, Layout.pageTop)
                .padding(.bottom, Layout.bottomPadding)
            }
            .frame(width: Layout.railWidth)
            .onChange(of: selected) { _, i in
                // 선택 항목이 레일 화면 밖이면 보이게 스크롤한다. 레일 아래는 탭 바가 덮으므로 가운데로 맞춘다
                // (레일이 화면에 다 들어가면 스크롤 범위가 없어 움직이지 않는다).
                withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: WishlistTokens.Motion.headerCollapseScroll)) {
                    proxy.scrollTo(railID(i), anchor: .center)
                }
            }
        }
    }

    private func railRow(_ top: DemoTopCategory, on: Bool, action: @escaping () -> Void) -> some View {
        // 보드: 15 · line-height 1.3, 선택 = 왼쪽 3 먹색 막대 + 700(padding 8/10/8/17), 비선택 = 보조색(padding 8/10/8/20).
        let style = (on ? WLTextStyle.bodyBold : WLTextStyle.body).resized(15, lineHeight: 15 * 1.3)
        return Button(action: action) {
            HStack(spacing: 0) {
                Rectangle().fill(on ? c.text : Color.clear).frame(width: 3)
                WLText(top.railLabel, style, color: on ? c.text : c.textSecondary)
                    .padding(.leading, 17)
                    .padding(.trailing, 10)
                    .padding(.vertical, WishlistTokens.Space.s8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(top.name)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }

    // MARK: 페이지

    private var pager: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(spacing: 0) {
                ForEach(Array(categories.enumerated()), id: \.offset) { i, top in
                    DemoCategoryPage(top: top)
                        .containerRelativeFrame(.vertical, alignment: .top)
                        .id(i)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $page, anchor: .top)
        .frame(maxWidth: .infinity)
    }

    /// 레일·탭 재선택: 목표를 고정하고 그 페이지로 이동한 뒤 고정을 푼다.
    private func go(to i: Int) {
        pinSerial += 1
        let serial = pinSerial
        pinned = i
        let release = { if pinSerial == serial { pinned = nil } }
        if reduceMotion {
            page = i
            DispatchQueue.main.async(execute: release)
            return
        }
        withAnimation(WishlistTokens.Curve.emphasized.animation(ms: WishlistTokens.Motion.headerCollapseScroll)) {
            page = i
        } completion: {
            release()
        }
    }
}

/// 상위 하나의 페이지: 섹션 제목(min-height 44, 13/500) + 8 + 칩(간격 8) + 점선 `+ 추가`. padding 8/16/120/8.
/// 큰 글자로 페이지보다 길어지면 페이지 안에서 스크롤한다(끝에서 다시 끌면 다음 페이지).
private struct DemoCategoryPage: View {
    let top: DemoTopCategory

    @Environment(\.wlColors) private var c

    var body: some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content }.scrollBounceBehavior(.basedOnSize)
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            WLText(top.name, .demoCaption, color: c.textSecondary, maxLines: 1)
                .frame(minHeight: 44, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            DemoFlowLayout {
                ForEach(top.types, id: \.name) { chip in
                    DemoListChip(top: top.name, chip: chip, sourceKey: "category/\(top.name)/\(chip.name)")
                }
                WLAddChip(text: "추가") {}
            }
        }
        .padding(.top, 8)
        .padding(.leading, WishlistTokens.Space.s8)
        .padding(.trailing, WishlistTokens.Space.s16)
        .padding(.bottom, 120)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}

#endif
