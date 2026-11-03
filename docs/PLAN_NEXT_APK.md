# Box Omni — ενιαίο σχέδιο για το επόμενο APK

Κατάσταση: ΕΓΚΕΚΡΙΜΕΝΟ ΩΣ ΕΡΓΑΣΙΑ ΣΧΕΔΙΑΣΜΟΥ, όχι ακόμη υλοποιημένο. Ημερομηνία: 2026-11-03.
Branch: feature/gguf-v080-native. Baseline: CI #41660795505, runtime text GGUF δοκιμή στο Poco.

## Αρχή
Δεν δημιουργούμε κάθε φορά νέο APK για μία αλλαγή. Παγώνουμε όλο το scope και τα acceptance gates, υλοποιούμε σε ελέγξιμα commits, παράγουμε ένα ολοκληρωμένο candidate APK μετά από έλεγχο συνέπειας. Δεν υποσχόμαστε μη υποστηριζόμενη NPU.

## Α. Αρχιτεκτονική και hardware truth
- Καθαρή διάκριση GGUF llama.cpp και LiteRT-LM ανά πραγματικό runtime/format.
- Χωριστό requested backend, actual backend, offloaded layers/operations, fallback reason, model compatibility.
- GPU: Vulkan GGUF build + actual offload με logs και συγκρίσιμο CPU baseline· όπου δεν γίνεται, CPU fallback με σαφή ειδοποίηση.
- NPU: LiteRT-LM native library, συμβατός compiled model, driver/SoC check και πραγματική dispatch trace. Για GGUF NPU δεν υπάρχει τρέχουσα native υποστήριξη: unsupported αν δεν κατασκευαστεί τεκμηριωμένο ανεξάρτητο backend.
- Runtime diagnostics: backend, model SHA/format, projector, RAM, sampling, token count, prefill/decode speed, TTFT, thermal/CPU frequency όπου επιτρέπεται.

## Β. Μοντέλα, import και mmproj
- Δύο επιλογείς αρχείων .gguf και matching mmproj. Δεν μαντεύουμε compatibility από όνομα μόνο.
- mtmd_init_from_file compatibility/real capability probe, file-size/free-space validation, safe copy/permissions, persistence μετά restart.
- Κατάσταση projector και image/audio capability εμφανίζονται μόνο όταν το μοντέλο τα υποστηρίζει πραγματικά.
- Αποτροπή διπλών, αμφίσημων ή λανθασμένων imported model metadata. Καθάρισμα legacy runtimeType=LITERT_LM για GGUF χωρίς καταστροφική migration.

## Γ. Text inference correctness
- LFM3-Omni role/chat template, stop/EOG και ασφαλή model-specific sampling defaults, όχι καθολικές αλλαγές για άλλα GGUF.
- System prompt, ορθή τροφοδότηση ιστορικού (και αποθήκευση/επαναφορά), εφαρμογή max output tokens, διόρθωση context indicator.
- Reset conversation χωρίς υποχρεωτικό reload weights, cancellation safe, native resource cleanup και error handling.
- Ελεγχόμενα prompts πολλαπλών γύρων στα ελληνικά/αγγλικά· καταγραφή tokenization/TTFT, όχι μόνο latency διαφόρου μήκους outputs.

## Δ. Πολυτροπικό πραγματικό inference
- Εικόνα: SAF picker / bitmap → mtmd marker, tokenize, encoder embeddings, llama_decode.
- Audio input: AudioRecord PCM16 mono → resample στην πραγματική sample rate projector → mtmd audio encoding και model inference.
- Audio output όπου υποστηρίζεται: σωστή native audio-generation state/codec → PCM → Android AudioTrack, cancel/release.
- Ελληνική φωνή ξεχωριστά ASR, κείμενο και TTS, χωρίς πρόωρους ισχυρισμούς ποιότητας.
- Αν κάποιο mode δεν υποστηρίζεται, ξεκάθαρο disabled state, όχι checkbox «Support audio» χωρίς υλοποίηση.

