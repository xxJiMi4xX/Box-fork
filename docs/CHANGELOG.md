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

## 2026-11-03 — Audit φωνητικής συνομιλίας

- **Αίτημα:** αναζήτηση UI για κανονική διαδραστική συνομιλία φωνής σαν ChatGPT.
- **Πηγαίος έλεγχος:** `data/Tasks.kt`, `data/Categories.kt`, `ui/whisper/WhisperTaskModule.kt`, `WhisperScreen.kt`, `textandvoiceinput/HoldToDictate.kt`, `HoldToDictateViewModel.kt`, `common/chat/MessageInputText.kt`, `AudioRecorderPanel.kt`, `AudioPlaybackPanel.kt`, `runtime/LlamaCppModelHelper.kt`.
- **Εύρημα:** Voice tab = Whisper STT, hold-to-dictate = push-to-talk text, audio clips = recording/playback, χωρίς dedicated hands-free/full voice LLM conversation. GGUF media inference και speech output δεν είναι συνδεδεμένα.
- **Απόφαση:** προσθήκη `Voice Conversation` ως ενιαίο μέρος επόμενου integrated APK scope, όχι ανεξάρτητο δοκιμαστικό APK. Πρόσθετος αναλυτικός προϋπολογισμός 24–42 ώρες για UI/session orchestration, VAD/barge-in/audio focus και tests (το native audio engineering ήδη προϋπολογισμένο).
- **Αποτέλεσμα:** μόνο τεκμηρίωση, χωρίς runtime αλλαγή, CI build ή APK.

## 2026-11-03 — Ανάκτηση προηγούμενης κοινής δοκιμής ελληνικής φωνής στο Termux

- **Αφορμή:** Ο χρήστης υπενθύμισε ότι δοκιμάσαμε μαζί στο Termux το LFM3-Omni-2B Q8_0 με matching mmproj.
- **Προηγούμενο αποτέλεσμα:** `llama-server` στο Poco X6 Pro 12 GB, localhost:8080, ctx 4096, 4 threads. Ελληνική προφορική ερώτηση («5+7») παρήγαγε σωστό γραπτό «12». Το `/tts` παρήγαγε κατανοητή ελληνική γυναικεία φωνή, σε WAV 24 kHz. Realtime HTML voice υπέφερε από διακοπές/κακή ελληνική συμπεριφορά.
- **Διόρθωση τεχνικού σχεδίου:** Greek speech input/output είναι ήδη *device-observed στο Termux*, και δεν χρειάζεται να δηλωθεί εκ νέου ανεπιβεβαίωτη ιδιότητα του μοντέλου. Αντίθετα, το Box JNI **δεν** έχει ακόμη ενσωματώσει αυτή την ηχητική διαδρομή και απαιτεί ξεχωριστή απόδειξη.
- **Αλλαγή scope:** Μεταφέρουμε το ήδη επιτυχημένο native speech path και δημιουργούμε Greek-first model-agnostic Voice Conversation. **Καμία αλλαγή στον app runtime και κανένα νέο APK σε αυτό το βήμα.**

## 2026-11-03 — Implementation WIP: backend identity and honest GGUF capabilities

- Imported GGUF classified as `RuntimeType.LLAMA_CPP` instead of LiteRT-LM. Existing imported GGUF is normalized on model reconstruction, no destructive migration.
- Until native GGUF media inference works, imported image/audio/actions/thinking flags cannot masquerade as supported UI features. GGUF accelerator is limited to CPU in this build regardless of historic import checkboxes.
- GGUF removed from AgentChat registration since that task calls LiteRT directly. Separate LiteRT model behavior is preserved.
- Source-level patch only, no new APK or actual device validation. Remaining: mmproj picker and native wiring, text correctness, GPU/NPU, voice and full QA.

## 2026-11-03 — ΕΝΑΡΞΗ ΥΛΟΠΟΙΗΣΗΣ / Code milestone M1 (χωρίς APK)

