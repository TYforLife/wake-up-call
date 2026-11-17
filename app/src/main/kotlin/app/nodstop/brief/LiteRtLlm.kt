package app.nodstop.brief

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gemma on the phone through LiteRT-LM (DESIGN §10). CPU backend: works on every phone; the GPU is a
 * later experiment. Prompts and answers stay in memory: nothing here logs, stores or sends them.
 *
 * Native safety: a cancelled call keeps running inside the runtime until it notices the cancel, so a
 * conversation is only closed after its callback says done, and [close] waits (off the main thread) for
 * every call to finish before closing the engine. If the runtime never finishes, the engine is left open
 * rather than closed under a running call: a leak until the process ends beats a native crash mid-trip.
 */
class LiteRtLlm(private val modelPath: String, private val cacheDir: String) : Llm, AutoCloseable {
    /** Timings of the last call, for the Briefing lab. */
    data class Stats(val loadMs: Long? = null, val firstTokenMs: Long? = null, val totalMs: Long? = null, val chars: Int = 0)

    @Volatile var lastStats = Stats()
        private set

    private val lock = Any()
    @Volatile private var engine: Engine? = null
    @Volatile private var closed = false
    /** Calls still running inside the runtime (including cancelled ones that haven't stopped yet). */
    private val inFlight = HashSet<CountDownLatch>()

    /** Loads the model if needed; returns how long that took (0 if already loaded). Blocking. */
    fun load(): Long = synchronized(lock) {
        check(!closed) { "closed" }
        if (engine != null) return 0
        val t0 = SystemClock.elapsedRealtime()
        val e = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), maxNumTokens = MAX_TOKENS, cacheDir = cacheDir))
        e.initialize()
        engine = e
        SystemClock.elapsedRealtime() - t0
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.Default) {
        val loadMs = load()
        val done = CountDownLatch(1)
        val conv: Conversation = synchronized(lock) {
            val e = engine?.takeIf { !closed } ?: error("model not loaded")
            inFlight += done
            runCatching {
                e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(1, 1.0, 0.1, 0), maxOutputToken = maxTokens))
            }.getOrElse { inFlight -= done; throw it }
        }
        val t0 = SystemClock.elapsedRealtime()
        var first: Long? = null
        val sb = StringBuilder()
        var started = false
        try {
            val text = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { conv.cancelProcess() } }
                conv.sendMessageAsync(Message.user(prompt), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (first == null) first = SystemClock.elapsedRealtime() - t0
                        synchronized(sb) { sb.append(textOf(message)) }
                    }
                    override fun onDone() {
                        done.countDown()
                        if (cont.isActive) cont.resume(synchronized(sb) { sb.toString() })
                    }
                    override fun onError(throwable: Throwable) {
                        done.countDown()
                        if (cont.isActive) cont.resumeWithException(throwable)
                    }
                })
                started = true
            }
            lastStats = Stats(loadMs, first, SystemClock.elapsedRealtime() - t0, text.length)
            text
        } finally {
            if (!started) done.countDown()
            // Close the conversation only once the runtime has finished with it (it may still be stopping).
            thread(name = "litertlm-conv-close", isDaemon = true) {
                val finished = done.await(STOP_WAIT_S, TimeUnit.SECONDS)
                if (finished) runCatching { conv.close() }
                synchronized(lock) { inFlight -= done }
            }
        }
    }

    /** Never blocks the caller: waits for running calls on its own thread, then closes the engine. */
    override fun close() {
        val e: Engine
        val running: List<CountDownLatch>
        synchronized(lock) {
            if (closed) return
            closed = true
            e = engine ?: return
            engine = null
            running = inFlight.toList()
        }
        thread(name = "litertlm-close", isDaemon = true) {
            val allStopped = running.all { it.await(STOP_WAIT_S, TimeUnit.SECONDS) }
            if (allStopped) runCatching { e.close() }
        }
    }

    private fun textOf(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        /** Context window: prompt budget (~6,000 characters) plus the answer fit well inside. */
        const val MAX_TOKENS = 4_096
        /** How long a cancelled call may take to stop before we give up closing it. */
        const val STOP_WAIT_S = 30L
    }
}
