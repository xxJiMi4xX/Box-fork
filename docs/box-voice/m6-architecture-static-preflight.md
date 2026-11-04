# Box Voice — M6 static architecture preflight (NOT an acceptance test)

Reference: `BOX_VOICE_MASTER_PLAN_2026-11-04.md`, Box-fork `d33d93bee0c8d2e209e53e3ec061d8e54058d0e3`, llama.cpp pin `8642541b255bfb5dc39cd351a178be09013a55d6`.

**Status:** Source inspection only. No Android compile feasibility, phone measurements, realtime voice-turn correctness tests or recurrent rollback proof. M6 is NOT passed.

## Verified source facts

- Existing Android native CMake builds `llama` and `mtmd` into `smollm` with `LLAMA_BUILD_SERVER=OFF`, `LLAMA_BUILD_MTMD=ON`, `GGML_VULKAN=ON`.
- Pinned `tools/mtmd/mtmd.h` exports `mtmd_support_audio_stream`, `mtmd_audio_stream_init/push/step/flush/free`; **experimental**. `push` expects F32 PCM at model-declared input sample rate. `step` emits decoder-ready embeddings and sequence-start/flush information. It does not implement chat ownership alone.
- Pinned `tools/server/server-realtime.h` depends on `server-http.h`, `server-queue.h`, `server-task.h`, `sampling.h`; its .cpp includes `server-chat.h`, `server-common.h`, `server-context.h` and more. The slot is not a standalone API. Android binary/dependency cost still requires cross-build evidence.
- Pinned `llama-memory-recurrent.cpp::seq_rm` handles bounded rollback only when sufficient `n_rs_seq` snapshots exist, and returns false for various shared-state and unsupported cases. LFM3 is hybrid, so arbitrary partial token eviction is unsafe.
- Android `LLMInference::startMixedCompletion()` calls full `startCompletion()` before creating `mtmd_helper_gen_audio`; `transcribeAudio()` clears the same context twice. Current Room recovery replays completed text pairs before every turn and does not preserve audio embeddings across turns.

## A/B/C (tentative assessment, no selection)

**A — Thin in-process adapter around public llama.cpp/mtmd APIs.** Smallest dependency surface in the existing `smollm` library. Must implement and test its own sequencing, state ownership, decode/sampling, flush, cancellation, mixed output, Greek transcript persistence, hybrid rollback and cold recovery. Worth feasibility prototyping, not proven.

**B — In-process upstream server realtime slot.** Potential reduction of semantic drift but imports server task/queue/HTTP/context dependencies and internal APIs. Not plug-and-play; cross-compile, binary size and maintenance must be quantified.

**C — Embedded localhost server.** Potential diagnostic oracle, but introduces server integration and resource ownership overhead. Not default production choice without measured superiority.

## Required decisions and gates

1. Complete M0–M3 baseline on the actual Poco, verify GGUF/mmproj hashes and `mtmd_support_audio_stream`.
2. Prove lifecycle safety (M4), then run M6 feasibility for A/B/C, NDK compile closure, measured memory, cancellation behavior and source compatibility. Do not update the pinned submodule silently.
3. Establish one native owner and serialized commands `LOAD APPEND_AUDIO COMMIT_AUDIO APPEND_TEXT GENERATE CANCEL CLEAR UNLOAD`, epochs and deterministic teardown; audio callback must never block under engine mutex.
4. Determine safe interruption/recovery policy with explicit tests of recurrent state; no arbitrary `seq_rm`, `n_keep` or partial rollback.
5. Validate Greek transcripts and mixed output (M8), persistent 10-turn semantics and overflow (M7) before enabling the new route by default.

**M6 full diagnostic budget:** 3.0 engineering hours, part of the 19-hour M0–M9 program. This static preflight is not the full M6 experiment.
