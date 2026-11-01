package zy.hotspot.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.clickable
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key as composeKey
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import zy.hotspot.app.R

val PreferenceSplitControlWidth: Dp = 52.dp

@Composable
fun rememberTextFieldValueAtEnd(text: String, vararg inputs: Any?): MutableState<TextFieldValue> =
    rememberSaveable(text, *inputs, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }

@Composable
fun rememberDialogFocusRequester(enabled: Boolean = true): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(enabled) {
        if (enabled) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    return focusRequester
}

data class ScrollIndicatorState(
    val isScrolling: Boolean = false,
    val offset: Float = 0f,
    val totalHeight: Float = 0f,
)

val LazyListState.scrollIndicatorState: ScrollIndicatorState
    get() = ScrollIndicatorState(
        isScrolling = isScrollInProgress,
        offset = firstVisibleItemScrollOffset.toFloat(),
        totalHeight = layoutInfo.totalItemsCount.toFloat(),
    )

@Composable
fun Modifier.nonInteractiveScrollbar(state: ScrollIndicatorState, orientation: Orientation): Modifier {
    if (orientation != Orientation.Vertical) return this
    val density = LocalDensity.current
    return drawWithContent {
        drawContent()
        if (state.totalHeight > 0f) {
            val total = state.totalHeight
            val frac = (state.offset / total).coerceIn(0f, 1f)
            val barHeight = (size.height * 0.3f).coerceAtLeast(24f)
            val y = (size.height - barHeight) * frac
            val barWidth = with(density) { 4.dp.toPx() }
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.5f),
                topLeft = Offset(size.width - barWidth - with(density) { 2.dp.toPx() }, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
fun Modifier.nonInteractiveVerticalScrollbar(state: ScrollIndicatorState?) = state?.let {
    nonInteractiveScrollbar(
        state = it,
        orientation = Orientation.Vertical,
    )
} ?: this

@Composable
fun SettingsList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .nonInteractiveVerticalScrollbar(state.scrollIndicatorState),
        contentPadding = contentPadding,
        content = content,
    )
}

fun LazyListScope.preferenceGroup(
    key: Any? = null,
    @StringRes title: Int? = null,
    content: PreferenceGroupScope.() -> Unit,
) {
    item(key = key ?: title) {
        PreferenceGroup(
            title = title?.let { stringResource(it) },
            content = content,
        )
    }
}

@Composable
fun PreferenceGroup(
    title: String? = null,
    horizontalPadding: Dp = 16.dp,
    content: PreferenceGroupScope.() -> Unit,
) {
    val items = ArrayList<@Composable () -> Unit>()
    PreferenceGroupScope(items).apply(content)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        title?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontWeight = FontWeight.SemiBold,
            )
        }
        for (item in items) item()
    }
}

class PreferenceGroupScope(private val items: MutableList<@Composable () -> Unit>) {
    private var rowCount = 0

    fun row(key: Any? = null, content: @Composable () -> Unit) {
        val index = rowCount++
        items += {
            if (key == null) {
                CompositionLocalProvider(LocalPreferenceRowPosition provides PreferenceRowPosition(index, rowCount)) {
                    content()
                }
            } else composeKey(key) {
                CompositionLocalProvider(LocalPreferenceRowPosition provides PreferenceRowPosition(index, rowCount)) {
                    content()
                }
            }
        }
    }

    fun contentItem(key: Any? = null, content: @Composable () -> Unit) {
        items += {
            if (key == null) content() else composeKey(key) {
                content()
            }
        }
    }
}

@Composable
fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color? = null,
    summary: CharSequence? = null,
    summaryContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    PreferenceRow(
        titleContent = { Text(title) },
        modifier = modifier,
        summary = summary,
        summaryContent = summaryContent,
        enabled = enabled,
        iconContent = icon?.let {
            {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = iconTint ?: LocalContentColor.current,
                )
            }
        },
        trailing = trailing,
        onClick = onClick,
    )
}

@Composable
fun PreferenceRow(
    titleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    summary: CharSequence? = null,
    summaryContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    iconContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val position = LocalPreferenceRowPosition.current
    val shape = preferenceRowShape(position)
    val color = preferenceRowColor(position)
    Surface(
        shape = shape,
        color = color,
        modifier = modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            iconContent?.let {
                Box(Modifier.padding(end = 16.dp)) { it() }
            }
            Column(Modifier.weight(1f)) {
                titleContent()
                if (summaryContent != null) summaryContent()
                else summary?.takeIf { it.isNotEmpty() }?.let { Text(it.toString()) }
            }
            trailing?.let {
                Box(Modifier.padding(start = 16.dp)) { it() }
            }
        }
    }
}

@Composable
fun PreferenceRadioRow(
    selected: Boolean,
    titleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    summaryContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val position = LocalPreferenceRowPosition.current
    Surface(
        shape = preferenceRowShape(position),
        color = preferenceRowColor(position),
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                titleContent()
                summaryContent?.invoke()
            }
        }
    }
}

