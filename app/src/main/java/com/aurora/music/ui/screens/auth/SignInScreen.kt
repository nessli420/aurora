package com.aurora.music.ui.screens.auth

import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appString

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.ui.theme.AuroraRose
import com.aurora.music.viewmodel.AuthStep
import com.aurora.music.viewmodel.AuthUiState

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
    onAuthUrlOpened: () -> Unit = {},
    onLocal: () -> Unit = {},
    onConnectSpotify: (String) -> Unit = {},
    onConnectYouTubeMusic: (String) -> Unit = {},
    savedSessions: List<com.aurora.music.data.Session> = emptyList(),
    onUseSaved: (com.aurora.music.data.Session) -> Unit = {},
) {
    val ctx = LocalContext.current
    LaunchedEffect(state.pendingAuthUrl) {
        val url = state.pendingAuthUrl ?: return@LaunchedEffect
        runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
        onAuthUrlOpened()
    }
    // local mode needs audio-read permission before sign-in
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var permDenied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { permDenied = false; onLocal() } else permDenied = true
    }
    val requestLocal: () -> Unit = {
        if (ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED) onLocal()
        else permLauncher.launch(audioPerm)
    }
    BackHandler(enabled = state.step != AuthStep.TYPE) { if (!state.loading) onBack() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.step) { scroll.scrollTo(0) }
    val palette = darkColorScheme(primary = AuroraRose, onPrimary = Color(0xFF281019),
        background = Color(0xFF111216), surface = Color(0xFF1B1D23), surfaceContainerHigh = Color(0xFF24262D),
        onBackground = Color(0xFFF6F2F3), onSurface = Color(0xFFF6F2F3), onSurfaceVariant = Color(0xFFB5B0BA),
        outline = Color(0xFF45434C), outlineVariant = Color(0xFF303139))
    MaterialTheme(colorScheme = palette) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            Box(Modifier.fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(AuroraRose.copy(alpha = .09f), Color.Transparent))))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                .verticalScroll(scroll).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.step != AuthStep.TYPE) {
                        IconButton(onClick = onBack, enabled = !state.loading) { Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36)) }
                    } else {
                        Icon(painterResource(R.drawable.ic_aurora_logo), null, Modifier.size(32.dp), tint = AuroraRose)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(appString(R.string.text_aurora_eeee9b), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (state.step == AuthStep.TYPE) appString(R.string.text_your_music_your_way_db5743) else when (state.step) {
                        AuthStep.SERVER -> appString(R.string.text_01_address_ea85bc)
                        AuthStep.CREDENTIALS -> appString(R.string.text_02_account_8088a4)
                        else -> appString(R.string.text_connect_6e2889)
                    }, style = MaterialTheme.typography.labelSmall, color = palette.onSurfaceVariant, letterSpacing = 1.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when (state.step) {
                        AuthStep.TYPE -> appString(R.string.text_a_home_for_your_music_20a46f)
                        AuthStep.SERVER -> appString(R.string.text_connect_your_server_8989fc)
                        AuthStep.CREDENTIALS -> appString(R.string.text_make_yourself_at_home_3ea2e2)
                        AuthStep.SPOTIFY -> appString(R.string.text_connect_spotify_529d56)
                        AuthStep.YOUTUBE_MUSIC -> appString(R.string.text_connect_youtube_music_64e032)
                    }, fontSize = if (state.step == AuthStep.TYPE) 38.sp else 30.sp, lineHeight = if (state.step == AuthStep.TYPE) 43.sp else 36.sp, fontWeight = FontWeight.Bold, color = palette.onBackground)
                    Text(when (state.step) {
                        AuthStep.TYPE -> appString(R.string.text_your_collection_and_your_discoveries_together_in_one_player_093361)
                        AuthStep.SERVER -> appString(R.string.text_enter_the_address_of_your_server_3f01ed, (if (state.type == ServerType.JELLYFIN) "Jellyfin" else appString(R.string.text_navidrome_or_subsonic_4e1c18)))
                        AuthStep.CREDENTIALS -> appString(R.string.text_use_your_server_account_to_open_your_library_800ff2)
                        AuthStep.SPOTIFY -> appString(R.string.text_bring_your_playlists_and_saved_music_into_aurora_df697e)
                        AuthStep.YOUTUBE_MUSIC -> appString(R.string.text_your_favourites_mixes_and_new_discoveries_6c2fa1)
                    }, style = MaterialTheme.typography.bodyLarge, color = palette.onSurfaceVariant)
                }
                when (state.step) {
                    AuthStep.TYPE -> TypeStep(onSelectType, requestLocal, permDenied, savedSessions, onUseSaved, !state.loading)
                    AuthStep.SERVER -> ServerStep(state, onScheme, onHost, canContinueServer, onContinueServer)
                    AuthStep.CREDENTIALS -> CredentialsStep(state, onUsername, onPassword, onBack, canSubmit, onSignIn)
                    AuthStep.SPOTIFY -> SpotifyStep(state, onConnectSpotify)
                    AuthStep.YOUTUBE_MUSIC -> YouTubeMusicStep(state, onConnectYouTubeMusic)
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
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TypeStep(onSelectType: (ServerType) -> Unit, onLocal: () -> Unit, permDenied: Boolean,
    savedSessions: List<com.aurora.music.data.Session>, onUseSaved: (com.aurora.music.data.Session) -> Unit, enabled: Boolean) {
    var savedExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (savedSessions.isNotEmpty()) {
            TextButton(onClick = { savedExpanded = !savedExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (savedExpanded) appString(R.string.text_hide_saved_accounts_d0e9da) else appString(R.string.text_continue_with_a_saved_account_a6bc3a, (savedSessions.size)))
            }
            AnimatedVisibility(savedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedSessions.forEach { account ->
                        ServerTypeCard(Icons.Outlined.Person, account.username.ifBlank { account.typeLabel.localizedMediaType() }, account.typeLabel.localizedMediaType(), enabled = enabled) { onUseSaved(account) }
                    }
                }
            }
        }
        ServerTypeCard(Icons.Outlined.PhoneAndroid, appString(R.string.text_music_on_this_device_21b6e9), appString(R.string.text_start_listening_without_an_account_2e734d), enabled = enabled, featured = true, onClick = onLocal)
        SourceLabel(appString(R.string.text_streaming_0247ce))
        ServerTypeCard(Icons.Outlined.PlayCircle, "YouTube Music", appString(R.string.text_your_google_account_playlists_and_mixes_120c0f), enabled = enabled) { onSelectType(ServerType.YOUTUBE_MUSIC) }
        ServerTypeCard(Icons.Outlined.MusicNote, "Spotify", appString(R.string.text_your_library_played_through_youtube_a53b5e), enabled = enabled) { onSelectType(ServerType.SPOTIFY) }
        SourceLabel(appString(R.string.text_your_server_d2f076))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerTile("Navidrome", appString(R.string.text_subsonic_compatible_b4584c), Icons.Outlined.Dns, Modifier.weight(1f), enabled) { onSelectType(ServerType.SUBSONIC) }
            ServerTile("Jellyfin", appString(R.string.text_your_media_library_77d854), Icons.Outlined.Cloud, Modifier.weight(1f), enabled) { onSelectType(ServerType.JELLYFIN) }
        }
        Text(appString(R.string.text_you_can_combine_local_files_server_libraries_and_youtube_music_la_8c6471), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (permDenied) Text(appString(R.string.text_allow_music_access_in_settings_apps_aurora_permissions_to_use_fil_1503ac), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = AuroraRose, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpotifyStep(state: AuthUiState, onConnect: (String) -> Unit) {
    val ctx = LocalContext.current
    var clientId by rememberSaveable { mutableStateOf("") }
    var setupExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            appString(R.string.text_aurora_streams_your_spotify_library_through_youtube_so_it_needs_y_23d0ad),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { setupExpanded = !setupExpanded }) { Text(if (setupExpanded) appString(R.string.text_hide_setup_instructions_9a08e8) else appString(R.string.text_set_up_your_spotify_app_de4e3c)) }
        AnimatedVisibility(setupExpanded) { Column {
        listOf(
            appString(R.string.text_open_the_spotify_developer_dashboard_and_create_an_app_20577c),
            appString(R.string.text_set_the_redirect_uri_to_exactly_aurora_spotify_407b7c),
            appString(R.string.text_under_apis_tick_web_api_and_android_61207d),
            appString(R.string.text_in_user_management_add_your_own_spotify_account_email_2a3bd3),
            appString(R.string.text_copy_the_app_s_client_id_and_paste_it_below_d4d070),
        ).forEachIndexed { i, line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = AuroraRose, modifier = Modifier.width(22.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        } }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(1.dp, AuroraRose.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.spotify.com/dashboard"))) }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(appString(R.string.text_open_spotify_dashboard_7a23e4), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AuroraRose) }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim() },
            enabled = !state.loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (clientId.isNotBlank() && !state.loading) onConnect(clientId) }),
            modifier = Modifier.fillMaxWidth(),
            label = { Text(appString(R.string.text_client_id_a766cd)) },
            placeholder = { Text(appString(R.string.text_e_g_4e041c00d85a40d9_b863af)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.MusicNote, null) },
            colors = fieldColors(),
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(if (state.loading) "" else appString(R.string.text_connect_b65463), enabled = clientId.isNotBlank() && !state.loading, modifier = Modifier.weight(1f), loading = state.loading) { onConnect(clientId) }
        }
    }
}

@Composable
private fun ServerTypeCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, featured: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(if (featured) AuroraRose.copy(alpha = .10f) else MaterialTheme.colorScheme.surface)
        .border(1.dp, if (featured) AuroraRose.copy(alpha = .3f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
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
            placeholder = { Text(if (state.type == ServerType.JELLYFIN) "192.168.1.10:8096" else "192.168.1.10:4533") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Dns, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { if (canContinue) onContinue() }),
            colors = fieldColors(),
        )
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
    var passwordVisible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = AuroraRose)
                Spacer(Modifier.width(12.dp))
                Text(state.scheme + state.host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack, enabled = !state.loading) { Text(appString(R.string.text_edit_530164)) }
            }
        }
        Spacer(Modifier.height(20.dp))
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
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(appString(R.string.text_password_8be3c9)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Lock, null) },
            enabled = !state.loading,
            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (passwordVisible) appString(R.string.text_hide_password_e40123) else appString(R.string.text_show_password_044b85)) } },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit && !state.loading) { focus.clearFocus(); onSignIn() } }),
            colors = fieldColors(),
        )
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
            .clickable(onClick = onClick),
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
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}