- **Στόχος:** η βάση Import Model → Model metadata → JNI να αντικατοπτρίζει πραγματικές δυνατότητες και να δέχεται ζεύγος GGUF+mmproj.
- **Commit 2c17eab:** διακριτός LLAMA_CPP runtime και καταστολή μη υλοποιημένων GGUF capabilities/false accelerator, αποφυγή AgentChat LiteRT mismatch.
- **Commit f0d6245:** proto + Model metadata matching projector, forwarding στο SmolLM JNI.
- **Commit a91c4a1:** mmproj picker, εισαγωγή στο app storage ως συσχετισμένο GGUF sidecar, ασφαλής staged αντιγραφή/backup πριν την αντικατάσταση.
- **Commit 1cd165f:** εφαρμογή MAX_TOKENS στον native GGUF sampling loop και σύνδεση Kotlin/JNI, διόρθωση missing coroutine import.
- **Commit 5304790:** in-place native reset ιστορικού/KV cache χωρίς επαναφόρτωση μοντέλου/projector.
- **Verification:** Source review only· **ούτε build ούτε real-device tests**. Αλλαγές σκόπιμα σημειώνονται [skip ci] ώστε να αποφευχθεί ενδιάμεσο APK.
- **Συνέχεια:** system prompt/history, CPU/GPU verification, multimodal audio/video paths, Greek Voice Conversation, privacy/settings, integrated CI και full acceptance gates.

## 2026-11-03 — Μ2: επανόρθωση συνομιλίας και συμπεριφορά native sampler (source-only)

- **Commit [e208757](https://github.com/xxJiMi4xX/Box-fork/commit/e2087570d9cd28e4eaffc19a284e2bd36c80f655):** LlmChatViewModel συσχετίζει το εμφανιζόμενο ιστορικό και το τρέχον system prompt με την αμέσως επόμενη GGUF inference. Προηγούμενα user/assistant turns περνούν στο JNI μετά από context-only reset, χωρίς δεύτερη προσθήκη του τωρινού user input. Αυτό αφορά επίσης συνομιλίες που επαναφέρθηκαν από Room ενώ το μοντέλο φορτωνόταν.
- **Ίδιο commit:** Το LlamaCppEngine.resetConversation έγινε συγχρονισμένο ως προς το background caller, ώστε η ViewModel να μην θεωρεί ολοκληρωμένο ένα reset που ακόμη εκτελείται. Το llama_sampler_accept ενημερώνεται για κάθε token που παράγεται ώστε να λειτουργούν οι repetition penalties.
- **Commit [db2d756](https://github.com/xxJiMi4xX/Box-fork/commit/db2d756bfaaf88c92702710786d81c2568320b39):** αλλαγή ρητά επιλεγμένου conversationId στο ίδιο μοντέλο αντικαθιστά τα παλιά εμφανιζόμενα μηνύματα, και καθαρίζει stale system prompt. Reset νέας συνεδρίας αποδεσμεύει το παλιό ID αποθήκευσης μόνο μετά επιτυχημένο reset. Retry budget 3 αντί για άπειρο loop, εμφάνιση error αν αποτύχει.
- **Commit [add4e92](https://github.com/xxJiMi4xX/Box-fork/commit/add4e92e2ef1057777e7d1bb7ec135a2338e93ee):** προφόρτωση sampler repeat-penalty history με τα prompt tokens πριν αρχίσει το decode, εκτός από τα generated tokens.
- **Επαλήθευση:** ΠΡΟΣΟΧΗ, μόνο πηγαίος κώδικας και στατική αντιπαραβολή με το pinned llama.cpp API (llama_sampler_accept, llama_memory_clear, llama_get_memory). **Χωρίς compile, CI, APK, πραγματική εκτέλεση ή benchmark.**
- **Παραμένουσες αβεβαιότητες:** διερεύνηση chat-template LFM3 στον πραγματικό native decoder, concurrency/cancellation κατά φόρτωση και επαναφορά, δυνατότητα σφάλματος από ανολοκλήρωτα μηνύματα στη βάση, περιβάλλον GPU/NPU. Native image/audio και Greek Voice Conversation δεν έχουν υλοποιηθεί.
