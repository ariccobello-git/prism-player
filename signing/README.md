# Firma dell'app

- `app/prism-debug.p12` è la vecchia chiave di prova: è pubblica (il repo è pubblico), quindi da sola non deve più bastare per aggiornare Prism sulla TV.
- `signing/prism-release.p12.enc` è la chiave vera di Prism, **cifrata** con la frase del segreto `PRISM_KEY_PASSPHRASE` (AES-256, PBKDF2 600.000 giri). Senza la frase è inutilizzabile.
- `signing/lineage.bin` è la prova firmata che la vecchia chiave passa l'app alla nuova (APK Signature Scheme v3, Android 9+), con il permesso di "rollback" tolto alla vecchia: un APK firmato solo con la chiave pubblica viene rifiutato dalla TV.

Il job `release` di *Android CI* rifirma l'APK con le due chiavi (`--rotation-min-sdk-version 28`) e controlla che la firma v3 sia quella nuova prima di pubblicare.

**Mai** rifare la chiave né cancellare il segreto: senza la stessa chiave la TV non accetta più aggiornamenti e bisogna disinstallare (perdendo le liste).
