import SwiftUI
import UIKit
import XCTest
@testable import Wishlist

/// `WLText`가 만든 글자 상자를 실제 레이아웃·렌더링으로 잰다(기본 글자 크기 = 배율 1).
/// 한 줄 상자는 줄 높이와 같고, N줄 상자는 N × 줄 높이, k번째 줄 글자는 첫 줄 + k × 줄 높이에 있어야 한다
/// (보드 CSS line-height, Android `wlLineBox`와 같음). 글자는 줄 안 가운데(CSS half-leading)다.
@MainActor
final class WLTypographyTests: XCTestCase {
    private let width: CGFloat = 200

    private func host<V: View>(_ v: V) -> UIHostingController<AnyView> {
        let h = UIHostingController(rootView: AnyView(v))
        h.safeAreaRegions = []
        return h
    }

    private func height<V: View>(_ v: V) -> CGFloat {
        host(v.frame(width: width, alignment: .leading).fixedSize(horizontal: false, vertical: true))
            .sizeThatFits(in: CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)).height
    }

    /// 줄마다 잉크의 [위, 아래](상자 위 기준 pt). UILabel도 찍히도록 창에 올려 drawHierarchy로 그린다.
    /// 상자 위 11pt에 1pt 표시 막대를 두어 상자 위치를 정확히 잡는다(글자가 상자 위로 나와도 막대와 붙지 않게 10pt 띄움).
    private func inkLines<V: View>(_ v: V) -> [(top: CGFloat, bottom: CGFloat)] {
        let pad: CGFloat = 30
        let h = host(VStack(spacing: 0) {
            Color.black.frame(width: width, height: 1)
            Color.clear.frame(height: 10)
            v.foregroundStyle(.black).frame(width: width, alignment: .leading).fixedSize(horizontal: false, vertical: true)
        }.padding(pad))
        h.view.backgroundColor = .clear
        let size = h.sizeThatFits(in: CGSize(width: width + 2 * pad, height: CGFloat.greatestFiniteMagnitude))
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.backgroundColor = .clear
        window.rootViewController = h
        window.isHidden = false
        defer { window.isHidden = true }
        h.view.frame = window.bounds
        h.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 4
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            _ = h.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
        let cg = image.cgImage!
        let w = cg.width, rows = cg.height
        var data = [UInt8](repeating: 0, count: w * rows * 4)
        let ctx = CGContext(data: &data, width: w, height: rows, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: rows))
        var runs: [(CGFloat, CGFloat)] = []
        var start = -1
        for y in 0...rows { // 비트맵 메모리는 맨 위 행부터다
            let ink = y < rows && (0..<w).contains { data[(y * w + $0) * 4 + 3] > 128 }
            if ink && start < 0 { start = y }
            if !ink && start >= 0 { runs.append((CGFloat(start) / 4, CGFloat(y) / 4)); start = -1 }
        }
        guard let marker = runs.first else { return [] }
        let boxTop = marker.1 + 10
        return runs.dropFirst().map { (top: $0.0 - boxTop, bottom: $0.1 - boxTop) }
    }

    func testSingleLineBoxesMatchLineHeight() {
        for limited in [false, true] {
            func h(_ t: String, _ s: WLTextStyle) -> CGFloat { height(WLText(t, s).lineLimit(limited ? 1 : nil)) }
            XCTAssertEqual(h("가격", .display28Edit), 24, accuracy: 0.5)
            XCTAssertEqual(h("가격", .display28), 28, accuracy: 0.5)
            XCTAssertEqual(h("KRW 1,190,000", .price), 18, accuracy: 0.5)
            XCTAssertEqual(h("상품", .body), 14 * 1.35, accuracy: 0.5)
        }
    }

    func testThreeLineBoxesAreThreeLineHeights() {
        let three = "가나다\n가나다\n가나다"
        let styles: [(String, WLTextStyle)] = [("body", .body), ("label", .label), ("title", .title), ("button", .button),
                                               ("display28TwoLine", .display28TwoLine), ("display20TwoLine", .display20TwoLine)]
        for (name, style) in styles {
            let h = height(WLText(three, style))
            print("WLTYPO \(name) 3-line h=\(h) expected=\(3 * style.lineHeight)")
            XCTAssertEqual(h, 3 * style.lineHeight, accuracy: 1, name)
        }
    }

    func testWrappedTextIsLineCountTimesLineHeight() {
        let long = "주말 캠핑용 가벼운 의자와 테이블 세트 고르기 주말 캠핑용 가벼운 의자와 테이블 세트 고르기"
        let h = height(WLText(long, .body))
        let lines = (h / WLTextStyle.body.lineHeight).rounded()
        XCTAssertGreaterThanOrEqual(lines, 2)
        XCTAssertEqual(h, lines * WLTextStyle.body.lineHeight, accuracy: 1)
        XCTAssertEqual(height(WLText(long, .body).lineLimit(2)), 2 * WLTextStyle.body.lineHeight, accuracy: 1)
    }

    /// 1–3번째 줄 글자 위치: 한 줄 padding 방식(Task 2에서 밑줄 3pt를 잰 기준)의 첫 줄 위치 + k × 줄 높이.
    /// 허용 0.65pt = 줄 위치 픽셀 맞춤(3x에서 1/3pt) + 측정 단위(1/4pt). 이전 lineSpacing 방식은 3번째 줄에서 4–9pt 어긋났다.
    func testGlyphsStayCenteredOnEveryLine() {
        let styles: [WLTextStyle] = [.body, .title, .button, .label, .price, .display28, .display28Edit, .display28TwoLine]
        for style in styles {
            let reference = inkLines(Text("H").wlText(style))
            let actual = inkLines(WLText("H\nH\nH", style))
            XCTAssertEqual(reference.count, 1, style.postScriptName)
            XCTAssertEqual(actual.count, 3, style.postScriptName)
            guard let ref = reference.first, actual.count == 3 else { continue }
            for (k, line) in actual.enumerated() {
                let offset = CGFloat(k) * style.lineHeight
                print("WLTYPO \(style.postScriptName) \(style.lineHeight) line\(k + 1) top=\(line.top) expected=\(ref.top + offset)")
                XCTAssertEqual(line.top, ref.top + offset, accuracy: 0.65, "\(style.postScriptName) line \(k + 1)")
                XCTAssertEqual(line.bottom, ref.bottom + offset, accuracy: 0.65, "\(style.postScriptName) line \(k + 1)")
            }
        }
    }

    /// 줄 높이가 글꼴보다 작은 스타일에서 한글·g 꼬리 잉크가 상자 밖으로 나와도 잘리지 않는다(이전 SwiftUI `Text`와 같은 범위).
    func testInkOutsideLineBoxIsNotClipped() {
        for style in [WLTextStyle.display28Edit, .display28, .price, .body] {
            for sample in ["가", "g", "가g"] {
                let reference = inkLines(Text(sample).wlText(style))
                let actual = inkLines(WLText(sample, style))
                print("WLTYPO clip \(style.postScriptName) \(style.lineHeight) '\(sample)' ref=\(reference) actual=\(actual)")
                XCTAssertEqual(actual.count, 1, "\(style.postScriptName) \(sample)")
                guard let r = reference.first, let a = actual.first else { continue }
                XCTAssertEqual(a.top, r.top, accuracy: 0.5, "\(style.postScriptName) \(sample) top")
                XCTAssertEqual(a.bottom, r.bottom, accuracy: 0.5, "\(style.postScriptName) \(sample) bottom")
            }
        }
    }

    private func accessibilityElements(_ o: NSObject, _ out: inout [NSObject], depth: Int = 0) {
        if depth > 30 { return }
        if o.isAccessibilityElement { out.append(o) }
        if let els = o.accessibilityElements as? [NSObject] {
            els.forEach { accessibilityElements($0, &out, depth: depth + 1) }
        } else if o.accessibilityElementCount() != NSNotFound, o.accessibilityElementCount() > 0 {
            for i in 0..<o.accessibilityElementCount() {
                if let e = o.accessibilityElement(at: i) as? NSObject { accessibilityElements(e, &out, depth: depth + 1) }
            }
        }
        if let v = o as? UIView, !v.accessibilityElementsHidden { v.subviews.forEach { accessibilityElements($0, &out, depth: depth + 1) } }
    }

    /// 여러 줄 경로(UILabel)도 VoiceOver에는 정적 글자 하나로 보이고(UILabel 자체는 빠짐), 바깥 trait·숨김이 먹는다.
    func testMultilineTextReadsAsOneStaticText() {
        let h = host(VStack {
            WLText("제목 글자", .title).accessibilityAddTraits(.isHeader)
            WLText("숨김", .body).accessibilityHidden(true)
            WLText("본문 글자", .body)
            WLText("한 줄", .body).lineLimit(1)
        })
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 300, height: 300)
        window.rootViewController = h
        window.isHidden = false
        defer { window.isHidden = true }
        h.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        var found: [NSObject] = []
        accessibilityElements(h.view, &found)
        XCTAssertEqual(found.map { $0.accessibilityLabel ?? "" }, ["제목 글자", "본문 글자", "한 줄"])
        XCTAssertFalse(found.contains { $0 is UILabel })
        XCTAssertTrue(found[0].accessibilityTraits.contains(.header))
        XCTAssertTrue(found.allSatisfy { $0.accessibilityTraits.contains(.staticText) })
    }
}
