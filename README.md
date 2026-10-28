# Prism Player

**Built by Red-74.** Lettore multimediale autonomo per Android TV / Google TV, pensato per Xiaomi TV Stick, con interfaccia Glassmorphism per il telecomando, riproduzione con AndroidX Media3 e importazione delle playlist da telecomando o da telefono tramite QR.

> ## Stato di verifica
>
> **Questo è uno scaffold iniziale. Non è ancora stato compilato né testato su una macchina con Android SDK.**
> Il codice è stato scritto in un ambiente senza Android SDK e senza accesso a Google Maven / Maven Central / Gradle Services, quindi nessuna compilazione, nessun test, nessun lint e nessuna prova su dispositivo reale sono stati eseguiti prima del commit.
>
> Il progetto va considerato **verificato solo dopo un run verde del workflow "Android CI"** (tab *Actions*), che esegue test unitari, lint e `assembleDebug`. Fino ad allora possono esserci errori di compilazione o di risoluzione delle dipendenze.

| Verifica | Stato |
| --- | --- |
| Compilazione `assembleDebug` | non eseguita (la esegue la CI) |
| Test unitari (`testDebugUnitTest`) | non eseguiti (li esegue la CI) |
| Lint | non eseguito (lo esegue la CI, non bloccante) |
| Risoluzione delle versioni delle dipendenze | non verificata da rete (la verifica la CI) |
| Prova su Xiaomi TV Stick / emulatore Android TV | non eseguita |
| Navigazione con telecomando reale | non provata |

## Funzionalità

