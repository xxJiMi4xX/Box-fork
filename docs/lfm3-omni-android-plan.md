# Box Android: dual-backend engineering plan

Status: implementation in progress, not yet verified on-device.
Working branch: `feature/lfm3-omni-android`.
Upstream Box: `jegly/Box` (fork `xxJiMi4xX/Box-fork`).
Invariant: preserve Google AI Edge LiteRT / LiteRT-LM while replacing the legacy GGUF native path.

## Core decisions

1. Keep the existing Box app and its Google AI Edge UI and `LlmChatModelHelper`. Do not rewrite a second application or remove unrelated features.
2. Retain `com.google.ai.edge.litertlm:litertlm-android:0.10.0` in the first working release. Keep changes to that backend minimal and measurable.
3. Start with the Box-pinned llama.cpp submodule `408225bb1a63faef725685515cfe583fcc964d09` for a baseline build. Upgrade GGUF to pinned **llama.cpp v0.8.0** commit `8642541b255bfb5dc39cd351a178be09013a55d6` only after baseline is understood.
4. Replace the GGUF `smollm` wrapper with a self-contained JNI adapter using the public llama.cpp API and `libmtmd`. Avoid `common_*` wrappers and CMake `common` dependency.
5. Separate Google LiteRT-LM and GGUF backends by *capability*, not just file extension: text, image, audio input, audio output, cancellation, context. The UI must never claim unsupported capabilities.
6. Model weights are user-selected on the device, not embedded in the APK. LFM3-Omni requires a matching text GGUF plus `mmproj`.
7. Build arm64-v8a CPU-first, no Vulkan until end-to-end operation succeeds. Treat GPU as an optional later optimization.
8. Debug variants install alongside upstream Box using an application ID suffix. Keep `main` unchanged throughout development.
9. Pin every tool/dependency and enable GitHub Actions build + uploaded artifacts. No source transformations in CI.

## Existing environment and compatibility policy

- Box currently: AGP 9.5.0, Kotlin 2.4.21, Gradle 9.8.2, LiteRT-LM 0.10.0, compileSdk 36, targetSdk 37, minSdk 35.
- Baseline branch: migrate obsolete Kotlin Android DSL in *source* and compile against Android SDK 37; JDK 21, NDK 27.2.12479018, CMake 3.22.1.
- Keep the baseline toolchain when it builds and works; otherwise change only the failing Gradle/AGP pair, preserving LiteRT-LM and avoiding simultaneous version churn. Candidate compatibility fallback: AGP 9.3.1 / Gradle 9.7 / Kotlin 2.4.21, subject to build verification.
- For GGUF v0.8.0: configure `LLAMA_BUILD_MTMD=ON`, `LLAMA_BUILD_TOOLS=OFF`, `LLAMA_BUILD_COMMON=OFF`, `LLAMA_BUILD_SERVER=OFF`, `MTMD_VIDEO=OFF`, `BUILD_SHARED_LIBS=OFF` for upstream dependencies and one application JNI shared object.
- Do not suppress all compiler/linker errors or copy symbol visibility flags with `PUBLIC` to downstream targets.

## Required JNI lifecycle and correctness

- Single owner of model, context, sampler, multimodal projector and audio state; every allocation has matched cleanup.
- Tokenize/detokenize with `llama_tokenize` and `llama_token_to_piece`, handling negative buffer sizing and UTF-8 fragments.
- Explicit, bounded `n_ctx`/batch/threads. Start from 4096 context and CPU; report memory failures without killing the app.
- Make decode and unload mutually exclusive: cooperative cancellation plus wait for native activity to finish before free. Never free a native pointer while decode runs.
- Maintain consistent chat history / KV cache; avoid duplicate prefill, incorrect role template, unbounded context or stale generation callbacks.
- Respect model EOG and model-specific chat template; do not rely on hardcoded universal stop strings.
- Ensure `llama_batch`, GGUF metadata contexts and all callbacks are freed. Check JNI errors and handle exceptions crossing native boundary.
- Model selection checks GGUF/associated mmproj pairing and capability metadata.

## Multimodal audio requirements

- LFM3-Omni voice conversation requires `libmtmd`; upgrading only `libllama` cannot provide it.
- Audio input: microphone PCM16 mono -> float audio in sample rate returned by `mtmd_get_audio_sample_rate` -> `mtmd` encoder -> text backbone. Use turn-based audio first; live streaming is a separate acceptance gate.
- Audio output: model-specific audio-generation state, codec -> PCM at `mtmd_gen_audio_get_info` sample rate -> Android `AudioTrack`.
- Greek voice: select documented `el` language and evaluate ASR, Greek text response and Greek TTS **separately**. Language quality is an empirical criterion, not a build property.
- Audio and text generation need distinct callbacks/messages, not audio forced into the text-only `ResultListener`.

