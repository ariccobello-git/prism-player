# Guida di design: Glass, splash del prisma, transizioni

Indicazioni per chi modifica l'aspetto di Prism Player. Descrivono **com'è fatto oggi** (valori presi dal codice) e **cosa conviene fare** quando si aggiunge o si cambia qualcosa. Le raccomandazioni segnate «da provare» non sono mai state viste su una TV vera.

Il contesto vincola tutto: si guarda da 3 metri, si usa solo il telecomando, e la Xiaomi TV Stick è un dispositivo economico (GPU modesta, Android TV 9–11). Un effetto che non gira fluido sulla stick non va usato, per quanto sia bello.

---

## 1. Stile Glass

### Principi

1. **Profondità con la trasparenza, non con la sfocatura.** La sfocatura vera dello sfondo (`RenderEffect`) esiste solo da Android 12 ed è troppo pesante per la stick. Il vetro si ottiene sovrapponendo strati semitrasparenti su uno sfondo con aloni colorati. Non introdurre `Modifier.blur` su contenuti grandi.
2. **Il fuoco è la cosa più visibile dello schermo.** Su una TV il cursore è l'unico modo di sapere dove si è: l'elemento con il fuoco deve distinguersi per tre segnali insieme (scala, bordo, colore), non per uno solo.
3. **Una sezione in primo piano, le altre dietro.** La sezione che contiene il cursore è a piena scala e luminosità; le altre arretrano.
4. **Tela fissa 1280×720 dp.** `PrismTheme` scala tutta l'interfaccia per farla stare nello schermo e ignora la dimensione del carattere di sistema. Si disegna sempre per 1280×720.

### Token attuali (`ui/Glass.kt`, `PrismColors`)

| Ruolo | Valore | Uso |
|---|---|---|
| `Bg0` / `Bg1` / `Bg2` | `#070B14` / `#111A33` / `#1C1140` | Sfondo a gradiente |
| `Accent` / `AccentAlt` | `#7C9CFF` / `#B57CFF` | Fuoco, elementi scelti, aloni |
| `TextPrimary` / `TextSecondary` | `#F4F6FF` / `#B4BBD6` | Testo / testo secondario e versione |
| `Ok` / `Warn` / `Danger` | `#6FE3B0` / `#FFC857` / `#FF7C8A` | Stato liste, errori, eliminazione |
| `Favorite` | `#D97757` | Stella dei preferiti |

| Elemento | Riempimento | Bordo | Raggio |
|---|---|---|---|
| `GlassPanel` | bianco 14% → 5% (gradiente lineare) | 1 dp, bianco 38% → 6% | 28 dp |
| `GlassButton` a riposo | bianco 12% → 4% | 1 dp, bianco 22% | 20 dp |
| `GlassButton` scelto (`emphasized`) | `Accent` 28% → `AccentAlt` 18% | 1 dp | 20 dp |
| `GlassButton` con fuoco | `Accent` 55% → `AccentAlt` 45% | **3 dp bianco**, scala **1,06** | 20 dp |
| `GlassTextField` | come i pulsanti | come i pulsanti | 16 dp |

Testo: 22 sp semibold nei pulsanti, 20 sp corpo, 16 sp sottotitoli, 13 sp la versione in basso.

### Come usarli

- **Nuovi colori solo come token** in `PrismColors`, mai `Color(0x…)` sparsi nelle schermate: così il tema resta uno.
- **Al massimo due livelli di vetro sovrapposti** (pannello e pulsante). Un terzo livello non si legge più come vetro ma come grigio sporco.
- **Contrasto.** Il testo primario su vetro è ampiamente leggibile. Il secondario (`#B4BBD6`) va bene per etichette e versione; per testi lunghi o importanti usare il primario. Mai testo `Accent` su riempimento `Accent`.
- **Margini di sicurezza.** Molti televisori tagliano i bordi dell'immagine. Tenere il contenuto interattivo ad almeno il 5% dai bordi: circa **64 dp ai lati e 36 dp in alto e in basso** sulla tela 1280×720. La versione in basso sta a 8 dp dal bordo: è accettabile perché è un'informazione secondaria, ma su un televisore che taglia molto può sparire (da verificare sulla TV).
- **Stati da progettare per ogni elemento nuovo:** a riposo, con il fuoco, premuto (scala 0,96), scelto, disattivato o non disponibile (opacità ridotta, come i contenuti segnati ⊘).
- **Nuove sezioni** dentro `PrismBackground` (che disegna sfondo e versione) e con `Modifier.depthFocus()` sul gruppo.

