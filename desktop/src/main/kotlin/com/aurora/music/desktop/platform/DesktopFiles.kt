package com.aurora.music.desktop.platform

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

fun desktopFileUri(path: String): String = File(path).toPath().toUri().toString()

fun openDesktopUri(uri: String): InputStream? =
    if (!uri.startsWith("file:")) null
    else runCatching { Files.newInputStream(Path.of(URI(uri))) }.getOrNull()
