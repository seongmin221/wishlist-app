package app.wishlist.android.feature.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLChevron
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.category.keepAllBreaks
import app.wishlist.android.feature.detail.ProductPhoto
import app.wishlist.android.feature.purpose.purposeCandidateTitleFallback
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomePreview
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.WishlistItem

/** A carousel item's second line: a server value as is, or a wording key. */
sealed interface PreviewCaption {
    data class Text(val text: String) : PreviewCaption
    data class Res(@param:StringRes val res: Int) : PreviewCaption
}

/** FHome todo card wording per HOME-01 group (pure). */
object HomeTodoText {
    @StringRes
    fun title(group: HomeActionGroup): Int = when (group) {
        HomeActionGroup.CLASSIFICATION_REVIEW -> R.string.home_group_review_title
        HomeActionGroup.INFORMATION_COMPLETION -> R.string.home_group_fill_title
        HomeActionGroup.ANALYSIS_IN_PROGRESS -> R.string.home_processing_title
    }

    @StringRes
    fun subtitle(group: HomeActionGroup): Int = when (group) {
        HomeActionGroup.CLASSIFICATION_REVIEW -> R.string.home_group_review_subtitle
        HomeActionGroup.INFORMATION_COMPLETION -> R.string.home_group_fill_subtitle
        HomeActionGroup.ANALYSIS_IN_PROGRESS -> R.string.home_processing_subtitle
    }

    fun icon(group: HomeActionGroup): WLLineIcon = when (group) {
        HomeActionGroup.CLASSIFICATION_REVIEW -> WLLineIcon.Pending
        HomeActionGroup.INFORMATION_COMPLETION -> WLLineIcon.Pen
        HomeActionGroup.ANALYSIS_IN_PROGRESS -> WLLineIcon.Sorting
    }

    /** Review and completion open a continuous-processing flow; 분류 중 has no screen (the card only expands). */
    fun opensFlow(group: HomeActionGroup): Boolean = group != HomeActionGroup.ANALYSIS_IN_PROGRESS

    /** Review: the category (else the host). Completion: why it waits — no / deleted category, else the host. */
    fun caption(item: WishlistItem, group: HomeActionGroup): PreviewCaption {
        val host = PreviewCaption.Text(DisplayFormat.host(item.sourceUrl))
        if (group == HomeActionGroup.CLASSIFICATION_REVIEW) return item.category.name?.let { PreviewCaption.Text(it) } ?: host
        return when (item.requiredAction) {
            RequiredAction.CATEGORY_ASSIGNMENT -> PreviewCaption.Res(R.string.home_preview_no_category)
            RequiredAction.CATEGORY_REASSIGNMENT -> PreviewCaption.Res(R.string.home_preview_category_deleted)
            else -> host
        }
    }
}

/**
 * 할 일 카드(screens.md FHome): 처음에 접혀 있고 오른쪽 화살표가 펼치기(200 `ease`로 180° — [WLChevron]), 펼침 내용은 높이·opacity
 * 200 `ease`(motion.md 작은 동작). [onOpen]이 있으면(분류·목적 확인·정보 보완) 화살표 밖 카드 머리가 연속 처리를 연다. 없으면
 * (분석 대기·분류 중, 갈 화면이 없다) 카드 머리 전체가 펼치기다.
 */
