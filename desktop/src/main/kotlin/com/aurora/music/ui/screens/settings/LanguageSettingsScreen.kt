package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch

@Composable
fun LanguageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
    val selected = AppStrings.languageTag.collectAsState().value.substringBefore('-')
    val languages = listOf("" to appString(R.string.language_system), "en" to "English", "ru" to "Русский", "tr" to "Türkçe")
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.language_title), onBack)
        SettingsScroll(contentPadding) {
            Text(appString(R.string.language_description), modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    languages.forEach { (tag, name) ->
                        Row(Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).selectable(
                            selected = tag == selected,
                            role = Role.RadioButton,
                            onClick = {
                                AppStrings.setLocale(tag)
                                // outlives the locale-keyed recomposition that disposes this screen
                                container.scope.launch { container.desktopSettings.setLanguageTag(tag) }
                            },
                        ).padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RadioButton(selected = tag == selected, onClick = null)
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
