# Box Omni — τρέχον σημείο συνέχειας

**Τελευταία καταγραφή:** 2026-11-03.
**Repo:** https://github.com/xxJiMi4xX/Box-fork
**Branch εργασίας:** feature/gguf-v080-native · **main:** χωρίς αλλαγές.
**Σε νέο session:** Έλεγξε οπωσδήποτε την τρέχουσα HEAD και κάθε commit νεότερο από την ημερομηνία αυτού του αρχείου. Το GitHub source και τα τεκμήρια υπερισχύουν της περίληψης.

## Τι φτιάχνουμε
Box Omni Android για Poco X6 Pro 5G, MediaTek Dimensity 8300 Ultra, Android 16, με δύο backends:
- GGUF → pinned llama.cpp v0.8.0 μέσω SmolLM JNI + mtmd. Πρώτο μοντέλο LiquidAI LFM3-Omni-2B Q8_0, στόχος κείμενο, εικόνα, είσοδος/έξοδος ήχου με mmproj.
- .litertlm → η προϋπάρχουσα Google LiteRT-LM διαδρομή να διατηρηθεί.
Το UI πρέπει να δείχνει τις πραγματικές δυνατότητες μοντέλου/backend, οι ρυθμίσεις να εφαρμόζονται και η CPU/GPU/NPU να έχει πραγματικά runtime τεκμήρια.

## Τι έχει ήδη γίνει
1. Baseline Android success: https://github.com/xxJiMi4xX/Box-fork/actions/runs/41656831949
2. Νέο llama.cpp v0.8.0 JNI build success: https://github.com/xxJiMi4xX/Box-fork/actions/runs/41658645319
3. Native mtmd mmproj loader/capabilities compile success: https://github.com/xxJiMi4xX/Box-fork/actions/runs/41660795505 (source commit a520dcbf3366175fc86522741a85303bee2e7827). Artifact 13242069163, λήξη 2026-11-17.
4. Πραγματικό Poco: ο χρήστης εγκατέστησε/άνοιξε το debug APK και πήρε κείμενο από LFM3-Omni-2B Q8_0 GGUF, ορθά ελληνικά/αγγλικά αλλά και ασυνεπείς απαντήσεις. Οι ετικέτες CPU/GPU/NPU και διαφορετικοί χρόνοι από το UI ΔΕΝ επιβεβαιώνουν NPU/GPU εκτέλεση. Support image/audio/mobile-actions/thinking ΔΕΝ ήταν επιλεγμένα στην πρώτη δοκιμή.
5. Settings → Allow screenshots υπάρχει, αφαιρεί FLAG_SECURE. Αυτό έλυσε την αδυναμία αποστολής screenshots.
6. Πρώτη αντικατάσταση παραπλανητικής upstream τεκμηρίωσης: commit 681b7b67cdbf8a4c865f67f275fdba89ff02e385, με νέα README και status.

## Τρέχοντα πραγματικά κενά
- GGUF compiled χωρίς Vulkan (GGML_VULKAN=OFF), native n_gpu_layers=0. Καμία GGUF NPU υλοποίηση. Ο accelerator selector του UI αγνοείται από GGUF runtime.
- Native loadProjector έχει μεταγλωττιστεί αλλά δεν συνδέεται με import picker/μόνιμη αποθήκευση/αληθινό media inference.
- GGUF runInference απορρίπτει εικόνα και audioClips, αγνοεί thinking extraContext, δεν έχει max output tokens, δεν περνά system prompt στη φόρτωση, χρειάζεται ιστορικό/resume/reset έλεγχο.
- Agent Chat και Mobile Actions καλούν απευθείας LiteRT helper· benchmark screen είναι LiteRT-specific και native GGUF benchmark stub.
- MediaTek vendor .so στο APK δεν αποδεικνύουν ότι LiteRT-LM τρέχει σε MT6897 NPU. Θέλει συμβατό μοντέλο, runtime logs και πραγματική συσκευή.
- Offline scope, SQLCipher migration safety, Firebase/permissions, άδειες και ολόκληρο Settings/UI απαιτούν audit.

## Τι είναι να κάνουμε
Ο χρήστης απαιτεί ΟΛΟΚΛΗΡΩΜΕΝΟ σχέδιο πριν από ΚΑΘΕ νέο APK, όχι μία-μία αλλαγές/δοκιμαστικές εκδόσεις. Αρχικά ολοκλήρωσε docs/PLAN_NEXT_APK.md, docs/UI_BACKEND_AUDIT.md, docs/RELEASE_PROTOCOL.md με πλήρη scope και acceptance gates. Στη συνέχεια υλοποίησε σε διακριτά source commits, επιβεβαίωσε CI/regressions, και μόνο όταν υπάρχει πλήρες testable milestone φτιάξε ένα ενιαίο candidate APK.
Η αλήθεια για GPU/NPU, το mmproj, τα settings και η προστασία ιστορικού έχουν ιδιαίτερη προτεραιότητα. Αν NPU δεν υποστηρίζεται, γράψε unsupported, ποτέ ψευδή ένδειξη.

## Υποχρέωση τεκμηρίωσης
Πριν και μετά από κάθε ουσιαστική εργασία ενημερώνουμε: σχέδιο, SHAs, αρχεία, πραγματικές δοκιμές, σφάλματα, διορθώσεις, αποφάσεις, τι μένει και πού συνεχίζει το επόμενο session. Δεν καθαρίζουμε LICENSE/copyright/attribution/Git history.

## Γρήγορος χάρτης
AGENTS.md → docs/HANDOFF.md → docs/STATUS.md → docs/PLAN_NEXT_APK.md → docs/UI_BACKEND_AUDIT.md → docs/CHANGELOG.md → docs/DECISIONS.md → docs/RELEASE_PROTOCOL.md → docs/DEVICE_TESTS.md.
Το docs/lfm3-omni-android-plan.md διατηρείται ως ιστορικό προηγούμενο σχέδιο, όχι ως μοναδική σημερινή αλήθεια.

## Νεότερη απαίτηση: κανονική φωνητική συνομιλία (2026-11-03)

Ο χρήστης θέλει dedicated voice mode όπως στο ChatGPT, δηλαδή διαδοχική αμφίδρομη συνομιλία με μικρόφωνο/απαντήσεις από ηχείο, όχι απλό Voice Input/Whisper. Audit: Category.VOICE περιλαμβάνει Whisper Voice Input για STT, υπάρχουν HoldToDictate/AudioRecorderPanel/AudioPlaybackPanel, **δεν** υπάρχει integrated conversational voice screen. Το GGUF απορρίπτει audio inputs και δεν παράγει ακόμη audio playback. Η πλήρης προσθήκη βρίσκεται στο `docs/PLAN_NEXT_APK.md` και η UI χαρτογράφηση στο `docs/UI_BACKEND_AUDIT.md`. Καμία νέα υλοποίηση ή APK μόνο από αυτόν τον έλεγχο.
