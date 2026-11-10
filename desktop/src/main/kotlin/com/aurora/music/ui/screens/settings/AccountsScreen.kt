package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.localizedMediaType
import com.aurora.music.ui.theme.paperClip

import com.aurora.music.localization.appString
import com.aurora.music.R

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.accountKey
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.ui.LocalDesktopContainer

@Composable
fun AccountsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSwitch: (Session) -> Unit,
    onForget: (Session) -> Unit,
    onAddAccount: () -> Unit,
    onSignIn: (ServerType) -> Unit = {},
) {
    val container = LocalDesktopContainer.current
    val saved by container.settingsStore.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    fun key(s: Session?) = s?.accountKey().orEmpty()
    val activeKey = key(active)
    val soundclouds = saved.filter { it.type == ServerType.SOUNDCLOUD && !it.isGuest }
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf<String?>(null) }
    val verified = remember { mutableStateMapOf<String, Boolean>() }

    // always offered so you can jump to local even without a saved login
    val rows = remember(saved) {
        saved + (if (saved.none { it.type == ServerType.LOCAL }) listOf(AccountAuthenticator.LOCAL_SESSION) else emptyList())
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_accounts_36bae3), onBack)
        SettingsList(contentPadding) {
            item { SettingsSectionTitle(appString(R.string.text_switch_account_a28b02)) }
            item {
                SettingsGroup {
                    rows.forEachIndexed { i, s ->
                        if (i > 0) SettingsRowDivider()
                        AccountRow(
                            session = s,
                            isActive = key(s) == activeKey,
                            canForget = saved.any { key(it) == key(s) },   // only saved logins can be forgotten
                            onSignIn = if (s.isGuest) ({ onSignIn(s.type) }) else null,
                            onClick = { if (key(s) != activeKey) onSwitch(s) },
                            onForget = { onForget(s) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsGroup {
                    Row(
                        Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onAddAccount).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(appString(R.string.text_add_another_account_5dcc79), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (soundclouds.isNotEmpty()) {
                item { SettingsSectionTitle("SoundCloud") }
                item {
                    SettingsGroup {
                        soundclouds.forEachIndexed { i, s ->
                            if (i > 0) SettingsRowDivider()
                            val title = appString(R.string.soundcloud_verify) + if (soundclouds.size > 1) " · ${s.username}" else ""
                            val subtitle = when (verified[key(s)]) {
                                true -> appString(R.string.soundcloud_verify_done)
                                false -> appString(R.string.soundcloud_verify_failed)
                                null -> appString(R.string.soundcloud_verify_detail)
                            }
                            val waiting = container.soundCloudWaiting(s)
                            val value = if (verifying == key(s)) "…" else if (waiting > 0) appString(R.string.soundcloud_verify_waiting, waiting) else null
                            SettingsNavRow(Icons.Filled.VerifiedUser, title, subtitle, value = value) {
                                if (verifying == null) scope.launch {
                                    verifying = key(s)
                                    verified[key(s)] = container.verifySoundCloud(s)
                                    verifying = null
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    appString(R.string.text_switching_servers_stops_playback_from_the_previous_one_cbfa45),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountRow(session: Session, isActive: Boolean, canForget: Boolean, onClick: () -> Unit, onForget: () -> Unit, onSignIn: (() -> Unit)? = null) {
    val badge = when (session.type) {
        ServerType.SPOTIFY -> "S"
        ServerType.YOUTUBE_MUSIC -> "Y"
        ServerType.SOUNDCLOUD -> "SC"
        ServerType.JELLYFIN -> "J"
        ServerType.PLEX -> "P"
        ServerType.LOCAL -> "L"
        ServerType.SUBSONIC -> "N"
        ServerType.EXTENSION -> "E"
    }
    val host = session.server.removePrefix("http://").removePrefix("https://")
    Row(
        Modifier.fillMaxWidth().then(if (isActive) Modifier else Modifier.pointerHoverIcon(PointerIcon.Hand))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).paperClip(CircleShape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
            contentAlignment = Alignment.Center,
        ) { Text(badge, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (session.type == ServerType.LOCAL) appString(R.string.text_local_library_a4b3e0) else session.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${session.typeLabel.localizedMediaType()}${if (session.type != ServerType.LOCAL) " · $host" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // a guest can still sign in later; the account takes the guest's place and its likes come along
        if (onSignIn != null) TextButton(onClick = onSignIn) { Text(appString(R.string.guest_sign_in), fontWeight = FontWeight.Bold) }
        if (isActive) {
            Row(
                Modifier.paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(appString(R.string.text_active_a733b8), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        } else if (canForget) {
            Icon(
                Icons.Filled.Delete, appString(R.string.text_forget_03d5d8),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp).paperClip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onForget).padding(6.dp),
            )
        }
    }
}
