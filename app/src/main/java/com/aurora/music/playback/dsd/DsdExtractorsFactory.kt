package com.aurora.music.playback.dsd

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

@UnstableApi
class DsdExtractorsFactory(private val delegate: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(DsdExtractor(), *delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf(DsdExtractor(), *delegate.createExtractors(uri, responseHeaders))
}
