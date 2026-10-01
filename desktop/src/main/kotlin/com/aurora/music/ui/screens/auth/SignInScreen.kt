package com.aurora.music.ui.screens.auth

import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appString

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.resources.AuroraLogo
import com.aurora.music.desktop.ui.FilePickers
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.theme.AuroraRose
import com.aurora.music.viewmodel.AuthStep
import com.aurora.music.viewmodel.AuthUiState
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignInScreen(
    state: AuthUiState,
    onSelectType: (ServerType) -> Unit,
    onScheme: (String) -> Unit,
    onHost: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onBack: () -> Unit,
    canContinueServer: Boolean,
    onContinueServer: () -> Unit,
    canSubmit: Boolean,
    onSignIn: () -> Unit,
    onLocal: () -> Unit = {},
    musicFolders: List<String> = emptyList(),
    onAddFolder: (String) -> Unit = {},
    onRemoveFolder: (String) -> Unit = {},
    savedSessions: List<Session> = emptyList(),
    onUseSaved: (Session) -> Unit = {},
) {
    BackHandler(enabled = state.step != AuthStep.TYPE) { if (!state.loading) onBack() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.step) { scroll.scrollTo(0) }
    val palette = darkColorScheme(primary = AuroraRose, onPrimary = Color(0xFF281019),
        background = Color(0xFF111216), surface = Color(0xFF1B1D23), surfaceContainerHigh = Color(0xFF24262D),
        onBackground = Color(0xFFF6F2F3), onSurface = Color(0xFFF6F2F3), onSurfaceVariant = Color(0xFFB5B0BA),
        outline = Color(0xFF45434C), outlineVariant = Color(0xFF303139))
    MaterialTheme(colorScheme = palette) {
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
            val wide = maxWidth >= 1200.dp
            Box(Modifier.fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(AuroraRose.copy(alpha = .09f), Color.Transparent))))
            val brand: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.step != AuthStep.TYPE) {
                        IconButton(onClick = onBack, enabled = !state.loading, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36))
                        }
                    } else {
                        Icon(rememberVectorPainter(AuroraLogo), null, Modifier.size(32.dp), tint = AuroraRose)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(appString(R.string.text_aurora_eeee9b), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(when (state.step) {
                        AuthStep.TYPE -> appString(R.string.text_your_music_your_way_db5743)
                        AuthStep.SERVER -> appString(R.string.text_01_address_ea85bc)
                        AuthStep.CREDENTIALS -> appString(R.string.text_02_account_8088a4)
                        AuthStep.FOLDERS -> appString(R.string.text_connect_6e2889)
                    }, style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant, letterSpacing = 1.sp)
                }
            }
            val intro: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp)) {
                    val headline = when (state.step) {
                        AuthStep.TYPE -> appString(R.string.text_a_home_for_your_music_20a46f)
                        AuthStep.SERVER -> appString(R.string.text_connect_your_server_8989fc)
                        AuthStep.CREDENTIALS -> appString(if (state.type == ServerType.PLEX) R.string.plex_connect_title else R.string.text_make_yourself_at_home_3ea2e2)
                        AuthStep.FOLDERS -> appString(R.string.text_music_on_this_device_21b6e9)
                    }
                    val type = state.step == AuthStep.TYPE
                    Text(headline, fontSize = if (wide) (if (type) 52.sp else 40.sp) else if (type) 38.sp else 30.sp,
                        lineHeight = if (wide) (if (type) 58.sp else 46.sp) else if (type) 43.sp else 36.sp,
                        fontWeight = FontWeight.Bold, color = palette.onBackground)
                    Text(when (state.step) {
                        AuthStep.TYPE -> appString(R.string.text_your_collection_and_your_discoveries_together_in_one_player_093361)
                        AuthStep.SERVER -> appString(R.string.text_enter_the_address_of_your_server_3f01ed, when (state.type) {
                            ServerType.JELLYFIN -> "Jellyfin"
                            ServerType.PLEX -> "Plex"
                            else -> appString(R.string.text_navidrome_or_subsonic_4e1c18)
                        })
                        AuthStep.CREDENTIALS -> appString(if (state.type == ServerType.PLEX) R.string.plex_token_detail else R.string.text_use_your_server_account_to_open_your_library_800ff2)
                        AuthStep.FOLDERS -> appString(R.string.text_start_listening_without_an_account_2e734d)
                    }, style = MaterialTheme.typography.bodyLarge, color = palette.onSurfaceVariant)
                }
            }
            val form: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    when (state.step) {
                        AuthStep.TYPE -> TypeStep(onSelectType, savedSessions, onUseSaved, !state.loading)
                        AuthStep.SERVER -> ServerStep(state, onScheme, onHost, canContinueServer, onContinueServer)
                        AuthStep.CREDENTIALS -> CredentialsStep(state, onUsername, onPassword, onBack, canSubmit, onSignIn)
                        AuthStep.FOLDERS -> FoldersStep(state, musicFolders, onAddFolder, onRemoveFolder, onLocal)
                    }
                    if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = AuroraRose, strokeWidth = 2.dp)
                        Text(appString(R.string.text_connecting_your_library_a0430a), color = palette.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    AnimatedVisibility(state.error != null) {
                        Surface(color = palette.errorContainer, shape = RoundedCornerShape(16.dp)) {
                            Text(state.error.orEmpty(), Modifier.fillMaxWidth().padding(16.dp), color = palette.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (wide) Row(
                    Modifier.widthIn(max = PageMetrics.SignInMaxWidth).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                        brand()
                        intro()
                    }
                    Box(Modifier.width(512.dp)) { form() }
                } else Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    brand()
                    intro()
                    form()
                }
            }
        }
    }
}

