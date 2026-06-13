package com.aurora.music.ui.screens.detail

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

/**
 * Tag editor: edit a local file's tags, optionally auto-filling from a MusicBrainz match (with
 * Cover Art Archive art). Saving goes through the MediaStore write-consent dialog on Android 11+.
 */
@Composable
fun TagEditScreen(
    contentPadding: PaddingValues,
    state: TagEditState,
    onEdit: ((AudioTags) -> AudioTags) -> Unit,
    onMatch: () -> Unit,
    onApplyMatch: (MetadataMatch) -> Unit,
    onIdentify: (() -> Unit)?,        // AcoustID fingerprint identify; null when unavailable
    identifying: Boolean = false,
    onBack: () -> Unit,
    confirm: (String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    // The actual write, run after any needed MediaStore consent.
    val doWrite: () -> Unit = {
        scope.launch {
            val uri = container.tagEditor.contentUriFor(state.songId)
            if (uri == null) { confirm("Can't write this file"); saving = false } else {
                val art = if (state.pickedCoverUrl.isNotBlank()) container.musicBrainz.fetchImage(state.pickedCoverUrl) else null
                val ok = container.tagEditor.write(uri, state.path, state.tags, art)
                saving = false
                if (ok) {
                    confirm("Tags saved")
                    runCatching { container.localLibrary.refresh() }
                    onBack()
                } else confirm("Save failed")
            }
        }
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) doWrite() else { saving = false; confirm("Write permission denied") }
    }
    val onSave: () -> Unit = {
        saving = true
        if (!state.localFile) {
            // Server item (Jellyfin): update via the backend metadata API — no file write / consent.
            scope.launch {
                val ok = container.repository.updateMetadata(state.songId, state.tags)
                saving = false
                if (ok) { confirm("Metadata updated"); onBack() } else confirm("Update failed — needs edit permission")
            }
        } else {
            val uri = container.tagEditor.contentUriFor(state.songId)
            if (uri == null) { confirm("Can't write this file"); saving = false } else {
                val consent = container.tagEditor.writeConsentIntent(uri)
                if (consent != null) consentLauncher.launch(IntentSenderRequest.Builder(consent).build()) else doWrite()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Edit tags", onBack)
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
                    Text(state.tags.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.pickedCoverUrl.isNotBlank()) Text("New cover staged", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))

            // Auto-match actions.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMatch, enabled = !state.matching) {
                    if (state.matching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Match metadata")
                }
                if (onIdentify != null) {
                    OutlinedButton(onClick = onIdentify, enabled = !identifying) {
                        if (identifying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(if (identifying) "Identifying…" else "Auto-identify")
                    }
                }
            }
            state.matchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
            state.matches.forEach { m -> MatchRow(m) { onApplyMatch(m) } }

            Spacer(Modifier.height(8.dp))
            TagField("Title", state.tags.title) { v -> onEdit { it.copy(title = v) } }
            TagField("Artist", state.tags.artist) { v -> onEdit { it.copy(artist = v) } }
            TagField("Album", state.tags.album) { v -> onEdit { it.copy(album = v) } }
            TagField("Album artist", state.tags.albumArtist) { v -> onEdit { it.copy(albumArtist = v) } }
            TagField("Genre", state.tags.genre) { v -> onEdit { it.copy(genre = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { TagField("Year", state.tags.year) { v -> onEdit { it.copy(year = v.filter { c -> c.isDigit() }) } } }
                Box(Modifier.weight(1f)) { TagField("Track #", state.tags.trackNumber) { v -> onEdit { it.copy(trackNumber = v.filter { c -> c.isDigit() }) } } }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (saving) "Saving…" else "Save tags", fontWeight = FontWeight.Bold)
            }
            Text(
                if (state.localFile) "Writing tags edits the file on your device. Android may ask you to allow the change."
                else "Updates this track's metadata on the server (requires an account with edit permission). Cover art isn't changed.",
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
            Text(
                listOfNotNull(m.artist.ifBlank { null }, m.album.ifBlank { null }, m.year.ifBlank { null }).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Apply", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
