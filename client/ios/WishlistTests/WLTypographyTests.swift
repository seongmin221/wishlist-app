import SwiftUI
import XCTest
@testable import Wishlist

/// `wlText`가 만든 글자 상자 높이를 실제 레이아웃으로 잰다(기본 글자 크기 = 배율 1).
/// 한 줄 상자는 줄 높이와 같고, N줄 상자는 N × 줄 높이여야 한다(보드 CSS line-height, Android `wlLineBox`와 같음).
@MainActor
final class WLTypographyTests: XCTestCase {
    private func height(_ text: String, _ style: WLTextStyle) -> CGFloat {
        let host = UIHostingController(rootView: Text(text).wlText(style).fixedSize(horizontal: false, vertical: true))
        return host.sizeThatFits(in: CGSize(width: 320, height: CGFloat.greatestFiniteMagnitude)).height
    }

    private let accuracy: CGFloat = 0.5

    func testSingleLineBoxesMatchLineHeight() {
        XCTAssertEqual(height("가격", .display28Edit), 24, accuracy: accuracy)
        XCTAssertEqual(height("가격", .display28), 28, accuracy: accuracy)
        XCTAssertEqual(height("KRW 1,190,000", .price), 18, accuracy: accuracy)
        XCTAssertEqual(height("상품", .body), 14 * 1.35, accuracy: accuracy)
    }

    func testThreeLinePlexBoxesAreThreeLineHeights() {
        let three = "가나다\n가나다\n가나다"
        for (name, style) in [("body", WLTextStyle.body), ("label", .label), ("title", .title), ("button", .button)] {
            let h = height(three, style)
            print("WLTYPO \(name) 3-line h=\(h) expected=\(3 * style.lineHeight)")
            XCTAssertEqual(h, 3 * style.lineHeight, accuracy: 1, name)
        }
    }

    func testThreeLineDisplayBoxes() {
        let three = "가나다\n가나다\n가나다"
        for (name, style) in [("display28TwoLine", WLTextStyle.display28TwoLine), ("display20TwoLine", .display20TwoLine)] {
            let h = height(three, style)
            print("WLTYPO \(name) 3-line h=\(h) expected=\(3 * style.lineHeight)")
            XCTAssertEqual(h, 3 * style.lineHeight, accuracy: 1, name)
        }
    }

    /// 잉크(글자 픽셀)의 맨 위·맨 아래(pt). 렌더러는 상자 밖을 자르므로 상자 안 위치만 비교한다.
    private func ink<V: View>(_ v: V) -> (top: CGFloat, bottom: CGFloat) {
        let r = ImageRenderer(content: v.foregroundStyle(.black).frame(width: 200, alignment: .leading).fixedSize(horizontal: false, vertical: true))
        r.scale = 4
        guard let cg = r.cgImage else { return (-1, -1) }
        let w = cg.width, h = cg.height
        var data = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &data, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var rows: [Int] = []
        for y in 0..<h where (0..<w).contains(where: { data[(y * w + $0) * 4 + 3] > 128 }) { rows.append(y) }
        guard let lo = rows.first, let hi = rows.last else { return (-1, -1) }
        return (CGFloat(lo) / 4, CGFloat(hi + 1) / 4) // 비트맵 메모리는 맨 위 행부터다
    }

    /// 글자는 줄 상자 안 가운데(CSS half-leading)에 있어야 한다: 한 줄 패딩 방식(Task 2에서 밑줄 3pt를 잰 기준)과 같은 자리.
    func testGlyphStaysCenteredInLineBox() {
        for style in [WLTextStyle.body, .title, .display28, .display28Edit, .display28TwoLine] {
            let natural = UIFont(name: style.postScriptName, size: style.size)!.lineHeight
            let reference = ink(Text("가").font(style.font).padding(.vertical, (style.lineHeight - natural) / 2))
            let actual = ink(Text("가").wlText(style))
            XCTAssertEqual(actual.top, reference.top, accuracy: 0.5, style.postScriptName)
            XCTAssertEqual(actual.bottom, reference.bottom, accuracy: 0.5, style.postScriptName)
        }
    }
}