@Composable
private fun TypeStep(onSelectType: (ServerType) -> Unit, savedSessions: List<Session>, onUseSaved: (Session) -> Unit, enabled: Boolean) {
    var savedExpanded by rememberSaveable { mutableStateOf(false) }
    val saved = savedSessions.filter { it.type in AccountAuthenticator.SUPPORTED }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (saved.isNotEmpty()) {
            TextButton(onClick = { savedExpanded = !savedExpanded }, modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)) {
                Text(if (savedExpanded) appString(R.string.text_hide_saved_accounts_d0e9da) else appString(R.string.text_continue_with_a_saved_account_a6bc3a, (saved.size)))
            }
            AnimatedVisibility(savedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved.forEach { account ->
                        val name = if (account.type == ServerType.LOCAL) appString(R.string.text_local_library_a4b3e0) else account.username.ifBlank { account.typeLabel.localizedMediaType() }
                        ServerTypeCard(Icons.Outlined.Person, name, account.typeLabel.localizedMediaType(), enabled = enabled) { onUseSaved(account) }
                    }
                }
            }
        }
        ServerTypeCard(Icons.Outlined.Computer, appString(R.string.text_music_on_this_device_21b6e9), appString(R.string.text_start_listening_without_an_account_2e734d), enabled = enabled, featured = true) { onSelectType(ServerType.LOCAL) }
        SourceLabel(appString(R.string.text_your_server_d2f076))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerTile("Navidrome", appString(R.string.text_subsonic_compatible_b4584c), Icons.Outlined.Dns, Modifier.weight(1f), enabled) { onSelectType(ServerType.SUBSONIC) }
            ServerTile("Jellyfin", appString(R.string.text_your_media_library_77d854), Icons.Outlined.Cloud, Modifier.weight(1f), enabled) { onSelectType(ServerType.JELLYFIN) }
        }
        ServerTypeCard(Icons.Outlined.PlayCircle, "Plex", appString(R.string.setup_source_plex_detail), enabled = enabled) { onSelectType(ServerType.PLEX) }
    }
}

