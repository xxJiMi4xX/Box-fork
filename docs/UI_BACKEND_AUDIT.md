# Χαρτογράφηση Box Omni: UI → settings → backend

Ημερομηνία ελέγχου: 2026-11-03. Πηγαίος κώδικας αναφοράς: a520dcbf3366175fc86522741a85303bee2e7827, το τελευταίο source APK που εγκαταστάθηκε. Το παρόν είναι code audit, όχι πλήρης πραγματική συσκευή πιστοποίηση.

## Εισαγωγή μοντέλου και dispatch
| UI / λειτουργία | Πραγματική διαδρομή | Διαπίστωση |
| --- | --- | --- |
| Import .gguf | GlobalModelManager → ModelImportDialog → ImportedModel / DataStore → Model.runtimeHelper → LlamaCppModelHelper → LlamaCppEngine → SmolLM JNI | Κείμενο επαληθεύτηκε στο Poco. |
| Import .litertlm | GlobalModelManager → Model.runtimeHelper → LlmChatModelHelper → LiteRT-LM Engine / Conversation | Κληρονομημένη υλοποίηση, LiteRT regression μη ελεγμένο στο fork. |
| Imported model runtimeType | ModelManagerViewModel.createModelFromImportedModelInfo | Imported .gguf χαρακτηρίζεται LITERT_LM, αν και dispatch μέσω επέκτασης πάει στο GGUF. Προκαλεί λάθος UI/benchmark assumptions. |
| Επιλογή .mmproj | SmolLM.loadProjector → JNI mtmd_init_from_file | Υπάρχει μόνο native API, χωρίς επιλογέα/αποθήκευση mmproj από UI. |

## Import Model controls και εφαρμογή τους
| Control | UI/config | Actual στο GGUF | Επόμενη εργασία |
| --- | --- | --- | --- |
| Max tokens | DEFAULT_MAX_TOKENS → MAX_TOKENS | Δεν περνά στη native stop condition | Ενεργό output-token budget |
| TopK, TopP, Temperature | ModelImportDialog → InferenceParams → native sampler | Εφαρμόζονται στην αρχικοποίηση | Επαλήθευση τιμών και δυνατότητας αλλαγής |
| CPU / GPU / NPU | COMPATIBLE_ACCELERATORS στο import → selectable ACCELERATOR | UI μόνο, helper αγνοεί value. Native n_gpu_layers=0, Vulkan disabled, NPU absent | Έλεγχος capability, actual/backend/fallback |
| Support image | metadata → εικόνα/task picker | Η GGUF runInference απορρίπτει images | mtmd encoding και media run |
| Support audio | metadata → audio picker | Η GGUF runInference απορρίπτει audioClips | PCM16/resampling/mtmd |
| Support mobile actions | metadata → task | MobileActionsTask καλεί LiteRT helper απευθείας | Διορθωμένο routing ή disabled |
| Support thinking | metadata → ENABLE_THINKING → extraContext | GGUF helper αγνοεί extraContext | Disabled όταν μη υποστηριζόμενο |
| mmproj | καμία UI διαδρομή | Μόνο native loader | Picker, matching, persistence, preview |