### Miglioramenti consigliati

1. **Riflesso sul bordo superiore** (da provare). Una linea di 1 dp bianca al 20–25% sul solo bordo alto di pannelli e pulsanti dà l'idea di una lastra illuminata dall'alto, a costo quasi zero. Si disegna con `drawWithCache` per non ricreare il pennello a ogni frame.
2. **Pennelli memorizzati.** Oggi i `Brush.linearGradient` dei pulsanti vengono creati a ogni ricomposizione. Con centinaia di voci in lista conviene crearli una volta (`remember` o costanti a livello di file) e scegliere tra quelli pronti.
3. **Opacità senza buffer extra in `depthFocus`.** Un `graphicsLayer` con `alpha < 1` disegna la sezione in un buffer fuori schermo e poi lo sfuma. Con `compositingStrategy = CompositingStrategy.ModulateAlpha` l'opacità si applica direttamente a ogni elemento, senza buffer: più leggero sulla stick. Va bene finché gli elementi di una sezione non si sovrappongono tra loro (vero per righe e griglie). Da provare e misurare.
4. **Bande nei gradienti.** I pannelli TV a 8 bit mostrano gradini nei gradienti scuri e larghi. Se si notano sullo sfondo, aggiungere un rumore molto leggero (1–2% di opacità) sopra il gradiente.

---

## 2. Splash: il raggio bianco che si scompone nei colori

### Com'è fatto oggi (`ui/SplashScreen.kt`, `ui/PrismGlass.kt`, `ui/PrismOptics.kt`, `ui/PrismMotion.kt`)

- **Ottica reale in 2D.** Un prisma equilatero di vetro flint denso ruota; un raggio bianco arriva dal basso a sinistra con un angolo di 28°. Ogni colore ha il suo indice di rifrazione (rosso 1,612 … violetto 1,652), viene piegato con la legge di Snell all'entrata e all'uscita, perde luce secondo Fresnel e può riflettersi dentro il vetro (riflessione totale). I raggi si ricalcolano a ogni frame, quindi il ventaglio di colori segue la rotazione come davanti a un prisma vero.
- **Prisma di vetro realistico** (`ui/PrismGlass.kt`, da v0.1.31). Visto un po' dall'alto a sinistra con prospettiva; dentro il vetro si vede il cielo sfocato, ingrandito e capovolto, che scorre mentre ruota; facce illuminate o in ombra secondo la luce in alto a sinistra; un'ombra morbida sulle stelle dietro; una faccia laterale riflette il cielo (riflessione totale); spessore verde-azzurro lungo gli spigoli, riflessi netti con una leggera frangia arcobaleno sugli spigoli illuminati; l'angolo più vicino alla luce scintilla sui colpi della musica (`PrismMotion.sparkle`).
- **Orologio legato all'audio** (da v0.1.31). La musica viene preparata fuori dal thread dello schermo; immagine e suono partono sullo stesso frame quando foto e audio sono pronti (massimo 1,5 s di attesa) e il tempo dell'animazione viene riallineato dolcemente a `MediaPlayer.currentPosition`. Non dipende più dalla *Scala durata animazione* di sistema. La Home comincia a comparire 450 ms prima della fine, così la dissolvenza finisce insieme all'ultima nota; se le liste non sono pronte la scena resta ferma e una luce scorre sulla barra.
- **Sette colori:** `#FF3B4E` rosso, `#FF9A3D` arancio, `#FFE14D` giallo, `#4DFF88` verde, `#3FE6FF` ciano, `#5F7BFF` blu, `#B765FF` violetto.
- **Luce additiva.** Raggio, bagliori e spigoli usano `BlendMode.Plus`: dove i colori si sovrappongono tornano verso il bianco, come la luce vera.
- **Tempi legati alla musica (10,6 s):**

