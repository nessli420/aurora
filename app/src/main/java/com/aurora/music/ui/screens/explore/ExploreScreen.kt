package com.aurora.music.ui.screens.explore

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ExploreCategory
import com.aurora.music.data.ExploreData
import com.aurora.music.data.HomeData
import com.aurora.music.model.Song
import com.aurora.music.ui.components.FeedSection
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.SectionHeader
import kotlinx.coroutines.CancellationException

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data object Failed : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

// loads once per key; retry bumps the attempt so the same page loads again
@Composable
private fun <T> rememberLoaded(key: Any, load: suspend () -> T): Pair<Loaded<T>, () -> Unit> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Loaded<T>>(Loaded.Loading) }
    LaunchedEffect(key, attempt) {
        state = Loaded.Loading
        state = try { Loaded.Ready(load()) } catch (e: CancellationException) { throw e } catch (_: Exception) { Loaded.Failed }
    }
    return state to { attempt++ }
}

@Composable
fun ExploreScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onOpenCategory: (ExploreCategory) -> Unit,
    load: (suspend () -> ExploreData)? = null,
) {
    val repository = (LocalContext.current.applicationContext as AuroraApplication).container.repository
    val (state, retry) = rememberLoaded(repository) { load?.invoke() ?: repository.explore() }
    Page(appString(R.string.discovery_explore), contentPadding, onBack) {
        when (val loaded = state) {
            Loaded.Loading -> item(key = "loading") { Loading() }
            Loaded.Failed -> item(key = "failed") { Failed(retry) }
            is Loaded.Ready<ExploreData> -> {
                loaded.value.groups.forEach { group ->
                    item(key = "group:${group.title}") {
                        Column {
                            SectionHeader(group.title, Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(10.dp))
                            CategoryGrid(group.categories, onOpenCategory)
                        }
                    }
                }
                loaded.value.sections.forEach { section ->
                    item(key = "section:${section.id}") { FeedSection(section, onOpenDetail, onPlayAll) }
                }
            }
        }
    }
}

@Composable
fun ExploreCategoryScreen(
    contentPadding: PaddingValues,
    params: String,
    title: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
) {
    val repository = (LocalContext.current.applicationContext as AuroraApplication).container.repository
    val (state, retry) = rememberLoaded(params) { repository.exploreCategory(params) }
    Page(title, contentPadding, onBack) {
        when (val loaded = state) {
            Loaded.Loading -> item(key = "loading") { Loading() }
            Loaded.Failed -> item(key = "failed") { Failed(retry) }
            is Loaded.Ready<HomeData> -> loaded.value.sections.forEach { section ->
                item(key = "section:${section.id}") { FeedSection(section, onOpenDetail, onPlayAll) }
            }
        }
    }
}

@Composable
private fun Page(title: String, contentPadding: PaddingValues, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36),
                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

// as many columns as fit, never fewer than two, so the moods fill a phone's width the way they fill a tablet's
@Composable
private fun CategoryGrid(categories: List<ExploreCategory>, onOpen: (ExploreCategory) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val columns = (maxWidth / ChipMinWidth).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            categories.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { CategoryChip(it, Modifier.weight(1f)) { onOpen(it) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val ChipMinWidth = 170.dp

// youtube music's own look: a coloured stripe down the left of each mood and genre
@Composable
private fun CategoryChip(category: ExploreCategory, modifier: Modifier, onClick: () -> Unit) {
    val stripe = if (category.colorArgb != 0L) Color(category.colorArgb.toInt()) else MaterialTheme.colorScheme.primary
    Row(
        modifier.height(48.dp).paperClip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
        Text(category.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { LottieLoader(Modifier.size(80.dp)) }
}

@Composable
private fun Failed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(appString(R.string.discovery_explore_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text(appString(R.string.text_try_again_042c86)) }
    }
}
