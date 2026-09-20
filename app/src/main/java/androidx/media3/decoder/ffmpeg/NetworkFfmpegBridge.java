package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

// exposes the bundled decoder without reflection or another native library.
@UnstableApi
public final class NetworkFfmpegBridge implements AutoCloseable {
    private final FfmpegAudioDecoder decoder;

    public NetworkFfmpegBridge(Format format) throws FfmpegDecoderException {
        decoder = new FfmpegAudioDecoder(format, 4, 4, 65536, true);
    }

    public DecoderInputBuffer input() throws FfmpegDecoderException { return decoder.dequeueInputBuffer(); }
    public void queue(DecoderInputBuffer input) throws FfmpegDecoderException { decoder.queueInputBuffer(input); }
    public SimpleDecoderOutputBuffer output() throws FfmpegDecoderException { return decoder.dequeueOutputBuffer(); }
    public int sampleRate() { return decoder.getSampleRate(); }
    public int channels() { return decoder.getChannelCount(); }
    public int encoding() { return decoder.getEncoding(); }
    @Override public void close() { decoder.release(); }
}
