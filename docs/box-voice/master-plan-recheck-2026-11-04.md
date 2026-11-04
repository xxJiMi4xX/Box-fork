# Box Voice — Independent master-plan recheck
Date: 2026-11-04
Baseline: `d33d93bee0c8d2e209e53e3ec061d8e54058d0e3`
Source reviewed: `5da7104c18a682718121c298a61dec6ab50463db`
Working branch: `work/box-voice-full-implementation-20261104`
Master plan: `/Box-Voice/BOX_VOICE_MASTER_PLAN_2026-11-04.md` (187-line Library document, complete).
Upstream pinned submodule: `8642541b255bfb5dc39cd351a178be09013a55d6`.

## Verdict

**The master plan is NOT complete.** There is substantive source implementation across Φ0–Φ5, but all M0–M9 *device* gates remain unpassed. There is NO CI run for the full implementation branch. Its source must not be labeled validated, production-ready, or a completed native realtime voice product.

This audit covers code in the connected repository and the actual master plan. It is not an Android compile, a static compiler, a runtime test, a GPU-offload measurement, or evidence that a specific GGUF/mmproj pair works.

## Newly found issues and corrective commits

| Severity | Finding | Status / mitigation |
| --- | --- | --- |
| Critical | `LlamaCppModelHelper` silently reloaded CPU after explicit Vulkan failure. The GPU selector could appear ineffective. | **FIXED in source** `785025a1`: forced Vulkan failure is surfaced; only an explicitly selected CPU uses CPU. No speed or offload claim. |
| Critical | `stopGeneration` zeroed `isGenerating` before the previous JNI/session owner finished; stale ready state could be displayed after cancellation. | **FIXED in source** `5ce29afb`, `e94c6ac6`: owner releases state on exit under session mutex and guarded transitions. |
| Critical | ASR/TTS fallback on the shared llama context cleared recurrent/KV state but left `contextUsed_` at an obsolete position. | **FIXED in source** `c3f73879`: cache and position invalidated on shared-context fallback. |
| Critical | A JNI close failure did not prohibit a second heavy model allocation. | **FAIL-CLOSED** `09ab7b93`: process-wide teardown error blocks new loads; restart is required. UI surfaces this via `4ddc0e88`. Native release remains subject to M4. |
| High | Experimental audio stream attempted hybrid multi-turn continuation without validated recurrent sequence semantics. | **GATED** `e94c6ac6`: only freshly reset context may attempt this optional path. Otherwise established ASR+mixed fallback. Multi-turn *true realtime* is **NOT implemented**. |
| High | Atomic session status was not Compose-observable and duplicated stale status checks existed. | **FIXED in source** `2c6701ab`, `91b594d5`: StateFlow + audible PCM drain indication. Manual UI check still required. |
| Moderate | Audio clip WAV writer wrote an invalid RIFF chunk size (+8 bytes). | **FIXED in source** `5da7104c`: chunk size = PCM length +36; total byte-array remains PCM +44. |
| Moderate | VAD and per-turn timestamps were not on a directly comparable logged clock. | **IMPROVED** `a03af77c`: monotonic event_ns from VAD and turn trace. Explicit shared VAD turn-ID still absent. |

Earlier source changes also include a 40-ms bounded PCM producer, adaptive playback prebuffer, Room cold-restore/paired-turn tests, GPU/IGPU enumeration, status/error UI and experimental mtmd command interfaces. Their functionality has **not been proven on-device**.

## Coverage matrix against master plan