@Composable
private fun FoldersStep(state: AuthUiState, folders: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onContinue: () -> Unit) {
    fun pick() {
        FilePickers.pickFolder(appString(R.string.text_add_music_folder_a083d8), folders.lastOrNull()?.let(::File))?.let { onAdd(it.absolutePath) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SourceLabel(appString(R.string.text_folders_19adc4).uppercase(java.util.Locale.getDefault()))
        folders.forEach { path ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, tint = AuroraRose, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(File(path).name.ifBlank { path }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(path) }, enabled = !state.loading, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Outlined.Close, appString(R.string.text_remove_4d28b5, path))
                }
            }
        }
        OutlinedButton(onClick = ::pick, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(appString(R.string.text_add_music_folder_a083d8), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        if (folders.isEmpty()) Text(appString(R.string.text_choose_where_your_music_lives_on_this_pc_876bd8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(if (state.loading) "" else appString(R.string.text_continue_2e0262), enabled = folders.isNotEmpty() && !state.loading,
                modifier = Modifier.weight(1f), loading = state.loading, onClick = onContinue)
        }
    }
}

@Composable
private fun SourceLabel(label: String) {
    Text(label, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun ServerTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = AuroraRose, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerTypeCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, featured: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(if (featured) AuroraRose.copy(alpha = .10f) else MaterialTheme.colorScheme.surface)
        .border(1.dp, if (featured) AuroraRose.copy(alpha = .3f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(AuroraRose.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AuroraRose, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AuroraRose,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLeadingIconColor = AuroraRose,
    cursorColor = AuroraRose,
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
)

@Composable
private fun ServerStep(
    state: AuthUiState,
    onScheme: (String) -> Unit,
    onHost: (String) -> Unit,
    canContinue: Boolean,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SchemePill("http://", state.scheme == "http://", Modifier.weight(1f)) { onScheme("http://") }
            SchemePill("https://", state.scheme == "https://", Modifier.weight(1f)) { onScheme("https://") }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = state.host,
            onValueChange = onHost,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(appString(R.string.text_server_address_b06792)) },
            placeholder = { Text(when (state.type) {
                ServerType.JELLYFIN -> "192.168.1.10:8096"
                ServerType.PLEX -> "192.168.1.10:32400"
                else -> "192.168.1.10:4533"
            }) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Dns, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { if (canContinue) onContinue() }),
            colors = fieldColors(),
        )
        if (state.type == ServerType.PLEX) {
            Text(appString(R.string.plex_server_detail), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(appString(R.string.text_continue_2e0262), enabled = canContinue, modifier = Modifier.weight(1f), onClick = onContinue)
        }
    }
}

@Composable
private fun CredentialsStep(
    state: AuthUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onBack: () -> Unit,
    canSubmit: Boolean,
    onSignIn: () -> Unit,
) {
    var passwordVisible by remember(state.type) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val isPlex = state.type == ServerType.PLEX
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = AuroraRose)
                Spacer(Modifier.width(12.dp))
                Text(state.scheme + state.host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack, enabled = !state.loading, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text(appString(R.string.text_edit_530164)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!isPlex) {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsername,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(appString(R.string.text_username_84c290)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(14.dp))
        }
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(appString(if (isPlex) R.string.plex_token_label else R.string.text_password_8be3c9)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Lock, null) },
            enabled = !state.loading,
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        appString(when {
                            isPlex && passwordVisible -> R.string.plex_token_hide
                            isPlex -> R.string.plex_token_show
                            passwordVisible -> R.string.text_hide_password_e40123
                            else -> R.string.text_show_password_044b85
                        }))
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit && !state.loading) { focus.clearFocus(); onSignIn() } }),
            colors = fieldColors(),
        )
        if (isPlex) {
            TextButton(onClick = {
                runCatching { uriHandler.openUri("https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/") }
            }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text(appString(R.string.plex_token_help)) }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(if (state.loading) "" else appString(R.string.text_connect_b65463), enabled = canSubmit && !state.loading, modifier = Modifier.weight(1f), loading = state.loading, onClick = onSignIn)
        }
    }
}

@Composable
private fun SchemePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AuroraRose else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 54.dp).pointerHoverIcon(PointerIcon.Hand), shape = RoundedCornerShape(16.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}
