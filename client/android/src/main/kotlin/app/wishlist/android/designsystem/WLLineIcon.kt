package app.wishlist.android.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** SVG 원(circle)을 path 문자열로(호 두 개). */
private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/**
 * 기능 화면(C3 로그인·홈·설정·공유 카드)의 보드 24 격자 선 아이콘(fill 없음, stroke = 글자색). `stroke`는 보드 SVG의
 * stroke-width(24 격자 기준)다. 둥근 끝은 뒤로(디자인 수정 명세 §6)와 점이 있는 경고에만 쓰고 나머지는 SVG 기본값(butt·miter)이다.
 * debug 데모의 `DemoIcon`과 같은 방식이다.
 */
enum class WLLineIcon(private vararg val d: String, val stroke: Float = 1.8f, val round: Boolean = false) {
    Back("M15 5l-7 7 7 7", round = true),
    ChevronRight("M9 5l7 7-7 7"),
    Settings(circle(12f, 12f, 3f), "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"),
    Person(circle(12f, 8f, 4f), "M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6"),
    Clock(circle(12f, 12f, 9f), "M12 7v5l3 2"),
    Sorting("M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1", stroke = 2f),
    External("M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"),
    Heart("M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"),
    Check("M5 12l5 5L20 7"),
    CheckBold("M5 12l5 5L20 7", stroke = 2f),
    ClockBold(circle(12f, 12f, 9f), "M12 7v5l3 2", stroke = 2f),
    CloudOff("M7 18h10a4 4 0 0 0 1.6-.3M20.5 13.5A4 4 0 0 0 17.5 10 6 6 0 0 0 9 5.6M5.7 9.7A4.5 4.5 0 0 0 7 18M3 3l18 18", stroke = 2f),
    Warning("M12 4l9 16H3z", "M12 10v4M12 17h.01", stroke = 2f, round = true),
    More(circle(5f, 12f, 1.5f), circle(12f, 12f, 1.5f), circle(19f, 12f, 1.5f)),
    Trash("M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13");

    // 처음 그릴 때 만든다(JVM 단위 테스트에서 enum 값만 다룰 때 android Path를 만들지 않는다).
    internal val path by lazy { PathParser().parsePathString(d.joinToString("")).toPath() }
}

/** 선 아이콘 하나를 `size` 크기로 그린다(보드 SVG와 같은 path). 장식용이라 접근성 설명은 부모가 붙인다. */
@Composable
fun WLIcon(icon: WLLineIcon, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = LocalWLColors.current.text) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val stroke = if (icon.round) {
            Stroke(width = icon.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        } else {
            Stroke(width = icon.stroke)
        }
        scale(k, k, pivot = Offset.Zero) { drawPath(icon.path, color, style = stroke) }
    }
}