| Area | Code coverage | Acceptance |
| --- | --- | --- |
| Φ0 — baseline diagnostic stages, exact quant/hashes and actual backend | Partial telemetry and protocol; exact phone-side GGUF/mmproj hashes missing | **M0–M3 NOT PASSED** |
| Φ1 — teardown, cancel/join, CPU/GPU selector truthfulness | Significant changes; silent GPU→CPU fallback removed; no double allocation after failed native close | **M1/M4 NOT PASSED** |
| Φ2 — bounded audio, real sample rates, adaptive buffering, AEC/barge-in | 40-ms producer chunks, finite channel, AudioTrack reuse and underrun observations; AEC is pre-existing best-effort | **M3/M5 NOT PASSED** |
| Φ3 — one owner, incremental microphone-time encoding, safe LFM3 recurrent state, durable transcript, ten-turn hot context | Mutex serialization and an *opt-in post-ASR* incremental adapter; no proven `APPEND_TEXT` command actor, no microphone-time feeding, no proven hybrid 10-turn retention; Room cold restore implemented at source level | **M6/M7/M8 NOT PASSED; MOST IMPORTANT INCOMPLETE PHASE** |
| Φ4 — chat-first UI and basic controls | Compose LazyColumn, text composer, settings sheet, observable status; no tested landscape/IME/48dp accessibility on Poco | **DEVICE UI GATE NOT PASSED** |
| Φ5 — JVM/JNI/room/lifecycle/realtime integration, sustained use, APK decision | 4 targeted JVM tests selected for CI; no full-branch build, JNI stress/instrumentation and phone acceptance tests | **M9 NOT PASSED** |

## Architectural omissions — not glossed over

1. **True realtime**: existing VAD collects a completed user utterance, after which Greek ASR runs and the experimental adapter sends 40-ms pieces to mtmd. It does *not* stream audio while AudioRecord captures, so input latency is not yet the target architecture.
2. **Hybrid recurrent semantics**: current hot-prefix reuse is limited to exact text-token matches; mixed acoustic embeddings cannot automatically be reconstructed from text. Cancellation invalidates the cache, and no arbitrary recurrent `seq_rm` is used. The 10-turn and overflow correctness proof is still absent.
3. **M6 A/B/C decision**: A thin in-process adapter is only a prototype. The alternative server slot pulls in internal server/queue/context/task dependencies, while localhost is a diagnostic candidate. No NDK cross-build cost or end-to-end comparative proof exists, so no final winner is proven.
4. **True GPU offload**: available logs enumerate devices and selected GPU request, but `actual_offloaded_layers=unverified` is literal, not an offload measurement. A successful Vulkan build does not prove Mali acceleration.
5. **Voice experience**: unknown real RTF, audible start delay, underruns, thermal degradation, performance of independent ASR scratch context, quality of Greek transcript, and whether Android AEC prevents speaker self-interruption.
6. **CI source quality**: names for all 24 SmolLM native declarations match 24 JNI exports; header/implementation methods are present; the test names are referenced by the workflow; selected Kotlin/C++ files have balanced raw brace counts. Those scans do not type-check, link, execute, or test the code.

## Required verification before saying complete

Run the **approved** single consolidated full-branch CI build only after explicit user permission. Reject until compiler/linker and tests are green; fix errors in the same isolated branch. Run phone gates M0–M9 with the exact GGUF/mmproj/quant/SHA-256 and thermal baseline, including 5 unload cycles, CPU vs Vulkan actual offload, 20 barge-in turns, 10-turn and overflow consistency, 30 Greek ASR/TTS phrases, and sustained 10–15-minute voice load. Do not assert that a gate passed merely because the code path exists.

**Existing master-plan engineering diagnostic budget**: M0 1.5h + M1 2.0h + M2 1.5h + M3 2.0h + M4 1.5h + M5 1.5h + M6 3.0h + M7 2.0h + M8 2.0h + M9 2.0h = **19.0 engineering hours** (planned, not elapsed).

Build handling: the full branch is excluded from automatic CI push filtering. Its debug application ID `com.box.gallery.omni.voice.full.dev` and label `Box Voice Full Test` permit side-by-side installation with the previous version; do not uninstall the established APK. No build or APK was triggered by this recheck.

## Go / no-go

**NO-GO for production or a declaration of whole-plan completion.**
**Eligible only for an explicitly authorized compile/test checkpoint**, with prior baseline preserved, followed by necessary corrections and real-device validation. The core unfinished research/engineering item is true microphone-time, persistent LFM3 audio with safe recurrent-state and transcript handling across multiple turns.
