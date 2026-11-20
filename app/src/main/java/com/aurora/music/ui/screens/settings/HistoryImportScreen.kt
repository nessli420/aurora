package com.aurora.music.ui.screens.settings

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.ExportSource
import com.aurora.music.data.HistoryImportPreview
import com.aurora.music.data.HistoryImportRecord
import com.aurora.music.data.HistoryService
import com.aurora.music.data.HistoryScanProgress
import com.aurora.music.data.ImportSkip
import com.aurora.music.data.InsightsPrefs
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.theme.Button
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.OutlinedButton
import com.aurora.music.ui.theme.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface ImportPhase {
    data object Idle : ImportPhase
    data class Reading(val progress: HistoryScanProgress?) : ImportPhase
    data class Ready(val preview: HistoryImportPreview) : ImportPhase
    data object Saving : ImportPhase
}

@Composable
fun HistoryImportScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val importer = container.historyImporter
    val scope = rememberCoroutineScope()
    val records by importer.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val insights by container.settingsStore.insightsPrefs.collectAsStateWithLifecycle(initialValue = InsightsPrefs())
    var phase by remember { mutableStateOf<ImportPhase>(ImportPhase.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    var undoing by remember { mutableStateOf<HistoryImportRecord?>(null) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        phase = ImportPhase.Reading(null)
        job = scope.launch {
            try {
                val sources = uris.map { uri -> source(context, uri) }
                val preview = importer.scan(sources) { phase = ImportPhase.Reading(it) }
                phase = ImportPhase.Ready(preview)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                phase = ImportPhase.Idle
                toast(appString(R.string.history_import_unreadable))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.history_import_title), onBack)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ImportNote(appString(R.string.history_import_intro)) }
            item {
                SettingsGroup {
                    AnimatedContent(phase, transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using null },
                        contentKey = { it::class }, label = "historyImport") { current ->
                        when (current) {
                            ImportPhase.Idle -> PickRow { picker.launch(arrayOf("*/*")) }
                            is ImportPhase.Reading -> ProgressBlock(appString(R.string.history_import_reading), current.progress) {
                                job?.cancel(); phase = ImportPhase.Idle
                            }
                            ImportPhase.Saving -> ProgressBlock(appString(R.string.history_import_saving), null, null)
                            is ImportPhase.Ready -> PreviewBlock(current.preview, insights.enabled, onCancel = { phase = ImportPhase.Idle }) {
                                phase = ImportPhase.Saving
                                scope.launch {
                                    val added = runCatching { importer.commit(current.preview) }
                                    phase = ImportPhase.Idle
                                    toast(added.fold({ appPlural(R.plurals.history_import_plays, it) }, { appString(R.string.history_import_unreadable) }))
                                }
                            }
                        }
                    }
                }
            }
            HistoryService.entries.forEach { service ->
                item { SettingsSectionTitle(service.label) }
                item { ServiceGuide(service) }
            }
            item { SettingsSectionTitle(appString(R.string.history_import_past)) }
            if (records.isEmpty()) item { EmptyState(Icons.Filled.History, appString(R.string.history_import_none)) }
            else item {
                SettingsGroup {
                    records.forEachIndexed { i, record ->
                        if (i > 0) SettingsRowDivider()
                        RowScaffold(Icons.Filled.History, record.service.label, recordLine(record), onClick = null) {
                            TextButton(onClick = { undoing = record }, enabled = phase !is ImportPhase.Saving) {
                                Text(appString(R.string.history_import_undo), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    undoing?.let { record ->
        AlertDialog(
            onDismissRequest = { undoing = null },
            title = { Text(appString(R.string.history_import_undo_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("${record.service.label} · ${appPlural(R.plurals.history_import_plays, record.plays)}\n\n" + appString(R.string.history_import_undo_body)) },
            confirmButton = {
                TextButton(onClick = {
                    undoing = null
                    scope.launch {
                        runCatching { importer.undo(record.origin) }
                        toast(appString(R.string.history_import_undone))
                    }
                }) { Text(appString(R.string.history_import_undo), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { undoing = null }) { Text(appString(R.string.text_cancel_77dfd2)) } },
        )
    }
}

private fun source(context: android.content.Context, uri: Uri): ExportSource {
    var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
    var size: Long? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    return ExportSource(name, size) { requireNotNull(context.contentResolver.openInputStream(uri)) }
}

private fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun recordLine(record: HistoryImportRecord): String = appString(R.string.history_import_record,
    appPlural(R.plurals.history_import_plays, record.plays), appString(R.string.history_import_range, date(record.from), date(record.to)), date(record.importedAt))

private fun skipLabel(reason: ImportSkip): String = appString(when (reason) {
    ImportSkip.PODCAST -> R.string.history_import_skip_podcast
    ImportSkip.AUDIOBOOK -> R.string.history_import_skip_audiobook
    ImportSkip.VIDEO -> R.string.history_import_skip_video
    ImportSkip.AD -> R.string.history_import_skip_ad
    ImportSkip.REMOVED -> R.string.history_import_skip_removed
    ImportSkip.NO_TRACK -> R.string.history_import_skip_no_track
    ImportSkip.NO_DATE -> R.string.history_import_skip_no_date
    ImportSkip.NOT_PLAYED -> R.string.history_import_skip_not_played
    ImportSkip.NOT_A_PLAY -> R.string.history_import_skip_not_a_play
    ImportSkip.PRIVATE -> R.string.history_import_skip_private
    ImportSkip.DUPLICATE -> R.string.history_import_skip_duplicate
    ImportSkip.ALREADY_IMPORTED -> R.string.history_import_skip_already
    ImportSkip.IN_AURORA_HISTORY -> R.string.history_import_skip_aurora
    ImportSkip.HISTORY_FULL -> R.string.history_import_skip_full
})

@Composable
private fun PickRow(onPick: () -> Unit) {
    RowScaffold(Icons.Filled.FileOpen, appString(R.string.history_import_pick), appString(R.string.history_import_pick_hint), onClick = onPick) {}
}

@Composable
private fun ProgressBlock(title: String, progress: HistoryScanProgress?, onCancel: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val fraction = progress?.fraction
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(progress?.let { appString(R.string.history_import_rows, "%,d".format(it.rows)) }.orEmpty(), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onCancel != null) TextButton(onClick = onCancel) { Text(appString(R.string.text_cancel_77dfd2)) }
        }
    }
}

@Composable
private fun PreviewBlock(preview: HistoryImportPreview, insightsOn: Boolean, onCancel: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (preview.plays.isEmpty()) appString(R.string.history_import_nothing) else appString(R.string.history_import_preview_title),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (preview.plays.isNotEmpty()) {
            Text(preview.services.joinToString(" · ") { it.label } + " · " + appPlural(R.plurals.history_import_plays, preview.plays.size),
                style = MaterialTheme.typography.bodyLarge)
            val from = preview.from
            val to = preview.to
            if (from != null && to != null) Text(appString(R.string.history_import_range, date(from), date(to)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(appString(R.string.history_import_linked, "%,d".format(preview.linked)), style = MaterialTheme.typography.bodyMedium)
            if (preview.unknownTime > 0) Text(appString(R.string.history_import_unknown_time, "%,d".format(preview.unknownTime)),
                style = MaterialTheme.typography.bodyMedium)
            if (preview.unknownArtist > 0) Text(appString(R.string.history_import_unknown_artist, "%,d".format(preview.unknownArtist)),
                style = MaterialTheme.typography.bodyMedium)
        }
        val skipped = preview.skipped.filterValues { it > 0 }
        if (skipped.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(appString(R.string.history_import_left_out), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            skipped.forEach { (reason, count) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(skipLabel(reason), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%,d".format(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (preview.files.isNotEmpty()) Text(appString(R.string.history_import_files, preview.files.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (insightsOn && preview.plays.isNotEmpty()) Text(appString(R.string.history_import_insights_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel) { Text(appString(R.string.text_cancel_77dfd2)) }
            if (preview.plays.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onImport) { Text(appString(R.string.history_import_confirm)) }
            }
        }
    }
}

@Composable
private fun ServiceGuide(service: HistoryService) {
    val uri = LocalUriHandler.current
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appString(when (service) {
                HistoryService.SPOTIFY -> R.string.history_import_spotify_steps
                HistoryService.APPLE_MUSIC -> R.string.history_import_apple_steps
                HistoryService.YOUTUBE_MUSIC -> R.string.history_import_youtube_steps
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { runCatching { uri.openUri(service.requestUrl) } }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.history_import_request))
            }
        }
    }
}

@Composable
private fun ImportNote(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
