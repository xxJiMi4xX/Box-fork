# Box Omni / Android — οδηγός εργασίας

Το παλαιό αρχείο εδώ ήταν μη χρηστική μεγάλη διακοσμητική αναπαράσταση. Αντικαθίσταται από οδηγίες που αντιστοιχούν στην **τρέχουσα** branch. Η τεκμηρίωση του προϊόντος βρίσκεται στο [κεντρικό README](../README.md).

## Δομή

- `src/app`: Android UI, model import, LiteRT-LM, επιλογή backend και packaging.
- `src/smollm`: ανεξάρτητος JNI adapter για GGUF και `llama.cpp` με `mtmd`.
- `src/llama.cpp`: pinned git submodule, commit `8642541b255bfb5dc39cd351a178be09013a55d6` (v0.8.0).
- `src/stablediffusion` και `src/whisper`: παλαιά modules/υποσυστήματα, **δεν έχει γίνει runtime έλεγχος σε αυτό το fork**.
- `src/app/src/main/jniLibs/arm64-v8a`: οι MediaTek LiteRT dispatch/compiler `.so` περιλαμβάνονται στο debug APK. Δεν συνιστούν απόδειξη NPU εκτέλεσης.

## Αναπαραγώγιμο build

Η [GitHub Actions ροή](../.github/workflows/box-android-validation.yml) χρησιμοποιεί Ubuntu 24.04, JDK 21, Android SDK platform 37.2, NDK 27.2.12479018, CMake 3.22.1, και τρέχει `./gradlew :app:assembleDebug --no-daemon --stacktrace --console=plain --max-workers=2` από τον φάκελο `Android/src`. Απαιτείται checkout με **recursive submodules**.

Το αποτέλεσμα βρίσκεται στο `Android/src/app/build/outputs/apk/debug/`. Το debug `applicationId` είναι `com.box.gallery.omni.dev`, ώστε να μην αντικαθιστά την εγκατάσταση του αρχικού Box. Πρόκειται για debug APK, όχι υπογεγραμμένη release για διανομή.

## Διαχωρισμός backends

- `.gguf` → `Model.runtimeHelper` → `LlamaCppModelHelper` → `LlamaCppEngine` → `SmolLM` JNI.
- `.litertlm` → `LlmChatModelHelper` → LiteRT-LM.
- Στο GGUF η GPU είναι επί του παρόντος κλειστή (`GGML_VULKAN=OFF`, `n_gpu_layers=0`). Δεν υπάρχει GGUF NPU διαδρομή.
- Η εισαγωγή `mmproj` δεν παρέχεται ακόμη στο UI. Η native ανίχνευση δυνατοτήτων του έχει μόνο μεταγλωττιστεί.
- Το `Agent Chat` και οι `Mobile Actions` έχουν ακόμη απευθείας κλήσεις προς LiteRT-LM· δεν πρέπει να προβάλλονται ως αποδεδειγμένες λειτουργίες GGUF.

## Διαδικασία τροποποίησης

1. Καταγραφή ζητήματος και τεκμηρίων στο `../docs/STATUS.md` ή `../docs/UI_BACKEND_AUDIT.md`.
2. Ενημέρωση πλήρους σχεδίου στο `../docs/PLAN_NEXT_APK.md` **πριν** από την υλοποίηση.
3. Commits κώδικα με σαφή αιτιολόγηση, χωρίς αυτόματη αλλαγή του `main`.
4. CI έλεγχος, ανάλυση σφαλμάτων, έλεγχοι παλινδρόμησης.
5. Συγκριτική δοκιμή στην πραγματική συσκευή· καταγραφή στο `../docs/CHANGELOG.md`.
6. Ολοκλήρωση checklist `../docs/RELEASE_PROTOCOL.md`, διαφορετικά η έκδοση χαρακτηρίζεται πειραματική.

Για λεπτομέρειες και την ιστορική ακολουθία εργασιών: [docs](../docs/README.md).
