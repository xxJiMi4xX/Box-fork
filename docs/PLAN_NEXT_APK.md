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
