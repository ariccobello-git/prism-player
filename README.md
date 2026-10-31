# Prism Player

**Built by Red-74.** Lettore multimediale autonomo per Android TV / Google TV, pensato per Xiaomi TV Stick, con interfaccia Glassmorphism per il telecomando, riproduzione con AndroidX Media3 e importazione delle playlist da telecomando o da telefono tramite QR.

> ## Stato di verifica
>
> Il progetto viene compilato e testato **solo dalla CI** (workflow *Android CI*: test unitari, lint, `assembleDebug`). Ogni APK pubblicata nelle *Releases* viene da un run verde.
> Non esistono test su dispositivo né test dell'interfaccia: grafica, suoni, telecomando, fluidità e tempi delle liste grandi si verificano a mano sulla Xiaomi TV Stick.

| Verifica | Stato |
| --- | --- |
| Compilazione `assembleDebug` | eseguita dalla CI a ogni push e pull request |
| Test unitari (`testDebugUnitTest`) | eseguiti dalla CI |
| Lint | eseguito dalla CI, non bloccante |
| Prova su Xiaomi TV Stick | manuale, a ogni release |
| Test strumentati / interfaccia | assenti |

## Funzionalità

- **Home**: Aggiungi URL playlist, Abbina dal telefono, Verifica tutte le liste, Scorciatoie, Aggiornamenti e codec, Suoni attivi/silenziati. A destra le playlist con il loro stato (verde: verificata, con il numero di voci; rosso: errore con il motivo; giallo: non ancora verificata), Riprova/Verifica ed Elimina con conferma. La versione è scritta in basso su ogni pagina.
- **Playlist M3U/M3U8** e pannelli Xtream (`get.php`): aggiunta con la tastiera della TV o dal telefono tramite QR. Una lista identica non viene salvata due volte. In lettura si provano 10 identità HTTP (User-Agent) e varianti dell'indirizzo, con messaggi d'errore specifici; tetto di 300.000 voci (oltre, la lista è troncata e segnalato).
- **Libreria Live / Film / Serie / Preferiti** organizzata come la lista: gruppi a più livelli sulla stessa pagina (gruppo principale › sottogruppo › contenuti, con la voce «Tutti»), ordinamento Standard / A–Z / Z–A / Ultimi inseriti, ricerca, locandine, guida programmi (EPG), anteprima live, schede di film e serie (stagioni ed episodi), preferiti con stella.
- **Liste molto grandi**: ogni lista aperta è salvata sul dispositivo in un piccolo database (SQLite). Alle aperture successive si leggono solo i nomi dei gruppi e i conteggi; le voci si leggono a pagine mentre si scorre, la ricerca usa un indice di parole (FTS4) e l'ordinamento lo fa il database. La copia si aggiorna in background quando ha più di 6 ore.
- **Player Media3** in stile Netflix: play/pausa, ±10 s (tenendo premuto accelera), barra del tempo, avanzamento 2×/4×/8×, audio e sottotitoli, episodi e prossimo episodio, info flusso, 14 formati immagine con scelta ricordata, LIVE con ritorno al vivo, ▲/▼ cambiano canale.
- **Descrizioni**: nella scheda di un film la trama compare sotto **Guarda**, in quella di una serie sotto il titolo; si chiede al pannello Xtream solo quando la scheda si apre. Senza trama compare «Descrizione non disponibile».
- **Prism, l'assistente**: un omino con la testa a prisma accanto al campo Cerca, animato e con una barra di luce che segue la voce mentre ascolta. Si chiama con OK su di lui o col tasto voce del telecomando, se la TV lo passa all'app. Saluta, ascolta subito dal microfono del telecomando, mostra la domanda al centro e chiede «Ho capito bene?»: con «no» riascolta, con una nuova richiesta la sostituisce, con «sì» risponde. Capisce richieste come «metti Rai 1», «voglio guardare Matrix», «apri la serie…» (e le avvia), «cerca un film horror», «ultimi film arrivati», «cosa c'è su…», «di cosa parla…», «quanti film ci sono» e domande sull'uso dell'app; al resto risponde che può aiutare solo con la lista. Voce italiana più naturale e più bassa, scelta tra quelle installate.
- **Scorciatoie**: apre le app installate sulla TV scelte dall'utente.
- **Aggiornamenti e codec**: controlla se c'è una release più recente dell'app e di Media3 ed elenca i decoder del dispositivo.
- **Interfaccia per telecomando**: movimento naturale del cursore tra le sezioni, la sezione attiva in primo piano e le altre arretrate, cursore ripristinato al rientro, riconoscimento del telecomando Xiaomi. Suoni d'interfaccia leggeri e disattivabili (mai nel player). Schermo sempre acceso con l'app in primo piano.
- **Apertura e uscita**: splash di 12 s con una foto reale della Via Lattea, un prisma di vetro che ruota in 3D con la musica e un raggio bianco scomposto con rifrazione reale, i cui colori escono dallo schermo verso chi guarda; musica originale sintetizzata. L'ultimo Indietro dalla Home chiede conferma, poi un buco nero inghiotte la scena pezzo per pezzo, si chiude in un punto di luce che resta 2 s e lascia 1,5 s di nero (9 s di musica, 10,5 s in tutto).
- **Italiano e inglese**. Compatibile Android TV / Google TV (`leanback`, banner e launcher TV).

