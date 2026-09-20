package com.aurora.music.playback.dsd

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

@UnstableApi
class DsdExtractorsFactory(private val delegate: ExtractorsFactory = DefaultExtractorsFactory(), private val rawOutput: Boolean = false) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(DsdExtractor(rawOutput = rawOutput), *delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf(DsdExtractor(rawOutput = rawOutput, sourceContainer = if (uri.scheme == "aurora-sacd") "SACD ISO" else null),
            *delegate.createExtractors(uri, responseHeaders))
}
