import java.awt.Image
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.imageio.ImageIO

val appVersion: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("versionName").replace('-', '~')
val appId = "aurora-Aurora"
val homepage = "https://github.com/nessli420/aurora"
val maintainer = "shuja <raikommix@gmail.com>"
val summary = "Music player for local files and home music servers"
val details = listOf(
    "Aurora plays music from local folders and from Navidrome/Subsonic, Jellyfin",
    "and Plex servers through its own sound engine, with equalizers, an effects",
    "rack, convolution and ReplayGain. Playback is gapless with optional",
    "crossfade, and exclusive mode sends bit-perfect audio straight to an ALSA",
    "device.",
)
val iconSizes = listOf(48, 64, 128, 256, 512)

data class Download(val name: String, val url: String, val sha256: String)

val unloadedNatives = Regex("lib(jni)?(avdevice|avfilter|swscale)[.-].*")

val appimagetool = Download(
    "appimagetool-1.9.1-x86_64.AppImage",
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage",
    "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0",
)
val appimageRuntime = Download(
    "type2-runtime-20251108-x86_64",
    "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
    "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d",
)

val packaging = layout.projectDirectory.dir("packaging")
val work = layout.buildDirectory.dir("linux")
val binaries = layout.buildDirectory.dir("compose/binaries/main")
val appImage = binaries.map { it.dir("app/Aurora") }
val stageRoot = work.map { it.dir("root") }
val createDistributable = "createDistributable"

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

fun requireTool(name: String, hint: String) {
    val found = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { File(it, name).canExecute() }
    if (!found) throw GradleException("$name was not found on PATH; $hint")
}

fun command(dir: File, vararg args: String, env: Map<String, String> = emptyMap(), allowFailure: Boolean = false): String {
    val log = File.createTempFile("aurora-command", ".log")
    try {
        val process = ProcessBuilder(*args).directory(dir).redirectError(log).apply { environment().putAll(env) }.start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0 && !allowFailure) throw GradleException("${args.joinToString(" ")} failed:\n${log.readText()}$output")
        return output
    } finally {
        log.delete()
    }
}

fun isElf(file: File): Boolean = file.isFile && !Files.isSymbolicLink(file.toPath()) &&
    file.inputStream().use { it.readNBytes(4) }.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))

fun isX64Elf(bytes: ByteArray): Boolean = bytes.size > 19 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
    bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[4] == 2.toByte() && bytes[18] == 0x3e.toByte()

fun needed(file: File): List<String> = command(file.parentFile, "readelf", "-d", file.path).lines()
    .mapNotNull { Regex("""\(NEEDED\).*\[(.+)]""").find(it)?.groupValues?.get(1) }

class NativeCode(val image: List<File>, val jars: List<File>) {
    val bundled: Set<String> = (image + jars).map { it.name }.toSet()
    val loaded: List<File> = image + jars.filterNot { unloadedNatives.matches(it.name) }
    val jarRequirements: List<String> by lazy {
        jars.filterNot { unloadedNatives.matches(it.name) }.flatMap(::needed).distinct().filterNot { it in bundled }.sorted()
    }
}

fun nativeCode(): NativeCode = NativeCode(
    stageRoot.get().dir("opt/aurora").asFile.walkTopDown().filter(::isElf).sortedBy { it.path }.toList(),
    work.get().dir("jar-natives").asFile.listFiles().orEmpty().sortedBy { it.name },
)

val linuxIcons by tasks.registering {
    val source = packaging.file("aurora.png")
    val output = work.map { it.dir("icons") }
    inputs.file(source)
    outputs.dir(output)
    doLast {
        val image = ImageIO.read(source.asFile)
        val root = output.get().asFile.apply { deleteRecursively() }
        iconSizes.forEach { size ->
            val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            scaled.createGraphics().apply {
                drawImage(image.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null)
                dispose()
            }
            File(root, "hicolor/${size}x$size/apps/$appId.png").apply { parentFile.mkdirs() }.also { ImageIO.write(scaled, "png", it) }
        }
    }
}

val linuxJarNatives by tasks.registering {
    dependsOn(createDistributable)
    val output = work.map { it.dir("jar-natives") }
    inputs.dir(appImage.map { it.dir("lib/app") })
    outputs.dir(output)
    doLast {
        val dir = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        appImage.get().dir("lib/app").asFile.listFiles { file -> file.extension == "jar" }.orEmpty().sorted().forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && Regex("""[^/]+\.so(\.\d+)*""").matches(it.name.substringAfterLast('/')) }.forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (isX64Elf(bytes)) File(dir, entry.name.substringAfterLast('/')).writeBytes(bytes)
                }
            }
        }
    }
}