### Telecomando

| Dove | Tasto | Azione |
| --- | --- | --- |
| Ovunque | Frecce | Si muovono tra le sezioni in modo naturale |
| Libreria | Destra / OK | Apre un gruppo (scende di un livello) |
| Libreria | Sinistra / Indietro | Risale di un livello |
| Fila dei tab | OK | Scende ai contenuti |
| Player (comandi nascosti) | ◀ / ▶ | Salto di 10 s; tenendo premuto 30 s, poi 60 s |
| Player | ▲ / ▼ | Canale successivo / precedente |
| Player | OK | Mostra i comandi |
| Player | ⏪ / ⏩ (a schermo o sul telecomando) | 2×, 4×, 8×, poi velocità normale |
| Home | Indietro | Chiede se chiudere l'app |

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
- La copia scaricata di ogni lista è compressa e cifrata a blocchi (AES-256-GCM). Nel database della lista gli indirizzi dei flussi (che contengono le credenziali) sono cifrati uno per uno con una chiave casuale del file, a sua volta cifrata dalla chiave del Keystore; nomi, gruppi e loghi sono in chiaro. Tutto sta in `noBackupFilesDir`.
- Il file è in `noBackupFilesDir`, escluso da backup e trasferimenti; `allowBackup` è disattivato.
- Un file illeggibile (corrotto o chiave persa) viene messo da parte come `.corrupt` senza far crashare l'app.
- L'interfaccia mostra solo l'**host** del link, mai percorso o query (spesso contengono token). L'URL non viene mai scritto nei log né nei messaggi di errore.

**Rete locale**

- Il server di abbinamento è **solo in scrittura**: serve moduli statici e accetta un invio per codice valido. Non esiste alcun endpoint che elenchi, legga o esporti le playlist salvate, quindi i loro URL non transitano mai sulla rete locale. Un test automatico lo verifica.
- Accetta solo client locali (loopback, indirizzi privati, link-local) e risponde con `Cache-Control: no-store`, `Referrer-Policy: no-referrer` e una Content-Security-Policy restrittiva, e chiude la connessione dopo ogni risposta. La pagina non usa JavaScript.
- Permessi Android: `INTERNET` e `RECORD_AUDIO`. Il microfono serve solo all'assistente Prism, viene chiesto la prima volta che lo si usa, e l'audio va al riconoscimento vocale della TV (Google sulla Xiaomi TV Stick).

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

L'APK compare in `app/build/outputs/apk/debug/app-debug.apk`. È firmato con una chiave di debug fissa (`app/prism-debug.p12`, non segreta), così ogni nuova build si installa sopra la precedente senza disinstallare: va bene per i test, **non** per la distribuzione.

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

Il modo più semplice: con l'app **Downloader** sulla TV apri il link diretto della release (vedi sopra). Dalla v0.1.17 ogni versione si installa sopra la precedente; Android chiede sempre la conferma.

In alternativa, con adb:

1. Sulla TV: *Impostazioni → Preferenze dispositivo → Informazioni*, tocca più volte **Build** per attivare le opzioni sviluppatore, poi abilita **Debug USB** / **Debug di rete**.
2. Dal computer, sulla stessa rete:

