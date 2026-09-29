package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.ServerType
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.ui.LocalDesktopContainer

private class Notice(val name: String, val licenseName: String, val detail: String, val source: String, val license: String)

private const val LGPL_21 = "licenses/aurora-dst-LGPL-2.1.txt"

private val notices: List<Notice> get() = listOf(
    Notice("FFmpeg", "LGPL-3.0", appString(R.string.text_audio_decoding_copyright_the_ffmpeg_developers_lgpl_3_0_or_later_6338f2),
        "https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg", ""),
    Notice(appString(R.string.text_dst_decoder_ffabc9), "LGPL-2.1", appString(R.string.text_ffmpeg_dsd_nexus_copyright_2014_peter_ross_lgpl_2_1_or_later_fb6d87),
        "https://github.com/nessli420/aurora/tree/main/app/src/main/cpp/dst", LGPL_21),
    Notice("Chromaprint", "LGPL-2.1", appString(R.string.text_audio_fingerprinting_copyright_luk_lalinsk_lgpl_2_1_or_later_0cb704),
        "https://github.com/acoustid/chromaprint", LGPL_21),
)

private fun resourceText(path: String): String? =
    Notice::class.java.classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

@Composable
fun AboutSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
    val uriHandler = LocalUriHandler.current
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    var openNotice by remember { mutableStateOf<Notice?>(null) }
    var showFontLicenses by remember { mutableStateOf(false) }
    openNotice?.let { notice ->
        val license = remember(notice) { notice.license.takeIf { it.isNotEmpty() }?.let(::resourceText) }
        AlertDialog(onDismissRequest = { openNotice = null }, title = { Text(notice.name) },
            text = { Text(listOfNotNull(notice.detail, license).joinToString("\n\n"),
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { openNotice = null }) { Text(appString(R.string.text_close_bbfa77)) } },
            dismissButton = { TextButton(onClick = { runCatching { uriHandler.openUri(notice.source) } }) { Text(appString(R.string.text_source_6da13a)) } })
    }
    if (showFontLicenses) {
        val licenses = remember {
            listOf(
                "DM Sans" to "dmsans-OFL.txt",
                "Plus Jakarta Sans" to "plusjakartasans-OFL.txt",
                "Manrope" to "manrope-OFL.txt",
            ).map { (name, file) -> name to (resourceText("font_licenses/$file") ?: "SIL Open Font License 1.1") }
        }
        AlertDialog(
            onDismissRequest = { showFontLicenses = false },
            title = { Text(appString(R.string.font_licenses_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    licenses.forEach { (name, license) ->
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(license, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontLicenses = false }) { Text(appString(R.string.text_close_bbfa77)) }
            },
        )
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_about_aurora_b4ed8c), onBack)
        SettingsScroll(contentPadding, horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(88.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(14.dp))
            Text(appString(R.string.text_aurora_eeee9b), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(appString(R.string.text_version_d2f210, (BuildInfo.VERSION_NAME)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(appString(R.string.text_windows_java_2566af, Runtime.version().feature()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            Column(Modifier.widthIn(max = 720.dp)) {
                val source = when (session?.type) {
                    ServerType.JELLYFIN -> "Jellyfin"
                    ServerType.PLEX -> "Plex"
                    ServerType.SUBSONIC -> "Subsonic / OpenSubsonic"
                    ServerType.SPOTIFY -> "Spotify"
                    ServerType.YOUTUBE_MUSIC -> "YouTube Music"
                    ServerType.LOCAL -> appString(R.string.text_on_this_device_a7f962)
                    ServerType.EXTENSION -> appString(R.string.text_extension_659087)
                    null -> "—"
                }
                InfoRow(appString(R.string.text_music_source_cb3c75), source)
                if (session != null && session?.type != ServerType.LOCAL) {
                    InfoRow(appString(R.string.text_signed_in_as_a02107), session?.username ?: "—")
                }
                InfoRow(appString(R.string.text_playback_engine_0255b8), "FFmpeg ${ffmpegVersion()} · WASAPI")
                InfoRow("Compose Multiplatform", BuildInfo.COMPOSE_VERSION)
            }
            Spacer(Modifier.height(8.dp))
            notices.forEach { notice ->
                TextButton(onClick = { openNotice = notice }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text("${notice.name} · ${notice.licenseName}") }
            }
            TextButton(onClick = { showFontLicenses = true }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text(appString(R.string.font_licenses_title)) }

            Spacer(Modifier.height(20.dp))
            Text(
                appString(R.string.text_built_with_compose_multiplatform_material_3_389c3a),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
    }
}

private fun ffmpegVersion(): String =
    org.bytedeco.ffmpeg.global.avutil::class.java.`package`?.implementationVersion?.substringBefore('-') ?: ""

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
