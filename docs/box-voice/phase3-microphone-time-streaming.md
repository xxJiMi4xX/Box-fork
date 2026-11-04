# Box Voice Φ3 — live microphone-time LFM3 audio, source implementation

Status: **implemented in the source, NOT compiled, NOT phone-validated, experimental off by default**.
Date: 2026-11-04
Repository: `xxJiMi4xX/Box-fork`
Development branch: `work/box-voice-full-implementation-20261104`
Starting code checkpoint: `5f3567df6483f3d6d22a4c6d8ead6aaf9f45345e`
Pinned upstream llama.cpp: `8642541b255bfb5dc39cd351a178be09013a55d6`
Unmodified baseline: `d33d93bee0c8d2e209e53e3ec061d8e54058d0e3`

## What changed

The former experimental input path encoded a **completed WAV after ASR**. The new `OmniLiveVoiceController` publishes copied 20-ms PCM16LE frames to a nonblocking callback **while AudioRecord is capturing**. On VAD onset, pre-roll is queued before new frames. On VAD completion, an explicit accepted/rejected signal closes or cancels the input. The capture loop does not invoke JNI or block on the model.

`LlmChatViewModelBase` owns an active capture turn and a bounded 1216-frame channel (~24.3 s / <1 MB at 16 kHz). The background worker first reconciles the committed Room history, then attempts a model-compatible native input stream. A full recorded WAV remains available for independent Greek ASR and for fallback. Queue overflow, unsupported projector, pending Room errors, invalid context, VAD rejection, user cancellation and lifecycle shutdown all fail without discarding the original audio recording.

`LlamaCppEngine.encodeLiveMicrophone` exclusively owns the native session via `sessionMutex` from BEGIN to COMMIT/CANCEL; the low-level `nativeMutex` is held only around short JNI operations, not during microphone wait. `activeMicrophoneFrames` lets stop/reset/unload cancel a pending frame receive. The coroutine translates each PCM frame through the phase-accurate `LivePcm16Resampler` and calls `beginRealtimeAudio → appendRealtimeAudio → commitRealtimeAudio` on the already loaded model.

JNI + C++ use `mtmd_audio_stream_init/push/step/flush` with model-reported audio sample rate, feeding ready embedding rows into the same `llama_context`. Each decoding batch uses `llama_batch_init`, position `n_past`, seq_id 0 and embeddings. This matches the *semantics of append/decode* from the pinned upstream `server_realtime_slot::audio_rows/decode`; no server, HTTP, external API or background service is introduced.

After VAD ends and all frames commit, Greek `transcribeWav` reads the original recording. The auxiliary ASR context is separate where allocation succeeds, and the actual transcript is used for the UI, paired Room persistence and response request. If scratch ASR must reuse the main context, it explicitly invalidates the prepared acoustic prefix, and the existing ASR+mixed path is used. Otherwise `streamMixedResponse(..., preparedAudio = true)` continues **directly** from the committed acoustic embeddings and generates mixed text/PCM. Audio playback still uses the bounded duration-based writer from Φ2.

## Multi-turn and recurrent-state safety

- The original GGUF model/context remain resident; Room is only consulted on cold reconciliation. A completed text or mixed response may expose a **hot, validated n_past** for next audio append.
- When the native position is not intact, when history has been cold restored without prefill, or when context remaining is insufficient, realtime append is refused and the original mixed/text path reconstructs context from Room.
- A canceled/failed audio stream clears incomplete hybrid state and invalidates hot-Room tracking. **No arbitrary `seq_rm`, `n_keep`, or fake token replay** is applied to the LFM3 recurrent model.
- The mic capture path can be canceled on VAD rejection, barge-in, app pause, model reset or unload without waiting indefinitely for another AudioRecord frame.
- This is an experimental multi-turn continuation strategy modeled on pinned upstream semantics. **It remains unverified until M7**, including interleaved voice/text turns and context overflow.
- **One process-wide typed native command mailbox** (`NativeSessionMailbox`) serializes model load, unload, clear, history append, ASR, TTS, inspect and generation. Each queued operation inherits its original coroutine Job for cancellation. The microphone's `APPEND_AUDIO → COMMIT_AUDIO` stages are one **atomic owner transaction**, preventing another command from interleaving during a live utterance. Cancellation interrupts decode immediately through the native atomic stop flag and channel close; cleanup is queued afterward. The mailbox FIFO, queued cancellation, active cancellation and recovery-after-error paths now have JVM regression tests. This is implemented in source, not yet validated by CI or on device.

