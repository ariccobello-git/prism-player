package dev.red74.prismplayer.assistant

/** What Prism can look up and do in the open list. Implemented over the list database and the guide (see PrismAssistant). */
interface PrismKnowledge {
    enum class Kind { FILM, SERIES, CHANNEL }

    /** Something Prism found and can open: plays a channel or a film, opens a series. */
    class Hit(val title: String, val kind: Kind, val open: () -> Unit)

    /** A category of the list whose name matches a genre ("Film Horror" for "horror"). */
    class Group(val name: String, val kind: Kind, val count: Int, val examples: List<String>)

    /** Titles that match [query], best first; a few spellings are tried before giving up. */
    suspend fun find(kind: Kind, query: String, limit: Int): List<String>

    /** The best match to open: of [kind], or a channel, then a film, then a series when [kind] is null. */
    suspend fun best(kind: Kind?, query: String): Hit?

    suspend fun count(kind: Kind): Int

    /** The category whose name contains one of [stems], of [kind] or of any kind. */
    suspend fun group(kind: Kind?, stems: List<String>): Group?

    /** The entries added last, newest first. */
    suspend fun latest(kind: Kind, limit: Int): List<String>

    /** The channel that best matches [query] and the programme on it now (null when the guide has nothing). */
    suspend fun onAir(query: String): Pair<String, String?>?

    /** The plot of the film or series that best matches [query], with its title. */
    suspend fun plot(query: String): Pair<String, String?>?
}

/** What Prism says, and what it does once it has said it (open or play something). */
data class PrismReply(val text: String, val action: (() -> Unit)? = null)

/**
 * Prism's brain, entirely inside the app. Like a voice assistant it expects short spoken requests about the open
 * list and the app (Italian, as heard by the remote's microphone): play or open something, search, genres, what is
 * on, plots, counts, the newest arrivals and how the app works. Anything else gets a polite "I can only help with
 * this list".
 */
object PrismBrain {

    sealed interface Intent {
        data object Hello : Intent
        data object Thanks : Intent
        data object WhoAreYou : Intent
        data class Help(val topic: Topic) : Intent
        data class Count(val kind: PrismKnowledge.Kind?) : Intent
        data class OnAir(val channel: String) : Intent
        data class Plot(val title: String) : Intent
        data class Play(val kind: PrismKnowledge.Kind?, val query: String) : Intent
        data class Genre(val kind: PrismKnowledge.Kind?, val genre: Genre) : Intent
        data class Latest(val kind: PrismKnowledge.Kind) : Intent
        data class Search(val kind: PrismKnowledge.Kind?, val query: String) : Intent
        data object Unknown : Intent
    }

