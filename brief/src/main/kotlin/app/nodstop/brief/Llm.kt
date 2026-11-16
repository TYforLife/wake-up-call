package app.nodstop.brief

/** The on-device model behind While You Slept (DESIGN §10). LiteRT-LM in the app; a fake in tests. */
fun interface Llm {
    /** The model's whole answer to [prompt], at most about [maxTokens] tokens. */
    suspend fun generate(prompt: String, maxTokens: Int): String
}