@Composable
fun PreferenceSwitchRow(
    checked: Boolean,
    @DrawableRes icon: Int? = null,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    PreferenceRow(
        icon = icon,
        title = title,
        modifier = modifier.semantics(mergeDescendants = true) {
            toggleableState = ToggleableState(checked)
            role = Role.Switch
        },
        summary = summary,
        summaryContent = summaryContent,
        enabled = enabled,
        trailing = {
            PreferenceSwitch(
                checked = checked,
                modifier = Modifier.clearAndSetSemantics { },
                enabled = enabled,
                onCheckedChange = null,
            )
        },
        onClick = { if (enabled) onCheckedChange(!checked) },
    )
}

@Composable
private fun preferenceRowShape(position: PreferenceRowPosition?): Shape {
    val r = Dp(12f)
    val z = Dp(0f)
    return when (position) {
        null -> RoundedCornerShape(r)
        else -> RoundedCornerShape(
            topStart = if (position.index == 0) r else z,
            topEnd = if (position.index == 0) r else z,
            bottomEnd = if (position.index == position.count - 1) r else z,
            bottomStart = if (position.index == position.count - 1) r else z,
        )
    }
}

@Composable
private fun preferenceRowColor(position: PreferenceRowPosition?): Color = if (position == null) {
    MaterialTheme.colorScheme.surfaceVariant
} else {
    MaterialTheme.colorScheme.surfaceContainer
}

private class PreferenceRowPosition(val index: Int, val count: Int)

private val LocalPreferenceRowPosition = compositionLocalOf<PreferenceRowPosition?> { null }

@Composable
fun PreferenceSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = {
            Icon(
                painter = painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
        enabled = enabled,
        interactionSource = interactionSource,
    )
}

@Composable
fun PreferenceSplitSwitch(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VerticalDivider(
            modifier = Modifier.height(40.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = modifier
                .width(PreferenceSplitControlWidth)
                .height(48.dp)
                .toggleable(
                    value = checked,
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .clearAndSetSemantics {
                    contentDescription = label
                    toggleableState = ToggleableState(checked)
                    role = Role.Switch
                    if (enabled) {
                        onClick {
                            onCheckedChange(!checked)
                            true
                        }
                    } else disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            PreferenceSwitch(
                checked = checked,
                modifier = Modifier.clearAndSetSemantics { },
                enabled = enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
            )
        }
    }
}

@Composable
fun rememberPreferenceSplitFocusModifiers(): Pair<Modifier, Modifier> {
    val rowRequester = remember { FocusRequester() }
    val controlRequester = remember { FocusRequester() }
    return if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        Modifier
            .focusRequester(rowRequester)
            .focusProperties { left = controlRequester } to Modifier
            .focusRequester(controlRequester)
            .focusProperties { right = rowRequester }
    } else {
        Modifier
            .focusRequester(rowRequester)
            .focusProperties { right = controlRequester } to Modifier
            .focusRequester(controlRequester)
            .focusProperties { left = rowRequester }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VpnHotspotModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
        content = content,
    )
}

@Composable
fun modalBottomSheetListContentPadding() = PaddingValues(
    start = 24.dp,
    end = 24.dp,
    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PreferenceSelectionSheet(
    title: String,
    entryCount: Int,
    selectedIndex: Int,
    entryLabel: (Int) -> String,
    entrySummary: (Int) -> AnnotatedString? = { null },
    description: AnnotatedString? = null,
    onDismissRequest: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    VpnHotspotModalBottomSheet(onDismissRequest = onDismissRequest) {
        val state = rememberLazyListState()
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .nonInteractiveVerticalScrollbar(state.scrollIndicatorState),
            contentPadding = modalBottomSheetListContentPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            description?.let {
                item("description") {
                    Text(
                        text = it,
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(entryCount, key = { it }) { index ->
                PreferenceSelectionRow(
                    index = index,
                    count = entryCount,
                    selected = index == selectedIndex,
                    title = entryLabel(index),
                    summary = entrySummary(index),
                ) {
                    onSelect(index)
                    onDismissRequest()
                }
            }
        }
    }
}

@Composable
fun PreferenceSelectionRow(
    index: Int,
    count: Int,
    selected: Boolean,
    title: String,
    summary: AnnotatedString? = null,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalPreferenceRowPosition provides PreferenceRowPosition(index, count)) {
        PreferenceRadioRow(
            selected = selected,
            titleContent = { Text(title) },
            summaryContent = summary?.let { { Text(it) } },
            onClick = onClick,
        )
    }
}

@Composable
fun MenuItemIcon(@DrawableRes icon: Int) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
fun annotatedStringResource(@StringRes id: Int, vararg formatArgs: Any) = AnnotatedString.fromHtml(
    if (formatArgs.isEmpty()) stringResource(id) else stringResource(id, *formatArgs),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            content = content,
        )
    }
}

@Composable
fun RowSelectionContainer(content: @Composable () -> Unit) {
    SelectionContainer(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { },
        ),
        content = content,
    )
}