    /** Genres as they are named in list categories: [stems] are matched inside the category names. */
    enum class Genre(val spoken: List<String>, val stems: List<String>, val label: String) {
        HORROR(listOf("horror", "dell'orrore", "paura", "terrore"), listOf("horror", "orrore", "paura"), "horror"),
        THRILLER(listOf("thriller", "suspense"), listOf("thriller", "suspense"), "thriller"),
        COMEDY(listOf("commedia", "commedie", "comico", "comici", "divertente", "divertenti", "ridere"), listOf("commedi", "comic", "comedy"), "commedia"),
        ACTION(listOf("azione", "d'azione"), listOf("azione", "action"), "azione"),
        ADVENTURE(listOf("avventura", "avventure"), listOf("avventur", "adventure"), "avventura"),
        ANIMATION(listOf("animazione", "animati", "cartoni", "cartone", "cartoon", "anime"), listOf("animazione", "animat", "cartoni", "cartoon", "anime", "kids", "bambini"), "animazione"),
        KIDS(listOf("bambini", "bimbi", "ragazzi", "famiglia"), listOf("bambini", "kids", "famiglia", "family", "ragazzi"), "bambini e famiglia"),
        SCIFI(listOf("fantascienza", "sci-fi", "scifi"), listOf("fantascienza", "sci-fi", "scifi", "sci fi"), "fantascienza"),
        FANTASY(listOf("fantasy", "fantastici", "fantastico"), listOf("fantasy", "fantastic"), "fantasy"),
        DRAMA(listOf("drammatico", "drammatici", "dramma", "drammi", "drama"), listOf("dramm", "drama"), "drammatico"),
        ROMANCE(listOf("romantico", "romantici", "romantica", "amore", "sentimentale", "sentimentali"), listOf("romantic", "sentimental", "romance", "amore"), "romantico"),
        CRIME(listOf("giallo", "gialli", "poliziesco", "polizieschi", "crime", "crimine", "mistero"), listOf("giall", "polizies", "crime", "crimin", "mister", "noir"), "giallo e poliziesco"),
        WAR(listOf("guerra"), listOf("guerra", "war"), "guerra"),
        WESTERN(listOf("western", "cowboy"), listOf("western"), "western"),
        DOCUMENTARY(listOf("documentario", "documentari", "documentaristico"), listOf("document"), "documentari"),
        MUSIC(listOf("musicale", "musicali", "musical", "concerti", "concerto"), listOf("music", "concert"), "musica"),
        SPORT(listOf("sport", "sportivi", "calcio", "partita", "partite"), listOf("sport", "calcio", "football", "dazn"), "sport"),
        NEWS(listOf("notizie", "telegiornale", "tg", "news", "informazione"), listOf("news", "notizi", "informazione", "tg"), "notizie"),
        HISTORY(listOf("storico", "storici", "storia"), listOf("storic", "history"), "storico"),
        BIOGRAPHY(listOf("biografico", "biografici", "biografia"), listOf("biografi", "biograph"), "biografico"),
        ;
    }

    enum class Topic(val words: List<String>, val text: String) {
        FAVORITES(
            listOf("preferit", "stella", "stellina"),
            "Per aggiungere un preferito apri il film, la serie o il canale e premi la stella. Li ritrovi tutti nella scheda Preferiti.",
        ),
        SPEED(
            listOf("veloce", "velocità", "avanti", "indietro", "riavvolg", "accelera", "saltare", "salto"),
            "Nel player i tasti avanti e indietro veloce vanno da 2x fino a 128x. Le frecce sinistra e destra saltano di 10 secondi, e tenendole premute il salto diventa più lungo.",
        ),
        AUDIO(
            listOf("sottotitol", "lingua", "doppiaggio", "traccia"),
            "Durante la visione premi OK per mostrare i comandi, poi scegli Audio o Sottotitoli.",
        ),
        PICTURE(
            listOf("formato", "zoom", "immagine", "schermo intero", "bande nere", "allarga"),
            "Durante la visione premi OK e scegli il formato immagine: ce ne sono 14 e la scelta viene ricordata.",
        ),
        CHANNELS(
            listOf("cambiare canale", "cambio canale", "canale successivo", "canale precedente", "zapping"),
            "Mentre guardi un canale live, le frecce su e giù passano al canale successivo o precedente.",
        ),
        LISTS(
            listOf("lista", "playlist", "liste"),
            "Per cambiare lista torna alla Home con Indietro e scegline un'altra. Per aggiungerne una usa Aggiungi URL oppure Abbina dal telefono.",
        ),
        SEARCH(
            listOf("come cerco", "come si cerca", "come faccio a cercare", "ricerca"),
            "Usa il campo Cerca in alto, oppure chiedilo a me: per esempio, cerca il film Matrix.",
        ),
        GUIDE(
            listOf("guida", "epg", "palinsesto", "programmi"),
            "Nella scheda Live, accanto a ogni canale, vedi il programma in onda e quello che segue, se la lista ha la guida.",
        ),
        UPDATES(
            listOf("aggiorn", "versione", "codec"),
            "Dalla Home apri Aggiornamenti e codec: vedi se c'è una versione nuova dell'app e quali decoder ha la TV.",
        ),
        EXIT(
            listOf("uscire", "esci", "chiudere", "chiudi", "spegn"),
            "Dalla Home premi Indietro e conferma. L'app si chiude anche da sola dopo 5 minuti senza usarla, con un conto alla rovescia di 15 secondi.",
        ),
        SOUNDS(
            listOf("suoni", "suono", "musica", "volume", "silenzi"),
            "Dalla Home puoi attivare o silenziare i suoni dei tasti. Il volume si regola con il telecomando della TV.",
        ),
        SHORTCUTS(
            listOf("scorciatoi", "altre app", "altra app", "aprire un'app"),
            "Dalla Home, Scorciatoie apre le altre app della TV che hai scelto.",
        ),
        PAIRING(
            listOf("telefono", "qr", "abbina", "cellulare"),
            "Dalla Home scegli Abbina dal telefono: inquadra il QR col telefono e incolla il link della lista.",
        ),
        ;
    }

