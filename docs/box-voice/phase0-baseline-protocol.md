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


## Diagnostic APK CI result — 2026-11-04

- Approved Phase 0 CI run: https://github.com/xxJiMi4xX/Box-fork/actions/runs/41757090318
- Build commit: `4d3efcfd673cdf9492325a10e61507d98a3a1647` (working branch only; baseline `d33d93be` preserved).
- CI result: **success** (Kotlin compile, complete debug APK with Vulkan, focused tests, MediaTek binary package check and artifact upload).
- Artifact: `box-omni-voice-arm64-debug` (GitHub Actions artifact ID `13289267046`).
- Extracted APK SHA-256: `4778748eea4ef4a84d39fa2ad5ec2934acb379895794f78b04c88a4254a0deea`; size 224,015,692 bytes.
- APK signer certificate SHA-256 baseline artifact: `9625b57ed1a6d5549ab938927420681ccff130e1c00c2e8fc81a34b46b033bd9`.
- APK signer certificate SHA-256 new diagnostic artifact: `2d7e4625a7ecde91f72d053e1d92d5ba22684901ceb056c3709395e4c872531d`.
- **Blocker:** Different v2 signing certificates for the same `com.box.gallery.omni.voice.dev` applicationId prevent updating the previous debug build in place. Do not uninstall or clear data. A separately named diagnostic APK (`applicationIdSuffix=".omni.voice.diag"`) is a candidate for a follow-up build *only with separate explicit approval*. Review hardcoded custom URL scheme `com.box.gallery` and provider authorities (currently `${applicationId}.provider`) for coexistence.
- Build success does **not** close M0–M3: actual phone timing, allocated memory, GPU offload evidence and audio quality are not measured. No device benchmark has been completed.

