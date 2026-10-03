package com.aurora.music.desktop.audio

import com.aurora.music.data.Preservation
import com.aurora.music.desktop.audio.decode.SampleKind
import com.aurora.music.desktop.audio.decode.SourceSampleFormat
import com.aurora.music.desktop.audio.decode.StreamInfo
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test

class EngineSignalPathTest {
    private val entry = QueueEntry(1, Song("a", "A", "Artist", "Album", "", 180))
    private val flac = StreamInfo("flac", "flac", 44_100, 2, SourceSampleFormat(SampleKind.INTEGER, 24), 2_100_000, 180_000, true)

    private fun state(output: OutputInfo, processing: ProcessingFacts = ProcessingFacts(), info: StreamInfo = flac) =
        EngineState(entries = listOf(entry), index = 0, playWhenReady = true, phase = EnginePhase.READY, source = info,
            decoderName = "flac", output = output, processing = processing)

    @Test fun exclusiveUntouchedPlaybackIsPreserved() {
        val output = OutputInfo("dac", "DAC", true, 44_100, OutputEncoding.S24_IN_32, false)
        val path = state(output, ProcessingFacts(bitPerfect = true)).signalPath(null, null, null, emptyList())
        assertEquals(Preservation.PRESERVED, path.preservation)
        assertTrue(path.bitPerfect)
        assertEquals("${outputApi(true)} exclusive", path.output)
        assertEquals(24, path.outputStage.format?.bitDepth)
        assertEquals(listOf("Driver and DAC behavior beyond the exclusive ${outputApi(true)} stream are not verified"), path.reasons)
    }

    @Test fun sharedResampledPlaybackIsModifiedWithReasons() {
        val output = OutputInfo(null, "Speakers", false, 48_000, OutputEncoding.F32, true)
        val path = state(output, ProcessingFacts(resampling = true, gainApplied = true)).signalPath(null, null, null, emptyList())
        assertEquals(Preservation.MODIFIED, path.preservation)
        assertFalse(path.bitPerfect)
        assertEquals("Resampled from 44100 Hz to 48000 Hz", path.note)
        assertTrue("Volume, ReplayGain or crossfade gain is applied" in path.reasons)
        assertEquals("System default output", path.device.detail)
    }

    @Test fun lossySourcesIntoIntegerOutputsReportQuantization() {
        val output = OutputInfo("dac", "DAC", true, 48_000, OutputEncoding.S16, false)
        val mp3 = flac.copy(codec = "mp3", container = "mp3", sampleRate = 48_000, sampleFormat = SourceSampleFormat(SampleKind.LOSSY, 0))
        val path = state(output, ProcessingFacts(ditherLabel = "TPDF dither"), mp3).signalPath(null, null, null, emptyList())
        assertEquals("Samples are quantized to 16-bit integers with TPDF dither", path.note)
        assertNull(path.source.format?.bitDepth)
    }

    @Test fun idleEngineHasNoActivePath() {
        assertFalse(EngineState().signalPath(null, null, null, emptyList()).active)
        assertTrue(EngineState(entries = listOf(entry), index = 0).signalPath(null, null, null, emptyList()).active)
    }
}
