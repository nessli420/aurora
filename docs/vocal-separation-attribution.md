# Vocal separation model

Aurora's optional on-device vocal separation uses **UVR-MDX-NET-Voc_FT**, trained and published by **Ultimate Vocal Remover**, with credit to **Anjok07 and aufr33** and the MDX-Net authors. UVR's README permits third-party applications to use these models under its stated MIT terms with attribution to UVR and its developers.

- Project and model attribution: https://github.com/Anjok07/ultimatevocalremovergui#license
- Original public weights: https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx
- Published inference parameters: https://github.com/Anjok07/ultimatevocalremovergui/blob/master/models/MDX_Net_Models/model_data/model_data.json
- Runtime: Microsoft ONNX Runtime Android 1.24.3 (MIT), https://github.com/microsoft/onnxruntime

Original model SHA-256: `534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111` (66,762,490 bytes). The app verifies this checksum before inference. The weights are downloaded on demand from the publisher, rather than bundled in the APK. The song is never sent to a separation service.

Aurora implements its own stereo STFT/ISTFT and overlapping inference windows. Model parameters: 44.1 kHz stereo, 7680-point periodic Hann FFT, 1024-sample hop, 3072 complex frequency bins, 256 time frames, vocal compensation 1.021. Complementary backing is the source minus the predicted vocal. Processing uses temporary PCM and retains two derived WAV files in the private app cache. These are distinct from library downloads.