| Tempo | Cosa succede |
|---|---|
| 0 s | Il cielo (foto reale della Via Lattea) emerge dal nero; il prisma inizia a ruotare piano |
| da 1,4 s | La rotazione accelera seguendo gli impulsi della musica |
| 3,9 s | Rotazione più veloce (circa 62°/s) |
| 5,3 s | Colpo principale della musica |
| 5,8–8,6 s | Sei bagliori brevi |
| 8,1 s | Il prisma si ferma esattamente nella posizione classica |
| 8,1–10,6 s | Quiete; il bagliore all'uscita «respira» |
| 10,15–10,6 s | La Home compare in dissolvenza mentre la musica sfuma |

### Regole da mantenere

- **Fisica corretta prima dell'effetto.** Il rosso si piega meno, il violetto di più; il ventaglio parte stretto all'uscita dal vetro e si allarga. Un ventaglio «a mano» con colori in ordine sbagliato si nota subito.
- **Il bianco resta bianco** finché è dentro il vetro: si separa solo dopo l'impatto.
- **Nessun lampo** più veloce di tre al secondo a piena luminosità (sicurezza per chi è sensibile ai lampeggiamenti). I bagliori attuali sono a 0,55 s di distanza: va bene.
- **Il prisma si ferma sempre nella stessa posizione**, così l'ultima immagine è sempre la stessa e collega bene alla Home.
- Lo splash non si può saltare (Indietro è ignorato) ed è lungo quanto la musica.

### Miglioramenti consigliati

