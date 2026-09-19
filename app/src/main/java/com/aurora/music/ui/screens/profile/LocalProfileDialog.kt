package com.aurora.music.ui.screens.profile

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
        title = { Text("Edit local profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.profile.name.orEmpty(), onValueChange = vm::name, singleLine = true,
                    enabled = !state.busy, label = { Text("Display name") }, placeholder = { Text("Local Library") },
                    isError = state.profile.name.orEmpty().length > 80, modifier = Modifier.fillMaxWidth())
                ProfileImageEditor("Avatar", state.preview.avatarUrl, false, state.busy,
                    onChoose = { avatar.launch("image/*") }, onRemove = { vm.removeImage(false) })
                ProfileImageEditor("Banner", state.preview.bannerUrl, true, state.busy,
                    onChoose = { banner.launch("image/*") }, onRemove = { vm.removeImage(true) })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !state.busy && state.profile.name.orEmpty().length <= 80,
            onClick = { vm.save(onDismiss) }) { Text("Save") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onDismiss) { Text("Cancel") } },
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
            TextButton(enabled = !busy, onClick = onChoose) { Text("Choose ${label.lowercase()}") }
            if (url.isNotBlank()) TextButton(enabled = !busy, onClick = onRemove) { Text("Remove ${label.lowercase()}") }
        }
    }
}
