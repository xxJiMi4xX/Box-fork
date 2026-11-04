# Box Voice — Phase 0 diagnostic baseline
Baseline `d33d93bee0c8d2e209e53e3ec061d8e54058d0e3`; pinned llama.cpp `8642541b255bfb5dc39cd351a178be09013a55d6`.

**Unverified diagnostic implementation only. No APK build or runtime test has been authorized.**

Enable debug-only `BoxVoiceNative` logcat logging to capture requested GPU layers, backend device enumeration (not proof of real offload), quant type, tensor bytes, prefill time/tokens, PSS/fd/mappings, JNI handles and destructor completion, mixed audio generation RTF, AudioTrack underruns/consumer gaps. Existing `BoxVoiceLatency` captures ASR, Room and speech stages. Neither tag records transcripts, prompts or audio.

Record exact GGUF/mmproj filenames, quant and hashes, Android fingerprint, RAM/storage, SDK and thermals from actual approved device runs. Comparing different files invalidates benchmarks. Registry detach does NOT imply destructor completion; retained RSS does not prove a model leak.

### Repeatable tests — only after authorization to build/install diagnostic APK

- **M0 (1.5 h):** baseline load/unload, model sizes and resource snapshots; three comparable repetitions.
- **M1 (2.0 h):** requested/actual CPU, Vulkan/IGPU, hybrid; 2/4/8 threads; prompt/decode speed and thermal controls. If actual offloaded layers cannot be corroborated by initialization logs or instrumentation, gate fails.
- **M2 (1.5 h):** replay identical 1-, 5- and 10-turn Room history; compare ASR, Room read, restore, native prefill and time to first text/PCM. Three repetitions, medians and spread.
- **M3 (2.0 h):** identical mixed/legacy audio inputs, generated audio duration versus generation wall time (RTF), actual sample rate, PCM gaps, underruns, first audible sample. Three repetitions.

**Subtotal M0–M3 = 7 engineering hours; complete M0–M9 plan = 19 hours.** These are estimates, not elapsed work.

**Phase 0 gate:** Real phone logs, stable quant/model, baseline medians and spread, full requested vs actual GPU evidence, no crashes. Missing logs or measurements mean NOT PASSED. No changes to hybrid recurrent state, context shifting, JNI ownership, audio buffer policy or UI behavior in this checkpoint. Preserve the original APK and do not update llama.cpp submodule.
