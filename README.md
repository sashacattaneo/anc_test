# ANC Android

App Android (Kotlin, nessuna dipendenza esterna) che porta su smartphone l'algoritmo FxLMS del
progetto [iancraz/ANC-Implementation](https://github.com/iancraz/ANC-Implementation) (licenza MIT),
in versione feedback a un solo microfono.

## Ottenere l'APK

### Opzione A – Android Studio
1. Installa Android Studio, `File > Open` sulla cartella di questo progetto.
2. Attendi la sincronizzazione Gradle, poi `Build > Build APK(s)`.
3. L'APK è in `app/build/outputs/apk/debug/app-debug.apk`.

### Opzione B – Senza installare nulla (GitHub Actions)
1. Crea un repository GitHub vuoto e carica tutto il contenuto di questa cartella
   (compresa `.github/`).
2. Vai su **Actions > Build APK > Run workflow** (parte anche da solo a ogni push).
3. A fine build scarica l'artefatto **anc-apk** (contiene `app-debug.apk`).

Per installarlo sul telefono: copia l'APK, aprilo e consenti "Installa app sconosciute".

## Uso
1. Collega cuffie (meglio cablate) con microfono vicino all'orecchio.
2. **Calibra**: l'app riproduce rumore bianco per 6 s e stima il percorso altoparlante→microfono (NLMS).
3. **Avvia ANC**: FxLMS in tempo reale. Se il rumore viene attenuato, vedi i dB stimati.
4. Usa **Stop** al minimo problema (fischi/larsen). Tieni il volume basso.

## Limiti reali
- Il ritardo audio di Android (anche con AudioTrack low-latency) è di decine di ms, molto più del
  tempo che il suono impiega a raggiungere l'orecchio: l'ANC può cancellare solo rumori
  periodici/prevedibili e a bassa frequenza (motori, ventole, ronzii), non voci o rumori casuali.
- Non sostituisce cuffie ANC con DSP dedicato.
- L'uscita è limitata a ±0.5 per sicurezza.
