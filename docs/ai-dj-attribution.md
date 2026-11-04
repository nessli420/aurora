# AI DJ model and runtime

Aurora's AI playlists and typed DJ requests can use a small language model that runs entirely on the device. The model only turns a request into playlist settings (energy, moods, genres, search terms); Aurora's own picker chooses real tracks from the library and the signed-in sources. Requests are never sent to an AI service. The AI DJ's own picks come from listening history and likes, and don't need the model.

- Model: **Qwen3.5 0.8B** by the Qwen team (Alibaba Cloud), Apache License 2.0, https://huggingface.co/Qwen/Qwen3.5-0.8B
- Quantized file: `Qwen3.5-0.8B-Q4_0.gguf` from https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF, revision `807dfd9a6fadbf703207aac3bfb0b2b3040d077c`
- Runtime: **llama.cpp** v0.8.0 by the ggml authors, MIT License, https://github.com/ggml-org/llama.cpp. A trimmed CPU-only copy is vendored in `native/llama.cpp/` with its `LICENSE`.

Model SHA-256: `57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf` (563,036,064 bytes). The app downloads the file only when the user asks, over Wi-Fi on Android, resumes interrupted downloads, and verifies this checksum before use. It is never bundled in the APK or the desktop installer, and it can be deleted from Downloads & Storage. Without it, requests go through Aurora's keyword parser.

## Talking to the DJ

Holding the AI button in the player records a short clip and transcribes it on the device. The audio is never saved or sent anywhere. The DJ's spoken lines use the natural voice below when it's downloaded, otherwise voices already installed on the system: Android's text-to-speech with offline voices only, Windows SAPI, or `espeak-ng` on Linux when it's installed.

- Model: **Whisper base** by OpenAI, MIT License, https://github.com/openai/whisper
- Quantized file: `ggml-base-q5_1.bin` from https://huggingface.co/ggerganov/whisper.cpp, revision `5359861c739e955e79d9a303bcbc70fb988958b1`
- Runtime: **whisper.cpp** v1.9.5 by the ggml authors, MIT License, https://github.com/ggml-org/whisper.cpp. The library sources are vendored in `native/whisper.cpp/` with its `LICENSE`.

Model SHA-256: `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` (59,707,625 bytes). It downloads and verifies the same way as the model above, only when the user asks, and it can be deleted from Downloads & Storage. Without it, requests can still be typed.

## The DJ's natural voice

An optional model gives the DJ a natural voice. It reads the DJ's lines on the device; the text never leaves it. It covers 31 languages, which includes every Aurora language except Chinese and Urdu; those keep the system voice.

- Model: **Supertonic 3** by Supertone Inc., BigScience Open RAIL-M License, https://huggingface.co/Supertone/supertonic-3, revision `3cadd1ee6394adea1bd021217a0e650ede09a323`. The licence is downloaded with the model as `LICENSE`, and its use restrictions (Attachment A) apply to anyone using it: among others, no impersonating people without consent and no passing off machine-generated speech as human.
- Runtime: **ONNX Runtime** (MIT), already used for vocal separation. `DjSpeaker` follows the pipeline of Supertone's MIT-licensed sample code.

The download is 381 MB across 12 files, each pinned by size and SHA-256 in `DjModelStore.SPEAKER`. It happens only when the user asks, over Wi-Fi on Android, and it can be deleted from Downloads & Storage.
