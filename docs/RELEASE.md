# Runbook: rilascio di Prism Player

**Responsabile:** Red-74 (unico tester su dispositivo) · **Frequenza:** a ogni versione da provare sulla TV
**Ultimo aggiornamento:** 29 ottobre 2026 · **Ultima release nota:** `v0.1.29-debug` (da `initial-scaffold` @ `0ccf12a`)

## Scopo

Portare un commit di `initial-scaffold` fino all'APK installato sulla Xiaomi TV Stick: CI verde, pre-release su GitHub con APK e SHA-256, installazione con Downloader, prova a mano.

Tutto fino alla pubblicazione della pre-release può farlo Claude in autonomia. L'installazione e la prova sulla TV le fa l'utente.

## Prerequisiti

- [ ] Accesso in scrittura al repo `ariccobello-git/prism-player` (pagina *Actions* e *Releases*).
- [ ] Il commit da rilasciare è su `initial-scaffold` (PR unite, nessun lavoro a metà).
- [ ] Sulla TV: app **Downloader** (AFTVnews) installata dal Play Store, con il permesso *Installa app sconosciute* attivo per Downloader.
- [ ] TV e rete funzionanti; per provare le liste dell'utente serve la **VPN attiva**.

## Come funziona la CI (da sapere prima)

- Il workflow è `.github/workflows/android.yml` («Android CI»). Il job `build` esegue validazione del wrapper, controllo toolchain, `testDebugUnitTest`, `lintDebug`, `assembleDebug`. Il job `release` parte **solo** dopo un `build` riuscito e solo se il run ha un tag (`workflow_dispatch` con `release_tag`, oppure push di un tag `v*`).
- L'etichetta mostrata in basso nell'app (`BuildConfig.BUILD_LABEL`) è il `release_tag`; senza tag è il commit corto.
- Il `versionCode` dell'APK è il `run_number` del workflow: ogni run ha un numero più alto, quindi ogni release si installa sopra la precedente. Per lo stesso motivo **non si può installare una release più vecchia sopra una più nuova** (vedi Rollback).
- Le build della CI sono firmate con la chiave di prova pubblica (`app/prism-debug.p12`); il job `release` rifirma l'APK con la chiave di Prism (`signing/`, segreto `PRISM_KEY_PASSPHRASE`, vedi `signing/README.md`). Dopo il primo aggiornamento con la chiave nuova, la TV rifiuta gli APK firmati solo con quella di prova: installare sempre e solo le release.
- `concurrency` usa `cancel-in-progress` per workflow e ref: **un nuovo run sullo stesso branch annulla quello in corso**. Per questo il `workflow_dispatch` si lancia solo dopo che il run del push è finito.

## Procedura

### Passo 1: controllare lo stato del branch

```bash
git fetch origin initial-scaffold --tags
git log --oneline -5 origin/initial-scaffold
```

**Risultato atteso:** in cima c'è il commit (o il merge della PR) da rilasciare.
**Se non c'è:** la PR non è unita. Non rilasciare da un altro branch senza deciderlo esplicitamente.

### Passo 2: scegliere un tag nuovo

Più thread pubblicano in parallelo sulla stessa sequenza di versioni, quindi il tag si sceglie **subito prima** di lanciare il workflow, guardando tag e release reali:

```bash
git ls-remote --tags origin | sed 's#.*refs/tags/##' | grep -v '\^{}' | sort -V | tail -3
```

e la pagina *Releases* del repo (una release in corso può non avere ancora il tag).

**Risultato atteso:** l'ultimo è, per esempio, `v0.1.29-debug`. Il prossimo è `v0.1.30-debug`.
**Versione finale:** un tag senza `-debug` (per esempio `v1.0.0`) viene pubblicato come release vera e segnata come «Latest» invece che come pre-release; si installa sopra le build di debug con la stessa firma.

**Regola:** formato `v0.1.NN-debug`, numero sempre più alto dell'ultimo esistente, mai riusato (`v0.1.10` ha già fatto fallire un rilascio). Se un altro thread sta per pubblicare, coordinarsi prima e lasciargli il suo numero.

### Passo 3: aspettare la CI verde del push

*Actions → Android CI* → run del push sul commit scelto.

**Risultato atteso:** run verde. Il riepilogo riporta numero di test, dimensione e SHA-256 dell'APK.
**Se fallisce:** l'annotazione *Build problems* elenca errori di compilazione e test falliti. Correggere con una nuova PR e ripartire dal passo 1. Non rilasciare mai da un run rosso e non disattivare test per farlo diventare verde.

