package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session

/** Saved logins: shows every remembered server, marks the active one, lets you switch / forget / add. */
@Composable
fun AccountsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSwitch: (Session) -> Unit,
    onForget: (Session) -> Unit,
    onAddAccount: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val saved by container.settingsStore.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    fun key(s: Session?) = s?.let { "${it.type}|${it.server}|${it.username}|${it.userId}" } ?: ""
    val activeKey = key(active)

    // The on-device library — no password, just the audio-read permission. Always offered so you can
    // jump to Local even if you've never signed into it.
    val localSession = remember { Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL) }
    val audioPerm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onSwitch(localSession) }
    val switchLocal: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, audioPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED) onSwitch(localSession)
        else permLauncher.launch(audioPerm)
    }

    // Saved logins + a synthetic Local row when it isn't already saved.
    val rows = remember(saved) { saved + (if (saved.none { it.type == ServerType.LOCAL }) listOf(localSession) else emptyList()) }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Accounts", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle("Switch account") }
            item {
                SettingsGroup {
                    rows.forEachIndexed { i, s ->
                        if (i > 0) SettingsRowDivider()
                        val isLocalRow = s.type == ServerType.LOCAL
                        AccountRow(
                            session = s,
                            isActive = key(s) == activeKey,
                            canForget = saved.any { key(it) == key(s) },   // only truly-saved logins can be forgotten
                            onClick = {
                                if (key(s) == activeKey) Unit
                                else if (isLocalRow) switchLocal()
                                else onSwitch(s)
                            },
                            onForget = { onForget(s) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsGroup {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onAddAccount).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text("Add another account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Text(
                    "Switching servers stops playback from the previous one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountRow(session: Session, isActive: Boolean, canForget: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    val badge = when (session.type) {
        ServerType.SPOTIFY -> "S"
        ServerType.JELLYFIN -> "J"
        ServerType.LOCAL -> "L"
        ServerType.SUBSONIC -> "N"
    }
    val host = session.server.removePrefix("http://").removePrefix("https://")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
            contentAlignment = Alignment.Center,
        ) { Text(badge, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(session.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${session.typeLabel}${if (session.type != ServerType.LOCAL) " · $host" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isActive) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Active", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        } else if (canForget) {
            Icon(
                Icons.Filled.Delete, "Forget",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onForget).padding(6.dp),
            )
        }
    }
}
