package app.nodstop.brief

/**
 * Share-back (DESIGN §10): one line, chosen by the rider, sent through the share sheet. Nothing else from
 * the briefing goes with it, and nothing is ever sent automatically.
 */
object ShareText {
    const val TAGLINE = "recapped while I napped · Nodstop"

    fun of(line: PanelLine): String = "${line.text.replace(Regex("\\s+"), " ").trim()}\n— $TAGLINE"
}
