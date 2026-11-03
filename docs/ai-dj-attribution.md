# AI DJ model and runtime

Aurora's optional AI DJ runs a small language model entirely on the device. The model only turns a request into playlist settings (energy, moods, genres, search terms); Aurora's own picker chooses real tracks from the library and the signed-in sources. Requests are never sent to an AI service.

- Model: **Qwen3.5 0.8B** by the Qwen team (Alibaba Cloud), Apache License 2.0, https://huggingface.co/Qwen/Qwen3.5-0.8B
- Quantized file: `Qwen3.5-0.8B-Q4_0.gguf` from https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF, revision `807dfd9a6fadbf703207aac3bfb0b2b3040d077c`
- Runtime: **llama.cpp** v0.8.0 by the ggml authors, MIT License, https://github.com/ggml-org/llama.cpp. A trimmed CPU-only copy is vendored in `native/llama.cpp/` with its `LICENSE`.

Model SHA-256: `57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf` (563,036,064 bytes). The app downloads the file only when the user asks, over Wi-Fi on Android, resumes interrupted downloads, and verifies this checksum before use. It is never bundled in the APK or the desktop installer, and it can be deleted from Downloads & Storage. Without it, the same screen uses Aurora's keyword parser.