## Acceptance gates and estimated engineering budgets

| Gate | Deliverable | Pass criterion | Estimate |
| --- | --- | --- | --- |
| 0 | Baseline GitHub CI | Source checks out, complete `:app:assembleDebug` or actionable isolated failure log; LiteRT dependency unchanged | 3-5 h |
| 1 | Android build contract | Toolchain reproducible, debug app ID isolated, artifact uploaded | 3-5 h |
| 2 | New JNI GGUF bridge | Public APIs only, native link succeeds, no unresolved symbols | 5-8 h |
| 3 | Text inference | Load GGUF; multiple-turn streaming; stop/reset/unload; bounded memory | 4-7 h |
| 4 | LFM3-Omni multimodal | Load matched mmproj; recognize Greek speech; PCM audio output | 10-18 h |
| 5 | End-to-end voice sessions | Mic->model->speech, 10+ turns, interruptions and cleanup without crash | 8-16 h |
| 6 | Regression/packaging | Existing LiteRT-LM path unaffected; installable APK and reproducible logs | 6-10 h |

Total estimated engineering budget: **39-69 hours**, excluding optional GPU acceleration evaluation (4-8 additional hours). These are workload estimates, not a promise of future delivery.

## Validation matrix

- Compile: Gradle Kotlin/Java resources, arm64 C++ compile/link, APK packaging, artifact upload.
- Native: text GGUF loading, model/template handling, sampling, UTF-8 streaming, EOS/EOG, no unresolved dynamic symbols.
- Memory/lifecycle: 4096 context initial cap, repeated load/reset/unload, cancel-during-decode, background/foreground, out-of-memory recovery.
- Audio: 16-bit microphone capture, correct input sample-rate conversion, selected projector, Greek ASR, Greek TTS 24-kHz if model reports 24 kHz, stop/pause and audible playback.
- Google: load and infer with one compatible LiteRT-LM model; regression of original UI/features.
- Device: actual Android arm64 phone. CI cannot verify microphone, playback, GPU/SoC behavior, thermal response, or model language quality.

## MediaTek LiteRT-LM NPU support — included in the initial Android APK

- The Box code already exposes CPU, GPU and NPU selections for imported LiteRT-LM models; `LlmChatModelHelper` selects `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)`.
- The fork already contains `Android/src/app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_MediaTek.so` and `libLiteRtCompilerPlugin_MediaTek.so`. Do not download replacement binaries without checking version/provenance.
- Native `.so` files are configured for extraction to `nativeLibraryDir`, allowing the LiteRT-LM dynamic loader to locate the dispatch/plugin binaries.
- Initial APK is scoped to `arm64-v8a`. GitHub Actions verifies these two binaries exist in source and in the final APK; **packaging alone does not establish SoC compatibility**.
- Important limitation: Although the generic LiteRT MediaTek page lists MT6897 (Dimensity 8300), LiteRT issue #6886 contains a contributor statement dated 2026-10-15 explicitly saying MT6897 NPU acceleration is *not currently supported*. The current LiteRT-LM MediaTek example only lists MT6989 and MT6991 NPU model bundles. Do not claim MT6897 NPU execution is verified.
- Keep CPU/GPU selections operational. Only select NPU for a model specifically compiled for the SoC, with verified compatible vendor runtime and driver. A successful LiteRT-LM engine initialization alone must not be interpreted as proof of NPU execution.
- Device NPU validation (future, after an installable APK): 12 engineering hours estimated: SoC/runtime 2 h, compatible model 3 h, integration troubleshooting 4 h, real-device profiling and regression 3 h; model conversion excluded.
- References: https://developers.google.com/edge/litert/next/mediatek , https://developers.google.com/edge/litert/next/litert_lm_npu , https://github.com/google-ai-edge/LiteRT/issues/6886

## Known blockers / caveats

- No successful Android APK build has been observed on this fork before the baseline run.
- The old Box GGUF engine is text-only; it currently ignores image and does not pass audio clips into llama.cpp.
- LFM3-Omni's separate projector and audio generator are mandatory for full speech functionality.
- The upstream multimodal API is evolving. Pin v0.8.0 until tests justify an update.
- GGUF file format support is not equivalent to model architecture or multimodal functionality support.
- Android installation with a different signature requires separate debug package ID; do not overwrite the original Box.
- Build success, model load success, Greek ASR quality, Greek TTS quality and full-session stability are different acceptance criteria.

## Working rules

- Commit substantive changes to the source branch; do not patch source on-the-fly inside CI.
- Investigate one failure class at a time. Preserve logs and reproducible revision IDs.
- Do not alter the stable `main` branch during development.
- Do not claim a functional voice APK until verified on a real Android device.