    private val hello = listOf("ciao", "buongiorno", "buonasera", "salve", "ehi prism", "hey", "ciao prism")
    private val thanks = listOf("grazie", "ti ringrazio", "perfetto grazie")
    private val who = listOf("chi sei", "come ti chiami", "cosa sai fare", "cosa puoi fare", "che cosa sai fare", "aiuto", "cosa posso chiederti", "cosa ti posso chiedere")

    private val onAir = listOf(
        Regex("""(?:cosa|che cosa|che)\s+(?:c'è|ce|c è|danno|fanno|trasmettono|va in onda|è in onda|stanno dando)\s+(?:ora\s+|adesso\s+|in questo momento\s+|stasera\s+)?(?:su|sul|sulla|a|in)\s+(.+)"""),
        Regex("""(?:cosa|che cosa|che)\s+(?:programma\s+)?(?:c'è|ce|danno|fanno)\s+(?:ora|adesso)\s+(?:su|sul|sulla)\s+(.+)"""),
        Regex("""(?:in onda|programma)\s+(?:ora\s+|adesso\s+)?(?:su|sul|sulla)\s+(.+)"""),
    )
    private val plot = listOf(
        Regex("""(?:di )?(?:cosa|che cosa|che) (?:parla|racconta|tratta)\s+(.+)"""),
        Regex("""(?:la )?(?:trama|storia|descrizione)\s+(?:di|del|della|dello|dei|delle|de)\s+(.+)"""),
    )
    private val play = Regex(
        """^(?:mett(?:i|imi|ici)|guarda|guardare|voglio guardare|vorrei guardare|voglio vedere|vorrei vedere|fammi vedere|fammi guardare|fai partire|avvia|riproduci|apri|aprimi|accendi|vai su|vai a|passa a|passa su|sintonizza(?:ti)? su|manda|play)\s+(.+)""",
    )
    private val search = Regex(
        """^(?:cerca(?:mi)?|trova(?:mi)?|ricerca|c'è|ce|c è|ci sono|avete|hai|esiste|è presente|sai se c'è|consigliami|suggeriscimi|proponimi|dammi)\s+(.+)""",
    )
    private val countWords = Regex("""^(?:quanti|quante|numero di)\s+(.+)""")
    private val latest = Regex("""\b(?:ultimi|ultime|nuovi|nuove|novità|appena aggiunt|aggiunti di recente|più recenti)\b""")
    private val opener = Regex("""^(?:(?:ciao|ehi|hey|ehm|senti|scusa|allora|dimmi|prism|per favore|mi sai dire|sai dirmi|vorrei sapere|puoi|potresti|mi puoi|me lo|me la)\s+)+""")