1. ~~**Orologio legato all'audio**~~ (fatto in v0.1.31). Prima l'animazione è un `Animatable` con `tween` di 10,6 s e la musica è un `MediaPlayer` avviato a parte: se la stick perde frame all'avvio o la musica parte in ritardo, i due possono sfasarsi di qualche decimo, proprio sul colpo a 5,3 s. Inoltre le animazioni Compose seguono l'impostazione di sistema *Scala durata animazione*: se è ridotta o disattivata, l'animazione finisce subito mentre la musica continua. Conviene calcolare il tempo dell'animazione da `MediaPlayer.currentPosition` (con un orologio di frame come riserva se l'audio non parte). Da provare sulla TV.
2. **Calcoli fuori dal disegno.** I percorsi del prisma e dei raggi cambiano a ogni frame, ma pennelli, colori e forme fisse si possono preparare una volta con `drawWithCache`, riducendo il lavoro per frame.
3. **Passaggio alla Home senza stacco.** L'ultimo frame (prisma fermo, spettro aperto) può dissolversi nello sfondo della Home in circa 400 ms, con il gradiente `Accent`→`AccentAlt` che riprende i colori blu e viola dello spettro. Evitare un frame nero o un salto di luminosità.
4. **Spettro più morbido ai lati** (da provare). Una leggera sfumatura verso il trasparente sui bordi esterni del ventaglio rende l'aspetto più ottico e meno «a strisce».

---

## 3. Transizioni in stile iOS

### Cosa rende «iOS» una transizione

- **Molle, non durate.** Il movimento rallenta in modo naturale alla fine invece di fermarsi di colpo.
- **Interrompibili.** Se l'utente preme di nuovo, l'animazione parte da dove si trova, senza scatti.
- **Direzione con un significato.** Avanti entra da destra, Indietro torna da sinistra; una finestra di conferma sale dal basso.
- **Parallasse.** La schermata che esce si sposta meno di quella che entra e si scurisce un po', così sembra restare «sotto».
- **Continuità.** Quando possibile un elemento si trasforma nel successivo (la locandina che diventa la scheda del film).

### Valori attuali

| Dove | Movimento | Valori |
|---|---|---|
| Cambio schermata (`PrismRoot.kt`), avanti | entra da destra di 1/3 della larghezza, esce a sinistra di 1/5 | molla smorzamento 0,86, rigidità `MediumLow`; dissolvenza 320 ms in entrata, 240 ms in uscita; scala 0,95 → 1 in entrata, 1 → 0,97 in uscita (380 ms) |
| Cambio schermata, indietro | speculare | stessi valori |
| Fuoco su un pulsante (`GlassButton`) | ingrandimento 1,06, pressione 0,96 | molla smorzamento 0,55, rigidità `MediumLow` |
| Fuoco tra sezioni (`depthFocus`) | scala 0,94 ↔ 1, opacità 0,55 ↔ 1 | `tween` 320 ms |
| Avvisi in Home | salgono dal basso con scala 0,9 → 1 | molla + dissolvenza 220 ms |

Lo spostamento parziale (1/3 invece dell'intera larghezza) è una scelta giusta per la TV: un'intera schermata che scorre su un televisore grande stanca l'occhio.

### Regole

1. **Mettere le molle in un posto solo.** Creare un oggetto di token (per esempio `PrismSprings`: `Focus`, `Screen`, `Sheet`) e usarlo ovunque, invece di scrivere `spring(...)` a mano in ogni schermata. Così il ritmo dell'app resta uno.
2. **Il fuoco deve essere più rapido della mano.** Tenendo premuta una freccia il telecomando ripete il tasto ogni 50 ms circa. Le animazioni del fuoco devono essere interrompibili (`animateFloatAsState` lo è) e arrivare quasi a destinazione entro 150–200 ms, altrimenti il cursore sembra in ritardo.
3. **Rimbalzo misurato sul fuoco** (da provare). Lo smorzamento 0,55 fa superare 1,06 e tornare indietro in modo ben visibile. Per un'aria più iOS, 0,7–0,75 mantiene un accenno di rimbalzo senza «ballare» quando si scorre una lista lunga.
4. **Uscita e conferme come pannello che sale.** La schermata «Chiudere l'app?» e le future conferme salgono dal basso; la schermata dietro resta visibile, scurita e ridotta a circa 0,95 (come i fogli di iOS), invece di essere sostituita.
5. **Niente input sulla schermata che esce.** Durante `AnimatedContent` le due schermate esistono insieme per qualche centinaio di millisecondi: un tasto premuto in quel momento può spostare il fuoco su quella che sta uscendo. La schermata in uscita non deve poter ricevere il fuoco, e la richiesta di fuoco della nuova va fatta quando è in posizione (oggi si aspetta un frame con `withFrameNanos`, e funziona; mantenerlo nelle schermate nuove).
6. **Continuità tra griglia e scheda** (da provare, costoso). Compose 1.7 offre `SharedTransitionLayout` per far crescere la locandina dalla griglia fino alla scheda del film. È l'effetto più «iOS» possibile, ma è sperimentale e pesante: va misurato sulla stick prima di adottarlo.
7. **Rispettare la scala animazioni di sistema.** Chi riduce le animazioni nelle impostazioni della TV deve ottenere dissolvenze brevi, non un'app rotta. Lo splash è l'unico caso delicato (vedi sopra).
8. **Solo trasformazioni e opacità.** Animare scala, posizione e opacità con `graphicsLayer`; mai larghezza, altezza o padding, che costringono a ricalcolare l'impaginazione a ogni frame e sulla stick fanno perdere fotogrammi.

---

## Fonti

Scritta leggendo il codice del branch `initial-scaffold` (`ui/Glass.kt`, `ui/SplashScreen.kt`, `ui/PrismOptics.kt`, `ui/PrismMotion.kt`, `PrismRoot.kt`, `ui/HomeScreen.kt`) e applicando le linee guida generali delle interfacce per TV (margine di sicurezza del 5%, navigazione col D-pad) e del movimento in stile iOS. Nessuna di queste indicazioni è stata provata su una TV vera.