@Composable
internal fun HomeTodoCard(
    icon: WLLineIcon,
    title: String,
    subtitle: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpen: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = LocalWLColors.current
    val state = stringResource(if (expanded) R.string.wl_expanded else R.string.wl_collapsed)
    val toggle = Modifier
        .semantics { stateDescription = state }
        .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.home_todo_collapse else R.string.home_todo_expand)) { onExpandedChange(!expanded) }
    WLCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (onOpen == null) toggle else Modifier)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = WishlistTokens.Space.minTouch)
                        .then(
                            if (onOpen != null) {
                                Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_todo_open_flow), onClick = onOpen)
                            } else {
                                Modifier
                            },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WLIconTile { WLIcon(icon) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        WLText(title, HomeStyles.cardTitle)
                        WLText(subtitle, HomeStyles.cardSubtitle, color = c.textSecondary)
                    }
                    WLText(count.toString(), HomeStyles.count, maxLines = 1)
                }
                Box(
                    Modifier
                        .size(WishlistTokens.Space.minTouch)
                        .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
                        .then(if (onOpen != null) toggle else Modifier),
                    contentAlignment = Alignment.Center,
                ) { WLChevron(expanded) }
            }
            val spec = tween<Float>(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)) + fadeIn(spec),
                exit = shrinkVertically(tween(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)) + fadeOut(spec),
            ) { content() }
        }
    }
}

/** 펼친 줄 목록(분석 대기·분류 중): 좌우·아래 12, 줄 사이 8. */
@Composable
internal fun HomeTodoRows(rows: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
        content = rows,
    )
}

/** 상품 미리보기 캐러셀(가로 스크롤, 좌우·아래 16, 간격 12). 상품을 누르면 그 상품부터 연속 처리를 연다. */
@Composable
internal fun HomeTodoCarousel(group: HomeActionGroup, previews: List<HomePreview>, onOpen: (HomePreview) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        previews.take(4).forEach { preview -> key(preview.item.id) { CarouselItem(group, preview.item) { onOpen(preview) } } }
    }
}

/** 92 폭: 사진 92(모서리 20, 없으면 점선 "사진 없음") · 이름 13/1.35(없으면 C6 규칙의 상태 이름, 보조색) · 11/500 보조 줄. */
@Composable
private fun CarouselItem(group: HomeActionGroup, item: WishlistItem, onClick: () -> Unit) {
    val c = LocalWLColors.current
    val fallback = purposeCandidateTitleFallback(item.product.name, item.analysis.status)
    val name = fallback?.let { stringResource(it) } ?: item.product.name.orEmpty()
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(WishlistTokens.Radius.xs)).clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val shape = RoundedCornerShape(WishlistTokens.Radius.m)
        if (item.product.imageUrl == null) {
            Box(Modifier.size(92.dp).dashed(c.underline), contentAlignment = Alignment.Center) {
                WLText(stringResource(R.string.home_preview_no_photo), WLType.label, color = c.textSecondary)
            }
        } else {
            ProductPhoto(item.product.imageUrl, Modifier.size(92.dp).clip(shape))
        }
        WLText(keepAllBreaks(name), WLType.body.copy(fontSize = 13.sp, lineHeight = 1.35.em), color = if (fallback != null) c.textSecondary else c.text,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val caption = when (val cap = HomeTodoText.caption(item, group)) {
            is PreviewCaption.Text -> cap.text
            is PreviewCaption.Res -> stringResource(cap.res)
        }
        WLText(caption, WLType.label.copy(fontSize = 11.sp), color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 분류 중 미리보기 한 줄(보드 FHome 펼침): 묶음 면 위 52 분류 타일 · host · "상품 정보 추출 중". 누르면 상품 상세(C4).
 * 보드의 삭제는 C8이라 오른쪽 동작이 없다(Ruling 15 유지).
 */
@Composable
internal fun HomeProcessingRow(item: WishlistItem, onClick: () -> Unit) {
    val c = LocalWLColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WishlistTokens.Radius.m))
            .background(c.sheetField)
            .clickable(role = Role.Button, onClickLabel = stringResource(HomeRowText.openDetail), onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(size = 52.dp, color = c.card) { WLIcon(WLLineIcon.Sorting) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WLText(DisplayFormat.host(item.sourceUrl), WLType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WLText(stringResource(R.string.row_processing), WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 1.5 점선 테두리(보드 `1.5px dashed #BDBDBD`, 모서리 20). */
private fun Modifier.dashed(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(WishlistTokens.Radius.m.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
