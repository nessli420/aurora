# Audio formats

Aurora checks the file itself. A filename or a server's quality label does not guarantee playback.

## DSD files

| Input | Rates | Channels | Playback output |
| --- | --- | --- | --- |
| DSF, raw DSD | DSD64–1024 | Mono or stereo | 176.4 kHz float PCM, or stereo raw USB |
| DFF, raw DSD | DSD64–1024 | Mono or stereo | 176.4 kHz float PCM, or stereo raw USB |

These rates are 2.8224, 5.6448, 11.2896, 22.5792 and 45.1584 MHz. DSF supports both bit orders with 4096-byte channel blocks. DFF supports mono or left/right stereo.

Playback converts DSD to PCM by default. The normal processing rack and PCM output controls then apply. Signal Path shows the original rate and conversion. The final device rate can differ from 176.4 kHz.

The decoder uses a 48 kHz low-pass filter with 512–8192 taps. It restores filter history when seeking. High-rate files require more CPU; real-time performance depends on the device and enabled processing.

DSF ID3 tags and DFF title/artist chunks are read during playback and local scanning. Local Library also checks visible MediaStore files ending in `.dsf` or `.dff`. Android storage permissions still apply: files hidden from MediaStore cannot be discovered by this scan.

The original Sony DSF specification lists DSD64 and DSD128. Higher rates use the same container layout with higher rate values.

## Raw USB output

Choose **Direct → DSD files → DoP or Native** in USB output settings, then restart Aurora. Raw output bypasses PCM effects and software volume. Use the DAC's own volume control. Playback stops if the raw route is unavailable.

The FiiO KA13 has been tested through native DSD256 and DoP128. Clean left/right listening checks cover DSD64; higher-rate checks cover USB transfers, clocks and playback controls.

**Experimental DSD** enables other DACs and rates up to DSD1024. It is off by default. This is an unverified hardware path, not a compatibility guarantee. It requires high-speed USB Audio Class 2, a supported clock and enough packet capacity. Native output currently requires a Type I raw-data alternate setting with 32-bit slots and MSB-first DSD packing. Vendor-specific mode commands and other native layouts are unsupported. DoP capability cannot be inferred from PCM rate support alone.

For community testing, disconnect headphones first and confirm the DAC switches into DSD mode. Start at low DAC volume for listening. Report the phone, DAC model, DSD rate, DoP/native mode, Experimental DSD setting, DAC mode indicator and Signal Path report. Note whether pause, seeking and reconnection work.

## DST and SACD images

DST-compressed DFF supports unsegmented mono/stereo DSD64. Other DST segmentation modes are rejected. Stereo SACD ISO tracks can be imported from Library & sources without extracting the whole image. SACD support has synthetic-image tests; compatibility with a broad collection of authored images is not yet verified. The same DST limits apply.

## Limits

- 48 kHz-family DSD rates and multichannel DSD are unsupported.
- PCM-to-DSD conversion is not implemented.
- Network Direct and Processed output currently reject DSD files. Convert them to a supported PCM format first.
- Remote files need working byte-range seeking for metadata and playback seeks. Incomplete or inconsistent containers are rejected.

## Format references

- [Sony DSF format specification](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf)
- [Philips DSDIFF 1.5 specification](https://www.sonicstudio.com/pdf/dsd/DSDIFF_1.5_Spec.pdf)
- [FFmpeg DSF reader](https://ffmpeg.org/doxygen/trunk/dsfdec_8c_source.html)
- [DoP 1.1 specification](https://dsd-guide.com/sites/default/files/white-papers/DoP_openStandard_1v1.pdf)
