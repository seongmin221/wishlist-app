package app.wishlist.android.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.WLDialogSpec
import app.wishlist.android.designsystem.overlay.WLDialogTarget
import app.wishlist.android.designsystem.overlay.WLMenuItem
import app.wishlist.android.designsystem.overlay.rememberWLMenuAnchor
import app.wishlist.android.designsystem.overlay.wlAnchor
import app.wishlist.android.feature.home.resolve
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.ui.ItemDetailRoute
import app.wishlist.android.ui.LocalSubmissionRoute
import app.wishlist.shared.presentation.LocalDetailOutcome
import app.wishlist.shared.presentation.RowStatus
import kotlinx.coroutines.delay

/**
 * 아직 서버에 가지 않은 링크의 상세(D4): 분석 중 틀 + 대기 타일(홈 줄과 같은 대기 이유), 제목 host, 저장 줄, 원본 보기,
 * ⋯에는 삭제만(보내는 중이면 비활성, D17). 삭제는 D8 확인창 뒤에 부른다. 새로고침은 없다(coordinator view 관찰).
 *
 * 끝(outcome): 전송되어 서버 상품이 되면 이 칸을 상품 상세로 교체(`replaceTop`, 전환 중이면 끝난 뒤), 삭제·이유 모를 사라짐은
 * 닫기, 서버가 이미 삭제한 상품이면(D19) "삭제된 상품이에요" + 닫기.
 */
@Composable
internal fun LocalSubmissionScreen(submissionId: String) {
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val storeOwner = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val owner = remember(storeOwner) {
        ViewModelProvider(storeOwner, LocalSubmissionPresenterOwner.factory(runtime))[LocalSubmissionPresenterOwner::class.java]
    }
    val state by owner.state.collectAsState()
    val route = remember(submissionId) { LocalSubmissionRoute(submissionId) }

    LaunchedEffect(owner) { owner.loadOnce(submissionId) }
    LaunchedEffect(owner) {
        while (true) {
            delay(60_000)
            owner.tick()
        }
    }
    val outcome = state.outcome
    LaunchedEffect(outcome) {
        when (outcome) {
            is LocalDetailOutcome.MovedTo -> nav.whenSettled(route) { replaceTop(ItemDetailRoute(outcome.itemId)) }
            LocalDetailOutcome.Deleted, LocalDetailOutcome.Gone -> nav.whenSettled(route) { pop() }
            LocalDetailOutcome.RemovedOnServer, null -> Unit
        }
    }

    val failedText = stringResource(R.string.local_delete_failed)
    // Each failed delete shows the line again (the flag is cleared by the next view).
    val failures = remember(owner) { mutableIntStateOf(0) }
    LaunchedEffect(state.deleteFailed) { if (state.deleteFailed) failures.intValue++ }
    val notice = rememberBriefNotice(failures.intValue.takeIf { it > 0 }, failedText)
    val close = { nav.pop(); Unit }
    val row = state.row
    val removed = outcome == LocalDetailOutcome.RemovedOnServer

    val deleteDialog = row?.let {
        WLDialogSpec(
            title = stringResource(R.string.local_delete_title),
            bullets = listOf(stringResource(R.string.local_delete_line_unsent), stringResource(R.string.webview_clear_line_irreversible)),
            cancelText = stringResource(R.string.dialog_cancel),
            confirmText = stringResource(R.string.local_delete),
            confirmKind = WLButtonKind.Danger,
            onConfirm = owner::delete,
            target = WLDialogTarget(stringResource(R.string.local_delete_target, it.host)) { DeleteThumbnail() },
        )
    }
    val deleteText = stringResource(R.string.local_delete)
    val anchor = rememberWLMenuAnchor()
    val canDelete = state.canDelete && !state.deleting

    DetailScaffold(
        onBack = close,
        originalUrl = row?.sourceUrl?.takeIf { !removed },
        notice = notice,
        more = if (row != null && !removed && outcome == null) {
            {
                WLCircleButton(
                    WLLineIcon.More,
                    stringResource(R.string.detail_more),
                    onClick = {
                        anchor.boundsInWindow()?.let { bounds ->
                            overlay.showMenu(
                                bounds,
                                listOf(
                                    WLMenuItem(deleteText, icon = { WLIcon(WLLineIcon.Trash, size = 18.dp) }, enabled = canDelete) {
                                        // D17: a link that started sending cannot be deleted (it becomes the item detail soon).
                                        if (owner.state.value.canDelete && deleteDialog != null) overlay.showDialog(deleteDialog)
                                    },
                                ),
                            )
                        }
                    },
                    modifier = Modifier.wlAnchor(anchor),
                )
            }
        } else {
            null
        },
    ) {
        when {
            removed -> DetailStatusBlock(stringResource(R.string.detail_not_found), stringResource(R.string.detail_close), close)
            row == null -> WaitingFrame(WLLineIcon.Clock, pendingTile(), "")
            else -> {
                WaitingFrame(
                    if (row.status == RowStatus.LOCAL_ONLY) WLLineIcon.Clock else WLLineIcon.Sorting,
                    pendingTile(),
                    stringResource(DetailText.localTileText(row.status)),
                )
                WaitingBody(row.host) {
                    WLText(DetailText.localSavedText(row.savedAt).resolve(), DetailStyles.caption, color = LocalWLColors.current.textSecondary)
                }
            }
        }
    }
}

/** 대기 타일 색: 보드 홈 "분석 대기"의 대기(pending) 상태 색. */
@Composable
private fun pendingTile() = if (LocalWLDark.current) {
    WishlistTokens.Status.Dark.pendingSurface to WishlistTokens.Status.Dark.pendingIcon
} else {
    WishlistTokens.Status.Light.pendingSurface to WishlistTokens.Status.Light.pendingIcon
}

/** 확인창 썸네일 자리: 44 모서리 10, 시트색 위 대기 아이콘(사진이 아직 없다). */
@Composable
private fun DeleteThumbnail() {
    val c = LocalWLColors.current
    Box(Modifier.size(44.dp)) {
        WLIconTile(size = 44.dp, radius = WishlistTokens.Radius.xs, color = c.sheet) { WLIcon(WLLineIcon.Clock, color = c.textSecondary) }
    }
}
