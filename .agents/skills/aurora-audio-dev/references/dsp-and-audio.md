# DSP & advanced audio

## The signal chain
`decoder → ExoPlayer renderer → app AudioProcessors (mono, AuroraDsp, Convolver) → resampler/AudioSink
→ output device`. App processors run **only in the 16-bit pipeline** — float output bypasses them (see
media3 reference). ReplayGain/crossfade/fades act on `player.volume` in the audio tick, downstream of
the processors. The two EQ engines are mutually exclusive so they never stack:
- **SYSTEM** = the Android `AudioEffect` chain (`AudioEffectsController` on `audioSessionId`): graphic
  EQ, bass boost, virtualizer, loudness — device-dependent, may no-op on some hardware.
- **CUSTOM** = Aurora's device-independent software DSP (`AuroraDspProcessor`).
- **OFF** = no tone shaping.

## AudioProcessor lifecycle (BaseAudioProcessor)
Subclass `androidx.media3.common.audio.BaseAudioProcessor`. Key methods:
- `onConfigure(inputAudioFormat)` → return the output `AudioFormat` (Aurora keeps 16-bit stereo).
- `queueInput(ByteBuffer)` → read input, write to `replaceOutputBuffer(size)`, process in place.
- `onQueueEndOfStream()` → flush tails (convolution overlap, delay ring buffers). **Note:**
  `queueEndOfStream` is final — override `onQueueEndOfStream()`.
- Toggle work with a `@Volatile var enabled` + an `update(params)` that swaps precomputed coefficients;
  never allocate or block in `queueInput`. Read prefs on the service scope and push immutable params
  into the processor.

The processor reads 16-bit shorts, converts to float for the math (headroom, no integer clipping),
then writes back 16-bit. This "process float internally, transport 16-bit" pattern is deliberate — it
keeps the app processors in the pipeline (unlike full float output) while avoiding quantization noise
in the math.

## DSP coefficients (`DspCoeffBuilder.kt`)
RBJ "Audio EQ Cookbook" biquads. Implemented filter types: **peaking**, **low-shelf**, **high-shelf**
(maps AutoEq PK/LSC/HSC). A biquad is `b0,b1,b2,a1,a2` (normalized by a0), applied as a Direct-Form
transposed II per channel with state carried across buffers. Layout notes:
- Graphic layouts: 10/15/31-band with per-layout Q (≈1.41 / 2.1 / 4.32). `GRAPHIC_LAYOUTS` holds
  `GraphicLayout(name, freqs, q)`.
- Capacity: `MAX_GRAPHIC=31`, `MAX_PARAMETRIC=12` (bumped from 6 because AutoEQ profiles can have
  ~10 filters including shelves), `TOTAL_BIQUADS = MAX_GRAPHIC + MAX_PARAMETRIC`.
- `DspParams` carries graphic gains+freqs+Q, parametric `DspBand(freq,gain,q,type)`, preamp, balance,
  width, crossfeed, saturation, per-channel delay (ms, ring buffer up to `MAX_CHANNEL_DELAY=9600`
  samples) and trim (dB), limiter and compressor settings.
- **Headroom**: `eqPeakDb(params, fs=48000)` evaluates the cascaded biquad magnitude response across
  20 Hz–20 kHz and returns the max positive gain in dB — used to auto-suggest a preamp so boosted EQ
  doesn't clip. Always offer attenuating preamp for positive-gain curves.

## Convolution (impulse responses)
`ConvolutionProcessor.kt` + `Fft.kt`: partitioned overlap-save FFT convolution (real radix-2 FFT),
a `FloatFifo`, and a WAV loader (`loadWav`). Independent of the EQ engine; has its own enable +
makeup-gain. Load the IR off the main thread; flush in `onQueueEndOfStream`.

## ReplayGain
`replayGainMode`: 0 off / 1 track / 2 album. Gain (dB) travels in each `MediaItem`'s
`MediaMetadata.extras` (`rgTrack`/`rgAlbum`, set in the VM's `toMediaItem`). Applied as a volume
multiplier in the audio tick, **attenuate-only** (`coerceIn(0.1f, 1f)`) to avoid inter-sample clipping
on quiet tracks. `Math.pow(10.0, gainDb/20.0)`.

## Bit-perfect output (Android 14+)
`setPreferredMixerAttributes(attrs, device, MIXER_BEHAVIOR_BIT_PERFECT)` on a USB DAC, only when float
passthrough is engaged. Query `getSupportedMixerAttributes(device)` first — many devices (e.g. Nothing
Phone 3a Pro) expose **no** bit-perfect mixer, so always fall back gracefully and report it. Truly
bit-perfect = exclusive mixer grant (no resampling). Float passthrough alone skips Aurora's 16-bit
stage but the system mixer may still resample; Bluetooth is **never** bit-perfect (re-encoded by
LDAC/aptX/AAC/SBC). The verdict + format is published to `container.signalPath` (a `SignalPath`
StateFlow) for the UI's live signal-path card — anything that modifies samples (DSP, mono, RG≠0,
convolution, speed≠1) flips `bitPerfect=false` with an explanatory `note`.

## AutoEQ
`data/AutoEqRepository.kt` + `AutoEqController.kt`: a bundled `assets/autoeq_index.tsv` (thousands of
profiles, generated from the GitHub tree API) + live fetch of `ParametricEQ.txt` from the AutoEq raw
GitHub. The controller binds a profile to an output device (`AudioDeviceCallback`) and auto-applies on
connect. Its flows use `.distinctUntilChanged()` and it **only applies bound corrections — never
clears a manual EQ** (clearing on every settings write was a real bug).

## Bluetooth codec awareness & device routing
The signal-path computation detects BT output (`TYPE_BLUETOOTH_A2DP`/`BLE_HEADSET`/`BLE_SPEAKER`) and
notes "re-encoded by the system codec". The exact active codec name (LDAC/aptX/…) is **not** available
to a third-party app — don't claim to read it. Output routing uses `player.setPreferredAudioDevice`
from `container.preferredAudioDeviceId`.

## Practical guidance
- Switching the active engine, layout, or any DSP param mid-playback is fine (volatile flags +
  `update()`), but switching what the **sink** does (float on/off) only takes effect on the next
  playback start.
- When reasoning about an audio bug, walk the chain: is the processor even in the pipeline (float?),
  are coefficients being recomputed on the param change, is the tick overwriting volume, is the device
  what you think it is. Most "my EQ does nothing" reports are the float-bypass.