- **Interfaccia Glassmorphism per TV** (Compose for TV): pannelli traslucidi con bordo luminoso su sfondo sfumato, testo grande, focus D-pad evidente (ingrandimento e bordo bianco). Niente blur reale dello sfondo: non è disponibile prima di Android 12 e pesa troppo sulle chiavette entry-level.
- **Player Media3 (ExoPlayer)** con HLS e DASH, flussi progressivi (MP4, TS), redirect tra http e https, gestione del focus audio e schermo sempre acceso durante la riproduzione.
- **Formato video** ciclabile in riproduzione: **Auto** (rapporto nativo), **16:9**, **4:3** e **Adatta allo schermo** (stira a tutto schermo). La scelta viene ricordata.
- **Importazione manuale** di un URL playlist con il telecomando (tastiera su schermo della TV). Sono accettati solo link `http` e `https`.
- **Abbinamento col telefono tramite QR** generato sulla TV, vedi sotto.
- **Playlist M3U/M3U8**: liste IPTV (`#EXTINF`, `group-title`, `#EXTGRP`), URL relativi, liste di soli URL e singoli flussi HLS (un link `.m3u8` diretto si riproduce così com'è).
- **Italiano e inglese**.
- Compatibile Android TV / Google TV: `leanback` richiesto, touchscreen non richiesto, banner e launcher TV.
- Icona e banner a tema prisma; all'avvio una schermata glass con un raggio bianco che attraversa il prisma e si scompone nei colori, finché i dati sono caricati; l'ultimo comando Indietro dalla home chiede conferma prima di chiudere l'app.

### Telecomando

| Dove | Tasto | Azione |
| --- | --- | --- |
| Ovunque | Frecce / OK / Indietro | Navigazione standard |
| Player (menu chiuso) | OK / Menu | Apre il menu |
| Player (menu chiuso) | ▲ / ▼ (o CH+ / CH−) | Canale successivo / precedente |
| Player (menu chiuso) | ◀ / ▶ | Cambia formato video |
| Player | Indietro | Chiude il menu, poi esce |

## Abbinamento col telefono

1. Dalla home scegli **Abbina dal telefono**. La TV avvia un piccolo server locale e mostra un QR e un codice.
2. Il telefono inquadra il QR e apre una pagina con un modulo (nome facoltativo e link della playlist). Senza fotocamera si apre l'indirizzo mostrato sulla TV e si digita il codice.
3. Il link arriva alla TV e viene salvato cifrato.

Regole del codice (applicate in `PairingManager` e coperte da test):

- **Validità di 3 minuti** dalla creazione.
- **Monouso**: dopo un invio riuscito il codice è invalidato.
- **Una nuova richiesta invalida il codice precedente** (e fa ripartire il timer).
- Dopo 5 tentativi errati il codice viene bruciato (anti forza bruta sulla LAN).
- Un link digitato male non brucia il codice: si può correggere e rinviare.
- Il server esiste solo mentre la schermata è aperta: si ferma a scadenza, dopo l'uso e quando si esce.

## Sicurezza

**Playlist salvate**

- Salvate in un unico file cifrato **AES-256-GCM**. La chiave sta nell'Android Keystore e non è esportabile.
- Il file è in `noBackupFilesDir`, escluso da backup e trasferimenti; `allowBackup` è disattivato.
- Un file illeggibile (corrotto o chiave persa) viene messo da parte come `.corrupt` senza far crashare l'app.
- L'interfaccia mostra solo l'**host** del link, mai percorso o query (spesso contengono token). L'URL non viene mai scritto nei log né nei messaggi di errore.

**Rete locale**

- Il server di abbinamento è **solo in scrittura**: serve moduli statici e accetta un invio per codice valido. Non esiste alcun endpoint che elenchi, legga o esporti le playlist salvate, quindi i loro URL non transitano mai sulla rete locale. Un test automatico lo verifica.
- Accetta solo client locali (loopback, indirizzi privati, link-local) e risponde con `Cache-Control: no-store`, `Referrer-Policy: no-referrer` e una Content-Security-Policy restrittiva, e chiude la connessione dopo ogni risposta. La pagina non usa JavaScript.
- Permesso Android richiesto: solo `INTERNET`.

**Limiti noti (da conoscere prima dell'uso)**

- **L'abbinamento usa HTTP in chiaro sulla LAN.** Il link che scrivi sul telefono viaggia una volta non cifrato fino alla TV, e il codice è visibile a chi intercetta il traffico durante i 3 minuti. Usa solo reti di cui ti fidi. La pagina sul telefono lo ricorda. Passare a HTTPS richiede un certificato locale e non è stato fatto.
- Un dispositivo malevolo sulla stessa LAN può consumare i 5 tentativi e far scadere il codice (negazione del servizio sull'abbinamento, non accesso ai dati).
- `usesCleartextTraffic` è attivo perché molte playlist e molti flussi sono solo `http`. Il traffico di riproduzione è quindi leggibile in rete se il provider non offre `https`.
- Un URL salvato resta in memoria in chiaro mentre l'app lo usa e viene inviato al server della playlist, come è ovvio.

## Toolchain e compatibilità

| Componente | Versione | Note |
| --- | --- | --- |
| JDK | 17 (CI: Temurin 17) | AGP 8.7 richiede JDK 17 come minimo e default. In locale un JDK più recente potrebbe funzionare, non verificato. |
| Gradle | 8.10.2 (wrapper) | AGP 8.7 richiede almeno 8.9. |
| Android Gradle Plugin | 8.7.3 | Supporta fino all'API 35, build-tools predefinite 34.0.0. |
| Kotlin | 2.0.21 | Con il plugin Compose Compiler di Kotlin. Compatibilità con Gradle 8.10 da confermare col primo run CI. |
| compileSdk / targetSdk | 35 | La CI installa `platforms;android-35`. |
| minSdk | 23 | Le chiavi AES-GCM nel Keystore richiedono l'API 23. |
| Compose BOM | 2024.12.01 | |
| Compose for TV (`tv-material`) | 1.0.0 | |
| Media3 | 1.5.1 | |
| NanoHTTPD | 2.3.1 | Coordinate `org.nanohttpd:nanohttpd`. |
| ZXing core | 3.5.3 | Solo generazione del QR. |

Il workflow ha un passo che **fallisce** se JDK, Gradle del wrapper, piattaforma Android 35 o build-tools 34.0.0 non sono quelli attesi.

**Versioni scelte con prudenza.** Sono un insieme coerente della generazione fine 2024, scelto perché non potevo compilare per validare combinazioni più recenti. Alla data del 28 ottobre 2026 le pagine di rilascio Android indicano Media3 1.11.1 e `tv-material` 1.1.0 come stabili. Aggiornare è consigliato, ma va fatto in un secondo momento, con la CI a fare da rete di sicurezza. I requisiti di AGP, Gradle e JDK sopra sono tratti dalle note di rilascio di AGP 8.7.0; l'esistenza esatta di tutte le versioni dichiarate nel catalogo (`gradle/libs.versions.toml`) sarà confermata solo dalla risoluzione delle dipendenze in CI.

Fonti consultate: [note di rilascio AGP 8.7.0](https://developer.android.com/build/releases/agp-8-7-0-release-notes), [Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv), [Media3](https://developer.android.com/jetpack/androidx/releases/media3).

## Compilare

### Con Android Studio

1. Apri la cartella del progetto (Android Studio recente, con JDK 17 per Gradle).
2. Lascia che sincronizzi Gradle e scarichi la piattaforma Android 35 se richiesta.
3. Scegli la configurazione **app** e un emulatore Android TV, oppure *Build → Build APK(s)*.

### Da riga di comando

Servono JDK 17 e l'Android SDK (con `platforms;android-35` e `build-tools;34.0.0`). Indica il percorso dell'SDK con `ANDROID_HOME` oppure con `sdk.dir` in `local.properties` (il file è ignorato da git).

```bash
./gradlew testDebugUnitTest        # test unitari
./gradlew lintDebug                # lint (non blocca la build)
./gradlew assembleDebug            # APK di debug
```

L'APK compare in `app/build/outputs/apk/debug/app-debug.apk`. È firmato con la chiave di debug generata da Android: va bene per i test, **non** per la distribuzione.

### Con GitHub Actions

Il workflow `.github/workflows/android.yml` parte a ogni push su `main` e `initial-scaffold`, sulle pull request e a mano (*Actions → Android CI → Run workflow*). Esegue nell'ordine: validazione del Gradle wrapper, verifica della toolchain, `testDebugUnitTest`, `lintDebug`, `assembleDebug`. Pubblica come artefatti:

- `prism-player-debug-apk`: l'APK di debug (conservato 30 giorni);
- `prism-player-reports`: report dei test e del lint (14 giorni).

Il riepilogo del run riporta numero di test, dimensione e SHA-256 dell'APK. Scarica l'APK **solo da un run verde**.

### Release con link diretto (anche da telefono)

Gli artefatti delle Actions richiedono il login a GitHub e sono zip. Per avere un link diretto, utile per l'app **Downloader** sulla TV, il workflow può pubblicare l'APK in una **pre-release**:

1. Apri *Actions → Android CI → Run workflow*.
2. Scegli il branch e compila **release_tag** con un tag nuovo, per esempio `v0.1.0-debug` (deve iniziare con `v`; un tag già usato fa fallire il passo).
3. A build verde compare in *Releases* una pre-release con `prism-player-debug.apk` e il relativo `.sha256`.
4. Il link diretto ha questa forma: `https://github.com/ariccobello-git/prism-player/releases/download/<tag>/prism-player-debug.apk`

In alternativa, pubblicando un tag `v*` da git, la release parte da sola. Il job di release gira solo se la build è riuscita. L'APK è una build di debug: solo per prove.

## Installare su Xiaomi TV Stick

1. Sulla TV: *Impostazioni → Preferenze dispositivo → Informazioni*, tocca più volte **Build** per attivare le opzioni sviluppatore, poi abilita **Debug USB** / **Debug di rete**.
2. Dal computer, sulla stessa rete:

```bash
adb connect <IP-della-TV>:5555
adb install -r app-debug.apk
```

In alternativa copia l'APK su una chiavetta USB e aprilo con un file manager della TV, consentendo l'installazione da origini sconosciute. I nomi esatti dei menu variano con la versione del sistema.

## Struttura del progetto

```
app/src/main/java/dev/red74/prismplayer/
  MainActivity.kt, PrismRoot.kt      ingresso e navigazione a pila
  PrismApp.kt                         dipendenze (AppContainer)
  AppViewModel.kt, PairViewModel.kt   stato di playlist, canali, abbinamento
  data/                               URL, parser M3U, cifratura, storage, download
  pairing/                            codice, server locale, pagine, QR, rete
  player/AspectMode.kt                formati video
  ui/                                 tema glass e schermate
app/src/test/                         test unitari
.github/workflows/android.yml         CI
```

## Test automatici

Test unitari JVM (JUnit 4), eseguiti da `testDebugUnitTest`:

- **Pairing**: durata di 3 minuti al millisecondo, monouso, invalidazione con nuova richiesta, blocco dopo tentativi errati, normalizzazione del codice.
- **Server di abbinamento** (vero server su loopback): form e header di sicurezza, un solo invio per codice, errore di battitura che non brucia il codice, limite di dimensione, nessun URL salvato servito dalla rete.
- **Cifratura e storage**: andata e ritorno, IV casuale, rilevamento di manomissioni e chiave errata, nessun URL in chiaro nel file, ripristino da file corrotto.
- **Parser M3U e validazione URL**: attributi, URL relativi, flussi HLS singoli, schemi non http scartati.
- **Formati video** e **generazione del QR**.

**Non coperto da test automatici:** interfaccia Compose, riproduzione Media3, Keystore Android reale, navigazione col telecomando e comportamento su Xiaomi TV Stick. Servono prove manuali o test strumentati, non ancora presenti.

## Limiti noti e idee

- Nessuna guida programmi (EPG), nessun logo dei canali, nessuna ricerca nell'elenco.
- Solo http/https: niente RTSP né RTMP.
- Nessun test strumentato né prova su hardware.
- Le prestazioni su chiavette entry-level con liste molto grandi (fino a 20.000 voci) non sono state misurate.
- Nessun file `LICENSE`: scegline uno prima di pubblicare o distribuire.

## Origine del codice

Codice scritto da zero per questo progetto. Non contiene codice proprietario né servizi interni di Nanomid. Le dipendenze sono librerie open source pubbliche (AndroidX, Media3, NanoHTTPD, ZXing, kotlinx).
