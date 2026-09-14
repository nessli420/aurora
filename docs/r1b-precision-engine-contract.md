# R1b precision engine contract and prototype

This is the reusable ENG-01/02 foundation for a future processed playback path. It is **not connected to production playback** and does not change existing presets, output selection, convolution, Media3 behavior or the USB bypass path. Full R1 acceptance still requires the existing effects to be migrated, an actual sink adapter, measured convolution, and playback regression evidence for that integrated path.

## Contracts

`playback/engine/AudioBlock.kt` defines schema version 1. An `AudioBlock` owns a reusable interleaved `DoubleArray`; `frameCount` specifies its valid region. A format specifies the sample rate and an explicit mono or stereo layout. Blocks carry input presentation time in microseconds and an optional absolute input-frame position. `AUDIO_TIME_UNSET` means unknown, rather than inferring a clock value. Metadata describes the first input frame before any processing latency.

Each compiled node declares arithmetic and state precision, latency and tail. All current prototype nodes use binary64 sample calculations, coefficients and state. Recursive biquad tails are explicitly unbounded; gain and mono have no stored tail. These nodes have zero algorithmic frame latency. Output adapters have a separate capability contract identifying actual output encoding/layout, clock owner, sample-preserving bypass support and whether those capabilities are observed or merely requested. No physical adapter is implemented here.

The sample precision enum distinguishes signed integer resolution from floating significand precision. In particular, a float32 decoder has 24 significand bits; moving that sample into a double buffer does not manufacture missing source information. This prototype has no adapter for the existing float32 convolver and makes no claim that a mixed old/new chain is binary64.

## Decoder and output boundaries

`PcmBoundary` supports packed little-endian signed PCM16, PCM24, PCM32, IEEE float32 and IEEE float64. Decoding divides integers by 2^(bits-1), preserving every supported integer value exactly in double. Floating values retain their finite source values and headroom; non-finite decoder samples become zero before they can poison recursive state. Buffers are interpreted explicitly as little endian, independently of their Java byte-order flag.

The rack processes the double buffer without integer conversion or clipping between nodes. Final integer encoding clips to its representable range and rounds to nearest, with exact ties toward positive infinity. Optional seeded TPDF dither is applied only at that integer output boundary. Float encoding preserves headroom; float32 output necessarily rounds to float32 precision and clamps double overflow to the largest finite float. Non-finite output values are sanitized to zero. Float64 support is a numerical interchange/test capability, not an Android hardware-output claim.

The separate `bypass` call requires matching input/output encoding and copies original bytes, including signed zero and NaN bit patterns, without processing or dither. A format-changing conversion is never described as unchanged-sample bypass. `PrecisionPcmPipeline` owns one reusable block and requires distinct, adequately sized input/output buffers from its caller.

## Bounded serial rack

`PrecisionSerialRack.compile` runs away from the audio callback. It validates stable unique node IDs, names, wet mix values, channel compatibility, filter stability and memory bounds, copies caller-owned coefficient lists, and allocates buffers and filter history. Limits are 32 nodes, 64 biquads across the rack, 8,192 frames per block, and mono/stereo only.

The prototype supports:

- Gain, with coherent control-thread target changes and a linear ramp measured in frames; both stereo channels receive the same gain for a frame.
- Stereo-to-mono downmix retaining stereo transport and computing `(L + R) / 2` in double.
- Cascaded double-precision biquads, including an RBJ peaking coefficient builder. The recurrence is transposed direct form II: `y = b0*x + z1`, `z1 = b1*x - a1*y + z2`, `z2 = b2*x - a2*y`. State is independent per channel and survives callback boundaries.
- Compiled per-node bypass and wet/dry. Bypassing freezes that node's history. The first prototype accepts only zero-latency nodes, so wet/dry does not introduce an uncompensated latency path.
- Preallocated input, per-node output and final output sample-peak/RMS taps. These values belong to the audio-thread owner; publishing to another thread requires a coherent copy. The taps do not measure true peak or LUFS.

The processing loop takes no locks, performs no I/O and allocates no buffers or objects. Schedule replacement, coefficient interpolation, tail-preserving swaps, click-free graph replacement, parallel paths and latency compensation remain future work. Gain smoothing does not establish those guarantees for other node types. This is a programmatic rack contract, with no rack editor or persistence schema yet.

## Numerical and device validation

`PrecisionEngineTest` covers integer round trips and least-significant-bit edges, float32 finite values and signed zero, sub-float32 binary64 detail, quiet PCM24 through mono/gain/EQ, preserved intermediate headroom, deterministic stereo taps and timing, analytic impulse response, filter state across callback partitions, bypass, gain smoothing, final quantization, seeded TPDF, malformed floating samples, invalid schedules, input buffer validation and defensive coefficient ownership.

`PrecisionEngineDeviceTest` repeats the quiet PCM24 proof on Android and compares a test-only JNI kernel against the Kotlin recurrence over 32,768 stereo frames. The native benchmark library is packaged only into the instrumentation APK; it is not a second production engine. Benchmark dimensions are 256 stereo frames, 64 alternating +/-1.5 dB peaking filters, Q 1.2, logarithmically spaced from 30 Hz to 18 kHz, at 48/96/192 kHz. Each case has 256 warmup calls and 1,000 measured calls.

Three timing cases separate costs: Kotlin flat-array reference kernel; equivalent native block-JNI kernel including marshaling; and the complete Kotlin PCM24-to-float32 rack with preamp, 64 biquads and all taps. Reports include p50, p95, maximum and counts exceeding the 256-frame duration. Results are written on the tested device to `Android/data/com.aurora.music/files/precision-engine-benchmark.json`.

Instrumentation worker timings are comparative measurements, not audio-thread scheduling guarantees. They exclude convolution, AudioTrack/USB writes, native decoder behavior, Mix/crossfade, graph replacement and physical output. The implementation report records captured build/test outcomes and the actual timing results; this contract does not treat the existence of tests as a passing run.

### Captured target-phone result, 2026-09-08

The [A059P/Android 16 arm64 report](../_artifacts/r1b/phone-precision-benchmark.json) records:

| Sample rate | Kotlin kernel p95 | Native block-JNI kernel p95 | Complete Kotlin rack p95 | Complete Kotlin rack maximum | Frame duration |
| --- | ---: | ---: | ---: | ---: | ---: |
| 48 kHz | 106.198 us | 94.063 us | 125.000 us | 149.375 us | 5,333.333 us |
| 96 kHz | 102.084 us | 94.063 us | 123.073 us | 146.875 us | 2,666.667 us |
| 192 kHz | 102.552 us | 94.114 us | 122.969 us | 181.146 us | 1,333.333 us |

Each measured case had zero calls exceeding its frame duration. The native reference kernel's median was approximately 8% faster than the Kotlin reference kernel for this workload. That modest difference supports retaining Kotlin for the next serial-rack integration prototype, while separately measuring convolution/FFT before selecting their implementation language. It does not establish a final production engine decision. The [phone test log](../_artifacts/r1b/phone-core-tests.log) captured all three precision device checks passing, and the JVM run captured all 17 `PrecisionEngineTest` checks passing.

## Next integration decisions

Use measured target-phone JNI and Kotlin costs to select implementation boundaries, accounting for APK size and development complexity. Keep block calls if native code is chosen. Port or replace the float32 convolver and all remaining legacy effects before exposing a high-precision engine option. Preserve observed legacy ordering, user volume/ReplayGain/fade ownership, transport behavior and bypass paths. Connect the chosen engine at a sink boundary that actually receives high-resolution decoder samples; inserting it into the existing PCM16 Media3 processor chain cannot recover previously discarded source precision.
