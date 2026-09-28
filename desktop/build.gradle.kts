import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.jetbrains.navigation.compose)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.material.color.utilities)
    implementation(libs.jaudiotagger.jvm)
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    testImplementation(libs.junit)
}

val nativeSource = rootProject.layout.projectDirectory.dir("native")
val nativeBuildDir = layout.buildDirectory.dir("native")
val appResources = layout.buildDirectory.dir("appResources")

val configureNative by tasks.registering(Exec::class) {
    inputs.file(nativeSource.file("CMakeLists.txt"))
    outputs.file(nativeBuildDir.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake", "-S", nativeSource.asFile.path, "-B", nativeBuildDir.get().asFile.path,
        "-G", "Visual Studio 17 2022", "-A", "x64",
    )
}

val buildNative by tasks.registering(Exec::class) {
    dependsOn(configureNative)
    inputs.dir(nativeSource)
    inputs.dir(rootProject.layout.projectDirectory.dir("app/src/main/cpp"))
    outputs.dir(nativeBuildDir.map { it.dir("bin") })
    commandLine("cmake", "--build", nativeBuildDir.get().asFile.path, "--config", "Release", "--parallel")
}

val syncNative by tasks.registering(Sync::class) {
    dependsOn(buildNative)
    from(nativeBuildDir.map { it.dir("bin") }) { include("*.dll") }
    into(appResources.map { it.dir("windows-x64") })
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(syncNative) }

tasks.test {
    dependsOn(syncNative)
    systemProperty("compose.application.resources.dir", appResources.get().dir("windows-x64").asFile.path)
}

compose.desktop {
    application {
        mainClass = "com.aurora.music.desktop.MainKt"
        jvmArgs += listOf("-XX:+UseZGC", "-XX:+ZGenerational", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Aurora"
            packageVersion = appVersion.getProperty("versionName")
            description = "Aurora music player"
            vendor = "Aurora"
            appResourcesRootDir.set(appResources)
            modules("java.management", "java.naming", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "Aurora"
                upgradeUuid = "67dc3d5a-e732-4a4a-b9a6-d1126d48bca5"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
            }
        }
    }
}

val androidRes = rootProject.layout.projectDirectory.dir("app/src/main/res")

