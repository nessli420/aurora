package com.aurora.music.ui.widget

import com.aurora.music.localization.appString
import com.aurora.music.R
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.ui.theme.AuroraTheme
import kotlin.math.roundToInt

// opened when the widget is placed and from the launcher's widget settings; the widget restyles live behind it
class AuroraWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // every option has a default, so backing out still keeps the widget
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(widgetId)
        val container = (application as AuroraApplication).container
        setContent {
            val uiPrefs by container.uiPrefs.collectAsStateWithLifecycle()
            val saved by produceState<WidgetStyle?>(null) {
                value = runCatching { WidgetStyle.of(getAppWidgetState(this@AuroraWidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)) }
                    .getOrDefault(WidgetStyle())
            }
            AuroraTheme(uiPrefs = uiPrefs) {
                saved?.let { WidgetStyleSheet(it, glanceId, onDone = ::finish) }
            }
        }
    }
}

@Composable
private fun WidgetStyleSheet(initial: WidgetStyle, glanceId: GlanceId, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var style by remember { mutableStateOf(initial) }
    var opacity by remember { mutableFloatStateOf(initial.opacity) }
    fun pick(next: WidgetStyle) {
        style = next
        AuroraWidgetController.restyle(context, glanceId, next)
    }
    val themes = WidgetTheme.entries.filter { it != WidgetTheme.WALLPAPER || Build.VERSION.SDK_INT >= 31 }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f).fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onDone))
        Surface(shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(appString(R.string.widget_style_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Choices(appString(R.string.text_layout_972ad8), WidgetLayout.entries, style.layout, ::layoutLabel) { pick(style.copy(layout = it)) }
                Spacer(Modifier.height(12.dp))
                Choices(appString(R.string.text_theme_a797e3), themes, style.theme, ::themeLabel) { pick(style.copy(theme = it)) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.widget_opacity), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { pick(style.copy(opacity = opacity)) },
                    steps = 19,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text(appString(R.string.text_done_e9b450)) }
            }
        }
    }
}

@Composable
private fun <T> Choices(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onPick(option) }, label = { Text(label(option)) })
        }
    }
}

private fun layoutLabel(layout: WidgetLayout) = appString(when (layout) {
    WidgetLayout.COMPACT -> R.string.text_compact_1df39a
    WidgetLayout.CARD -> R.string.widget_layout_card
    WidgetLayout.COVER -> R.string.widget_layout_cover
})

private fun themeLabel(theme: WidgetTheme) = appString(when (theme) {
    WidgetTheme.DARK -> R.string.text_dark_ae1ef0
    WidgetTheme.LIGHT -> R.string.text_light_a36ef8
    WidgetTheme.SYSTEM -> R.string.text_system_bc0792
    WidgetTheme.WALLPAPER -> R.string.widget_theme_wallpaper
    WidgetTheme.ALBUM -> R.string.widget_theme_album
})
