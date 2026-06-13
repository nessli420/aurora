# Third-party notices — `decent/`

The three modules in this directory are vendored from **decent-player**
(https://github.com/Ma145/decent-player) and are used under the MIT License.
Build settings were realigned to Aurora's toolchain and a control-interface fix
was applied to the USB driver, but the implementation is otherwise unchanged.

## decent-player — MIT License

Copyright (c) 2026 Marcelo Silva

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Bundled libFLAC (xiph/flac)

`decent-media3-decoder-flac/src/main/jni/libflac/` is the upstream
[xiph/flac](https://github.com/xiph/flac) source, distributed under its
original BSD-style license — see the headers of those files for the full
notice. The Java/JNI glue in that module derives from
[androidx/media](https://github.com/androidx/media) (Apache-2.0).

decent-player's AGPL-3.0 proof-of-concept harness (the Felicity fork) is **not**
included here — only the MIT-licensed libraries.
