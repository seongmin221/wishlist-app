package app.wishlist.android.feature.purpose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.wishlist.android.R
import app.wishlist.android.designsystem.*
import app.wishlist.android.feature.category.tabBarClearance
import app.wishlist.shared.model.PurposeDetail
import app.wishlist.shared.presentation.PurposeFormPresenter
import app.wishlist.shared.presentation.PurposeFormState
import kotlin.math.roundToInt

private const val TileCollapsedScale = 36f / 44f
private const val NameCollapsedScale = 20f / 28f
private class HeaderAnchors {
    var origin by mutableStateOf(Offset.Zero)
    var width by mutableFloatStateOf(0f)
    var topRowCenter by mutableFloatStateOf(0f)
    var tileRoot by mutableStateOf(Offset.Zero)
    var nameRoot by mutableStateOf(Offset.Zero)
    var nameHeight by mutableFloatStateOf(0f)
    val topRowCenterY get() = topRowCenter - origin.y
    val tile get() = tileRoot - origin
    val name get() = nameRoot - origin
}

/** Measure the resting header once; transforms read sheet progress during drawing. */
@Suppress("ModifierParameter") // This modifier belongs to the menu anchor, not the header container.
@Composable
internal fun PurposeHeader(
    p: PurposeDetail, form: PurposeFormPresenter?, edit: PurposeFormState?, progress: () -> Float,
    onBack: () -> Unit, onMenu: () -> Unit, menuModifier: Modifier, onCancel: () -> Unit,
) {
    val c = LocalWLColors.current
    val on = WishlistTokens.Purpose.onPurpose
    val density = LocalDensity.current
    val anchors = remember { HeaderAnchors() }
    val faded by remember { derivedStateOf { progress() >= 0.5f } }
    val margin = with(density) { 20.dp.toPx() }
    val button = with(density) { 44.dp.toPx() }
    val gap10 = with(density) { 10.dp.toPx() }
    val tileTargetX = margin + button + gap10
    val nameTargetX = tileTargetX + with(density) { 44.dp.toPx() }
    val fadeOut = { (1f - progress() / 0.5f).coerceIn(0f, 1f) }
    val fields = if (form != null) rememberPurposeFields(form) else null
    val barHalf = with(density) { (WLTopBarMetrics.Height / 2).toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val bottomClearance = maxOf(tabBarClearance(), WindowInsets.ime.asPaddingValues().calculateBottomPadding())
    val editorMaxHeight = (maxHeight - wlTopBarBottom() - bottomClearance).coerceAtLeast(200.dp)
    Column(Modifier.fillMaxWidth().onPlaced { anchors.origin = it.positionInRoot(); anchors.width = it.size.width.toFloat() }) {
        WLTopBar(
            modifier = Modifier.onPlaced { anchors.topRowCenter = it.positionInRoot().y + it.size.height - barHalf },
            leading = { WLCircleButton(WLLineIcon.Back, stringResource(R.string.category_back), onBack) },
            trailing = if (form == null) { {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.graphicsLayer { alpha = ((progress() - 0.5f) / 0.5f).coerceIn(0f, 1f) }.clearAndSetSemantics { }) {
                        PurposePill("", WLLineIcon.Plus, c.card, c.text, enabled = false)
                    }
                    WLCircleButton(WLLineIcon.More, stringResource(R.string.purpose_menu), onMenu, menuModifier)
                }
            } } else null,
        )
        Column(Modifier.then(if (form != null) Modifier.heightIn(max = editorMaxHeight) else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp)) {
                // This transforming row must stay outside the scroll viewport: it draws into the topbar.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WLIconTile(
                        modifier = Modifier.onPlaced { anchors.tileRoot = it.positionInRoot() }.graphicsLayer {
                            val t = progress()
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = lerp(1f, TileCollapsedScale, t); scaleY = scaleX
                            translationX = (tileTargetX - anchors.tile.x) * t
                            translationY = (anchors.topRowCenterY - size.height * TileCollapsedScale / 2f - anchors.tile.y) * t
                        }, size = 44.dp, radius = 14.dp, color = c.card,
                    ) { WLIcon(purposeIcon(edit?.iconKey ?: p.iconKey), size = 22.dp) }
                    Spacer(Modifier.width(12.dp))
                    val nameModifier = Modifier.weight(1f).onPlaced { anchors.nameRoot = it.positionInRoot(); anchors.nameHeight = it.size.height.toFloat() }
                        .layout { m, cs ->
                            val targetVisual = anchors.width - margin - button - gap10 - button - gap10 - nameTargetX
                            val targetLayout = (targetVisual / NameCollapsedScale).coerceAtLeast(0f)
                            val w = lerp(cs.maxWidth.toFloat(), targetLayout, progress()).roundToInt().coerceAtLeast(0)
                            val placeable = m.measure(cs.copy(minWidth = w, maxWidth = w))
                            layout(cs.maxWidth, placeable.height) { placeable.place(0, 0) }
                        }.graphicsLayer {
                            val t = progress()
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = lerp(1f, NameCollapsedScale, t); scaleY = scaleX
                            translationX = (nameTargetX - anchors.name.x) * t
                            translationY = (anchors.topRowCenterY - anchors.nameHeight * NameCollapsedScale / 2f - anchors.name.y) * t
                        }.padding(top = 3.dp, bottom = 1.dp)
                    InPlaceText(p.name, fields?.first, WLType.display28Edit, nameModifier, on, edit?.submitting != true, 40,
                        stringResource(R.string.purpose_name_label))
                }
            // Only editor details scroll on short displays/above IME; transformed title and footer stay outside.
            Column(Modifier.then(if (form != null) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (p.description != null || form != null) {
                    InPlaceText(p.description.orEmpty(), fields?.second, WLType.body.copy(fontSize = 15.sp, lineHeight = 15.sp),
                        Modifier.fillMaxWidth().graphicsLayer { alpha = fadeOut() }.then(if (faded) Modifier.clearAndSetSemantics {} else Modifier)
                            .padding(top = 13.dp, bottom = 3.75.dp), on, edit?.submitting != true, 200, stringResource(R.string.purpose_description_label), multiline = true)
                }
            AnimatedVisibility(form == null, enter = fadeIn(tween(200, easing = WishlistTokens.Curve.ease)), exit = fadeOut(tween(200, easing = WishlistTokens.Curve.ease))) {
                Row(Modifier.graphicsLayer { alpha = fadeOut() }.then(if (faded) Modifier.clearAndSetSemantics {} else Modifier), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PurposePill(stringResource(R.string.purpose_candidate_add), WLLineIcon.Plus, c.card, c.text, enabled = false)
                    if (p.candidateCount > 0) PurposePill(stringResource(R.string.purpose_finish), WLLineIcon.Archive, c.text, c.onInverse, enabled = false)
                }
            }
            AnimatedVisibility(form != null, enter = fadeIn(tween(200, easing = WishlistTokens.Curve.ease)), exit = fadeOut(tween(200, easing = WishlistTokens.Curve.ease))) {
                if (form != null && edit != null) Column(
                    Modifier.graphicsLayer { alpha = fadeOut() }.then(if (faded) Modifier.clearAndSetSemantics {} else Modifier),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.card).padding(16.dp)) { PurposeStylePicker(edit, form) }
                    PurposeFieldError(edit.fieldErrors["name"])
                    PurposeFieldError(edit.fieldErrors["description"])
                    PurposeFormMessage(edit, form)
                }
            }
            }
            if (form != null && edit != null) Row(Modifier.padding(top = 16.dp).graphicsLayer { alpha = fadeOut() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WLButton(stringResource(R.string.dialog_cancel), WLButtonKind.Secondary, onCancel, enabled = !edit.submitting && !faded)
                WLButton(stringResource(R.string.category_form_save), WLButtonKind.Primary, form::submit, enabled = edit.canSubmit && !faded, loading = edit.submitting)
            }
        }
    }
    }
}

