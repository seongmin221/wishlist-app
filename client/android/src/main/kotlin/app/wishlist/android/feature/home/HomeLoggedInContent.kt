package app.wishlist.android.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wishlist.android.R
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.shared.presentation.HomeState

/**
 * FHome(C3-D3): "분류 중" 카드 하나. 이 계정의 미전송(보내는 중·연결되면 보내요·보낼 수 없는 링크)과 서버에서 분석 중인
 * 항목을 한 목록으로 보여 주고, 줄이 없으면 할 일 섹션을 숨긴다. 줄에는 오른쪽 동작이 없다(Ruling 15: 보드의 삭제는
 * C4/C8). 줄을 누르면 분석 중 항목은 상품 상세, 미전송 줄은 로컬 대기 화면으로 간다(C4, `HomeRow.target`). 줄 key는 전송이 끝나면 `local-…` → `item-…`로 바뀌는
 * 불투명 값이라 목록 key로만 쓴다.
 */
@Composable
internal fun HomeLoggedInContent(state: HomeState.LoggedIn) {
    if (state.processing.isEmpty()) return
    HomeSection(stringResource(R.string.home_todo)) {
        var expanded by rememberExpanded("home/processing")
        HomeTodoCard(
            icon = WLLineIcon.Sorting,
            title = stringResource(R.string.home_processing_title),
            subtitle = stringResource(R.string.home_processing_subtitle),
            count = state.processing.size,
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            state.processing.forEach { row -> key(row.key) { HomeLinkRow(row, WLLineIcon.Sorting, tileSize = 52.dp) } }
        }
    }
}
