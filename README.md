# Box Omni — Android on-device AI (πειραματικό fork)

> **Πηγή αλήθειας:** Αυτό το README αφορά αποκλειστικά το repository `xxJiMi4xX/Box-fork` και τη δουλειά στη branch `feature/gguf-v080-native`. Δεν αποτελεί περιγραφή, release notes ή σελίδα λήψεων του αρχικού `jegly/Box`.

## Σκοπός

Εφαρμογή Android με δύο ανεξάρτητες διαδρομές τοπικής εκτέλεσης μοντέλων:

- **GGUF**: pinned `llama.cpp v0.8.0` μέσω JNI, με `libmtmd` για μελλοντική πολυτροπική είσοδο/έξοδο.
- **LiteRT-LM**: διατηρείται η υφιστάμενη ενσωμάτωση της εφαρμογής για συμβατά μοντέλα `.litertlm`.

Συσκευή αναφοράς: Poco X6 Pro 5G / Dimensity 8300 Ultra, Android 16. Η υποστήριξη άλλων συσκευών δεν έχει επικυρωθεί.

## Πραγματική κατάσταση — 3 Νοεμβρίου 2026

| Στοιχείο | Κατάσταση | Τεκμήριο |
| --- | --- | --- |
| Πλήρες Android debug APK | Επιτυχές CI | [Build 41660795505](https://github.com/xxJiMi4xX/Box-fork/actions/runs/41660795505) |
| Εγκατάσταση και εκκίνηση στο Poco | Επιτυχές | Αναφορά δοκιμής συσκευής, όχι αυτοματοποιημένος έλεγχος |
| LFM3-Omni-2B Q8_0, GGUF κείμενο | Παραγωγή απαντήσεων παρατηρήθηκε | Δοκιμές χρήστη, ποιότητα ασταθής |
| GGUF CPU | Διαδρομή που χρησιμοποιείται στο native build | `n_gpu_layers=0`, `GGML_VULKAN=OFF` |
| GGUF GPU / NPU | **Δεν ενεργοποιούνται** στο τρέχον GGUF backend | Η επιλογή UI δεν περνά στον native μηχανισμό |
| MediaTek LiteRT NPU | Τα vendor `.so` συσκευάζονται· εκτέλεση **ανεπιβεβαίωτη** | Έλεγχος CI μόνο για παρουσία αρχείων |
| Matching `mmproj` | Native loader μεταγλωττίζεται· δεν υπάρχει UI επιλογής αρχείου | Πέρασε CI· δεν έχει επιβεβαιωθεί σε συσκευή |
| GGUF εικόνα / είσοδος ήχου / φωνητική έξοδος | **Δεν είναι λειτουργικά** | Η πολυτροπική διαδρομή δεν έχει συνδεθεί |
| Thinking / mobile actions σε GGUF | **Δεν υποστηρίζονται** από τον παρόντα GGUF adapter | Οι διακόπτες εισαγωγής δηλώνουν μόνο δυνατότητες |

Δεν ισχυριζόμαστε επιτάχυνση NPU ή GPU επειδή το UI αναγράφει «Model on NPU/GPU». Οι ενδείξεις αυτές προέρχονται από τη ρύθμιση χρήστη και χρειάζονται διόρθωση.

## Τεκμηρίωση αυτού του fork

- [Ευρετήριο και κανόνες τεκμηρίωσης](docs/README.md)
- [Σκοπός και όρια έργου](docs/PROJECT.md)
- [Επαληθευμένη κατάσταση](docs/STATUS.md)
- [Χαρτογράφηση UI → runtime](docs/UI_BACKEND_AUDIT.md)
- [Πλήρες σχέδιο επόμενου APK](docs/PLAN_NEXT_APK.md)
- [Χρονικό εργασιών και δοκιμών](docs/CHANGELOG.md)
- [Αποφάσεις](docs/DECISIONS.md)
- [Πρωτόκολλο έκδοσης](docs/RELEASE_PROTOCOL.md)
- [Προέλευση, άδειες και τρίτα έργα](docs/UPSTREAM.md)

## Ανάπτυξη

Οι διορθώσεις γίνονται στη branch `feature/gguf-v080-native`, χωρίς αυτόματη συγχώνευση στο `main`. Ένα νέο APK προϋποθέτει **πλήρες σχέδιο**, checklist, περάσματα CI και πραγματική δοκιμή στη συσκευή. Δείτε [Android/README.md](Android/README.md).

Το προηγούμενο debug APK περιλαμβάνεται προσωρινά στα artifacts του [επιτυχούς workflow](https://github.com/xxJiMi4xX/Box-fork/actions/runs/41660795505). Το artifact **δεν αποτελεί επαληθευμένη τελική έκδοση**.

## Προέλευση / δικαιώματα

Αυτό το έργο βασίζεται στο [jegly/Box](https://github.com/jegly/Box), το οποίο βασίζεται στο [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery), και χρησιμοποιεί άλλα ανεξάρτητα upstream έργα. Διατηρούνται οι αντίστοιχες άδειες και notices. Δεν υπάρχει επίσημη σύνδεση ή υποστήριξη από τους αρχικούς δημιουργούς. Βλ. [LICENSE](LICENSE) και [docs/UPSTREAM.md](docs/UPSTREAM.md).
