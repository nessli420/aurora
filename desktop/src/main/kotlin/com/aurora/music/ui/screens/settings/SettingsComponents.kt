package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics

val LocalSettingsPaneRoots = compositionLocalOf<Set<String>?> { null }

@Composable
private fun isSettingsPaneRoot(): Boolean {
    val roots = LocalSettingsPaneRoots.current ?: return false
    val entry = LocalViewModelStoreOwner.current as? NavBackStackEntry ?: return true
    return entry.id in roots
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    SettingsTopBar(title, onBack, showBack = !isSettingsPaneRoot())
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit, showBack: Boolean) {
    val gutter = LocalPageGutter.current
    Box(
        Modifier.fillMaxWidth()
            .then(if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) Modifier.auroraPanel(RectangleShape) else Modifier)
            .padding(start = gutter, end = if (LocalSettingsListPane.current) ListPaneEnd + GroupInset else gutter, bottom = 4.dp),
    ) {
        PageHeader(title, onBack = if (showBack) onBack else null)
    }
}

private val GroupInset = 12.dp
private val ListPaneEnd = 4.dp

val LocalSettingsListPane = compositionLocalOf { false }

@Composable
fun settingsContentPadding(width: Dp, contentPadding: PaddingValues, bottom: Dp = 24.dp): PaddingValues {
    val start = (LocalPageGutter.current - GroupInset).coerceAtLeast(0.dp)
    val end = if (LocalSettingsListPane.current) ListPaneEnd
        else (width - start - PageMetrics.FormMaxWidth - GroupInset * 2).coerceAtLeast(start)
    return PaddingValues(start = start, end = end, bottom = contentPadding.calculateBottomPadding() + bottom)
}

@Composable
fun ColumnScope.SettingsList(
    contentPadding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = settingsContentPadding(maxWidth, contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
fun ColumnScope.SettingsScroll(
    contentPadding: PaddingValues,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(settingsContentPadding(maxWidth, contentPadding)),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .then(
                if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                else Modifier.auroraPanel(MaterialTheme.shapes.medium)
            ),
        content = content,
    )
}

@Composable
fun SettingsRowDivider(inset: Dp = 72.dp) {
    Box(
        Modifier.fillMaxWidth().padding(start = inset).height(0.7.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

@Composable
private fun RowScaffold(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    selected: Boolean = false,
    trailing: @Composable () -> Unit,
) = SettingsRowScaffold(
    leading = icon?.let { { SettingsRowIcon(it) } },
    title = title, subtitle = subtitle, onClick = onClick, selected = selected, trailing = trailing,
)

@Composable
internal fun SettingsRowIcon(icon: ImageVector) {
    Box(
        Modifier.size(if (LocalSettingsListPane.current) 34.dp else 38.dp).then(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
        ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun SettingsRowScaffold(
    leading: (@Composable () -> Unit)?,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    selected: Boolean = false,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit,
) {
    val dense = LocalSettingsListPane.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) else Modifier)
            .then(if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (dense) 14.dp else 20.dp, vertical = if (dense) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(if (dense) 12.dp else 14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
        trailing()
    }
}

@Composable
fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String? = null, value: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    RowScaffold(icon, title, subtitle, onClick, selected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!LocalSettingsListPane.current) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSwitchRow(icon: ImageVector? = null, title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    RowScaffold(icon, title, subtitle, { onCheckedChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
fun SettingsSliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value, onValueChange = onValueChange, valueRange = range, steps = steps,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun SegmentedRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(50) else MaterialTheme.shapes.small
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth().then(
                if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                    Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier.auroraPanel(shape)
            ).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { i, opt ->
                val active = i == selected
                Box(
                    Modifier.weight(1f).clip(shape)
                        .background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onSelect(i) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(opt, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun SettingsDropdownRow(
    title: String,
    value: String,
    options: List<String>,
    selected: Int,
    icon: ImageVector? = null,
    subtitle: String? = null,
    menuLabel: String? = null,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(12.dp) else MaterialTheme.shapes.small
    RowScaffold(icon, title, subtitle, { expanded = true }) {
        Box(Modifier.padding(start = 16.dp)) {
            Row(
                Modifier.widthIn(min = 160.dp, max = 260.dp)
                    .then(if (aurora) Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier.auroraPanel(shape))
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ArrowDropDown, menuLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label, fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { onSelect(index); expanded = false },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        trailingIcon = if (index == selected) {
                            { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                    )
                }
            }
        }
    }
}
