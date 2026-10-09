package app.wishlist.android.share

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.WLTheme
import app.wishlist.android.platform.NetworkSignals
import app.wishlist.shared.submission.ShareCardKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `ACTION_SEND` text/plain 수신(투명 테마 `Theme.Wishlist.ShareCard`, 전환 애니메이션 없음). 같은 프로세스의 runtime으로
 * 바로 저장·전송하고(C3-D1 Android), 저장 결과로 카드 문구를 정한 뒤(C3-D9) 카드만 올렸다 내리고 닫는다.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // Restored after process death: the share was already received (and saved) by the killed
        // instance; receiving again would add a duplicate row under a new key. A configuration
        // recreation keeps its ViewModel (the running receive) and carries on.
        if (!ShareLaunch.shouldReceive(restored = savedInstanceState != null, modelRetained = lastNonConfigurationInstance != null)) {
            close()
            return
        }
        val app = application as WishlistApplication
        val factory = ShareReceiveModel.factory(app, ShareIntentText.from(intent), NetworkSignals.isOnline(this))
        val model = ViewModelProvider(this, factory)[ShareReceiveModel::class.java]
        setContent {
            WLTheme {
                val kind by model.kind.collectAsState()
                ShareCardHost(kind, onFinished = ::close)
            }
        }
    }

    private fun close() {
        finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

/**
 * One share's receive, started once per Activity instance (kept across configuration changes).
 * The save runs in the Application scope, not this ViewModel's: leaving the card early (back,
 * home, `noHistory`) never cancels a save in progress. receive waits for the runtime at most
 * 1500ms; after that (or when the store fails) the share goes to the app's [ShareInbox] file
 * (DEFERRED card) and is imported once the runtime is ready. A local save itself takes milliseconds.
 */
class ShareReceiveModel(app: WishlistApplication, text: String?, online: Boolean) : ViewModel() {
    private val mutableKind = MutableStateFlow<ShareCardKind?>(null)
    val kind: StateFlow<ShareCardKind?> = mutableKind.asStateFlow()

    init {
        val submissions = app.runtime.submissions()
        app.appScope.launch {
            val kind = submissions.receiveShared(text, online) { app.shareInbox.write(it) }
            mutableKind.value = kind
            if (kind == ShareCardKind.DEFERRED) app.importShareInbox()
        }
    }

    companion object {
        fun factory(app: WishlistApplication, text: String?, online: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShareReceiveModel(app, text, online) }
        }
    }
}

/** Whether this Activity instance should receive its share (pure, for JVM tests). */
internal object ShareLaunch {
    /**
     * [restored]: `savedInstanceState != null`. [modelRetained]: the ViewModelStore survived (a configuration
     * recreation, not process death). Only a restore without a retained model skips the receive.
     */
    fun shouldReceive(restored: Boolean, modelRetained: Boolean): Boolean = !restored || modelRetained
}
