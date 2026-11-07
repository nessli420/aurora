package com.aurora.music.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// keeps the settings search index in step with the settings screens on both platforms
class SettingsSearchIndexTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val english: Map<String, String> = File(root, "app/src/main/res/values").listFiles { f -> f.extension == "xml" }.orEmpty()
        .flatMap { STRING.findAll(it.readText()).map { m -> m.groupValues[1] to m.groupValues[2] }.toList() }.toMap()

    @Test fun androidIndexCoversEverySettingsRow() = check("app/src/main/java/com/aurora/music/ui/screens/settings")

    @Test fun desktopIndexCoversEverySettingsRow() = check("desktop/src/main/kotlin/com/aurora/music/ui/screens/settings")

    private fun check(path: String) {
        val dir = File(root, path)
        val index = File(dir, "SettingsSearchIndex.kt").readText()
        val missing = dir.listFiles { f -> f.extension == "kt" && f.name !in SKIPPED }.orEmpty().flatMap { file ->
            rowTitles(file.readText()).filter { "R.string.$it," !in index }.map { "${file.name}: $it" }
        }
        assertTrue("add these settings rows to $path/SettingsSearchIndex.kt:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // the string resource each settings row is titled with, as the screens write it
    private fun rowTitles(source: String): List<String> = ROW.findAll(source).mapNotNull { m ->
        var i = m.range.last + 1
        var depth = 1
        val start = i
        while (i < source.length && depth > 0) {
            when (source[i]) { '(' -> depth++; ')' -> depth-- }
            i++
        }
        val args = source.substring(start, (i - 1).coerceAtLeast(start))
        val title = TITLE.find(args) ?: return@mapNotNull null
        if ('{' in args.substring(0, title.range.first)) return@mapNotNull null
        title.groupValues[1].takeUnless { english[it].orEmpty().contains('%') }
    }.toList()

    private companion object {
        val SKIPPED = setOf("SettingsComponents.kt", "SettingsDestinations.kt", "SettingsSearchIndex.kt")
        val ROW = Regex("""Settings(Switch|Nav|Slider)Row\(""")
        val TITLE = Regex("""(?:appString|stringResource)\(\s*(?:com\.aurora\.music\.)?R\.string\.(\w+)""")
        val STRING = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    }
}