## Ε. Κάλυψη όλου του UI/settings/ασφάλειας
- Ολοκλήρωση mapping docs/UI_BACKEND_AUDIT.md από import ως πραγματική εκτέλεση.
- Basic chat, Ask Image/Audio, Agent/Skills/MobileActions: ορθή δρομολόγηση ή εμφανής απενεργοποίηση για GGUF.
- Theme, screenshots, biometrics, HF auth, licenses/version, permissions, downloads, offline mode, encrypted history, migration.
- Δεν διαγράφουμε ιστορικό ή κλειδιά για να περάσει δοκιμή. Audit ενδεχόμενης απώλειας μέσω fallbackToDestructiveMigration και Firebase/network.
- LiteRT-LM regression και κληρονομημένα features με ακριβή δήλωση tested / not tested.

## Προϋπολογισμός ανθρωποωρών
| Ενότητα | Ώρες |
| --- | ---: |
| Αρχιτεκτονική και UI mapping | 8–12 |
| Model import / mmproj / persistence | 8–14 |
| Text correctness / history / sampling | 12–20 |
| Vulkan / actual GPU backend | 12–20 |
| MediaTek NPU πραγματική διερεύνηση | 8–14 |
| Image/audio input | 14–24 |
| Native audio output / playback | 16–28 |
| Settings/security/persistence | 12–20 |
| Diagnostics/CI/regression QA | 14–22 |
| **Σύνολο** | **104–174** |

Ενσωματωμένα στον προϋπολογισμό: συγκρίσιμο CPU/GPU test 3 ώρες, NPU proof attempt 4 ώρες, image integration test 2 ώρες, voice test 4 ώρες. Δεν συμπεριλαμβάνει ανάπτυξη νέου proprietary GGUF→NPU compiler/driver αν δεν υπάρχει υποστηριζόμενη διαδρομή.

## Κριτήρια πριν από επόμενο APK
1. Καμία ψευδής λειτουργία από metadata checkbox.
2. Full CI/JNI/Gradle/packaging χωρίς παλινδρόμηση LiteRT-LM.
3. CPU text και LiteRT smoke tests· εφαρμογή system prompt/history/max tokens/stop/reset.
4. Vulkan offload measured, ή σαφής unsupported/fallback.
5. NPU proof measured σε κατάλληλο μοντέλο/driver, ή disabled/unsupported, **ποτέ** UI label ως proof.
6. mmproj imported/persisted/loaded με πραγματικό device evidence.
7. Image/audio E2E μόνο όταν περαστούν input/output tests, αλλιώς απενεργοποίηση.
8. Settings/persistence/SQLCipher migration/offline/licensing/privacy tests, χωρίς απώλεια δεδομένων.
9. Ενημερωμένα STATUS, CHANGELOG, DEVICE_TESTS, HANDOFF, DECISIONS, release gate report.

Αν κάποιο κρίσιμο gate αποτύχει, δεν χαρακτηρίζουμε τη λειτουργία επιτυχή. Το scope μπορεί να διορθωθεί **με καταγεγραμμένη απόφαση**, αντί να γίνει ψευδής δήλωση ή σιωπηρό fallback.

## Προσθήκη ολοκληρωμένης λειτουργίας Voice Conversation (2026-11-03)

Ο χρήστης ζήτησε **κανονική συνομιλία με φωνή**, όπως ChatGPT Voice, όχι μεμονωμένη απομαγνητοφώνηση.

