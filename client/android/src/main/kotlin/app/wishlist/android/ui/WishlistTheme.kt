package app.wishlist.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D1D1D),
    onPrimary = Color.White,
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF1D1D1D),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF1D1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F3F0),
    onPrimary = Color(0xFF1D1D1D),
    background = Color(0xFF1D1D1D),
    onBackground = Color(0xFFF4F3F0),
    surface = Color(0xFF1D1D1D),
    onSurface = Color(0xFFF4F3F0),
)

@Composable
fun WishlistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
