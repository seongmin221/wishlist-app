package app.wishlist.android.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/** 모든 컴포넌트의 색 원본. 하드코딩 색 없이 이 값만 읽는다(테마가 바뀌면 전체가 다시 그려진다). */
val LocalWLColors = staticCompositionLocalOf<WLColors> { LightColors }

/** 현재 테마가 다크인지(목적 점 테두리처럼 테마별로 달라지는 값용). */
val LocalWLDark = staticCompositionLocalOf { false }

/** 시트·확인창 위인지. 입력칸·보조 버튼이 카드색 대신 `sheetField`를 쓰게 한다(디자인 결정 2026-10-03). */
val LocalWLOnSheet = compositionLocalOf { false }

/** 시트·확인창 안쪽 내용에 `onSheet = true`를 건다. */
@Composable
fun WLOnSheet(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWLOnSheet provides true, content = content)
}

@Composable
fun WLTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    // Material은 기본값(리플·선택 영역 등) 용도로만 아래에 둔다. 컴포넌트 색은 LocalWLColors에서만 온다.
    val scheme = if (darkTheme) {
        darkColorScheme(primary = c.text, onPrimary = c.onInverse, background = c.background,
            onBackground = c.text, surface = c.background, onSurface = c.text)
    } else {
        lightColorScheme(primary = c.text, onPrimary = c.onInverse, background = c.background,
            onBackground = c.text, surface = c.background, onSurface = c.text)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalWLColors provides c, LocalWLDark provides darkTheme, content = content)
    }
}
