package dev.red74.prismplayer.pairing

/**
 * The tiny HTML pages served to the phone during pairing. No JavaScript, no external resources:
 * the server's Content-Security-Policy forbids anything but inline CSS and same-origin form posts.
 */
internal object PairPage {
    enum class Lang { IT, EN }

    fun langFrom(acceptLanguage: String?): Lang =
        if (acceptLanguage?.trim()?.lowercase()?.startsWith("it") == true) Lang.IT else Lang.EN

    /** Shown at `/`: lets the user type the code by hand when the QR cannot be scanned. */
    fun codeEntry(lang: Lang): String = layout(
        lang,
        title = t(lang, "Abbina Prism Player", "Pair Prism Player"),
        body = """
            <h1>${t(lang, "Inserisci il codice", "Enter the code")}</h1>
            <p>${t(lang, "Lo trovi sullo schermo della TV.", "You can find it on your TV screen.")}</p>
            <form method="get" action="/pair" autocomplete="off">
              <label for="c">${t(lang, "Codice", "Code")}</label>
              <input id="c" name="c" type="text" inputmode="text" autocapitalize="characters"
                     maxlength="12" required autofocus>
              <button type="submit">${t(lang, "Continua", "Continue")}</button>
            </form>
        """.trimIndent(),
    )

    fun form(code: String, lang: Lang, showError: Boolean = false): String = layout(
        lang,
        title = t(lang, "Aggiungi una playlist", "Add a playlist"),
        body = """
            <h1>${t(lang, "Aggiungi una playlist", "Add a playlist")}</h1>
            <p>${t(lang, "Il link verrà inviato alla tua TV.", "The link will be sent to your TV.")}</p>
            ${if (showError) """<p class="err">${t(lang, "Link non valido: controlla l'indirizzo e riprova.", "Invalid link: check the address and try again.")}</p>""" else ""}
            <form method="post" action="/submit" autocomplete="off">
              <input type="hidden" name="c" value="${esc(code)}">
              <label for="n">${t(lang, "Nome (facoltativo)", "Name (optional)")}</label>
              <input id="n" name="n" type="text" maxlength="60">
              <label for="u">${t(lang, "Link della playlist", "Playlist link")}</label>
              <input id="u" name="u" type="text" inputmode="url" maxlength="2048" required
                     autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="esempio.com/lista.m3u">
              <button type="submit">${t(lang, "Invia alla TV", "Send to TV")}</button>
            </form>
            <p class="note">${t(lang, "Nota: il link viaggia sulla tua rete locale in HTTP non cifrato. Usa solo una rete di cui ti fidi.", "Note: the link travels over your local network as unencrypted HTTP. Only use a network you trust.")}</p>
        """.trimIndent(),
    )

    fun success(lang: Lang): String = layout(
        lang,
        title = t(lang, "Fatto", "Done"),
        body = """
            <h1>${t(lang, "Playlist inviata", "Playlist sent")}</h1>
            <p>${t(lang, "Controlla la TV. Questo codice non è più valido.", "Check your TV. This code is no longer valid.")}</p>
        """.trimIndent(),
    )

    /** Deliberately generic: it never says whether a code was wrong, expired, used or locked out. */
    fun denied(lang: Lang): String = layout(
        lang,
        title = t(lang, "Non disponibile", "Unavailable"),
        body = """
            <h1>${t(lang, "Codice non valido", "Invalid code")}</h1>
            <p>${t(lang, "Il codice è scaduto, già usato o errato. Chiedi un nuovo codice sulla TV.", "The code expired, was already used, or is wrong. Request a new code on the TV.")}</p>
        """.trimIndent(),
    )

    fun notFound(lang: Lang): String = layout(
        lang,
        title = "404",
        body = "<h1>404</h1>",
    )

    private fun t(lang: Lang, it: String, en: String) = if (lang == Lang.IT) it else en

    internal fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun layout(lang: Lang, title: String, body: String): String = """
        <!doctype html>
        <html lang="${if (lang == Lang.IT) "it" else "en"}">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="referrer" content="no-referrer">
          <title>${esc(title)}</title>
          <style>
            :root { color-scheme: dark; }
            body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                   font:16px/1.5 system-ui, sans-serif; color:#f4f6ff;
                   background:linear-gradient(135deg,#070b14,#111a33 55%,#1c1140); }
            main { box-sizing:border-box; width:min(92vw,440px); padding:28px; border-radius:24px;
                   background:rgba(255,255,255,.08); border:1px solid rgba(255,255,255,.22); }
            h1 { margin:0 0 8px; font-size:22px; }
            p { margin:0 0 16px; color:#b4bbd6; }
            .err { color:#ff9aa5; }
            .note { font-size:13px; margin:18px 0 0; }
            label { display:block; margin:14px 0 6px; font-size:14px; color:#b4bbd6; }
            input { box-sizing:border-box; width:100%; padding:14px; font-size:16px; color:#f4f6ff;
                    background:rgba(255,255,255,.08); border:1px solid rgba(255,255,255,.28);
                    border-radius:14px; }
            button { margin-top:20px; width:100%; padding:14px; font-size:16px; font-weight:600;
                     color:#fff; border:0; border-radius:14px;
                     background:linear-gradient(135deg,#7c9cff,#b57cff); }
          </style>
        </head>
        <body><main>
        $body
        </main></body>
        </html>
    """.trimIndent()
}
