package dev.red74.prismplayer.assistant

/** What Prism can look up in the open list. Implemented over the list database and the guide (see PrismAssistant). */
interface PrismKnowledge {
    enum class Kind { FILM, SERIES, CHANNEL }

    /** Titles that match [query], best first. */
    suspend fun find(kind: Kind, query: String, limit: Int): List<String>

    suspend fun count(kind: Kind): Int

    /** The channel that best matches [query] and the programme on it now (null when the guide has nothing). */
    suspend fun onAir(query: String): Pair<String, String?>?

    /** The plot of the film or series that best matches [query], with its title. */
    suspend fun plot(query: String): Pair<String, String?>?
}

/**
 * Prism's brain, entirely inside the app: it recognises the kinds of question people ask about Prism Player and
 * the open list (Italian, as spoken to the remote), and answers from the list, the guide and a set of help texts.
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
        data class Search(val kind: PrismKnowledge.Kind?, val query: String) : Intent
        data object Unknown : Intent
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

    private val hello = listOf("ciao", "buongiorno", "buonasera", "salve", "ehi prism", "hey")
    private val thanks = listOf("grazie", "ti ringrazio", "perfetto grazie")
    private val who = listOf("chi sei", "come ti chiami", "cosa sai fare", "cosa puoi fare", "che cosa sai fare", "aiuto")

    private val onAir = listOf(
        Regex("""(?:cosa|che cosa|che)\s+(?:c'è|ce|c è|danno|fanno|trasmettono|va in onda|è in onda|stanno dando)\s+(?:ora\s+|adesso\s+|in questo momento\s+|stasera\s+)?(?:su|sul|sulla|a|in)\s+(.+)"""),
        Regex("""(?:cosa|che cosa|che)\s+(?:programma\s+)?(?:c'è|ce|danno|fanno)\s+(?:ora|adesso)\s+(?:su|sul|sulla)\s+(.+)"""),
        Regex("""(?:in onda|programma)\s+(?:ora\s+|adesso\s+)?(?:su|sul|sulla)\s+(.+)"""),
    )
    private val plot = listOf(
        Regex("""(?:di )?(?:cosa|che cosa|che) (?:parla|racconta|tratta)\s+(.+)"""),
        Regex("""(?:la )?(?:trama|storia|descrizione)\s+(?:di|del|della|dello|dei|delle|de)\s+(.+)"""),
    )
    private val search = Regex(
        """^(?:cerca(?:mi)?|trova(?:mi)?|cercami|ricerca|voglio vedere|vorrei vedere|fammi vedere|metti|c'è|ce|c è|ci sono|avete|hai|esiste|è presente|sai se c'è)\s+(.+)""",
    )
    private val countWords = Regex("""^(?:quanti|quante|numero di)\s+(.+)""")

    private val opener = Regex("""^(?:(?:ciao|ehi|hey|ehm|senti|scusa|allora|dimmi|prism|per favore|mi sai dire|sai dirmi|vorrei sapere)\s+)+""")

    fun understand(spoken: String): Intent {
        val whole = normalize(spoken)
        if (whole.isEmpty()) return Intent.Unknown
        if (hello.any { whole == it } || whole == "ciao prism") return Intent.Hello
        // "Ciao Prism, cerca Matrix": the greeting and the name in front do not change the question.
        val text = whole.replace(opener, "").ifEmpty { whole }

        countWords.find(text)?.let { m -> return Intent.Count(kindIn(m.groupValues[1])) }
        for (r in onAir) r.find(text)?.let { m -> clean(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Intent.OnAir(it) } }
        for (r in plot) r.find(text)?.let { m -> clean(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Intent.Plot(it) } }

        // "How do I ..." questions go to the help texts before anything is searched.
        val howTo = text.startsWith("come ") || text.contains("come si") || text.contains("come faccio") ||
            text.contains("dove ") || text.contains("posso ")
        if (howTo) helpTopic(text)?.let { return Intent.Help(it) }

        search.find(text)?.let { m ->
            val rest = m.groupValues[1]
            val kind = kindIn(rest.split(' ').take(2).joinToString(" "))
            val query = clean(rest)
            if (query.isNotEmpty()) return Intent.Search(kind, query)
        }
        helpTopic(text)?.let { return Intent.Help(it) }
        if (who.any { text.contains(it) }) return Intent.WhoAreYou
        if (thanks.any { text.contains(it) }) return Intent.Thanks
        if (hello.any { text == it || text.startsWith("$it ") }) return Intent.Hello
        return Intent.Unknown
    }

    suspend fun answer(spoken: String, knowledge: PrismKnowledge): String = when (val intent = understand(spoken)) {
        Intent.Hello -> "Ciao! Chiedimi pure di film, serie, canali o di come si usa l'app."
        Intent.Thanks -> "Prego, è stato un piacere!"
        Intent.WhoAreYou -> "Sono Prism, l'assistente di Prism Player. Posso cercare film, serie e canali nella lista, dirti cosa va in onda, raccontarti la trama di un titolo e spiegarti come si usa l'app."
        is Intent.Help -> intent.topic.text
        is Intent.Count -> countAnswer(intent.kind, knowledge)
        is Intent.OnAir -> {
            val found = knowledge.onAir(intent.channel)
            when {
                found == null -> "Non ho trovato un canale che si chiama ${intent.channel} in questa lista."
                found.second == null -> "Ho trovato ${found.first}, ma la guida non dice cosa va in onda adesso."
                else -> "Adesso su ${found.first} c'è ${found.second}."
            }
        }
        is Intent.Plot -> {
            val found = knowledge.plot(intent.title)
            when {
                found == null -> "Non ho trovato ${intent.title} tra i film e le serie di questa lista."
                found.second == null -> "Ho trovato ${found.first}, ma per questo titolo la descrizione non è disponibile."
                else -> "${found.first}: ${found.second}"
            }
        }
        is Intent.Search -> searchAnswer(intent, knowledge)
        Intent.Unknown -> "Scusa, non ho capito la domanda. Puoi chiedermi per esempio: cerca un film, cosa c'è su un canale, di cosa parla una serie, oppure come si usa una funzione dell'app."
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
            return "Non ho trovato niente per ${intent.query}$where in questa lista."
        }
        return "Ecco cosa ho trovato per ${intent.query}. " + parts.joinToString(". ") + "."
    }

    private fun label(kind: PrismKnowledge.Kind, n: Int): String = when (kind) {
        PrismKnowledge.Kind.FILM -> "film"
        PrismKnowledge.Kind.SERIES -> "serie"
        PrismKnowledge.Kind.CHANNEL -> if (n == 1) "canale" else "canali"
    }

    /** True for yes, false for no, null when the answer is neither. "Non è giusto" is a no. */
    fun yesOrNo(spoken: String): Boolean? {
        val words = normalize(spoken).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val no = setOf("no", "non", "sbagliato", "sbagliata", "errato", "negativo", "nope", "ripeti", "riprova")
        val yes = setOf("si", "sì", "esatto", "esattamente", "giusto", "certo", "ok", "okay", "corretto", "confermo", "perfetto", "bene", "yes", "certamente", "proprio")
        // "Nò", "noo", "no no": every spelling the recogniser gives of a no.
        if (words.any { it in no || Regex("""n[oò]+""").matches(it) }) return false
        if (words.any { it in yes }) return true
        return null
    }

    private fun helpTopic(text: String): Topic? = Topic.values().firstOrNull { t -> t.words.any { text.contains(it) } }

    private fun kindIn(text: String): PrismKnowledge.Kind? = when {
        Regex("""\bfilm\b|\bfilms\b|\bpellicol""").containsMatchIn(text) -> PrismKnowledge.Kind.FILM
        Regex("""\bseri[ei]\b|\btelefilm\b|\bserial""").containsMatchIn(text) -> PrismKnowledge.Kind.SERIES
        Regex("""\bcanal[ei]\b|\blive\b|\bdirett""").containsMatchIn(text) -> PrismKnowledge.Kind.CHANNEL
        else -> null
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
        val lead = Regex("""^\s*(?:(?:il|lo|la|i|gli|le|l'|un|uno|una|un'|del|della|dello|dei|delle|qualche|qualcosa|di|da)\s+)*(?:(?:film|serie|telefilm|canale|canali|programma|programmi|cartone|cartoni|documentario)\s+)?(?:(?:il|lo|la|i|gli|le|l'|di|del|della|chiamat[oa]|intitolat[oa])\s+)*""")
        t = t.replace(lead, "")
        t = t.replace(Regex("""\s+(?:per favore|per piacere|grazie|adesso|ora|in questa lista|nella lista|stasera)\s*$"""), "")
        return t.trim().removePrefix("l'").trim()
    }
}
