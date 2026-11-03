# LFM3-Omni / Box — Remaining-issues source audit

**Date:** 2026-11-03  
**Repository:** xxJiMi4xX/Box-fork  
**Branch:** feature/lfm3-omni-audio-stage  
**Base of corrective pass:** c26737a9f3007bda7cc8ab3005ec5d458530732b  
**Pinned upstream:** ggml-org/llama.cpp@8642541b255bfb5dc39cd351a178be09013a55d6

## Execution and release restrictions

- **No APK build, no Gradle task, no JNI/Kotlin compilation, no tests
  executed, and no Android installation or device run occurred.**
- The main branch was not modified; the MediaTek NPU investigation is
  explicitly deferred.
- This report records source-level changes and static inspection. It is
  **not** a claim of 100% functional correctness or production certification.

## Seven remaining findings and their source-level resolutions

### A. Persisted dialogue and canceled turns

- Added a Room `withTransaction` repository method that commits the
  user and assistant messages together, then updates metadata; its
  model-name check prevents writing into a different model's conversation.
- GGUF prompts are no longer written as durable messages before inference
  completes. A canceled/failed generation does not create a half-turn.
- Commits are serialized. Before the next GGUF turn, the app awaits the
  prior commit, checks for persistence failure, and restores native
  messages from the encrypted Room conversation. On failure it blocks the
  next native turn and reports the problem instead of silently continuing.
- Message ordering uses `timestamp, rowid` to preserve user/assistant
  insertion order, including identical timestamps.

### B. Restoring saved conversations and edited system prompts

- Conversation identifiers and system prompts are now model-specific.
- Resuming an existing conversation verifies model ownership before
  presenting or replaying its history.
- The UI synchronizes Room history with the native `SmolLM` context; a
  saved-history barrier prevents a new GGUF prompt from running before
  replay finishes.
- Replay is keyed by the current restoration gate, so obsolete restore
  tasks cannot overwrite newer session history. Reloading a GGUF also
  re-triggers history synchronization.
- The native context uses the last 200 complete message entries at most.
  Older messages remain in Room and the UI; a warning is shown if the
  active context omits old/incomplete turns.
- Saving an edited system prompt updates the encrypted conversation and
  replays the native dialogue with the new instruction.

### C. Stale synthesized speech

- The TTS coroutine verifies the active response epoch before starting
  each segment. Barge-in cancels playback and current synthesis.
- A response's combined voice clip is shown only after uninterrupted
  segment playback has completed.

### D. True native cancellation before a new question

- Starting a new turn signals `runtimeHelper.stopResponse()` for the old
  model instead of canceling only the ViewModel coroutine.
- The engine's generation epoch drops stale callbacks and model teardown
  is coordinated through native and process-wide model-load mutexes.
- Model teardown awaits a canceled load job before closing the actual
  active JNI model object, reducing the risk of leaked or prematurely
  released handles.

### E. Context exhaustion

- Native prompt assembly reserves capacity for response tokens.
- Exhausting `n_ctx` during generation rolls the unfinished turn back
  and raises a distinct context error; it is never committed as a
  successful assistant response.
- The UI reports context errors without destructive model reloading.

### F. Reset and stored-session identity

- Successful native reset creates a fresh, durable Room conversation
  after pending turn commits have settled. The new conversation's ID
  becomes the active one.
- Auto-resume breaks equal-timestamp ties deterministically; it cannot
  silently select a different model's conversation.
- Reset invalidates older history-replay gates, so an in-flight restore
  cannot silently resurrect the pre-reset native conversation.

### G. Long Greek spoken replies

- Voice text is split into bounded, punctuation-aware segments (maximum
  200 UTF-16 code units per default segment) without splitting surrogate
  pairs, including emoji.
- TTS synthesizes and plays these segments sequentially. This reduces
  the need for a single oversized TTS result.
- Memory for the combined playable reply is bounded; the UI reports
  overflow or a failed segment.
- **Explicit limit:** This is chunked TTS after the text answer finishes,
  **not** low-latency simultaneous text/audio token streaming.

## Additional defects discovered during corrective review

- The pinned upstream `llama_sampler_sample()` already invokes
  `llama_sampler_accept()`; the extra accepts were removed.
- More importantly, the LFM3 TTS pipeline in pure audio mode consumes
  hidden states and ignores discrete sampled tokens. The audio generator
  now passes `LLAMA_TOKEN_NULL` rather than sampling unnecessary text
  logits.
- The live `AudioRecord` controller now clears its active session
  handle before reporting microphone stop to the UI.
- Several chat `StateFlow` mutations now use immutable, atomic
  `update { state -> ... }` transactions to avoid stale-state overwrites.

## Checks performed

Static source review covered all changed Kotlin, C++/JNI, Room DAO,
and chat UI paths. The pinned llama.cpp headers declare the 59 distinct
native functions referenced by the inference file, and all 15 Kotlin JNI
external method names map to native entry points. Those are **textual**
compatibility checks, not a compiler or linker result.

Source tests were added:
- `src/test/.../VoiceTextSegmentsTest.kt`: Greek segmentation and Unicode.
- `src/androidTest/.../CommittedGgufTurnTest.kt`: transaction behavior,
  model isolation, ordering and fresh-session selection.
- Existing `src/test/.../WavPcm16Test.kt`: PCM16 WAV decode and conversion.

**None of the above tests has run.**

## Remaining necessary acceptance gates

| Gate | Source work | Functionally verified |
| --- | --- | --- |
| C++ JNI and Android Kotlin/Room compilation | Written | **No** |
| Correct GGUF / mmproj and audible Greek ASR/TTS | Written | **No** |
| Ten or more uninterrupted continuous spoken turns | Written | **No** |
| Barge-in through speaker, wired and Bluetooth routes | Written | **No** |
| Rapid stop/reset/unload and memory-pressure stability | Hardened | **No** |
| Encrypted Room migrations / instrumentation tests | Written | **No** |
| Existing LiteRT-LM regression and MediaTek library packaging | Kept separate | **No new run** |
| MediaTek NPU runtime acceleration | Explicitly deferred | **No** |
| Final APK | Not authorized | **Not built** |

### Conclusion

The enumerated source-level corrections are present. The project still
cannot legitimately be described as 100% verified until the current
branch compiles and passes device/instrumentation tests. The previous
successful baseline CI run on a different branch is not evidence for
these changes.