    fun understand(spoken: String): Intent {
        val whole = normalize(spoken)
        if (whole.isEmpty()) return Intent.Unknown
        if (hello.any { whole == it }) return Intent.Hello
        // "Ciao Prism, puoi mettere Rai 1": the greeting, the name and "puoi" in front do not change the request.
        val text = whole.replace(opener, "").replace(Regex("""^(mettere|guardare|cercare|trovare|aprire|avviare)\b"""), { m ->
            mapOf("mettere" to "metti", "guardare" to "guarda", "cercare" to "cerca", "trovare" to "trova", "aprire" to "apri", "avviare" to "avvia")[m.value] ?: m.value
        }).ifEmpty { whole }

        countWords.find(text)?.let { m -> return Intent.Count(kindIn(m.groupValues[1])) }
        for (r in onAir) r.find(text)?.let { m -> clean(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Intent.OnAir(it) } }
        for (r in plot) r.find(text)?.let { m -> clean(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Intent.Plot(it) } }

        // "How do I ..." questions go to the help texts before anything is searched.
        val howTo = text.startsWith("come ") || text.contains("come si") || text.contains("come faccio") ||
            text.contains("dove ") || text.contains("posso ")
        if (howTo) helpTopic(text)?.let { return Intent.Help(it) }

        if (latest.containsMatchIn(text) && (kindIn(text) != null || text.contains("arriv") || text.contains("aggiunt"))) {
            return Intent.Latest(kindIn(text) ?: PrismKnowledge.Kind.FILM)
        }

        play.find(text)?.let { m ->
            val rest = m.groupValues[1]
            val kind = kindIn(rest.split(' ').take(3).joinToString(" "))
            genreOnly(rest)?.let { return Intent.Genre(kind, it) }
            val query = clean(rest)
            if (query.isNotEmpty()) return Intent.Play(kind, query)
        }
        search.find(text)?.let { m ->
            val rest = m.groupValues[1]
            val kind = kindIn(rest.split(' ').take(3).joinToString(" "))
            genreOnly(rest)?.let { return Intent.Genre(kind, it) }
            val query = clean(rest)
            if (query.isNotEmpty()) return Intent.Search(kind, query)
        }
        // "Film horror", "qualche commedia": a genre on its own.
        genreOnly(text)?.let { return Intent.Genre(kindIn(text), it) }
        helpTopic(text)?.let { return Intent.Help(it) }
        if (who.any { text.contains(it) }) return Intent.WhoAreYou
        if (thanks.any { text.contains(it) }) return Intent.Thanks
        if (hello.any { text == it || text.startsWith("$it ") }) return Intent.Hello
        // A short request naming a film or a series ("film Matrix", "la serie Dr House") is a search.
        kindIn(text)?.let { kind ->
            val query = clean(text)
            if (query.isNotEmpty() && query != text.trim() && query.split(' ').size <= 6) return Intent.Search(kind, query)
        }
        return Intent.Unknown
    }

    suspend fun answer(spoken: String, knowledge: PrismKnowledge): PrismReply = when (val intent = understand(spoken)) {
        Intent.Hello -> PrismReply("Ciao! Chiedimi pure di film, serie e canali di questa lista.")
        Intent.Thanks -> PrismReply("Prego, è un piacere!")
        Intent.WhoAreYou -> PrismReply(
            "Sono Prism. Posso mettere un canale o un film, cercare titoli e generi in questa lista, dirti cosa va in onda, " +
                "raccontarti una trama e spiegarti come si usa l'app.",
        )
        is Intent.Help -> PrismReply(intent.topic.text)
        is Intent.Count -> PrismReply(countAnswer(intent.kind, knowledge))
        is Intent.OnAir -> {
            val found = knowledge.onAir(intent.channel)
            PrismReply(
                when {
                    found == null -> "Non trovo un canale che si chiama ${intent.channel} in questa lista."
                    found.second == null -> "Ho trovato ${found.first}, ma la guida non dice cosa va in onda adesso."
                    else -> "Adesso su ${found.first} c'è ${found.second}."
                },
            )
        }
        is Intent.Plot -> {
            val found = knowledge.plot(intent.title)
            PrismReply(
                when {
                    found == null -> "Non trovo ${intent.title} tra i film e le serie di questa lista."
                    found.second == null -> "Ho trovato ${found.first}, ma la sua descrizione non è disponibile."
                    else -> "${found.first}. ${found.second}"
                },
            )
        }
        is Intent.Play -> {
            val hit = knowledge.best(intent.kind, intent.query)
            if (hit == null) {
                PrismReply("Non trovo ${intent.query} in questa lista.")
            } else {
                val verb = if (hit.kind == PrismKnowledge.Kind.SERIES) "Ti apro" else "Ecco"
                PrismReply("$verb ${hit.title}.", hit.open)
            }
        }
        is Intent.Genre -> {
            val group = knowledge.group(intent.kind, intent.genre.stems)
            if (group == null) {
                PrismReply("In questa lista non trovo una categoria ${intent.genre.label}.")
            } else {
                val what = label(group.kind, group.count)
                val examples = if (group.examples.isEmpty()) "" else ", per esempio " + group.examples.joinToString(", ")
                PrismReply("Nella categoria ${group.name} ci sono ${group.count} $what$examples.")
            }
        }
        is Intent.Latest -> {
            val titles = knowledge.latest(intent.kind, 5)
            if (titles.isEmpty()) PrismReply("Non trovo ${label(intent.kind, 2)} in questa lista.")
            else PrismReply(
                when (intent.kind) {
                    PrismKnowledge.Kind.FILM -> "Gli ultimi film arrivati sono: "
                    PrismKnowledge.Kind.SERIES -> "Le ultime serie arrivate sono: "
                    PrismKnowledge.Kind.CHANNEL -> "Gli ultimi canali aggiunti sono: "
                } + titles.joinToString(", ") + ".",
            )
        }
        is Intent.Search -> PrismReply(searchAnswer(intent, knowledge))
        Intent.Unknown -> PrismReply(
            "Scusa, posso aiutarti solo con i contenuti di questa lista e con l'app. Prova per esempio: metti Rai 1, " +
                "cerca un film horror, oppure di cosa parla una serie.",
        )
    }

