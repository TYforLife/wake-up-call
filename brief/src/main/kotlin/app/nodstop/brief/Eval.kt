package app.nodstop.brief

/** One made-up conversation and the facts a good briefing line must keep (case-insensitive). */
data class EvalCase(val id: String, val title: String, val messages: List<Pair<String, String>>, val facts: List<String>)

data class EvalCaseResult(
    val id: String,
    val line: String?,
    /** The line invented a fact (it would be dropped in real use). */
    val invented: Boolean,
    val kept: List<String>,
    val missed: List<String>,
)

data class EvalReport(val results: List<EvalCaseResult>) {
    val answered: Int get() = results.count { it.line != null }
    val invented: Int get() = results.count { it.invented }
    val factsTotal: Int get() = results.sumOf { it.kept.size + it.missed.size }
    val factsKept: Int get() = results.sumOf { it.kept.size }
    val keptPercent: Int get() = if (factsTotal == 0) 0 else factsKept * 100 / factsTotal
}

/**
 * Briefing quality eval (DESIGN §10, Run 3 C5): 20 made-up conversations, run through the real prompt,
 * parser and guardrails. Scores facts kept and lines that invented something. Pure data and logic, so the
 * Briefing lab can run it on the phone with the real model and the JVM tests with a fake.
 */
object EvalSet {
    val cases: List<EvalCase> = listOf(
        EvalCase("time-change", "Boss", listOf("Boss" to "The 10:00 standup is moved to 10:30 today", "Boss" to "Same room"), listOf("10:30")),
        EvalCase("dinner-plan", "Flat group", listOf("Jess" to "dinner this week?", "Sam" to "Friday is bad", "Ari" to "Saturday 7pm then? I'll cook", "Jess" to "Saturday 7pm works"), listOf("Saturday", "7pm")),
        EvalCase("pickup", "Mum", listOf("Mum" to "Can you pick up Mia from school at 3:15?", "Mum" to "I'm stuck at work"), listOf("Mia", "3:15")),
        EvalCase("money", "Ari", listOf("Ari" to "you owe me $15 for the pizza", "Ari" to "no rush"), listOf("$15", "pizza")),
        EvalCase("place-change", "Running club", listOf("Coach" to "Tonight's run starts at Western Springs instead of the Domain", "Coach" to "6pm as usual"), listOf("Western Springs", "6pm")),
        EvalCase("cancel", "Dentist", listOf("Dentist" to "Your appointment on Thursday is cancelled. Please call to rebook."), listOf("Thursday", "cancelled")),
        EvalCase("question", "Kai", listOf("Kai" to "are you coming to Lena's birthday on Sunday?", "Kai" to "need numbers by tonight"), listOf("Lena", "Sunday")),
        EvalCase("delivery", "Courier", listOf("Courier" to "Your parcel will arrive between 2pm and 4pm today", "Courier" to "Someone must sign for it"), listOf("2pm", "4pm")),
        EvalCase("flight", "Dad", listOf("Dad" to "Landing at 9:40pm, gate 12", "Dad" to "can you get me from the airport?"), listOf("9:40pm", "airport")),
        EvalCase("rent", "Landlord", listOf("Landlord" to "Rent goes up to $620 a week from 1 March"), listOf("$620", "March")),
        EvalCase("meeting-room", "Project team", listOf("Priya" to "Design review moved to room 4B", "Tom" to "still 2pm?", "Priya" to "yes 2pm"), listOf("4B", "2pm")),
        EvalCase("game", "Football", listOf("Sione" to "game tomorrow at 8:30am, field 3", "Sione" to "bring the orange bibs"), listOf("8:30am", "field 3")),
        EvalCase("vet", "Vet clinic", listOf("Vet clinic" to "Biscuit is ready to be picked up after 5pm"), listOf("Biscuit", "5pm")),
        EvalCase("gym", "Gym", listOf("Gym" to "Your class on Wednesday at 6:15am is full; you're number 2 on the waitlist"), listOf("Wednesday", "6:15am")),
        EvalCase("bbq", "Cousins", listOf("Rua" to "BBQ at Aunty Mere's on Saturday", "Rua" to "bring a salad", "Hemi" to "what time?", "Rua" to "from 1pm"), listOf("Mere", "1pm")),
        EvalCase("exam", "Study group", listOf("Wen" to "the exam is in room 260-115", "Wen" to "starts 9am sharp, bring ID"), listOf("260-115", "9am")),
        EvalCase("car", "Mechanic", listOf("Mechanic" to "Your car is ready. Total is $480. We close at 5:30pm"), listOf("$480", "5:30pm")),
        EvalCase("ticket", "Friends", listOf("Zoe" to "got us 4 tickets for Friday's gig", "Zoe" to "send me $45 each"), listOf("Friday", "$45")),
        EvalCase("swap-shift", "Work roster", listOf("Ana" to "can you swap my Tuesday late shift for your Thursday early?", "Ana" to "let me know by 6pm"), listOf("Tuesday", "Thursday")),
        EvalCase("lost-item", "Neighbour", listOf("Neighbour" to "Found your keys on the porch, left them in the letterbox"), listOf("keys", "letterbox")),
    )

    /** The case's messages as captured chat messages (made-up app, unique conversation titles). */
    fun messages(c: EvalCase, startMs: Long = 0): List<CapturedMessage> =
        c.messages.mapIndexed { i, (sender, text) -> CapturedMessage("eval", c.title, sender, text, startMs + i * 1_000L) }
}

class EvalRunner(private val llm: Llm) {
    /** Runs [cases] in prompts of up to [batchSize] conversations (the prompt's own limits apply). */
    suspend fun run(cases: List<EvalCase> = EvalSet.cases, batchSize: Int = 10): EvalReport {
        val results = ArrayList<EvalCaseResult>()
        for (batch in cases.chunked(batchSize)) {
            val messages = batch.flatMapIndexed { i, c -> EvalSet.messages(c, i * 100_000L) }
            val convs = PromptBuilder.conversations(messages)
            val answer = llm.generate(PromptBuilder.build(convs), PromptBuilder.maxTokens(convs))
            val parsed = BriefParser.parse(answer, convs.map { it.id }.toSet())
            for (c in batch) {
                val conv = convs.firstOrNull { it.title == c.title }
                val line = conv?.let { parsed[it.id]?.line }
                val invented = line != null && !Guardrails.passes(line, messages.filter { it.conversation == c.title }, c.title)
                val kept = if (line == null || invented) emptyList() else c.facts.filter { line.contains(it, ignoreCase = true) }
                results += EvalCaseResult(c.id, line, invented, kept, c.facts - kept.toSet())
            }
        }
        return EvalReport(results)
    }
}
