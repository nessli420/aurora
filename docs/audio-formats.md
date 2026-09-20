# Audio formats

Aurora checks the file itself. A filename or a server's quality label does not guarantee playback.

## DSD files

| Input | Rates | Channels | Playback output |
| --- | --- | --- | --- |
| DSF, raw DSD | DSD64, DSD128, DSD256, DSD512 | Mono or stereo | 176.4 kHz float PCM |
| DFF, raw DSD | DSD64, DSD128, DSD256, DSD512 | Mono or stereo | 176.4 kHz float PCM |

These rates are 2.8224, 5.6448, 11.2896 and 22.5792 MHz. DSF supports both bit orders with 4096-byte channel blocks. DFF supports mono or left/right stereo with uncompressed `DSD ` data.

Playback always converts DSD to PCM. The normal processing rack and PCM output controls then apply. Signal Path shows the original DSD rate and the conversion. The final device rate can differ from 176.4 kHz.

The decoder uses a 48 kHz low-pass filter with 512–4096 taps. It compensates filter delay, preserves the exact output frame count and restores filter history when seeking. Tests compare output with direct bit convolution, check the 0–30 kHz passband and ultrasonic rejection, and cover partial final bytes, metadata and end-of-track seeking. High-rate files require more CPU; real-time performance depends on the device and enabled processing.

DSF ID3 tags and DFF title/artist chunks are read during playback and local scanning. Local Library also checks visible MediaStore files ending in `.dsf` or `.dff`. Android storage permissions still apply: files hidden from MediaStore cannot be discovered by this scan.

The original Sony DSF specification lists DSD64 and DSD128. DSD256 and DSD512 use the same container layout with higher rate values; Aurora supports these explicitly within the limits above.

## Limits

- DSD1024, 48 kHz-family DSD rates and multichannel DSD are unsupported.
- DST-compressed DFF and SACD ISO images are unsupported.
- DoP, native DSD over USB and PCM-to-DSD conversion are unsupported. USB playback receives PCM after conversion.
- Network Direct and Processed output currently reject DSD files. Convert them to a supported PCM format first.
- Remote files need working byte-range seeking for metadata and playback seeks. Incomplete or inconsistent containers are rejected.

## Format references

- [Sony DSF format specification](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf)
- [Philips DSDIFF 1.5 specification](https://www.sonicstudio.com/pdf/dsd/DSDIFF_1.5_Spec.pdf)
- [FFmpeg DSF reader](https://ffmpeg.org/doxygen/trunk/dsfdec_8c_source.html)
