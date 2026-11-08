package com.aurora.music.ui.screens.library

import androidx.compose.animation.animateColorAsState
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PhoneAndroid
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.PlaylistTarget
import com.aurora.music.data.ServerType
import com.aurora.music.localization.appString
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatePlaylistSheet(
    destination: String,
    targets: List<PlaylistTarget>,
    onCreate: suspend (name: String, description: String, target: String?) -> Boolean,
    onCreateSmart: () -> Unit,
    onImportM3u: () -> Unit,
    onDismiss: () -> Unit,
    onCreateWithAi: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var target by remember(targets) { mutableStateOf(targets.firstOrNull()) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !saving || it != SheetValue.Hidden })
    val home = target?.let { it.label.localizedMediaType() } ?: destination
    val accent by animateColorAsState(Color(accentArgbFor(name.trim().ifBlank { "new playlist" })), label = "newPlaylistAccent")

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheet,
        containerColor = colors.surface, tonalElevation = 0.dp,
        shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(76.dp).paperClip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = .45f), colors.surfaceContainerHigh))),
                    contentAlignment = Alignment.Center,
                ) {
                    val initial = name.trim().firstOrNull()?.uppercase()
                    if (initial != null) Text(initial, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    else Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(name.trim().ifBlank { appString(R.string.text_new_playlist_a5474a) }, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (home.isNotBlank()) Text(appString(R.string.playlist_saved_to, home), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            val fieldShape = RoundedCornerShape(16.dp)
            OutlinedTextField(
                value = name, onValueChange = { name = it; failed = false },
                label = { Text(appString(R.string.text_playlist_name_544f75)) },
                singleLine = true, enabled = !saving, shape = fieldShape, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description, onValueChange = { description = it; failed = false },
                label = { Text(appString(R.string.playlist_description_optional)) },
                minLines = 2, maxLines = 4, enabled = !saving, shape = fieldShape, modifier = Modifier.fillMaxWidth(),
            )
            if (targets.isNotEmpty()) {
                Text(appString(R.string.playlist_save_to), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Box {
                    val picked = target ?: targets.first()
                    TargetRow(picked, trailing = Icons.Outlined.KeyboardArrowDown, enabled = !saving, onClick = { picking = true })
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
                        targets.forEach { option ->
                            DropdownMenuItem(
                                leadingIcon = { Icon(targetIcon(option.type), null) },
                                text = {
                                    Column {
                                        Text(option.label.localizedMediaType(), fontWeight = FontWeight.SemiBold)
                                        if (option.type == ServerType.LOCAL) Text(appString(R.string.playlist_local_hint),
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                },
                                trailingIcon = { if (option == picked) Icon(Icons.Outlined.Check, null, tint = colors.primary) },
                                onClick = { target = option; picking = false },
                            )
                        }
                    }
                }
            }
            if (failed) Text(appString(R.string.playlist_create_failed), color = colors.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            Button(
                onClick = {
                    saving = true
                    failed = false
                    scope.launch {
                        try { if (onCreate(name.trim(), description.trim(), target?.id)) onDismiss() else failed = true }
                        finally { saving = false }
                    }
                },
                enabled = name.isNotBlank() && !saving, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)),
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp).height(54.dp),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                else Text(appString(R.string.text_create_playlist_62c988), fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutCard(Icons.Outlined.AutoAwesome, appString(R.string.text_smart_playlist_f77ad7), appString(R.string.playlist_smart_hint),
                    enabled = !saving, onClick = onCreateSmart, modifier = Modifier.weight(1f))
                ShortcutCard(Icons.Outlined.FileOpen, appString(R.string.import_title), appString(R.string.import_card_hint),
                    enabled = !saving, onClick = onImportM3u, modifier = Modifier.weight(1f))
            }
            if (onCreateWithAi != null) ShortcutCard(Icons.Outlined.AutoAwesome, appString(R.string.ai_playlist_title), appString(R.string.ai_dj_card_hint),
                enabled = !saving, onClick = onCreateWithAi, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

private fun targetIcon(type: ServerType): ImageVector = if (type == ServerType.LOCAL) Icons.Outlined.PhoneAndroid else Icons.Outlined.Dns

@Composable
private fun TargetRow(target: PlaylistTarget, trailing: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerHigh.copy(alpha = .6f))
            .border(1.dp, colors.outlineVariant.copy(alpha = .4f), shape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).paperClip(RoundedCornerShape(11.dp)).background(colors.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(targetIcon(target.type), null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(target.label.localizedMediaType(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (target.type == ServerType.LOCAL) Text(appString(R.string.playlist_local_hint), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        Icon(trailing, null, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun ShortcutCard(icon: ImageVector, title: String, hint: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp))
    Column(
        modifier.clip(shape).background(colors.surfaceContainerLow).border(1.dp, colors.outlineVariant.copy(alpha = .3f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
    ) {
        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
