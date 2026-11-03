# Χρονολογικό αρχείο εργασιών — Box Omni

Δικό μας engineering log, όχι release notes προηγούμενου Box. Καταγράφονται επιτυχίες, αποτυχίες, αβέβαια στοιχεία και πραγματικό επόμενο βήμα.

## 2026-11-03 — Σύστημα τεκμηρίωσης και συνέχειας
- Αίτημα: να αποθηκεύονται κάθε πλάνο, ιστορικό, τι κάναμε/τι μένει, δοκιμές, σφάλματα/διορθώσεις και πληροφορίες για νέα session.
- Προηγούμενο commit 681b7b67 αντικατέστησε ενεργά upstream README και πρόσθεσε δικά μας docs/PROJECT.md, STATUS.md, UPSTREAM.md, DEVICE_TESTS.md.
- Νέος κανόνας: AGENTS.md + docs/HANDOFF.md + docs/NEXT_SESSION_PROMPT.md, αυτό το αρχείο, docs/DECISIONS.md, πλήρης UI audit, release plan/protocol.
- Αυτές είναι αλλαγές τεκμηρίωσης και δεν αποτελούν νέο build ή επαλήθευση hardware.

## 2026-11-03 — Πραγματικό Poco X6 Pro
- Ο χρήστης εγκατέστησε το debug APK, φόρτωσε LFM3-Omni-2B Q8_0 GGUF, έλαβε ελληνικές και αγγλικές απαντήσεις αλλά και ακατάλληλες/ασυνεπείς απαντήσεις.
- Η ετικέτα CPU/GPU/NPU αποδείχθηκε να προέρχεται από UI metadata. Native GGUF CMake έχει GGML_VULKAN=OFF, JNI n_gpu_layers=0. Το γεγονός ότι η απάντηση βγήκε με ετικέτα NPU δεν τεκμηριώνει NPU.
- Support image/audio/mobile-actions/thinking ΔΕΝ είχαν ενεργοποιηθεί κατά το αρχικό test.
- Βρέθηκε και ενεργοποιείται από Settings η επιλογή Allow screenshots.

## 2026-11-03 — Native mmproj loader
- Πηγή commit a520dcbf3366175fc86522741a85303bee2e7827.
- GitHub Actions #41660795505: SUCCESS, artifact 13242069163 (expires 2026-11-17).
- Native mtmd projector init/capability detection compile. Δεν έχει συνδεθεί UI import, media, speaker και δεν έχει δοκιμαστεί runtime projector.

## 2026-11-03 — llama.cpp v0.8.0 JNI
- Pinned upstream submodule 8642541b255bfb5dc39cd351a178be09013a55d6.
- GitHub Actions #41658645319: SUCCESS, πλήρες ARM64 GGUF JNI μαζί με libmtmd, LiteRT-LM διατηρείται.

## 2026-11-03 — Αρχικό baseline
- Branch feature/lfm3-omni-android.
- GitHub Actions #41656831949: SUCCESS, πλήρες Android debug APK, MediaTek LiteRT .so μέσα στο APK.
- Packaging δεν είναι πραγματική επιτάχυνση NPU.

## Υποχρεωτική φόρμα ιστορικού για κάθε επόμενη εργασία
Ημερομηνία · σκοπός · σχετικό πλάνο · αρχεία/commit · CI run και status · συσκευή/μοντέλο · test inputs · αποτέλεσμα · error/αιτία · fix · τι απομένει · ενημέρωση handoff.
