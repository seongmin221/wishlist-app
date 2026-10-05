package app.wishlist.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.wishlist.android.ui.WishlistApp

/**
 * 시스템 테마(uiMode)가 바뀌어도 Activity를 다시 만들지 않는다(manifest `configChanges="uiMode"`).
 * 색은 `isSystemInDarkTheme()`로 바로 바뀌고, 탭 스택·열린 시트 같은 화면 상태가 유지된다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WishlistApp() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 상태 바·내비게이션 바 아이콘 색을 새 테마에 맞춘다.
        enableEdgeToEdge()
    }
}