### Passo 4: lanciare il rilascio

*Actions → Android CI → Run workflow*:

- **Branch:** `initial-scaffold`
- **release_tag:** il tag del passo 2, per esempio `v0.1.30-debug`

(Equivalente da riga di comando: `gh workflow run android.yml --ref initial-scaffold -f release_tag=v0.1.30-debug`.)

Il run ricompila tutto con l'etichetta della versione e poi esegue il job `release`.

**Risultato atteso:** entrambi i job verdi; in *Releases* compare la pre-release «Prism Player v0.1.30-debug» con due file:

- `prism-player-debug.apk`
- `prism-player-debug.apk.sha256`

**Se fallisce `release` con «The tag … already exists»:** qualcuno ha preso quel numero. Rilanciare con il numero successivo; nessuna pulizia necessaria.
**Se fallisce `build`:** come al passo 3. Nota che il `workflow_dispatch` compila la testa del branch nel momento in cui parte: se nel frattempo è arrivato un altro commit, si rilascia quello.

### Passo 5: annotare la release

Riportare a chi installa:

- tag e commit (la release lo cita nelle note: «built … from commit …»);
- cosa cambia rispetto alla release precedente;
- cosa **non** è stato provato: tutto ciò che si vede, si sente o dipende dal telecomando non ha test automatici.

Il link diretto per la TV è:

```
https://github.com/ariccobello-git/prism-player/releases/download/v0.1.30-debug/prism-player-debug.apk
```

Qui finisce la parte che Claude può fare da solo.

### Passo 6 (facoltativo): verificare l'SHA-256 da computer

```bash
TAG=v0.1.30-debug
BASE=https://github.com/ariccobello-git/prism-player/releases/download/$TAG
curl -sSL -O "$BASE/prism-player-debug.apk" -O "$BASE/prism-player-debug.apk.sha256"
sha256sum -c prism-player-debug.apk.sha256
```

**Risultato atteso:** `prism-player-debug.apk: OK`. Downloader non mostra l'hash, quindi questa è l'unica verifica dell'integrità del file.

### Passo 7: installare sulla Xiaomi TV Stick con Downloader

1. Aprire **Downloader** sulla TV.
2. Nella casella dell'indirizzo scrivere il link diretto del passo 5 e premere **Go**.
3. A download finito Downloader apre l'installer di Android: scegliere **Installa** (è un aggiornamento: playlist, preferiti e impostazioni restano).
4. Alla fine scegliere **Fine** e, quando Downloader lo propone, **Elimina** il file APK per liberare spazio.
5. Aprire Prism Player dalla schermata principale.

**Se Android dice «App non installata»:** di solito si sta installando una versione più vecchia di quella presente, oppure un APK firmato con un'altra chiave (versioni precedenti alla v0.1.17). Vedi *Risoluzione problemi*.
**Se il download è un file di pochi KB:** il tag nel link è sbagliato o la release non esiste ancora; GitHub ha restituito una pagina d'errore.

## Checklist di prova sulla TV

Segnare ogni voce come OK / KO / non provata. Le voci KO vanno riportate con la versione mostrata in basso.

**Avvio e versione**
- [ ] Lo splash dura circa 10,6 s, la musica e il prisma sono sincronizzati, Indietro durante lo splash non fa nulla.
- [ ] In basso, su splash, Home e Libreria, c'è esattamente il tag installato (es. `v0.1.30-debug`), non `dev` né un commit.

**Home**
- [ ] Le playlist salvate ci sono ancora dopo l'aggiornamento.
- [ ] *Verifica tutte le liste* (con VPN) colora ogni lista in verde con il numero di voci, o in rosso con il motivo.
- [ ] L'interruttore suoni silenzia e riattiva, e resta com'era dopo aver chiuso e riaperto l'app.

**Telecomando e fuoco**
- [ ] Le frecce si muovono in modo naturale tra le sezioni; la sezione attiva è in primo piano, le altre arretrate.
- [ ] Il cursore è sempre visibile.
- [ ] Indietro sale di un solo livello alla volta, in ogni schermata.
- [ ] Rientrando in una schermata il cursore è dove era (playlist chiusa, canale guardato, pulsante che ha aperto la pagina).
- [ ] Passare sopra un campo di testo non apre la tastiera; OK la apre.

