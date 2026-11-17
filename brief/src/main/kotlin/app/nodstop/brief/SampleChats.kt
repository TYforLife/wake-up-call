package app.nodstop.brief

/** Made-up chats for the Briefing lab and tests: nothing personal, always the same. */
object SampleChats {
    private const val WA = "com.whatsapp"
    private const val SL = "com.Slack"
    private fun m(app: String, conv: String, sender: String?, text: String, t: Long) = CapturedMessage(app, conv, sender, text, t)

    val messages: List<CapturedMessage> = listOf(
        m(WA, "Flat group", "Jess", "ok so dinner this week?", 1_000),
        m(WA, "Flat group", "Sam", "Friday is bad for me", 2_000),
        m(WA, "Flat group", "Ari", "Saturday 7pm then? I can cook", 3_000),
        m(WA, "Flat group", "Jess", "Saturday 7pm works. I'll bring cake", 4_000),
        m(WA, "Flat group", "Sam", "sweet, Saturday it is", 5_000),
        m(WA, "Flat group", "Jess", "and you're on snacks lol", 6_000),
        m(SL, "Boss", "Boss", "Heads up: the 10:00 meeting is moved to 10:30 today", 7_000),
        m(SL, "Boss", "Boss", "Can you bring the Q3 slides?", 8_000),
        m(WA, "Mum", "Mum", "Are you home for dinner tonight?", 9_000),
        m(WA, "Kai", "Kai", "lol did you see that", 10_000),
    )

    /** For couch-testing urgent wake: add "Daycare" to your important contacts first. */
    val urgent: CapturedMessage = m(WA, "Daycare", "Daycare", "Please call us now, Mia has a fever", 11_000)
}