## Chat, ιστορικό, μετρήσεις
| UI | Πραγματικό receiver | Πρόβλημα / ζητούμενη δοκιμή |
| --- | --- | --- |
| Chat text streaming | LlmChatViewModel → LlamaCppModelHelper → LlamaCppEngine → JNI | Παράγει κείμενο αλλά έχει ασταθές output. LFM3 template/stop/sampling check. |
| Ετικέτα «Model on NPU» | ChatViewModel παίρνει ConfigKeys.ACCELERATOR· ChatPanel το εμφανίζει | Προέρχεται από επιλογή, όχι runtime telemetry. |
| System prompt | LlmChatScreen/ViewModel | GGUF initialize δεν περνά systemInstruction στο LlamaCppEngine. |
| Ιστορικό | Room SQLCipher / ChatRepository, ViewModel και native messages | Δεν αποδεικνύεται πλήρης resume του native context με όλη τη συνομιλία. |
| Reset | LlamaCppEngine.resetConversation | Κλείνει και ξαναφορτώνει μοντέλο, παρά το σχόλιο «keeping model loaded». |
| Stop/unload | interruptGeneration / native abort | Λειτουργεί ως κώδικας· race/stress tests εκκρεμούν. |
| Context indicator | LlmChatScreen.ContextWindowIndicator | Υπολογισμός tokens περίπου chars/4, όχι native GGUF actual tokens. |
| Benchmark | BenchmarkViewModel → LiteRT benchmark | Native GGUF benchmark stub, επιπλέον imported GGUF runtimeType=LITERT_LM. |
| Audio recorder | AudioRecorderPanel → AudioRecord / SAMPLE_RATE 16000 | UI διαθέσιμο, αλλά GGUF reject και sample rate πρέπει να προέρχεται από projector. |
| Model settings | Config.kt / ConfigDialog.kt → ConfigKeys | Ορισμένες επιλογές δεν φτάνουν στον backend· πρέπει να ελεγχθούν όλες. |

## Settings (SettingsDialog.kt) και ασφάλεια
| Setting/οθόνη | Διαδρομή | Κατάσταση |
| --- | --- | --- |
| Theme | ThemeSettings + DataStoreRepository + UiModeManager | Συνδεδεμένο στον κώδικα, persistence test εκκρεμεί. |
| Hugging Face token | ModelManagerViewModel save/clear | Συνδεδεμένο, έλεγχος ασφαλούς αποθήκευσης/εμφάνισης. |
| Biometric lock | AppLockManager / MainActivity lifecycle | Συνδεδεμένο, πραγματικό test και conflict με DB encryption. |
| Allow screenshots | AppLockManager.screenshotsEnabled → MainActivity FLAG_SECURE | Συνδεδεμένο, default off. |
| Biometric DB encryption | BiometricEncryptionManager, SQLCipher / BoxChatDatabase | Έλεγχος key migration και fail-safe. |
| Offline mode | OfflineMode.assertOnlineOrThrow στον DownloadWorker | Περιορίζεται ρητά σε κλήσεις που χρησιμοποιούν assert· όχι αποδεδειγμένη πλήρης διακοπή δικτύου. |
| Licenses | OssLicensesMenuActivity | Το Gradle OssLicensesTask είναι disabled, άρα πρέπει να ελεγχθεί πραγματική λίστα αδειών. |
| About/version/legal | SettingsDialog | Παλιά labels/version links χρειάζονται ενημέρωση για fork, χωρίς αφαίρεση license notices. |
| Firebase / FCM | GalleryApplication, Analytics, AndroidManifest | Παρόντα στο fork, πρέπει να ελεγχθεί η συνοχή με το offline/privacy claim. |
| History/data | ChatRepository / BoxChatDatabase | Περιέχει fallbackToDestructiveMigration: κίνδυνος απώλειας δεδομένων, χρειάζεται ασφαλής στρατηγική migrations. |

## Άλλες περιοχές UI και tasks
- HomeScreen, GalleryNavGraph, GlobalModelManager: διαδρομές navigation υπάρχουν, χρειάζονται πλήρεις navigation tests.
- Basic Chat / Ask Image / Ask Audio: LlmChatTaskModule χρησιμοποιεί generic model.runtimeHelper, όμως εικόνα/ήχος δεν εφαρμόζονται στο GGUF.
- AgentChatTaskModule και MobileActionsTask: καλούν απευθείας LiteRT helper· δεν ισχύει ως verified GGUF capability.
- Agent skills, Whisper, image generation, stable-diffusion: κληρονομημένα υποσυστήματα, δεν έχουν γίνει end-to-end device regression στην τρέχουσα branch.