**Libreria**
- [ ] Tab Live, Film, Serie, Preferiti: OK su un tab scende ai contenuti, Indietro torna ai tab.
- [ ] Gruppi a più livelli: Destra/OK scende, Sinistra/Indietro risale.
- [ ] Ordinamenti Standard, A–Z, Z–A, Ultimi inseriti: la lista torna in cima e non si blocca, anche sulla lista grande.
- [ ] Ricerca: trova risultati; cambiando tab o uscendo il campo si svuota.
- [ ] Preferiti: la stella aggiunge e toglie, con suono diverso.
- [ ] Tempo di apertura della lista grande (prima volta e volte successive): annotare i secondi.

**Player**
- [ ] Un canale live, un film e un episodio partono.
- [ ] ◀/▶ saltano di 10 s, tenendo premuto accelerano; ▲/▼ cambiano canale; OK mostra i comandi.
- [ ] Avanzamento veloce 2× (senza audio) e velocità superiori; ritorno alla velocità normale.
- [ ] Nessun suono d'interfaccia dentro il player.
- [ ] Un flusso che fallisce mostra il codice d'errore HTTP.

**Standby e uscita**
- [ ] Con un menu fermo per più del tempo di salvaschermo della TV, lo schermo resta acceso.
- [ ] Con un flusso in pausa, idem.
- [ ] Indietro dalla Home chiede conferma con il cursore su **No**; Indietro annulla.
- [ ] **Sì**: lo schermo si dissolve nel nero con il jingle, poi l'app si chiude.

**Suoni**
- [ ] Un solo suono per pressione di freccia, nessuna raffica tenendo premuto.
- [ ] Suoni diversi per avanti, indietro, conferma, errore.

## Risoluzione problemi

| Sintomo | Causa probabile | Cosa fare |
|---|---|---|
| Job `release` rosso: «tag already exists» | Numero già usato da un altro thread | Rilanciare con il numero successivo |
| Job `release` saltato | `release_tag` vuoto | Rilanciare compilando `release_tag` |
| Run del push «Cancelled» | Lanciato un altro run sullo stesso branch mentre girava (`cancel-in-progress`) | Aspettare che finisca il nuovo run; per sicurezza lanciare il dispatch solo a push concluso |
| CI rossa su «Check toolchain compatibility» | Cambiata l'immagine del runner o il wrapper Gradle | Leggere l'annotazione, ripristinare JDK 17 / Gradle ≥ 8.9 / platform 35 / build-tools 34.0.0 |
| «App non installata» sulla TV | APK più vecchio di quello installato (`versionCode` minore) o firma diversa (pre v0.1.17) | Installare una release più nuova; per tornare indietro vedi *Rollback* |
| Downloader scarica pochi KB | Link sbagliato o release non ancora pubblicata | Controllare il tag nella pagina *Releases* |
| In basso compare un commit invece del tag | APK preso dagli *Artifacts* di un run senza `release_tag` | Installare l'APK della pre-release |
| Liste in errore subito dopo l'installazione | VPN spenta | Accendere la VPN e verificare di nuovo |

## Rollback

Un APK più vecchio non si installa sopra uno più nuovo, perché ha un `versionCode` più basso. Disinstallare per poi installare la versione vecchia **cancella playlist e preferiti** (la chiave di cifratura nel Keystore se ne va con l'app). Quindi il rollback non si fa reinstallando una release vecchia, ma **ripubblicando il codice buono con un tag nuovo**:

```bash
git fetch origin --tags
GOOD=v0.1.29-debug          # ultima release che funzionava
NEW=v0.1.31-debug           # tag nuovo, scelto come al passo 2
git tag "$NEW" "$GOOD^{commit}"
git push origin "$NEW"
```

Il push del tag avvia la CI su quel commit e, se verde, pubblica la pre-release `$NEW` con un `versionCode` più alto, installabile sopra quella difettosa. Poi si corregge il problema su `initial-scaffold` con una PR normale.

Una pre-release sbagliata si può segnare come tale nelle note o eliminare da *Releases*; il suo tag non va mai riusato.

## Escalation

| Situazione | Chi | Come |
|---|---|---|
| CI rossa che non dipende dal codice (runner, toolchain, permessi) | Claude, poi l'utente | Nel thread del progetto, con il testo dell'annotazione |
| Prova sulla TV fallita | L'utente segnala, Claude corregge | Nel thread: versione in basso, voce della checklist, cosa si vede |
| Accesso GitHub negato | L'utente | Ricollegare GitHub dalle impostazioni di Claude |

## Storico

| Data | Tag | Note |
|---|---|---|
| 29/10/2026 | `v0.1.28-debug` | Contiene PR #2, #3, #5 |
| 29/10/2026 | `v0.1.29-debug` | Da `0ccf12a` (PR #4: cambio tab svuota la ricerca) |
