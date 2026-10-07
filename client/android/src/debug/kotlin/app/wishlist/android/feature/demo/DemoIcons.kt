package app.wishlist.android.feature.demo

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
import app.wishlist.android.designsystem.LocalWLColors

/** SVG 원(circle)을 path 문자열로(호 두 개). */
private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** SVG 모서리 둥근 사각형(rect rx)을 path 문자열로. */
private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
    "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}" +
        "a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

/**
 * 보드의 24 격자 선 아이콘(fill 없음, stroke = currentColor). `stroke`는 24 격자 기준 선 두께다.
 * 뒤로는 디자인 수정 명세 §6에 따라 하나의 path에 둥근 끝·이음을 쓴다. 나머지는 SVG 기본값(butt·miter)이다.
 */
internal enum class DemoIcon(vararg d: String, val stroke: Float = 1.8f, val round: Boolean = false) {
    Back("M15 5l-7 7 7 7", round = true),
    More(circle(5f, 12f, 1.5f), circle(12f, 12f, 1.5f), circle(19f, 12f, 1.5f)),
    Plus("M12 5v14M5 12h14", stroke = 2f),
    ChevronRight("M9 5l7 7-7 7"),
    Archive(rect(3f, 4f, 18f, 5f, 1.5f), "M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"),
    External("M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"),
    PurposeTab(rect(4f, 8f, 16f, 12f, 3f), "M7 5h10"),
    Pending("M12 4v16M4 12h16M6.3 6.3l11.4 11.4M17.7 6.3L6.3 17.7", stroke = 2f),

    // 목적 아이콘(FPurposeHomeL `k_i_*`).
    Music("M9 18V6l10-2v12", circle(7f, 18f, 2f), circle(17f, 16f, 2f)),
    Star("M12 4l2.4 5 5.6.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.6-.8z"),
    Book("M5 4h10a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"),
    Tent("M3 20L12 5l9 15zM12 5v15"),
    Home("M4 11l8-7 8 7v9H4z"),
    Gift(rect(4f, 9f, 16f, 11f, 1f), "M12 9v11M4 13h16M12 9c-2-4-6-3-5 0M12 9c2-4 6-3 5 0"),
    Plane("M3 13l18-7-7 18-3-8z"),
    Heart("M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z");

    val path = PathParser().parsePathString(d.joinToString("")).toPath()
}

/** 선 아이콘 하나를 `size` 크기로 그린다(보드 SVG와 같은 path 하나). */
@Composable
internal fun DemoIconView(icon: DemoIcon, size: Dp = 20.dp, color: Color = LocalWLColors.current.text, modifier: Modifier = Modifier) {
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