    private suspend fun countAnswer(kind: PrismKnowledge.Kind?, k: PrismKnowledge): String = when (kind) {
        PrismKnowledge.Kind.FILM -> "In questa lista ci sono ${k.count(kind)} film."
        PrismKnowledge.Kind.SERIES -> "In questa lista ci sono ${k.count(kind)} serie."
        PrismKnowledge.Kind.CHANNEL -> "In questa lista ci sono ${k.count(kind)} canali live."
        null -> "In questa lista ci sono ${k.count(PrismKnowledge.Kind.CHANNEL)} canali live, " +
            "${k.count(PrismKnowledge.Kind.FILM)} film e ${k.count(PrismKnowledge.Kind.SERIES)} serie."
    }

    private suspend fun searchAnswer(intent: Intent.Search, k: PrismKnowledge): String {
        val kinds = intent.kind?.let { listOf(it) } ?: PrismKnowledge.Kind.values().toList()
        val limit = if (intent.kind != null) 5 else 3
        val parts = kinds.mapNotNull { kind ->
            val titles = k.find(kind, intent.query, limit)
            if (titles.isEmpty()) null else "${label(kind, titles.size)}: ${titles.joinToString(", ")}"
        }
        if (parts.isEmpty()) {
            val where = intent.kind?.let { " tra ${label(it, 2)}" } ?: ""
            return "Non trovo niente per ${intent.query}$where in questa lista."
        }
        return "Per ${intent.query} ho trovato. " + parts.joinToString(". ") + "."
    }

    private fun label(kind: PrismKnowledge.Kind, n: Int): String = when (kind) {
        PrismKnowledge.Kind.FILM -> "film"
        PrismKnowledge.Kind.SERIES -> "serie"
        PrismKnowledge.Kind.CHANNEL -> if (n == 1) "canale" else "canali"
    }

    /** The answer to "Ho capito bene?". */
    sealed interface Confirmation {
        data object Yes : Confirmation
        data object No : Confirmation
        /** Not a yes or a no but a new request: it replaces the one heard before. */
        data class Instead(val text: String) : Confirmation
        data object Unclear : Confirmation
    }

    fun confirmation(spoken: String): Confirmation {
        yesOrNo(spoken)?.let { return if (it) Confirmation.Yes else Confirmation.No }
        val words = normalize(spoken).split(' ').filter { it.isNotEmpty() }
        return if (words.size >= 2 && understand(spoken) != Intent.Unknown) Confirmation.Instead(spoken) else Confirmation.Unclear
    }