abstract class GenerateAndroidStrings : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceDir: DirectoryProperty

    private class Values {
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
    }

    @TaskAction
    fun generate() {
        val locales = sources.files.groupBy { it.parentFile.name }.mapNotNull { (dir, files) ->
            localeTag(dir)?.let { tag -> tag to Values().apply { files.sortedBy { it.name }.forEach { read(it, this) } } }
        }.toMap()
        val strings = locales.values.flatMap { it.strings.keys }.distinct().sorted()
        val plurals = locales.values.flatMap { it.plurals.keys }.distinct().sorted()
        val source = kotlinDir.get().asFile.apply { deleteRecursively() }.resolve("com/aurora/music/R.kt")
        source.parentFile.mkdirs()
        source.writeText(buildString {
            appendLine("package com.aurora.music")
            appendLine()
            appendLine("object R {")
            listOf(Triple("string", 0x7f010000, strings), Triple("plurals", 0x7f020000, plurals)).forEach { (type, base, names) ->
                appendLine("    object $type {")
                names.forEachIndexed { index, name -> appendLine("        const val `${name.replace('.', '_')}` = 0x%08x".format(base + index)) }
                appendLine("    }")
            }
            appendLine("}")
        })
        val tables = resourceDir.get().asFile.apply { deleteRecursively() }.resolve("strings").apply { mkdirs() }
        locales.forEach { (tag, values) ->
            tables.resolve("$tag.bin").outputStream().buffered().use { out ->
                fun int(value: Int) = out.write(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
                fun text(value: String?) = if (value == null) int(-1) else value.toByteArray().let { int(it.size); out.write(it) }
                int(strings.size)
                strings.forEach { text(values.strings[it]) }
                int(plurals.size)
                plurals.forEach { name -> QUANTITIES.forEach { text(values.plurals[name]?.get(it)) } }
            }
        }
    }

    private fun localeTag(dir: String): String? =
        if (dir == "values") "default"
        else Regex("values-([a-z]{2,3})(?:-r([A-Z]{2}))?").matchEntire(dir)?.groupValues?.let { (_, language, region) ->
            if (region.isEmpty()) language else "$language-$region"
        }

    private fun read(file: java.io.File, values: Values) {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(file).documentElement.elements().forEach { element ->
            val name = element.getAttribute("name")
            when (element.localName) {
                "string" -> check(values.strings.put(name, text(element)) == null) { "Duplicate string $name in $file" }
                "plurals" -> {
                    val items = element.elements().filter { it.localName == "item" }.associate { item ->
                        item.getAttribute("quantity").also { check(it in QUANTITIES) { "Bad quantity $it in $name" } } to text(item)
                    }
                    check(values.plurals.put(name, items) == null) { "Duplicate plurals $name in $file" }
                }
            }
        }
    }

    private fun org.w3c.dom.Node.children() = (0 until childNodes.length).map(childNodes::item)

    private fun org.w3c.dom.Node.elements() = children().filterIsInstance<org.w3c.dom.Element>()

    private fun isSpace(c: Char) = c == ' ' || c in '\t'..'\r'

    private fun text(element: org.w3c.dom.Element): String {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var styled = false
        fun flush() {
            if (current.isNotEmpty()) segments += current.toString()
            current.setLength(0)
        }
        fun walk(node: org.w3c.dom.Node) {
            node.children().forEach { child ->
                when (child) {
                    is org.w3c.dom.Text -> current.append(child.data)
                    is org.w3c.dom.Element -> {
                        styled = styled || child.namespaceURI == null
                        flush()
                        walk(child)
                        flush()
                    }
                }
            }
        }
        walk(element)
        flush()
        if (!styled && segments.isNotEmpty()) {
            segments[0] = segments.first().trimStart { isSpace(it) }
            segments[segments.lastIndex] = segments.last().trimEnd { isSpace(it) }
        }
        check(!Regex("@null|@empty|[@?]\\S*/\\S+").matches(segments.joinToString("").trim { isSpace(it) })) { "Resource references are not supported: $segments" }
        val out = StringBuilder()
        var quoted = false
        var space = false
        segments.forEach { segment ->
            var i = 0
            while (i < segment.length) {
                val c = segment[i++]
                if (!quoted && isSpace(c)) {
                    if (!space) out.append(' ')
                    space = true
                    continue
                }
                space = false
                when {
                    c == '\\' && i < segment.length -> when (val escaped = segment[i++]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'u' -> {
                            val hex = segment.substring(i, minOf(i + 4, segment.length))
                            check(Regex("[0-9a-fA-F]{4}").matches(hex)) { "Invalid unicode escape in $segment" }
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(escaped)
                    }
                    c == '\\' -> Unit
                    c == '"' -> quoted = !quoted
                    c == '\'' && !quoted -> error("Unescaped apostrophe in $segment")
                    else -> out.append(c)
                }
            }
        }
        return out.toString()
    }

    companion object {
        private val QUANTITIES = listOf("zero", "one", "two", "few", "many", "other")
    }
}

val generateAndroidStrings by tasks.registering(GenerateAndroidStrings::class) {
    sources.from(fileTree(androidRes) { include("values/*.xml", "values-*/*.xml") })
    kotlinDir.set(layout.buildDirectory.dir("generated/androidStrings/kotlin"))
    resourceDir.set(layout.buildDirectory.dir("generated/androidStrings/resources"))
}

val syncAndroidAssets by tasks.registering(Sync::class) {
    from(androidRes.dir("font")) {
        include("dm_sans*.ttf", "plus_jakarta_sans*.ttf", "manrope.ttf")
        exclude("circular_*")
        into("font")
    }
    from(androidRes.file("drawable/ic_aurora_logo.xml")) {
        filter { it.replace("@android:color/transparent", "#00000000") }
        into("drawable")
    }
    filteringCharset = "UTF-8"
    into(layout.buildDirectory.dir("generated/androidAssets"))
}

kotlin.sourceSets.named("main") { kotlin.srcDir(generateAndroidStrings.flatMap { it.kotlinDir }) }

sourceSets.named("main") {
    resources.srcDir(generateAndroidStrings.flatMap { it.resourceDir })
    resources.srcDir(syncAndroidAssets)
}