- Το νέο Voice Conversation UI να έχει ξεχωριστό entrypoint και μία συνεδρία: `Idle → Listening → Processing → Speaking → Listening`, καθώς και `Paused/Error/Ended`.
- Ξεχωριστός συντονιστής audio session από την υπάρχουσα οθόνη Voice Input (Whisper), με Android permissions, audio focus, εξωτερικά ακουστικά, Bluetooth όπου επιτρέπεται, safety timeout, mute, stop, recovery.
- Προτιμώμενη διαδρομή για πραγματικό LFM3-Omni: microphone PCM16 → συμβατό mmproj/mtmd audio encoder → LFM3 text/audio inference → native speech output/codec → Android AudioTrack. Αυτή η διαδρομή παραμένει **μη υλοποιημένη**.
- Εναλλακτικό υποστηριζόμενο STT → LLM → TTS pipeline μόνο αν native speech generation δεν είναι εφικτό· το UI να εμφανίζει τη διαφορά και τα αντίστοιχα πραγματικά backends.
- Διατήρηση της ίδιας συνεδρίας συνομιλίας, χωρίς διπλή καταχώριση στον chat history και χωρίς ανεξέλεγκτη επανεγγραφή prompt.
- Voice Activity Detection, auto-turn, διακοπή αναπαραγωγής/barge-in, acoustic echo cancellation, αποφυγή ανατροφοδότησης μικροφώνου/ηχείου αποτελούν **ξεχωριστά acceptance gates**. Δεν τα παρουσιάζουμε ως ήδη υλοποιημένα.
- Ελληνικά: ξεχωριστά tests εισερχόμενης ομιλίας, εξαγόμενου κειμένου, παραγόμενης ελληνικής φωνής, καθυστέρησης και σταθερότητας.
- Ενδεικτικός επιπλέον προϋπολογισμός **Voice UI/session controller 8–14 ώρες**, **VAD/auto-turn/audio focus/barge-in 10–18 ώρες**, **end-to-end αξιολόγηση/παλινδρόμηση 6–10 ώρες**. **Σύνολο επιπλέον 24–42 ανθρωποώρες**, πέραν των ήδη εκτιμημένων native audio input/output εργασιών. **Αναθεωρημένο συνολικό πλαίσιο 128–216 ανθρωποώρες**, υπό την επιφύλαξη πραγματικής συμβατότητας του audio backend.
- Απαραίτητο πριν δοθεί APK: αποδεδειγμένη τουλάχιστον turn-based φωνητική συνομιλία· auto-hands-free/barge-in μόνο αν τα επιμέρους device tests περάσουν. Αλλιώς η επιλογή εμφανίζεται ως beta με ακριβείς περιορισμούς ή παραμένει ανενεργή.

## Greek-first model-native voice: απαίτηση με ήδη επιβεβαιωμένη Termux βάση (2026-11-03)

**Δεν πρόκειται για υπόθεση:** ο χρήστης έχει ήδη εκτελέσει στην ίδια συσκευή Termux `llama-server` με το Q8_0 και το matching `mmproj`: ελληνική είσοδος ομιλίας (5+7→12) και κατανοητή ελληνική φωνητική έξοδος μέσω `/tts` (WAV 24 kHz) **πέτυχαν**. Τα realtime HTML sessions είχαν διακοπές και προβλήματα γλώσσας. Βλ. `DEVICE_TESTS.md`.

**Ενιαίο σχέδιο ενσωμάτωσης:**
- Πρώτη προτεραιότητα είναι η **αναπαραγωγή στο Box native JNI της ήδη επιτυχημένης model-native Termux διαδρομής**. Πριν υλοποιηθεί νέο audio codec, αντιστοίχιση reference `llama-server` API (speech input και `/tts`) με το pinned llama.cpp v0.8.0 / libmtmd / GGUF JNI, εντοπισμός των ακριβών επιπλέον native APIs και κατάσταση lifecycle.
- Καινούρια οθόνη `Voice Conversation`: ελληνική γλώσσα `el-GR` ως επιλογή, μικρόφωνο, ενεργή κατάσταση listening/processing/speaking, αυτόματη εναλλαγή turn, mute, stop, interruption, ίδιο conversation state και αποθήκευση ιστορικού.
- **Model-native speech** όπου πραγματικά διαθέσιμη· fallbacks STT→LLM→TTS μόνο όταν το μοντέλο δεν μπορεί να παράγει φωνή, με ρητά εμφανιζόμενα components.
- Ανεξάρτητη ανίχνευση `Greek text`, `Greek audio input`, `Greek audio output` ανά model+backend+loaded mmproj. Άλλο μοντέλο με πραγματική υποστήριξη ελληνικών πρέπει να λειτουργεί μέσω της ίδιας διεπαφής χωρίς bespoke αλλαγές.
- Το ήδη δουλεμένο `/tts` και η αναγνώριση ελληνικής ομιλίας είναι **baseline acceptance evidence για το Termux**, όχι για το Box. Για το Box απαιτείται νέα ακριβής δοκιμή με ίδιο audio prompt/συνθήκες πριν περάσει το gate.
- Το realtime HTML error επηρεάζει τις απαιτήσεις για session state machine, VAD, audio focus, buffering, echo control και stopping· δεν θεωρείται λυμένο επειδή έχει λειτουργήσει μεμονωμένο `/tts`.

