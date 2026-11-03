# LFM3-Omni Android voice — source review and acceptance gates

Date: 2026-11-03
Working branch: `feature/lfm3-omni-audio-stage`
Pinned upstream: `ggml-org/llama.cpp@8642541b255bfb5dc39cd351a178be09013a55d6`

**Verification boundary:** No Android build, APK creation, application execution,
instrumented test or physical-device playback was carried out. This is a
source-level review and set of code changes, not a release sign-off.

## 1. LFM3 speech templates and codec

- The LFM3-Omni GGUF contains a named `tokenizer.chat_template.tts`.
  Its evaluated Greek/female TTS prompt begins with `<|startoftext|>`,
  a system turn `Perform TTS in Greek using a female voice.`, the user text,
  and an assistant turn with `<think></think><|audio_start|>`.
- The application checks the named template's supported form before enabling
  speech. General-purpose `llama_chat_apply_template` is not passed the
  named Jinja TTS template because it does not evaluate its named variables.
- The LFM3 GGUF sets `tokenizer.ggml.add_bos_token=false`. Native text
  tokenization no longer prepends a second BOS when rendering the full
  LFM3 template.
- Native TTS now accepts the discrete token through the upstream sampler,
  passes the hidden-state to the LFM3A codec helper, and terminates on audio
  EOS. Incomplete output (frame cap reached) is an error, not a successful
  synthesized reply. Greek ASR likewise fails if it runs out of context or
  token budget before producing its end token.

## 2. Header/JNI compatibility with pinned upstream

Read actual pinned `include/llama.h`, `tools/mtmd/mtmd.h`, and
`tools/mtmd/mtmd-helper.h` against the native integration. Textual API
inspection found **59 distinct llama/mtmd function names** used by
`LLMInference.cpp` declared across the upstream headers.
The JNI registry has **15 Kotlin external methods**, and a static
name, parameter-count and parameter-type comparison found an exact
native bridge for every one of them.

This cannot detect missing link objects, CMake configuration errors, Android
NDK ABI differences, incorrect Kotlin imports, unsupported runtime tensors,
or CPU/GPU performance. Passing a textual check is not equivalent to a
successful compile.

## 3. Session consistency and cancellation

- `generationEpoch` gates callbacks and `isGenerating`; stale jobs cannot
  report a successful answer for a newer turn.
- The native cancel path restores pre-turn history; canceled turns no longer
  report a successful completion and initiate TTS. Premature text-flow
  termination in Kotlin was removed; native decoding owns normal EOS.
- Synchronous conversation reset only runs off the Android main thread,
  checks the model's lifecycle epoch, and clears native messages, sampler,
  and KV memory without reloading the entire GGUF. A failed reset propagates
  an exception and preserves the visible chat.
- Loading/unloading uses a native mutex, cancellation signal and serialized
  cross-instance teardown. A successful build and stress tests are still
  required to establish behavior across every scheduling interleaving.

## 4. Continuous capture, VAD, barge-in and audio focus

- `OmniLiveVoiceController` fills full 20-ms/16-kHz mono PCM16 frames,
  rather than treating short platform reads as full VAD frames.
- VAD has pre-roll, consecutive speech onset, silence termination, a bounded
  utterance and foreground-only lifecycle cleanup.
- Playback uses voice-communication audio attributes. The microphone and
  playback no longer request mutually exclusive audio focus.
- `AudioTrack.routedDevice`, not the list of all available system outputs,
  determines whether playback is privately routed. With neither a private
  output route nor functioning hardware AEC, self-recording of the
  assistant is suppressed, which intentionally limits hands-free barge-in.
- Real-device speaker leak, AEC quality and noise sensitivity cannot be
  established statically.

## Test source and remaining acceptance criteria

`Android/src/app/src/test/java/com/google/ai/edge/gallery/engine/WavPcm16Test.kt`
defines deterministic WAV parsing, signed stereo downmix, resampling and
malformed-data regression tests. These tests have been **written but not run**.

Before release, tests need to be approved and carried out on the current
branch, without silently reusing the older text-only CI build:

| Acceptance gate | Estimated engineering hours |
| --- | ---: |
| Android arm64 C++/JNI and Kotlin compilation / repair | 1.5 |
| Actual LFM3 GGUF + mmproj load; Greek ASR/TTS WAV verification | 2 |
| 10+ spoken turns, VAD, headphone/speaker interruption and audio focus | 2 |
| Stop/reset/reload, cancellation and history consistency under stress | 1.5 |
| LiteRT-LM / MediaTek packaging and routing regression | 1 |
| **Total estimated verification** | **8** |

The estimates are planning budgets, not measured completion time. New
failures may require more engineering. No APK was produced.
