package com.aurora.music.ui.screens.profile

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.ui.components.Artwork
import com.aurora.music.viewmodel.LocalProfileViewModel

@Composable
fun LocalProfileDialog(onDismiss: () -> Unit) {
    val vm: LocalProfileViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.begin() }
    val avatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> vm.image(uri, false) } }
    val banner = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> vm.image(uri, true) } }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(appString(R.string.text_edit_local_profile_6f9d95)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.profile.name.orEmpty(), onValueChange = vm::name, singleLine = true,
                    enabled = !state.busy, label = { Text(appString(R.string.text_display_name_c7874a)) }, placeholder = { Text(appString(R.string.text_local_library_a4b3e0)) },
                    isError = state.profile.name.orEmpty().length > 80, modifier = Modifier.fillMaxWidth())
                ProfileImageEditor(appString(R.string.text_avatar_7631b2), state.preview.avatarUrl, false, state.busy,
                    onChoose = { avatar.launch("image/*") }, onRemove = { vm.removeImage(false) })
                ProfileImageEditor(appString(R.string.text_banner_c8af83), state.preview.bannerUrl, true, state.busy,
                    onChoose = { banner.launch("image/*") }, onRemove = { vm.removeImage(true) })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !state.busy && state.profile.name.orEmpty().length <= 80,
            onClick = { vm.save(onDismiss) }) { Text(appString(R.string.text_save_efc007)) } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun ProfileImageEditor(label: String, url: String, banner: Boolean, busy: Boolean, onChoose: () -> Unit, onRemove: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (url.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Artwork(url, MaterialTheme.colorScheme.primary,
                if (banner) Modifier.fillMaxWidth().height(90.dp) else Modifier.size(72.dp), corner = if (banner) 12.dp else 36.dp)
        }
        Row {
            TextButton(enabled = !busy, onClick = onChoose) { Text(appString(R.string.text_choose_c2c00f, (label.lowercase()))) }
            if (url.isNotBlank()) TextButton(enabled = !busy, onClick = onRemove) { Text(appString(R.string.text_remove_4d28b5, (label.lowercase()))) }
        }
    }
}