**Προϋπολογισμός (ανθρώπινη προσπάθεια, μέσα στο ήδη προβλεπόμενο σύνολο 128–216 ωρών):** mapping reference Termux/server → JNI **4–6 ώρες**, ελληνικό ASR/TTS regression στο Box **4 ώρες**, έλεγχος συνεχούς εναλλαγής turns και interruptions **4–6 ώρες**. Οι ώρες αυτές είναι τμήματα των εγγεγραμμένων native audio + Voice Conversation + QA workstreams, **όχι προσθήκη στο συνολικό άθροισμα**.

## R1 release scope lock / code implementation started — 2026-11-03

Ο χρήστης ενέκρινε την έναρξη του σχεδίου με «Ξεκίνα». Η πλήρης αποδοχή αφορά **ένα ενοποιημένο υποψήφιο APK**, όχι διαδοχικούς installers. Οι εργασίες source μπορούν να γίνονται σε commits με `[skip ci]`, ώστε να μην ενεργοποιείται παραγωγή debug APK στην τρέχουσα φάση.

### Αναθεωρημένη συνολική περίμετρος (περιλαμβάνει βίντεο)

| Υποσύστημα | Προϋπολογισμός ανθρωποωρών |
| --- | ---: |
| Baseline, audit, σύγκριση Termux/server | 6–10 |
| Runtime/capability manager, UI routing | 12–20 |
| Model import, matching mmproj, persistence | 8–14 |
| GGUF correctness, history, lifecycle | 12–20 |
| Vulkan GPU + πραγματικό offload | 12–24 |
| MediaTek NPU feasibility/trace | 8–16 |
| Εικόνες και υποστηριζόμενα video frames | 16–28 |
| Είσοδος ήχου / ASR | 10–18 |
| Native speech generation, codec, playback | 14–26 |
| Greek-first Voice Conversation, VAD, interruptions | 24–42 |
| Settings, security, database migration | 12–20 |
| Diagnostics, CI, regression, device verification | 18–30 |
| **Σύνολο** | **152–268** |

Ο προϋπολογισμός είναι εκτίμηση εργασίας, δεν δηλώνει ότι η NPU/real-time full-duplex θα υλοποιηθούν ανεξαρτήτως συμβατότητας. Οι επιμέρους δοκιμές CPU/GPU 3 ώρες, NPU 4 ώρες, Greek ASR 2 ώρες, Greek TTS 2 ώρες, VAD/turns 4–6 ώρες, media 3–5 ώρες, settings 6–10 ώρες περιλαμβάνονται στο σύνολο.

### Implementation milestone M1 — Source changes committed, UNVERIFIED

- `2c17eab`: imported GGUF classified as LLAMA_CPP, suppress false GGUF image/audio/actions/thinking and GPU/NPU availability, prevent direct LiteRT AgentChat routing.
- `f0d6245`: proto fields projector_file_name/projector_file_size, Model projectorFileName and forwarding to existing JNI mtmd loader.
- `a91c4a1`: import UI matching mmproj picker, selected URI forwarding and staging both files before replacing originals, with backup-based rollback.
- `1cd165f`: real native max output token limit and matching Kotlin/JNI parameter.
- `5304790`: native in-place session reset, preserving loaded GGUF and projector.

**Κατάσταση:** Στον GitHub κώδικα, **χωρίς build/CI, δοκιμή εγκατάστασης ή λειτουργικό έλεγχο**. Τα source commits με [skip ci] δεν είναι λόγος να δηλωθεί λειτουργική δυνατότητα. M1 χρειάζεται κώδικα/στατική επιθεώρηση, αργότερα μία ενιαία υποψήφια έκδοση και device tests, όχι μεμονωμένο APK.

**Συνέχεια M2:** Διορθώσεις model-specific template, native session history/system prompt, πραγματικό capability validation μετά τη φόρτωση mmproj, και σχεδίαση σταθερής audio bridge με αναφορά pinned llama-server. Μ2 δεν σημαίνει άδεια πρόωρου build.