```bash
adb connect <IP-della-TV>:5555
adb install -r app-debug.apk
```

In alternativa copia l'APK su una chiavetta USB e aprilo con un file manager della TV, consentendo l'installazione da origini sconosciute. I nomi esatti dei menu variano con la versione del sistema.

## Documentazione

- [docs/RELEASE.md](docs/RELEASE.md): procedura di rilascio, dal tag all'installazione con Downloader, con la checklist di prova sulla TV.
- [docs/DESIGN.md](docs/DESIGN.md): stile Glass, splash del prisma e transizioni.

## Struttura del progetto

```
app/src/main/java/dev/red74/prismplayer/
  MainActivity.kt, PrismRoot.kt      ingresso e navigazione a pila
  PrismApp.kt                         dipendenze (AppContainer)
  AppViewModel.kt, PairViewModel.kt   stato di playlist, canali, abbinamento
  data/                               URL, parser M3U, cifratura, storage, download
  pairing/                            codice, server locale, pagine, QR, rete
  data/CatalogDb.kt                   database delle liste (pagine, ricerca, ordinamento)
  device/                             profilo del telecomando, app installate, codec
  player/                             formati video, avanzamento veloce
  ui/                                 tema glass e schermate
app/src/test/                         test unitari
.github/workflows/android.yml         CI
```

## Test automatici

Test unitari JVM (JUnit 4), eseguiti da `testDebugUnitTest`:

- **Pairing**: durata di 3 minuti al millisecondo, monouso, invalidazione con nuova richiesta, blocco dopo tentativi errati, normalizzazione del codice.
- **Server di abbinamento** (vero server su loopback): form e header di sicurezza, un solo invio per codice, errore di battitura che non brucia il codice, limite di dimensione, nessun URL salvato servito dalla rete.
- **Cifratura e storage**: andata e ritorno, IV casuale, rilevamento di manomissioni e chiave errata, nessun URL in chiaro nel file, ripristino da file corrotto.
- **Parser M3U e validazione URL**: attributi, URL relativi, flussi HLS singoli, schemi non http scartati; pannello Xtream simulato.
- **Catalogo e liste grandi**: gruppi a livelli, ordinamenti, ricerche e preferiti; lettura a pagine (`RowList`) e testo della ricerca a parole (`FtsQuery`); copia cifrata a blocchi (`ListCache`).
- **Formati video**, **QR**, **pulizia del testo incollato**, **controllo aggiornamenti**, **Scorciatoie**, **ottica e movimento del prisma**.

**Non coperto da test automatici:** interfaccia Compose, riproduzione Media3, database SQLite reale (le query girano solo sul dispositivo), Keystore Android reale, navigazione col telecomando e comportamento su Xiaomi TV Stick. Servono prove manuali o test strumentati, non ancora presenti.

## Limiti noti e idee

- Solo http/https: niente RTSP né RTMP.
- Nessun test strumentato: tutto ciò che si vede e si sente è provato solo a mano sulla TV.
- La prima apertura di una lista dopo un aggiornamento che cambia il formato salvato è lenta (la lista si rilegge e si salva di nuovo); i tempi reali con 250.000 voci vanno misurati sulla Xiaomi.
- Il telecomando Xiaomi non ha tasti di avanzamento veloce: si usano i pulsanti ⏪ ⏩ a schermo.
- Nessun file `LICENSE`: scegline uno prima di pubblicare o distribuire.

## Origine del codice e crediti

Codice scritto da zero per questo progetto. Non contiene codice proprietario né servizi interni di Nanomid. Le dipendenze sono librerie open source pubbliche (AndroidX, Media3, NanoHTTPD, ZXing, kotlinx, Coil).

- Musica di apertura, chiusura e suoni dell'interfaccia: originali, sintetizzati per questo progetto.
- Foto della Via Lattea nella schermata d'apertura (`res/drawable-nodpi/galaxy_bg.jpg`): **ESO/S. Brunier**, «The Milky Way panorama» (eso0932a), licenza [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Ritagliata, ruotata e leggermente scurita. Fonte: <https://www.eso.org/public/images/eso0932a/>.
