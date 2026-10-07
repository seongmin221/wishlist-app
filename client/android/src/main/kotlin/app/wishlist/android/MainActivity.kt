package app.wishlist.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import app.wishlist.android.feature.home.HomePresenterOwner
import app.wishlist.android.feature.session.AccountPresenterOwner
import app.wishlist.android.ui.WishlistApp

/**
 * 시스템 테마(uiMode)가 바뀌어도 Activity를 다시 만들지 않는다(manifest `configChanges="uiMode"`).
 * 색은 `isSystemInDarkTheme()`로 바로 바뀌고, 탭 스택·열린 시트 같은 화면 상태가 유지된다.
 * 로그인 상태·홈 목록 Presenter의 owner(ViewModel)는 이 Activity의 ViewModelStore에 둔다(구성 변경 동안 유지, 끝나면 close).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as WishlistApplication).runtime
        val account = ViewModelProvider(this, AccountPresenterOwner.factory(runtime))[AccountPresenterOwner::class.java]
        val home = ViewModelProvider(this, HomePresenterOwner.factory(runtime))[HomePresenterOwner::class.java]
        setContent { WishlistApp(account, home) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 상태 바·내비게이션 바 아이콘 색을 새 테마에 맞춘다.
        enableEdgeToEdge()
    }
}
