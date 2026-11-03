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
