# Measured device presets

The device library combines the existing 5,057 AutoEQ index entries with 1,083
Spinorama speaker entries. Browsing and model search work from bundled indexes;
applying a preset downloads its parametric filter file. Applied filters and device
bindings continue to use Aurora's Custom DSP and existing settings persistence.

- **AutoEQ:** https://github.com/jaakkopasanen/AutoEq
  The `results/<source>/<measurement rig and form factor>/...` path supplies the
  category. Over-ear/on-ear includes wired, Bluetooth and studio models. In-ear
  includes IEMs and sealed wireless buds; earbud includes unsealed/open-ear models.
  These categories describe the source's measurement form factor, not a complete
  connection-type database. Each result keeps its original measurement source.
- **Spinorama:** https://github.com/pierreaubert/spinorama
  Index revision: `b38d2715441f134a63b737b2fd913d14058a2643`.
  Entries reference actual `datas/eq/<model>/iir-autoeq.txt` files at that revision.
  Symlinks and other filter variants are excluded. The index contains names and
  paths only; the upstream filter files are downloaded on selection.
- **Live squig.link:** the existing IEM measurement and target workflow remains
  separate. It generates filters on the device using its selected IEM target.
  Speaker presets never pass through an IEM target.

Representative speaker sources:

- [Bose SoundLink Revolve, measured by Erin's Audio Corner](https://github.com/pierreaubert/spinorama/blob/b38d2715441f134a63b737b2fd913d14058a2643/datas/eq/Bose%20SoundLink%20Revolve/iir-autoeq.txt)
- [Sonos Roam](https://github.com/pierreaubert/spinorama/blob/b38d2715441f134a63b737b2fd913d14058a2643/datas/eq/Sonos%20Roam/iir-autoeq.txt)
- [JBL 305P Mark ii studio monitor](https://github.com/pierreaubert/spinorama/blob/b38d2715441f134a63b737b2fd913d14058a2643/datas/eq/JBL%20305P%20Mark%20ii/iir-autoeq.txt)

The parser accepts Equalizer APO parametric PK, LS/LSC and HS/HSC filters, including
explicit positive gains. Unsupported active filters, invalid numbers and profiles
larger than Aurora's 12-band capacity fail as a whole instead of silently applying
a partial correction. Speaker presets correct the measured device response;
placement, volume, onboard DSP modes and the listening room can change the result.

To refresh the speaker index, run
`python scripts/update_speaker_presets.py <full Spinorama commit SHA>` and set
`SPEAKER_RAW_BASE` in `AutoEqRepository.kt` to that same revision. This keeps all
download URLs consistent with the bundled index. Inspect upstream changes and
verify representative Bluetooth and studio speaker files before shipping.
