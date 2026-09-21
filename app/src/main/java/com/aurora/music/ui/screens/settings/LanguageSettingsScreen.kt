package com.aurora.music.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.aurora.music.R

@Composable
fun LanguageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val selected = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
    val languages = listOf("" to stringResource(R.string.language_system), "en" to "English", "ru" to "Русский")
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.language_title), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            Text(stringResource(R.string.language_description), modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsGroup {
                Column(Modifier.selectableGroup()) {
                    languages.forEach { (tag, name) ->
                        Row(Modifier.fillMaxWidth().selectable(
                            selected = tag == selected,
                            role = Role.RadioButton,
                            onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
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
