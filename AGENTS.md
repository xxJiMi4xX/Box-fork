# Box Omni — instructions for future ChatGPT sessions and coding agents

Repository: https://github.com/xxJiMi4xX/Box-fork
Active development branch: feature/gguf-v080-native
The main branch remains unchanged unless the user explicitly authorizes changing it.

## On every request to "continue"
1. Read docs/HANDOFF.md FIRST, then docs/STATUS.md, docs/PLAN_NEXT_APK.md, docs/UI_BACKEND_AUDIT.md, docs/CHANGELOG.md, docs/DECISIONS.md, docs/RELEASE_PROTOCOL.md.
2. Read the current GitHub branch HEAD, recent commits and workflows. Reconcile any commits newer than the handoff; do not assume old summaries are complete.
3. Continue the last coherent plan, respecting previously completed steps, observed errors and user decisions.
4. Record actions, changed files, SHAs, tests, errors and next steps in docs/CHANGELOG.md and docs/HANDOFF.md. Update status/plans/decisions if scope or evidence changes.
5. Before a new APK: complete an integrated plan covering UI, settings, GGUF, LiteRT-LM, mmproj, audio/vision, real CPU/GPU/NPU proof, data safety and regression. No one-off APK per tweak.
6. Never infer actual GPU/NPU execution from a UI label or packaged library. Present unverified capabilities as unverified; disable unsupported ones.
7. Preserve third-party license notices, copyrights, original git provenance, and correct attribution. Clearing inherited promotional claims is not erasing authorship.
8. Distinguish CI build success from device validation. Do not claim a new APK was tested until real evidence exists.

User-facing communication and project status: Greek.

A different ChatGPT session may retrieve account memory, but it is not guaranteed to recall every implementation detail. For reliable continuity, read the current repository. A reusable opening instruction is at docs/NEXT_SESSION_PROMPT.md.
