# Box Voice — Full implementation checkpoint and approval gate
Date: 2026-11-04
Master Plan: /Box-Voice/BOX_VOICE_MASTER_PLAN_2026-11-04.md
Baseline: d33d93bee0c8d2e209e53e3ec061d8e54058d0e3
Pinned llama.cpp submodule: 8642541b255bfb5dc39cd351a178be09013a55d6
Development branch: work/box-voice-full-implementation-20261104

## Important verification boundary

All changes on this branch are SOURCE CHANGES, not proven production fixes. There has been **no build or runtime test of this full branch**. A separate, earlier Phase-0 diagnostic build succeeded, but validates only the instrumentation branch; that CI result cannot validate this branch.

The user authorized implementation through the plan but requires **separate explicit approval before final APK build / a push that triggers CI**. This branch is excluded from the workflow's automatic `push.branches` filter, preserving that boundary.

## Code work by phase

- Φ0: event/epoch stage telemetry, Room read/replay, native prefill, selected backend / requested layers, destructor+registry, PSS/fds/mmap, mixed audio RTF, AudioTrack underruns. Device measurements M0–M3 are still pending.
- Φ1: unloader waits for current load/generation and holds model-owner mutex; `UNLOADED` is published only after native close, and errors are surfaced. GPU selection enumerates both GPU and IGPU, selecting an explicit Vulkan device when requested; forced GPU without a device throws rather than silently using CPU. **Actual layer count is not claimed as proven**, the status says `unverified`. M1/M4 pending.
- Φ2: bounded PCM producer queue partitioned into 40-ms windows; AudioTrack writer with 250–850 ms adaptive startup prebuffer and underrun telemetry; no JNI mutex held while producer awaits playback queue. Existing AEC/NS and conservative speaker feedback avoidance remain. M3/M5 pending, no guarantee of uninterrupted audio when model RTF is below 1.
- Φ3: Room used for cold restore and completed-turn durable storage; hot turns avoid repeated `getMessagesSync` / reset; native prompt tokens can reuse **only exactly equal** decoded prefixes, otherwise full prefill. ASR/TTS scratch context attempts to preserve chat state, with safe shared-context fallback on allocation failure. Serialization via `sessionMutex` plus short JNI mutex sections; experimental native `mtmd_audio_stream` append/commit/mixed commands exist and are **opt-in off by default**. Native streaming still receives PCM from the completed utterance, after Greek ASR (not yet low-latency microphone-time incremental encoding), and LFM3 hybrid-state multi-turn correctness has not been proven. A (thin in-process adapter) is the source prototype; B/C dependency tradeoffs are documented but M6 comparative feasibility remains pending. M6–M8 pending.
- Φ4: chat-first `LazyColumn` and text composer, mic/stop control, model/backend/status bar, full settings and diagnostics via `ModalBottomSheet`. Manual IME/landscape/accessibility review pending.
- Φ5: pure-JVM tests for PCM window calculations and paired Room turns, workflow unit test selection extended. No APK, native JNI or real-phone end-to-end validation of the full branch yet.

## Deliberate fallbacks and limitations

- `mtmd_support_audio_stream` is checked before trying opt-in realtime input. If unavailable the preceding mixed text+audio route is retained; if that fails before any text/audio is produced, existing text+Greek TTS route remains.
- A canceled or interrupted hybrid generation invalidates the fast prefix cache; its next turn performs a conservative full rebuild rather than arbitrary `seq_rm` or `n_keep`. Recorded Room turns remain authoritative.
- `actual_offloaded_layers=unverified` is not evidence of GPU offload, even when Vulkan is requested. Never describe Mali's actual offload without native device evidence.
- OpenAI or third-party cloud models/APIs are not introduced.
- The original Gallery/LiteRT-LM source route and pinned llama.cpp submodule remain unchanged.
- This branch's debug application ID is `com.box.gallery.omni.voice.full.dev` (label `Box Voice Full Test`) so the full-test APK can install alongside an already installed debug version with a different signing key. It will have separate app-private Room data and imported-model permissions. **Never uninstall the original app to test this APK**.

## Authorized next step (NOT executed yet)

One consolidated CI build of this branch after the user's separate approval. The workflow should:
1. Verify SHA and pinned submodule.
2. Compile Kotlin first.
3. Compile native ARM64 with Vulkan, JNI and unchanged MediaTek LiteRT-LM.
4. Run `VoiceTextSegmentsTest`, `WavPcm16Test`, `VoiceAudioBufferPolicyTest`, `GgufConversationPairsTest`.
5. Verify NPU libraries are in the APK and publish the artifact.
6. If compile/tests fail, investigate and correct; a successful workflow alone is not a passed phone acceptance gate.

## On-device test program (master plan budget)

Baseline & diagnostics M0 1.5 h, CPU/Vulkan M1 2.0 h, per-turn breakdown M2 1.5 h, PCM/underruns M3 2.0 h, five load/unload/cancel cycles M4 1.5 h, AEC & barge-in M5 1.5 h, A/B/C and recurrent feasibility M6 3.0 h, 10-turn + overflow M7 2.0 h, Greek ASR/TTS 30 phrases M8 2.0 h, sustained 10–15-minute voice M9 2.0 h. Total estimated engineering budget **19.0 hours**. None of the device acceptance gates is marked PASSED without device logs; individual engineering hours are budget estimates, not elapsed time.

Freeze the exact GGUF/mmproj SHA-256, quant, input audio and prompt, requested threads/GPU layers, Android build fingerprint, temperature and battery/memory before comparable tests. Take ≥3 repeats and report medians/spread. Verify exact backend layer counts through the runtime logs. Treat a null/incomplete log as INCONCLUSIVE, not success.

## Go / no-go criteria

- Five completed unload cycles with zero native registry handles, native destructors completed, no use-after-free and no monotone fd/mmap/PSS drift unexplained by allocator retention.
- CPU/Vulkan forced mode truthfulness and measured latency/throughput.
- ASR transcript genuinely recognized in Greek and durably paired with its assistant answer.
- Realtime/PCM playback at model-reported audio rate, measured RTF and underruns, safe cancellation, no accidental self-barge-in.
- 10-turn completion and overflow recovery without hybrid-state corruption or silent turn loss.
- Correct UI on the physical Poco including keyboard, landscape and configuration change.

No claim of final production readiness is valid until these gates pass.