    /** True for yes, false for no, null when the answer is neither. "Non è giusto" is a no. */
    fun yesOrNo(spoken: String): Boolean? {
        val words = normalize(spoken).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 6) return null
        val no = setOf("no", "non", "sbagliato", "sbagliata", "errato", "negativo", "nope", "ripeti", "riprova")
        val yes = setOf("si", "sì", "sí", "esatto", "esattamente", "giusto", "giusta", "certo", "ok", "okay", "corretto", "confermo", "perfetto", "bene", "yes", "certamente", "proprio", "vai", "procedi", "affermativo")
        // "Nò", "noo", "no no": every spelling the recogniser gives of a no.
        if (words.any { it in no || Regex("""n[oò]+""").matches(it) }) return false
        if (words.any { it in yes || Regex("""s[iìí]+""").matches(it) }) return true
        return null
    }

    private fun helpTopic(text: String): Topic? = Topic.values().firstOrNull { t -> t.words.any { text.contains(it) } }

    private fun kindIn(text: String): PrismKnowledge.Kind? = when {
        Regex("""\bfilm\b|\bfilms\b|\bpellicol|\bmovie""").containsMatchIn(text) -> PrismKnowledge.Kind.FILM
        Regex("""\bseri[ei]\b|\btelefilm\b|\bserial|\bepisod|\bstagion""").containsMatchIn(text) -> PrismKnowledge.Kind.SERIES
        Regex("""\bcanal[ei]\b|\blive\b|\bdirett|\brai\b|\bmediaset\b|\bsky\b""").containsMatchIn(text) -> PrismKnowledge.Kind.CHANNEL
        else -> null
    }

    /** The genre when [text] asks only for one ("un film horror", "qualche commedia"), not for a title. */
    internal fun genreOnly(text: String): Genre? {
        val filler = setOf(
            "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "dei", "degli", "delle", "del", "della", "di", "d'",
            "qualche", "qualcosa", "film", "serie", "telefilm", "cartoni", "cartone", "genere", "categoria", "canali",
            "canale", "tipo", "da", "per", "vedere", "guardare", "stasera", "bello", "bei", "belle", "bella", "buon",
            "buoni", "nuovo", "nuovi", "altro", "altri", "mi", "ci", "che", "sono", "c'è", "ce", "hai", "avete",
        )
        val words = text.split(' ').filter { it.isNotEmpty() }
        val genre = Genre.values().firstOrNull { g -> words.any { w -> g.spoken.any { it == w || it == w.removePrefix("d'") } } }
            ?: Genre.values().firstOrNull { g -> g.spoken.any { it.contains(' ') && text.contains(it) } }
            ?: return null
        val others = words.filter { w -> w !in filler && genre.spoken.none { it == w || it == w.removePrefix("d'") } && w != "animati" }
        return if (others.isEmpty()) genre else null
    }

    /** Lower case, the apostrophe kept, other punctuation and repeated spaces removed. */
    internal fun normalize(spoken: String): String =
        spoken.lowercase()
            .replace('’', '\'')
            .replace(Regex("""[^\p{L}\p{N}' ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Drops the words around a title: "il film matrix per favore" becomes "matrix". */
    internal fun clean(text: String): String {
        var t = " " + text.trim() + " "
        val lead = Regex("""^\s*(?:(?:il|lo|la|i|gli|le|l'|un|uno|una|un'|del|della|dello|dei|delle|qualche|qualcosa|di|da|su|sul|sulla|a)\s+)*(?:(?:film|serie|telefilm|canale|canali|programma|programmi|cartone|cartoni|documentario|episodio|puntata)\s+)?(?:(?:il|lo|la|i|gli|le|l'|di|del|della|chiamat[oa]|intitolat[oa]|che si chiama)\s+)*""")
        t = t.replace(lead, "")
        t = t.replace(Regex("""\s+(?:per favore|per piacere|grazie|adesso|ora|subito|in questa lista|nella lista|stasera|in tv|sulla tv)\s*$"""), "")
        return t.trim().removePrefix("l'").trim()
    }
}
