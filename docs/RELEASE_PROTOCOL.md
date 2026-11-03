# Πρωτόκολλο κάθε νέου Box Omni APK

Το APK είναι ένα ενοποιημένο candidate, όχι σειρά μεμονωμένων installers. Τα παρακάτω είναι απαιτούμενα gates, όχι δήλωση ότι έχουν ήδη επιτευχθεί.

## 0. Πριν την ανάπτυξη
- [ ] Ένα πλήρες, ενημερωμένο PLAN_NEXT_APK με όλες τις συσχετίσεις UI / settings / backends, ρίσκα, μη στόχους, εκτίμηση ωρών και acceptance gates.
- [ ] Αναλυτικό UI_BACKEND_AUDIT: κάθε στοιχείο με actual receiver ή disabled mode.
- [ ] STATUS με ταξινόμηση bugs σε fix τώρα / unsupported εμφανώς / deferred.
- [ ] Αντιμετώπιση διατήρησης δεδομένων, rollback, δικαιωμάτων και license compliance.

## 1. Κώδικας και αποτελέσματα
- [ ] Σαφή commits, ενημερωμένο CHANGELOG/HANDOFF, όλα τα failures μαζί με τη διόρθωσή τους.
- [ ] Δεν υπάρχει GUI selected GPU/NPU χωρίς πραγματικό backend mapping ή σαφή unsupported ένδειξη.
- [ ] GGUF/LiteRT χωριστά, matched mmproj, σωστό model/template, sampling, token limits.
- [ ] Android permissions/privacy/diagnostics δεν εκθέτουν μυστικά, prompt ή ιδιωτικό περιεχόμενο.
- [ ] Σταθερή lifecycle: load, generate, cancel, reset, unload, background, low memory.

## 2. CI
- [ ] Android debug assemble success και native .so στη σωστή ABI.
- [ ] MediaTek NPU libs packaging verification χωρίς ισχυρισμό NPU execution.
- [ ] Δεν έχει αλλαχτεί τυχαία LiteRT version, upstream submodule ή signing.
- [ ] Υπάρχουν URL CI, commit SHA, artifact ID, build toolchain.

## 3. Πραγματική συσκευή
- [ ] Install/upgrade χωρίς απώλεια ιστορικού.
- [ ] GGUF multi-turn text, stop/reset, true max tokens, template/UTF-8 correctness.
- [ ] LiteRT text smoke regression σε συμβατό μοντέλο.
- [ ] CPU/GPU benchmark με ίδιο prompt/token budget, actual backend logs και thermal/TTFT.
- [ ] NPU validation μέσω αληθινού συμβατού LiteRT runtime/model και trace ή ρητό unsupported.
- [ ] mmproj πραγματικά loaded και capabilities validated, error για ασυμβατότητα.
- [ ] Audio/image full E2E μόνο αν υποστηρίζεται, διαφορετικά disabled.
- [ ] Όλα τα app settings που αλλάχθηκαν: persist, lifecycle, offline, screenshots, biometrics, database migration, licenses.

## 4. Κλείσιμο
- [ ] STATUS, CHANGELOG, DEVICE_TESTS, DECISIONS, HANDOFF updated.
- [ ] Καταγραφή pass/fail κάθε gate και γνωστών περιορισμών με ακριβή source/CI/device evidence.
- [ ] Ετικέτα έκδοσης experimental / candidate / validated βασισμένη σε πραγματικές δοκιμές, όχι υπόσχεση.
- [ ] Καμία ανακοίνωση GPU, NPU ή voice acceleration/function χωρίς actual proof.

## Πρότυπο εγγραφής νέου artifact
Ημερομηνία, branch, commit, toolchain, CI link/status, artifact, συσκευή, μοντέλο/mmproj, actual backend proof, συγκρίσιμες μετρήσεις, λειτουργικά tests, regression, αποτυχίες, διορθώσεις, επόμενο βήμα.

Η τεκμηρίωση από μόνη της δεν πρέπει να ενεργοποιεί καινούργιο build. Για docs-only commits χρησιμοποιείται [skip ci] και έλεγχος workflow filters.

## M2 acceptance tests (συμπεριλαμβάνονται στο προηγούμενο QA budget)

- [ ] Άνοιγμα αποθηκευμένης ελληνικής συνομιλίας 5+ user/assistant turns, νέο follow-up που αναφέρεται σε παλιά λεπτομέρεια: σύγκριση UI history και native content. **2 ώρες**.
- [ ] Αλλαγή μεταξύ δύο `conversationId` ίδιου GGUF μοντέλου, επαναφορά σωστού system prompt και αποφυγή ανάμιξης ιστορικού. **1 ώρα**.
- [ ] Reset συνεδρίας, νέο μήνυμα, επανεκκίνηση εφαρμογής, έλεγχος ότι δημιουργήθηκε νέο DB conversation και δεν χάθηκε το παλιό. **1 ώρα**.
- [ ] Sampling test με ίδια prompt/settings/seed, διαφορετικό repeatPenalty, επιβεβαίωση ότι native sampler δέχεται prompt και generated tokens. **1 ώρα**.
- [ ] Failure test reset όταν backend δεν είναι loaded: χωρίς ατέρμονο spinner, διατήρηση ιστορικού και καταγραφή σφάλματος. **1 ώρα**.

Οι 6 ώρες περιλαμβάνονται στον προϋπολογισμό QA του `PLAN_NEXT_APK.md` και δεν αυξάνουν το σύνολο. Όλα τα παραπάνω είναι **εκκρεμή** και όχι περασμένα τεστ.

## M3 audio acceptance gates (budget = 14 hours, ήδη μέσα στο R1)

- [ ] 2 ώρες: GGUF+mmproj source/JNI/NDK compile και ABI compatibility σε integrated candidate.
- [ ] 3 ώρες: «Perform TTS in Greek using a female voice.» πραγματικό LFM3 output WAV 24kHz, κατανοητή ελληνική ομιλία, template equivalence με Termux /tts.
- [ ] 3 ώρες: δεδομένα από μικρόφωνο WAV → mtmd → σωστές ελληνικές text replies με ελεγχόμενο input.
- [ ] 2 ώρες: abort long speech, invalid mmproj/audio, repeated load/unload και χαμηλή μνήμη χωρίς crash/leak.
- [ ] 4 ώρες: UI voice session πολλαπλών γύρων, προστασία διπλής εγγραφής ιστορικού, σωστό mute/stop/playback.

**Δεν έχει περαστεί κανένα gate.** Σημερινά commits είναι μόνο source, χωρίς νέα έκδοση APK ή πραγματικό TTS test.