## Key source files

- `engine/OmniLiveVoiceController.kt`: VAD, capture-time 20-ms frames, accepted/rejected signal, no capture-thread JNI.
- `ui/llmchat/OmniLiveVoicePanel.kt`: opt-in hook and lifecycle stop.
- `ui/llmchat/LlmChatViewModel.kt`: Room prewarm, bounded frames, outcome tracking, cancellation, preserved Greek transcript.
- `engine/LlamaCppEngine.kt`: exclusive state owner and live encoder session, prepared mixed response, `sessionStatus` observation.
- `engine/LivePcm16Resampler.kt`: continuous fractionally aligned stream conversion.
- `smollm/SmolLM.kt`, `smollm.cpp`, `LLMInference.h/.cpp`: JNI and pinned mtmd integration.
- `engine/LivePcm16ResamplerTest.kt`: multi-rate, discontinuous-chunk invariance, malformed input.
- `engine/NativeSessionMailbox.kt`: typed process-wide FIFO actor preserving caller cancellation.
- `engine/NativeSessionMailboxTest.kt`: command order, canceled queued and active requests, recovery after operation failure.
- `.github/workflows/box-android-validation.yml`: includes the additional JVM test.

## Explicit known gaps / evidence boundary

1. **No full branch build**. Kotlin compiler, NDK/C++, link/ABI, unit tests, MediaTek/Mali backend and runtime must all be checked in the approved consolidated CI.
2. **Greek ASR still completes AFTER VAD** (but audio encoding happens during recording). The master plan permits a separate ASR context, but lower-latency streaming transcripts are not implemented.
3. **Model performance unknown**: if LFM3 encoding slower than mic sample production, queued frames are delayed; if the model's output RTF < 1, speaker buffer alone cannot eliminate pauses.
4. **One process-wide typed FIFO mailbox is implemented**, but the audio `APPEND_AUDIO/COMMIT_AUDIO` pair is intentionally serialized as a single owner transaction rather than separate interleavable public requests. Cancellation remains a high-priority atomic signal outside the mailbox; final native cleanup is queued. The implementation's correctness, fairness under slow decode, and cancellation behavior still require approved CI and physical-device tests.
5. **M6/M7/M8 gates not passed** and not claimable until model-specific/runtime evidence. Requested vs actual GPU offload remains unverified in Φ1.
6. No claim of speaker AEC perfection, consistent hybrid-state preservation, 10-turn voice performance, or memory stability without device evidence.

## Required acceptance probes — existing plan budgets

- **M6, 3.0 engineering hours**: confirm pinned `mtmd_support_audio_stream`, real 20-ms delivery during capture, audio stream flush and start response, no JNI deadlocks. Compare thin adapter vs upstream oracle behavior and memory overhead; verify actual GGUF/mmproj.
- **M7, 2.0 engineering hours**: 10 consecutive Greek voice turns, alternating spoken/written turns, cancellation during encoder decode and response, cold restore after corruption, near-context-limit fallback, no lost/duplicated turns.
- **M8, 2.0 engineering hours**: 30 authentic Greek phrases, ASR transcript accuracy + durable Room user/assistant pairs + PCM output with correct rates.
- Related **M4, 1.5 engineering hours**: 5 load/unload cycles with live mic interruption, verify zero native handles and no steadily rising file mappings.
- Related **M5, 1.5 engineering hours**: 20 barge-ins with speaker, headset and AEC conditions; no spontaneous self-interruption.
- Related **M9, 2.0 engineering hours**: sustained thermal/performance workload.

The full M0–M9 **19.0 engineering-hour** experimental budget remains unchanged; no part is marked passed by adding source code.

## Build approval boundary

No CI build was triggered. The development branch remains excluded from automatic `push.branches`. Explicit user approval is required before launching the full APK build. Debug package ID remains `com.box.gallery.omni.voice.full.dev` and preserves the installed baseline application.

A successful CI build establishes only **compilability and selected automated tests**. It is not a substitute for Poco on-device M6–M9 gates.
