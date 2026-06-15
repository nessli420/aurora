package com.aurora.music.ui.screens.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF2A0E1C), MaterialTheme.colorScheme.background))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            val transition = rememberInfiniteTransition(label = "logo")
            val angle by transition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
                label = "spin",
            )
            Box(
                Modifier
                    .size(92.dp)
                    .rotate(angle)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(listOf(AuroraRose, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, AuroraRose))),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(74.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.GraphicEq, null, tint = AuroraRose, modifier = Modifier.size(38.dp).rotate(-angle))
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Aurora", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                when (state.step) {
                    AuthStep.TYPE -> "Choose your music server"
                    AuthStep.SERVER -> "Enter the server address"
                    AuthStep.CREDENTIALS -> "Sign in to ${if (state.type == ServerType.JELLYFIN) "Jellyfin" else "Navidrome"}"
                    AuthStep.SPOTIFY -> "Connect your own Spotify app"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            when (state.step) {
                AuthStep.TYPE -> TypeStep(onSelectType, requestLocal, permDenied, savedSessions, onUseSaved)
                AuthStep.SERVER -> ServerStep(state, onScheme, onHost, onBack, canContinueServer, onContinueServer)
                AuthStep.CREDENTIALS -> CredentialsStep(state, onUsername, onPassword, onBack, canSubmit, onSignIn)
                AuthStep.SPOTIFY -> SpotifyStep(state, onBack, onConnectSpotify)
            }

            if (state.loading && state.type == ServerType.SPOTIFY) {
                Spacer(Modifier.height(20.dp))
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(56.dp))
                Text("Connecting to Spotify…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }

            AnimatedVisibility(visible = state.error != null) {
                Text(
                    state.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TypeStep(
    onSelectType: (ServerType) -> Unit,
    onLocal: () -> Unit,
    permDenied: Boolean,
    savedSessions: List<com.aurora.music.data.Session> = emptyList(),
    onUseSaved: (com.aurora.music.data.Session) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (savedSessions.isNotEmpty()) {
            Text("Saved logins", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            savedSessions.forEach { s ->
                val host = s.server.removePrefix("http://").removePrefix("https://")
                ServerTypeCard(
                    icon = Icons.Outlined.Person,
                    title = "Continue as ${s.username}",
                    subtitle = "${s.typeLabel}${if (s.type != ServerType.LOCAL) " · $host" else ""}",
                    onClick = { onUseSaved(s) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("Or add a server", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ServerTypeCard(
            icon = Icons.Outlined.PhoneAndroid,
            title = "Local",
            subtitle = "Music files on this device · no sign-in",
            onClick = onLocal,
        )
        ServerTypeCard(
            icon = Icons.Outlined.Dns,
            title = "Navidrome",
            subtitle = "Subsonic / OpenSubsonic",
            onClick = { onSelectType(ServerType.SUBSONIC) },
        )
        ServerTypeCard(
            icon = Icons.Outlined.Cloud,
            title = "Jellyfin",
            subtitle = "Jellyfin media server",
            onClick = { onSelectType(ServerType.JELLYFIN) },
        )
        ServerTypeCard(
            icon = Icons.Outlined.MusicNote,
            title = "Spotify",
            subtitle = "Your library · streamed via YouTube",
            onClick = { onSelectType(ServerType.SPOTIFY) },
        )
        if (permDenied) {
            Text(
                "Permission to read your music is needed for Local mode. Enable it in Settings → Apps → Aurora → Permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SpotifyStep(state: AuthUiState, onBack: () -> Unit, onConnect: (String) -> Unit) {
    val ctx = LocalContext.current
    var clientId by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Aurora streams your Spotify library through YouTube, so it needs your own free Spotify app:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            "Open the Spotify Developer Dashboard and create an app.",
            "Set the Redirect URI to exactly:  aurora://spotify",
            "Under APIs, tick Web API (and Android).",
            "In User Management, add your own Spotify account email.",
            "Copy the app's Client ID and paste it below.",
        ).forEachIndexed { i, line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = AuroraRose, modifier = Modifier.width(22.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(1.dp, AuroraRose.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.spotify.com/dashboard"))) }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Open Spotify Dashboard", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AuroraRose) }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Client ID") },
            placeholder = { Text("e.g. 4e041c00d85a40d9…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.MusicNote, null) },
            colors = fieldColors(),
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackButton(onBack)
            PrimaryButton(if (state.loading) "" else "Connect", enabled = clientId.isNotBlank() && !state.loading, modifier = Modifier.weight(1f), loading = state.loading) { onConnect(clientId) }
        }
    }
}

@Composable
private fun ServerTypeCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(AuroraRose.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = AuroraRose, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    onBack: () -> Unit,
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
            label = { Text("Server IP and port") },
            placeholder = { Text(if (state.type == ServerType.JELLYFIN) "192.168.1.10:8096" else "192.168.1.10:4533") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Dns, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackButton(onBack)
            PrimaryButton("Continue", enabled = canContinue, modifier = Modifier.weight(1f), onClick = onContinue)
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
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsername,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Person, null) },
            colors = fieldColors(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Lock, null) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors(),
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackButton(onBack)
            PrimaryButton(if (state.loading) "" else "Connect", enabled = canSubmit && !state.loading, modifier = Modifier.weight(1f), loading = state.loading, onClick = onSignIn)
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
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(54.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) AuroraRose else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(48.dp))
        } else {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
