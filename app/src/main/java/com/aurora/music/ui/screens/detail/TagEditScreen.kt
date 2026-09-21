package com.aurora.music.ui.screens.detail

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioTags
import com.aurora.music.data.remote.MetadataMatch
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.screens.settings.SettingsTopBar
import com.aurora.music.viewmodel.TagEditState
import kotlinx.coroutines.launch

// saving a local file goes through the mediastore write-consent dialog on android 11+
@Composable
fun TagEditScreen(
    contentPadding: PaddingValues,
    state: TagEditState,
    onEdit: ((AudioTags) -> AudioTags) -> Unit,
    onMatch: () -> Unit,
    onApplyMatch: (MetadataMatch) -> Unit,
    onIdentify: (() -> Unit)?,        // null when acoustid identify unavailable
    identifying: Boolean = false,
    onBack: () -> Unit,
    confirm: (String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    val doWrite: () -> Unit = {
        scope.launch {
            val uri = container.tagEditor.contentUriFor(state.songId)
            if (uri == null) { confirm(appString(R.string.text_can_t_write_this_file_b1889c)); saving = false } else {
                val art = if (state.pickedCoverUrl.isNotBlank()) container.musicBrainz.fetchImage(state.pickedCoverUrl) else null
                val ok = container.tagEditor.write(uri, state.path, state.tags, art)
                saving = false
                if (ok) {
                    confirm(appString(R.string.text_tags_saved_521155))
                    runCatching { container.localLibrary.refresh() }
                    onBack()
                } else confirm(appString(R.string.text_save_failed_0a4444))
            }
        }
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) doWrite() else { saving = false; confirm(appString(R.string.text_write_permission_denied_ad9ca7)) }
    }
    val onSave: () -> Unit = {
        saving = true
        if (!state.localFile) {
            // server item updates via backend metadata api no file write or consent
            scope.launch {
                val ok = container.repository.updateMetadata(state.songId, state.tags)
                saving = false
                if (ok) { confirm(appString(R.string.text_metadata_updated_38fc70)); onBack() } else confirm(appString(R.string.text_update_failed_needs_edit_permission_449ec8))
            }
        } else {
            val uri = container.tagEditor.contentUriFor(state.songId)
            if (uri == null) { confirm(appString(R.string.text_can_t_write_this_file_b1889c)); saving = false } else {
                val consent = container.tagEditor.writeConsentIntent(uri)
                if (consent != null) consentLauncher.launch(IntentSenderRequest.Builder(consent).build()) else doWrite()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.text_edit_tags_d8a5fc), onBack)
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(state.pickedCoverUrl.ifBlank { state.artUrl }, MaterialTheme.colorScheme.primary, Modifier.size(72.dp), corner = 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.tags.title.ifBlank { appString(R.string.text_untitled_621521) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.pickedCoverUrl.isNotBlank()) Text(appString(R.string.text_new_cover_staged_ac4238), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMatch, enabled = !state.matching) {
                    if (state.matching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(appString(R.string.text_match_metadata_f9f1d7))
                }
                if (onIdentify != null) {
                    OutlinedButton(onClick = onIdentify, enabled = !identifying) {
                        if (identifying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(if (identifying) appString(R.string.text_identifying_883ba7) else appString(R.string.text_auto_identify_f23ca3))
                    }
                }
            }
            state.matchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
            state.matches.forEach { m -> MatchRow(m) { onApplyMatch(m) } }

            Spacer(Modifier.height(8.dp))
            TagField(appString(R.string.text_title_768e0c), state.tags.title) { v -> onEdit { it.copy(title = v) } }
            TagField(appString(R.string.text_artist_6c3f3d), state.tags.artist) { v -> onEdit { it.copy(artist = v) } }
            TagField(appString(R.string.text_album_dfb4c9), state.tags.album) { v -> onEdit { it.copy(album = v) } }
            TagField(appString(R.string.text_album_artist_f7fa2e), state.tags.albumArtist) { v -> onEdit { it.copy(albumArtist = v) } }
            TagField(appString(R.string.text_genre_2aa31f), state.tags.genre) { v -> onEdit { it.copy(genre = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { TagField(appString(R.string.text_year_879e32), state.tags.year) { v -> onEdit { it.copy(year = v.filter { c -> c.isDigit() }) } } }
                Box(Modifier.weight(1f)) { TagField(appString(R.string.text_track_4c2135), state.tags.trackNumber) { v -> onEdit { it.copy(trackNumber = v.filter { c -> c.isDigit() }) } } }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (saving) appString(R.string.text_saving_56a228) else appString(R.string.text_save_tags_842d99), fontWeight = FontWeight.Bold)
            }
            Text(
                if (state.localFile) appString(R.string.text_writing_tags_edits_the_file_on_your_device_android_may_ask_you_to_a56f12)
                else appString(R.string.text_updates_this_track_s_metadata_on_the_server_requires_an_account_w_1127e0),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TagField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun MatchRow(m: MetadataMatch, onApply: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable(onClick = onApply)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.coverUrl.isNotBlank()) {
            Artwork(m.coverUrl, MaterialTheme.colorScheme.primary, Modifier.size(40.dp), corner = 8.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(m.artist.ifBlank { null }, m.album.ifBlank { null }, m.year.ifBlank { null }).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(appString(R.string.text_apply_cfea41), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
