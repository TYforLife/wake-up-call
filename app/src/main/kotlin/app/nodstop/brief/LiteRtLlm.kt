package app.nodstop.brief

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gemma on the phone through LiteRT-LM (DESIGN §10). CPU backend: works on every phone; the GPU is a
 * later experiment. Prompts and answers stay in memory: nothing here logs, stores or sends them.
 */
class LiteRtLlm(private val modelPath: String, private val cacheDir: String) : Llm, AutoCloseable {
    /** Timings of the last call, for the Briefing lab. */
    data class Stats(val loadMs: Long? = null, val firstTokenMs: Long? = null, val totalMs: Long? = null, val chars: Int = 0)

    @Volatile var lastStats = Stats()
        private set

    private val lock = Any()
    @Volatile private var engine: Engine? = null

    /** Loads the model if needed; returns how long that took (0 if already loaded). Blocking. */
    fun load(): Long = synchronized(lock) {
        if (engine != null) return 0
        val t0 = SystemClock.elapsedRealtime()
        val e = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir))
        e.initialize()
        engine = e
        SystemClock.elapsedRealtime() - t0
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.Default) {
        val loadMs = load()
        val e = engine ?: error("model not loaded")
        val conv = e.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(1, 1.0, 0.1, 0), maxOutputToken = maxTokens),
        )
        val t0 = SystemClock.elapsedRealtime()
        var first: Long? = null
        val sb = StringBuilder()
        try {
            val text = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { conv.cancelProcess() } }
                conv.sendMessageAsync(Message.user(prompt), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (first == null) first = SystemClock.elapsedRealtime() - t0
                        synchronized(sb) { sb.append(textOf(message)) }
                    }
                    override fun onDone() { if (cont.isActive) cont.resume(synchronized(sb) { sb.toString() }) }
                    override fun onError(throwable: Throwable) { if (cont.isActive) cont.resumeWithException(throwable) }
                })
            }
            lastStats = Stats(loadMs, first, SystemClock.elapsedRealtime() - t0, text.length)
            text
        } finally {
            runCatching { conv.close() }
        }
    }

    override fun close() = synchronized(lock) {
        runCatching { engine?.close() }
        engine = null
    }

    private fun textOf(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        /** Context window: prompt budget (~6,000 characters) plus the answer fit well inside. */
        const val MAX_TOKENS = 4_096
    }
}