## Definition of done για κάθε control
Καταγραφή (1) πότε φαίνεται, (2) τι αποθηκεύει, (3) τι validation κάνει, (4) ποιο runtime API καλεί, (5) τι εφαρμόστηκε πραγματικά, (6) fallback/error, (7) ελάχιστο automated + device test. Ακόμη και αν ένα παλιό feature δεν περιλαμβάνεται στο επόμενο release scope, δεν επιτρέπεται να εμφανίζεται ως verified χωρίς evidence.

## Voice Conversation: UI inventory (2026-11-03)

**Εύρημα:** Δεν υπάρχει dedicated, συνεχής αμφίδρομη φωνητική συνομιλία ή voice-call screen. Το `Category.VOICE` έχει task `WhisperTask` με τίτλο "Voice Input", δηλαδή εγγραφή → απομαγνητοφώνηση, όχι AI voice chat.

- `ui/whisper/WhisperTaskModule.kt` και `WhisperScreen.kt`: εικονίδιο μικροφώνου, start/stop recording, transcript, copy/clear.
- `ui/common/textandvoiceinput/HoldToDictate.kt`: push-to-talk και release-to-send, όχι διαρκές hands-free session.
- `HoldToDictateViewModel.kt`: χρησιμοποιεί Android `SpeechRecognizer`, σήμερα με `EXTRA_LANGUAGE="en-US"` — απαιτεί διόρθωση για ελληνική ομιλία/locale και ξεκάθαρη πολιτική offline.
- `ui/common/chat/MessageInputText.kt` / `AudioRecorderPanel.kt`: επιλογή ή εγγραφή audio clip και προσθήκη σε μήνυμα.
- `ui/common/chat/AudioPlaybackPanel.kt`: παίζει PCM ήχο υπάρχοντος clip μέσω `AudioTrack`, **όχι** φωνητική έξοδο μοντέλου.
- `runtime/LlamaCppModelHelper.kt`: GGUF image/audio inputs ακόμη απορρίπτονται· model-generated audio δεν έχει κλήση/renderer.

**Απαίτηση επόμενης έκδοσης:** ανεξάρτητο `Voice Conversation` entrypoint στην κύρια διεπαφή, πραγματική διαδρομή μικρόφωνο → inference → speech playback, διατήρηση context, σαφείς καταστάσεις listening/thinking/speaking/paused/error, permission/mute/stop, χειρισμός ηχείου/ακουστικών και δυνατότητα διακοπής. Να μη συγχέεται με Voice Input ή Audio Scribe. Δεν διαφημίζουμε real-time/full-duplex/barge-in μέχρι να επαληθευτούν.

## Διόρθωση provenance: προηγούμενο Termux Greek voice test

Η δήλωση «δεν υπάρχει φωνητική συνομιλία» αφορά το **τρέχον UI του Box Omni** και παραμένει αληθής. Ο χρήστης έχει ήδη επιβεβαιώσει στην ίδια συσκευή **Termux llama-server + Q8_0 + matching mmproj** με λειτουργική ελληνική αναγνώριση ομιλίας και ελληνικό `/tts`. Άρα το πρόβλημα είναι η **έλλειψη JNI/UI διασύνδεσης και αξιοπιστίας voice loop**, όχι η παντελής απουσία βασικής ελληνικής speech ικανότητας του LFM3. Το realtime HTML demo είχε διακοπές. Βλ. `docs/DEVICE_TESTS.md`.

## M1 code implementation diff (not yet runtime verified)

- Import .gguf → RuntimeType.LLAMA_CPP, to prevent falsely identifying GGUF as LiteRT-LM.
- Existing and newly imported .gguf → temporarily CPU-only and text-only capability advertisements until the media and GPU paths are actually implemented.
- Import Model UI offers optional matching mmproj picker; imported file sidecar is staged under __imports and projectorFileName passed from ModelManagerViewModel to LlamaCppModelHelper → LlamaCppEngine → SmolLM.loadProjector.
- MAX_TOKENS reaches a new native maxOutputTokens guard. Reset uses an in-place JNI session reset rather than reloading weights.
- All above are **code-only/unbuilt** and require CI then actual device verification in the integrated release, not a claim that features now work in the installed APK.