val linuxRoot by tasks.registering(Sync::class) {
    dependsOn(createDistributable)
    into(stageRoot)
    from(appImage) { into("opt/aurora") }
    from(packaging.file("linux/$appId.desktop")) { into("usr/share/applications") }
    from(linuxIcons) { into("usr/share/icons") }
    doLast {
        val link = stageRoot.get().file("usr/bin/aurora").asFile.toPath()
        Files.createDirectories(link.parent)
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, File("../../opt/aurora/bin/Aurora").toPath())
    }
}

tasks.register("packageDeb") {
    group = "compose desktop"
    description = "Builds the Debian/Ubuntu package."
    dependsOn(linuxRoot, linuxJarNatives)
    val output = binaries.map { it.file("deb/aurora_${appVersion}_amd64.deb") }
    outputs.file(output)
    doLast {
        requireTool("dpkg-deb", "install dpkg-dev to build the .deb")
        requireTool("dpkg-shlibdeps", "install dpkg-dev to build the .deb")
        val dir = work.get().dir("deb").asFile.apply { deleteRecursively(); mkdirs() }
        val root = File(dir, "root")
        command(dir, "cp", "-al", stageRoot.get().asFile.path, root.path)
        val natives = nativeCode()
        val excluded = natives.loaded.flatMap(::needed).distinct().filter { it in natives.bundled }.flatMap { soname ->
            command(dir, "dpkg-query", "-S", "*/$soname", allowFailure = true).lines().filter { ": /" in it }
                .flatMap { it.substringBefore(": /").split(", ") }.map { it.substringBefore(':') }
        }.distinct()
        File(dir, "debian").mkdirs()
        File(dir, "debian/control").writeText("Source: aurora\n\nPackage: aurora\nArchitecture: amd64\n")
        val shlibs = command(dir, "dpkg-shlibdeps", "-O", "--ignore-missing-info", *excluded.map { "-x$it" }.toTypedArray(),
            *natives.loaded.map { "-l${it.parent}" }.distinct().toTypedArray(), *natives.loaded.map { it.path }.toTypedArray())
        val depends = shlibs.lines().first { it.startsWith("shlibs:Depends=") }.substringAfter('=').split(", ").map { clause ->
            Regex("""^(\S+)t64(\s.*)?$""").find(clause)?.let { "$clause | ${it.groupValues[1]}${it.groupValues[2]}" } ?: clause
        }
        val installedKb = root.walkTopDown().sumOf { if (it.isFile && !Files.isSymbolicLink(it.toPath())) (it.length() + 1023) / 1024 else 1L }
        File(root, "DEBIAN").mkdirs()
        File(root, "DEBIAN/control").writeText(buildString {
            appendLine("Package: aurora")
            appendLine("Version: $appVersion")
            appendLine("Section: sound")
            appendLine("Priority: optional")
            appendLine("Architecture: amd64")
            appendLine("Maintainer: $maintainer")
            appendLine("Installed-Size: $installedKb")
            appendLine("Depends: ${depends.joinToString(", ")}")
            appendLine("Recommends: xdg-desktop-portal")
            appendLine("Homepage: $homepage")
            appendLine("Description: $summary")
            details.forEach { appendLine(" $it") }
        })
        val target = output.get().asFile.apply { parentFile.mkdirs(); delete() }
        command(dir, "dpkg-deb", "--build", "--root-owner-group", "-Zxz", root.path, target.path)
        root.deleteRecursively()
    }
}

