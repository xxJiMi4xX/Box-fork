# Προέλευση, κληρονομημένος κώδικας και άδειες

Αυτό είναι **ανεξάρτητο πειραματικό fork**, όχι η επίσημη σελίδα ή έκδοση του αρχικού Box.

## Προέλευση

- Άμεση βάση κώδικα: [jegly/Box](https://github.com/jegly/Box).
- Προγενέστερο upstream: [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery).
- Native εξαρτήσεις: [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp), [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), [whisper.cpp](https://github.com/ggml-org/whisper.cpp), μαζί με τις εξαρτήσεις Android/LiteRT-LM και τις αντίστοιχες άδειες.

Ανατρέξτε στο [LICENSE](../LICENSE), στα copyright headers, στο `.gitmodules`, στις native dependencies και στις επιμέρους license notices. Τα στοιχεία αυτά **διατηρούνται**.

## Τι εκκαθαρίστηκε ως παραπλανητικό περιεχόμενο

- Το κεντρικό README αντικαταστάθηκε επειδή διεκδικούσε downloads, stable releases, hardware acceleration και features από το *άλλο* repository χωρίς δικό μας έλεγχο.
- Το τεράστιο διακοσμητικό `Android/README.md` αντικαταστάθηκε με πραγματικό οδηγό build.
- Η γενική upstream περιγραφή skills αντικαταστάθηκε με σαφή δήλωση ότι η διαδρομή εργαλείων GGUF δεν έχει επαληθευτεί.
- Οι υπάρχουσες λίστες μοντέλων και τα SKILL metadata παραμένουν ως λειτουργικά στοιχεία/δεδομένα, όχι ως ισχυρισμοί δυνατότητας.

## Τι ΔΕΝ διαγράφουμε

Δεν διαγράφουμε git commits, άδειες, notices, κώδικα τρίτων, submodules, αρχεία build ή τις αναφορές σε upstream στις πηγές. Μια καθαρή περιγραφή προϊόντος **δεν** συνεπάγεται απόκρυψη προέλευσης ή ψευδή απόδοση πνευματικής εργασίας.
