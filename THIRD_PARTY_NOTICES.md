# Third Party Notices

Jarvis bundles or build-fetches the following third-party runtime/model files for on-device voice verification and command recognition.

| Component | Source | Notes |
| --- | --- | --- |
| sherpa-onnx Android runtime `v1.13.3` | https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.3 | Kotlin API jar and `arm64-v8a` native libraries are packaged in the app. |
| 3D-Speaker CAM++ ONNX model | https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models | `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` is packaged in `app/src/main/assets`. |
| sherpa-onnx streaming Zipformer Korean ASR model | https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16 | Gradle downloads the int8 encoder, fp32 decoder, int8 joiner, and `tokens.txt` into `app/build/generated/sherpaAssets` for APK packaging. |
| 3D-Speaker project | https://github.com/modelscope/3D-Speaker | Upstream speaker verification model project. |
