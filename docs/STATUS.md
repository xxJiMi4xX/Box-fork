# Τρέχουσα κατάσταση και ανεπίλυτα ζητήματα

**Επικαιροποίηση:** 2026-11-03. **Εκτέλεση πηγαίου κώδικα:** καταγεγραμμένο CI και δοκιμή χρήστη, χωρίς ανεξάρτητη πρόσβαση σε Android profiler της συσκευής.

## Επιβεβαιωμένα

- CI [#41656831949](https://github.com/xxJiMi4xX/Box-fork/actions/runs/41656831949): αρχικό ολόκληρο Android APK με διατήρηση LiteRT-LM, πακετάρισμα δύο MediaTek `.so`; success.
- CI [#41658645319](https://github.com/xxJiMi4xX/Box-fork/actions/runs/41658645319): native GGUF JNI `llama.cpp v0.8.0`, `libmtmd` σε ARM64· success.
- CI [#41660795505](https://github.com/xxJiMi4xX/Box-fork/actions/runs/41660795505): `mtmd_init_from_file` projector loader, πακετάρισμα MediaTek και πλήρες debug APK· success, artifact `13242069163` (λήξη 2026-11-17).
- Πραγματικό Poco X6 Pro: χρήστης εγκατέστησε/άνοιξε APK· φόρτωσε LFM3-Omni-2B Q8_0, παρήγαγε κείμενο. Παρατηρήθηκαν σωστές αλλά και ακατάλληλες/πολύγλωσσες απαντήσεις. Βλ. `DEVICE_TESTS.md`.

## Έτοιμο μόνο σε κώδικα, όχι πιστοποιημένο σε συσκευή

- Native φόρτωση matching `mmproj` και επιστροφή πληροφοριών audio/vision· κανένα UI pathway και καμία πραγματική δοκιμή projector.
- LiteRT-LM διαδρομή `Backend.NPU(nativeLibraryDir=...)` σε κώδικα και MediaTek shared objects στο APK· **καμία απόδειξη NPU inference** για το Poco MT6897.
- Screenshot switch, offline switch, SQLCipher και διάφορες λειτουργίες αρχικού UI υπάρχουν στον κώδικα· δεν έγινε πλήρης λειτουργικός/ασφαλείας έλεγχος αυτών στη συγκεκριμένη build.

## Σίγουρα μη λειτουργικό ή μη συνδεδεμένο για GGUF

- Vulkan GPU offload: `GGML_VULKAN=OFF` και `n_gpu_layers=0`.
- GGUF NPU: δεν υλοποιείται στο τρέχον JNI.
- UI `Accelerator` για GGUF: δεν περνά στον `LlamaCppModelHelper`.
- UI `Support image/audio`: δηλώνει δυνατότητα και εμφανίζει task, ενώ `runInference` επιστρέφει σφάλμα για πολυτροπικά inputs.
- UI `Support thinking`: εκτίθεται option αλλά το GGUF helper δεν εφαρμόζει το `extraContext`.
- `max tokens` και συσχετισμένο πεδίο GGUF: ο native decoder δεν έχει αντίστοιχο ενεργό όριο παραγωγής.
- `mmproj` picker και persistent association μοντέλου: λείπουν.
- GGUF πραγματικό audio input / audio generation / playback: λείπουν.
- GGUF benchmark: native stub εκτοξεύει εξαίρεση· η υπάρχουσα benchmark υποδομή χρησιμοποιεί LiteRT-LM.

## Κίνδυνοι υψηλής προτεραιότητας

**P0**: Ψευδής ένδειξη επιταχυντή, ασυμφωνία δηλωμένων δυνατοτήτων, έλλειψη συστηματικής επαλήθευσης runtime. **P1**: chat-template, system prompt/history, max tokens, reset που επαναφορτώνει μοντέλο, κλήσεις agent/mobile actions προς LiteRT, εξαίρεση σφαλμάτων. **P1 ασφάλειας**: `fallbackToDestructiveMigration()` σε Room, offline blocking όχι καθολικά τεκμηριωμένο, διατήρηση ευαίσθητων πληροφοριών στα diagnostics.

**Επόμενη έκδοση:** Κανένα νέο APK πριν εγκριθεί η συνολική λίστα του `PLAN_NEXT_APK.md` και περάσουν τα gates του `RELEASE_PROTOCOL.md`.

## Σημαντική διόρθωση τεκμηρίωσης: Greek ASR και TTS επιβεβαιώθηκαν στο Termux (2026-11-03)

Η προηγούμενη διατύπωση «δεν έχει δοκιμαστεί ελληνική ομιλία» ισχύει **μόνο για το Box APK**. Σε προηγούμενη πραγματική δοκιμή της ίδιας συσκευής με Termux και `llama-server`, το LFM3-Omni-2B Q8_0 + matching `mmproj` αναγνώρισε ελληνική ερώτηση («5+7» → γραπτό «12») και παρήγαγε **κατανοητή ελληνική γυναικεία ομιλία** με `/tts` (WAV 24 kHz). Το realtime HTML demo έκανε διακοπές και προβλήματα ελληνικής φωνής. Άρα βασική ελληνική speech input/output **device-observed στο Termux**, αλλά **όχι υλοποιημένη στο Box GGUF JNI**. Λεπτομέρειες: `docs/DEVICE_TESTS.md`.

Απαιτούμενο: νέο Box Voice Conversation να αξιοποιεί πρώτα εγγενή audio διαδρομή ανά μοντέλο και γλώσσα· για άλλα μοντέλα, μόνο ρητά επιβεβαιωμένο Greek support, χωρίς ψευδές capability flag.

## Implementation M1 status (2026-11-03, source-only)

**Committed, UNVERIFIED:** LLAMA_CPP type, honest CPU/text-only GGUF capability display, mmproj import/persistence/wiring, native output-token cap, in-place session reset. Commits 2c17eab, f0d6245, a91c4a1, 1cd165f, 5304790. Αυτά είναι αδοκίμαστα source patches. Τα προηγούμενα «λείπουν» αφορούσαν το τελευταίο εγκατεστημένο APK (commit a520dcbf), όχι τον πλέον ενημερωμένο GitHub κώδικα.

**Remaining:** rebuild/integration validation later as ONE candidate; actual mmproj capability probe on device, real image/audio, native speech output, Greek Voice Conversation, real Vulkan offload, MediaTek NPU verification/unsupported, full UI/settings/security audit, LiteRT regression.

## Μ2: GGUF history / sampling / reset — source-only (2026-11-03)

Commits `e208757`, `db2d756`, `add4e92`. Προστέθηκε μετάδοση UI/Room ιστορικού και system prompt προς GGUF native state στον κάθε γύρο, χρήση `llama_sampler_accept` σε prompt και generated tokens, σωστή εναλλαγή explicit saved conversation, νέα ταυτότητα αποθήκευσης μετά reset και ανώτατο όριο retry. Τα παραπάνω είναι **μη επαληθευμένα σε build/runtime**. Το τελευταίο πραγματικά εγκατεστημένο APK εξακολουθεί να είναι από το `a520dcbf`. GPU/NPU, Greek voice, image/audio και video δεν λειτουργούν ακόμη στην εφαρμογή.

## M3 code status — native audio staged but **not** functional in released APK

Commits `3dc71cc`, `be51aae`, `475face`, `4cc3870`: TTS WAV/audio-input mtmd JNI Kotlin adapters και projector capability reporting. **Δεν υπάρχει νέα δοκιμή compile, Android APK ή φωνητική συνεδρία UI.** Ειδικά η μορφοποίηση του TTS prompt χρειάζεται σύγκριση με το επιτυχημένο Termux /tts πριν χαρακτηριστεί συμβατή. Το GGUF UI ορθώς παραμένει text-only. Κίνδυνοι: πιθανές C++/Kotlin ασυμβατότητες, session state synchronization, διατήρηση audio transcript, ανώτατη διάρκεια/μέγεθος WAV και όρια μνήμης Poco. Βλ. `SPEECH_PIPELINE.md`.
