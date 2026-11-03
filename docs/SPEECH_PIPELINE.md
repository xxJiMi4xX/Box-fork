# Box Omni — LFM3 Omni: Native Greek speech pipeline (M3)

**Ημερομηνία:** 2026-11-03
**Source reference:** branch `feature/gguf-v080-native`, commits `3dc71cc`, `be51aae`, `475face`, `4cc3870`. Όλα είναι **source-only, χωρίς compile/device verification**.

## Πραγματική αφετηρία

Ο χρήστης δοκίμασε στο Poco X6 Pro, μέσω **Termux `llama-server`**, ελληνική είσοδο φωνής («5+7» → «12») και κατανοητό ελληνικό `/tts` WAV 24 kHz με LFM3-Omni-2B Q8_0 + matching mmproj. Αυτό ΔΕΝ αποδεικνύει ότι το Box JNI λειτουργεί με τον ίδιο τρόπο. Το realtime HTML demo του Termux είχε διακοπές.

Επίσημη αρχιτεκτονική και προδιαγραφές του μοντέλου: https://huggingface.co/LiquidAI/LFM3-Omni-2B. Επίσημες ελληνικές δυνατότητες ομιλίας, αλλά η ποιότητα πρέπει να ελεγχθεί στην πραγματική συσκευή.

## Ακριβής διαδρομή code — TTS (χωρίς UI)

1. `LlamaCppEngine.synthesizeSpeech(text, language="Greek", voice="female", maxFrames=512)` εκτελείται σε background dispatcher.
2. `SmolLM.synthesizeSpeech` → JNI `SmolLM_synthesizeSpeechNative` → `Inference::synthesizeSpeech`.
3. Απαιτεί ήδη φορτωμένα GGUF + `mmproj` και `MTMD_GEN_AUDIO_TYPE_LFM3A`.
4. Το LFM3 ChatML template μορφοποιεί system prompt «Perform TTS in Greek using a female voice.» και το κείμενο. Προστίθεται `<|audio_start|>` στην έξοδο του template για την επιθυμητή speech mode. Αυτός ο ακριβής prompt renderer θέλει **αντιπαραβολή με το επιτυχημένο `llama-server` /tts** στο validation.
5. `mtmd_helper_gen_audio_init`, `set_input`, `step_prompt`, `step_gen`, `flush/get_output` παράγουν bounded WAV. Ο decoder/codec είναι ήδη ενσωματωμένος στο pinned `libmtmd`.
6. Το API επιστρέφει `ByteArray` WAV. **Ακόμη δεν αναπαράγεται από το Box UI**, ούτε διαθέτει αυτόματη ροή.

Προστασίες: έως 4096 UTF-8 bytes ανά κείμενο, 1–1024 frames, μέχρι 16 MiB WAV, serializing με κλειδαριά, `stopSpeech()` στέλνει native atomic cancellation, επαναφορά embeddings mode. Τα όρια είναι όρια ασφαλείας αρχικής υλοποίησης, όχι τεκμηριωμένη βέλτιστη απόδοση.

## Ακριβής διαδρομή code — είσοδος WAV (χωρίς UI enablement)

1. `LlamaCppModelHelper.runInference(audioClips.singleOrNull())` → `LlamaCppEngine.generateResponse(audioWav)`.
2. `SmolLM.getResponseAsFlow(query, audioWav)` → JNI `startCompletionAudio` → `Inference::start`.
3. `mtmd_helper_bitmap_init_from_buf` διαβάζει WAV και φτιάχνει audio bitmap (το `libmtmd` κάνει audio decode/resampling).
4. Η είσοδος προστίθεται στο μοντέλο με το `mtmd_default_marker()` στο rendered user prompt, `mtmd_tokenize` και `mtmd_helper_eval_chunks`.
5. Κατόπιν χρησιμοποιείται το κανονικό GGUF text decode, δηλαδή πρόκειται για **audio→text response**, όχι πλήρες interleaved speech-to-speech.

Η είσοδος περιορίζεται σε 1 clip και 16 MiB. Το ιστορικό σώζει μόνο textual placeholder/attached user text, **όχι απομαγνητοφώνηση του ήχου**. Πρέπει να σχεδιαστεί audio transcript persistence πριν θεωρήσουμε πλήρη φωνητική συνομιλία.

## Τι λείπει μέχρι να θεωρηθεί λειτουργικό

- [ ] Ενιαίο compile με pinned llama.cpp / mtmd-helper APIs. Δεν έχει εκτελεστεί.
- [ ] Δοκιμή native TTS ελληνικού WAV / rate / διάρκεια / καθαρότητα σε Poco. Δεν έχει εκτελεστεί.
- [ ] Δοκιμή πραγματικής ηχογράφησης μικροφώνου → native input → κατανόηση ελληνικής πρότασης. Δεν έχει εκτελεστεί.
- [ ] Αντιπαραβολή renderer TTS με Termux `/tts`. Το εγγενές TTS named template / voice configuration μπορεί να διαφέρει.
- [ ] AudioTrack player, ήχος εξόδου, audio-focus, route, Bluetooth, interruption.
- [ ] Χειρισμός VAD, echo cancellation, pause/mute, app lifecycle, πολλαπλοί γύροι και history transcripts.
- [ ] Άλλες γλώσσες/μοντέλα μόνο με πραγματική runtime capability και δοκιμή, όχι μέσω checkbox.
- [ ] GPU/NPU αληθινή dispatch verification. Η τρέχουσα GGUF engine παραμένει CPU-only.

## Ειδικές δοκιμές και προϋπολογισμοί (μέρος του ήδη εγκεκριμένου QA, όχι επιπλέον)

| Test | Ώρες | Κριτήριο |
| --- | ---: | --- |
| Έλεγχος ABI/JNI και build | 2 | compile success χωρίς unsupported symbol |
| Ελληνικό TTS `Greek/female` / WAV | 3 | κατανοητός ήχος, σωστό rate, σωστός prompt renderer |
| Ελληνικό audio input WAV | 3 | predetermined spoken phrases αναγνωρίζονται/απαντώνται |
| Abort, repeated load/unload, invalid files | 2 | ασφαλής αποδέσμευση χωρίς διαρροή/crash |
| Αμφίδρομο UI audio loop | 4 | πολλαπλοί γύροι, transcript και controlled playback |
| **Σύνολο επιμέρους** | **14** | Ενσωματώνεται στις υπάρχουσες εργασίες audio+QA |

## Επόμενο βήμα

Πριν από το ένα ολοκληρωμένο APK του R1: επόμενη ομάδα πηγαίου κώδικα πρέπει να συνδέσει audio session UI/controller και ελεγχόμενο WAV playback, έπειτα ολοκληρωμένες capability/actual backend ενδείξεις, GPU Vulkan διερεύνηση και regression όλης της εφαρμογής. Οποιαδήποτε native audio αβεβαιότητα παραμένει disabled σε production UI έως το device gate.