tasks.register("packageRpm") {
    group = "compose desktop"
    description = "Builds the Fedora/openSUSE package."
    dependsOn(linuxRoot, linuxJarNatives)
    val output = binaries.map { it.file("rpm/aurora-$appVersion-1.x86_64.rpm") }
    outputs.file(output)
    doLast {
        requireTool("rpmbuild", "install rpm (or rpm-build) to build the .rpm")
        val dir = work.get().dir("rpm").asFile.apply { deleteRecursively(); mkdirs() }
        val natives = nativeCode()
        val bundled = natives.bundled.sorted().joinToString("|") { it.replace(".", "[.]") }
        val spec = File(dir, "aurora.spec")
        spec.writeText(buildString {
            appendLine("%global debug_package %{nil}")
            appendLine("%global __os_install_post %{nil}")
            appendLine("%global _build_id_links none")
            appendLine("%global __provides_exclude_from ^/opt/aurora/.*$")
            appendLine("%global __requires_exclude ^($bundled)[(]")
            appendLine()
            appendLine("Name: aurora")
            appendLine("Version: $appVersion")
            appendLine("Release: 1")
            appendLine("Summary: $summary")
            appendLine("License: Apache-2.0")
            appendLine("URL: $homepage")
            appendLine("Packager: $maintainer")
            appendLine("ExclusiveArch: x86_64")
            natives.jarRequirements.forEach { appendLine("Requires: $it()(64bit)") }
            appendLine("Recommends: xdg-desktop-portal")
            appendLine()
            appendLine("%description")
            details.forEach { appendLine(it) }
            appendLine()
            appendLine("%install")
            appendLine("cp -a '${stageRoot.get().asFile.path}/.' '%{buildroot}/'")
            appendLine()
            appendLine("%files")
            appendLine("/opt/aurora")
            appendLine("/usr/bin/aurora")
            appendLine("/usr/share/applications/$appId.desktop")
            iconSizes.forEach { appendLine("/usr/share/icons/hicolor/${it}x$it/apps/$appId.png") }
        })
        val target = output.get().asFile.apply { parentFile.mkdirs(); delete() }
        command(dir, "rpmbuild", "-bb", "--target", "x86_64",
            "--define", "_topdir ${dir.path}",
            "--define", "_rpmdir ${target.parentFile.path}",
            "--define", "_build_name_fmt %%{NAME}-%%{VERSION}-%%{RELEASE}.%%{ARCH}.rpm",
            "--define", "_binary_payload w19.zstdio",
            spec.path)
        File(dir, "BUILDROOT").deleteRecursively()
    }
}

val appImageTools by tasks.registering {
    val dir = work.map { it.dir("tools") }
    val downloads = listOf(appimagetool, appimageRuntime)
    inputs.property("downloads", downloads.map { "${it.url}#${it.sha256}" })
    outputs.files(downloads.map { download -> dir.map { it.file(download.name) } })
    doLast {
        downloads.forEach { download ->
            val file = dir.get().file(download.name).asFile
            if (!file.isFile || sha256(file) != download.sha256) {
                file.parentFile.mkdirs()
                URI(download.url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                val actual = sha256(file)
                if (actual != download.sha256) {
                    file.delete()
                    throw GradleException("${download.url} has SHA-256 $actual, expected ${download.sha256}")
                }
            }
            file.setExecutable(true)
        }
    }
}

tasks.register("packageAppImage") {
    group = "compose desktop"
    description = "Builds the portable AppImage."
    dependsOn(linuxRoot, appImageTools)
    val output = binaries.map { it.file("appimage/Aurora-$appVersion-x86_64.AppImage") }
    inputs.dir(packaging.dir("linux"))
    outputs.file(output)
    doLast {
        val dir = work.get().dir("appimage").asFile.apply { deleteRecursively(); mkdirs() }
        val appDir = File(dir, "Aurora.AppDir")
        command(dir, "cp", "-al", stageRoot.get().asFile.path, appDir.path)
        packaging.file("linux/AppRun").asFile.copyTo(File(appDir, "AppRun")).setExecutable(true, false)
        packaging.file("linux/$appId.desktop").asFile.copyTo(File(appDir, "$appId.desktop"))
        File(appDir, "usr/share/icons/hicolor/256x256/apps/$appId.png").copyTo(File(appDir, "$appId.png"))
        Files.createSymbolicLink(File(appDir, ".DirIcon").toPath(), File("$appId.png").toPath())
        val tools = work.get().dir("tools")
        val target = output.get().asFile.apply { parentFile.mkdirs(); delete() }
        command(dir, tools.file(appimagetool.name).asFile.path, "--no-appstream",
            "--runtime-file", tools.file(appimageRuntime.name).asFile.path, appDir.path, target.path,
            env = mapOf("ARCH" to "x86_64", "APPIMAGE_EXTRACT_AND_RUN" to "1"))
        appDir.deleteRecursively()
    }
}

tasks.matching { it.name == "packageDistributionForCurrentOS" }.configureEach {
    dependsOn("packageDeb", "packageRpm", "packageAppImage")
}