@Composable
private fun InPlaceText(text: String, field: TextFieldState?, style: TextStyle, modifier: Modifier, color: Color, enabled: Boolean, limit: Int, label: String, multiline: Boolean = false) {
    if (field == null) WLText(text, style, modifier, color = color, maxLines = if (multiline) 3 else 1, overflow = TextOverflow.Ellipsis)
    else {
        LaunchedEffect(field, limit) {
            snapshotFlow { field.text.toString() to field.composition }.collect { enforceCommittedInputLimit(field, limit, InputLimitUnit.CodePoint) }
        }
        BasicTextField(field, modifier.semantics { contentDescription = label }.drawBehind {
            drawLine(color.copy(alpha = 0.35f), Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 1.dp.toPx())
        }, enabled = enabled, textStyle = style.copy(color = color), cursorBrush = SolidColor(color), lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 3) else TextFieldLineLimits.SingleLine)
    }
}

/** Disabled follow-up feature entries remain visible until C9/C11. */
@Composable
private fun PurposePill(text: String, icon: WLLineIcon, bg: Color, fg: Color, enabled: Boolean) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(999.dp)).background(bg)
        .clickable(enabled = enabled, role = Role.Button) {}.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WLIcon(icon, size = 18.dp, color = fg.copy(alpha = if (enabled) 1f else 0.4f))
        if (text.isNotEmpty()) WLText(text, WLType.button, color = fg.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1)
    }
}
