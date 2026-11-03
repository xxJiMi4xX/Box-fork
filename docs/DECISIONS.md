# Αποφάσεις Box Omni (ADR-lite)

## ADR-001 — Δύο backend (2026-11-03)
Απόφαση: διατηρούμε LiteRT-LM για .litertlm και pinned llama.cpp v0.8.0/mtmd για GGUF, χωρίς να παραπλανούμε για τα κοινά capabilities. Αιτιολόγηση: διαφορετικά formats, runtimes και υποστήριξη accelerator.

## ADR-002 — Requested ≠ actual accelerator (2026-11-03)
Απόφαση: ξεχωριστές τιμές ζητούμενου backend, πραγματικά αρχικοποιημένου backend, offload και fallback reason. Η τρέχουσα GGUF διαδρομή είναι CPU-only, ανεξάρτητα από CPU/GPU/NPU checkbox. Δεν ισχυριζόμαστε NPU χωρίς πραγματική απόδειξη.

## ADR-003 — Ένα συνολικό πλάνο ανά APK (2026-11-03)
Απόφαση: ένα πλήρες release scope για UI, settings, μοντέλα, ασφάλεια, GPU/NPU, multimodal και tests πριν από κάθε APK. Εσωτερικά επιτρέπονται διαδοχικά commits/ελέγχοι. Αποφεύγουμε να στέλνουμε APK για κάθε μεμονωμένη αλλαγή.

## ADR-004 — Καθαρή τεκμηρίωση δικού μας fork (2026-11-03)
Απόφαση: αντικαθιστούμε παλιά promotional README του upstream με την πραγματική κατάσταση και ιστορικό της εργασίας μας. Αρχικό commit 681b7b67. Διατηρούμε πλήρως αναγνώριση αρχικών δημιουργών, άδειες, notices, git history.

## ADR-005 — GitHub ως κύρια, μόνιμη μνήμη έργου (2026-11-03)
Απόφαση: όλα τα σχέδια, βήματα, αποτυχίες, διορθώσεις, commits, CI και δοκιμές καταγράφονται στο repository. Για νέα sessions το AGENTS.md οδηγεί στο docs/HANDOFF.md, το οποίο πρέπει να μένει σύντομο, ακριβές και επίκαιρο.
Αιτιολόγηση: η account-level ChatGPT memory μπορεί να είναι διαθέσιμη, αλλά δεν εγγυάται όλες τις ακριβείς τεχνικές λεπτομέρειες κάθε session.

## Πρότυπο νέας απόφασης
ADR ID · ημερομηνία · πρόβλημα · εναλλακτικές · απόφαση · αιτιολόγηση · αλλαγές · SHA · status επαλήθευσης.

## ADR-006 — Ελληνική native φωνή του μοντέλου ως πρώτη επιλογή (2026-11-03)

**Evidence:** LFM3-Omni-2B Q8_0 + matching mmproj στο Poco/Termux: λειτουργική ελληνική speech input («5+7»→«12») και ελληνική `/tts` WAV output 24 kHz. Realtime HTML ασταθές. Τα tests αυτά δεν έχουν μεταφερθεί στο Box.
**Απόφαση:** Αξιοποιούμε πρώτα τα εγγενή audio-in/audio-out interfaces του μοντέλου, δημιουργούμε τοπικό Greek-first Voice Conversation mode και διακριτό capability profile ανά model/backend/projector/language. Άλλα μοντέλα με επιβεβαιωμένα ελληνικά αποκτούν την ίδια UI λειτουργία.
**Περιορισμός:** Δεν ισχυριζόμαστε πλήρη voice session, GPU/NPU acceleration ή φωνή ελληνικών για όποιο μοντέλο δεν έχει πραγματικά περάσει δοκιμή. Αν native speech δεν υποστηρίζεται για μοντέλο, εναλλακτική STT/LLM/TTS αναφέρεται ρητά.
