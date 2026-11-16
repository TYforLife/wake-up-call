package app.nodstop.notify

import app.nodstop.brief.CapturedMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chat messages caught during a trip, in process memory only (DESIGN §10, W3). Plain Kotlin: no files,
 * no logs, no trace. Filled only while a trip runs; cleared when the alarm screen closes and when the trip
 * stops. A process death loses it, and the alarm screen then says the briefing is unavailable (W2).
 */
class MessageStore(private val maxMessages: Int = 200) {
    data class Snapshot(val active: Boolean, val messages: List<CapturedMessage>, val otherNotifications: Int)

    private val lock = Any()
    private var active = false
    private val messages = ArrayList<CapturedMessage>()
    private val seenMessages = LinkedHashSet<String>()
    private val otherKeys = LinkedHashSet<String>()
    private val _state = MutableStateFlow(Snapshot(false, emptyList(), 0))
    val state: StateFlow<Snapshot> = _state

    val isActive: Boolean get() = synchronized(lock) { active }

    /** A trip started: begin catching (anything left over is dropped). */
    fun start() = synchronized(lock) { active = true; clearLocked() }

    /** The trip ended: stop catching and forget everything. */
    fun stop() = synchronized(lock) { active = false; clearLocked() }

    /** The alarm screen closed: forget what was shown, keep catching for the rest of the trip. */
    fun clear() = synchronized(lock) { clearLocked() }

    /** Adds a chat message unless it was seen already (chat apps re-post the whole history). */
    fun add(m: CapturedMessage): Boolean = synchronized(lock) {
        if (!active) return false
        if (!seenMessages.add("${m.convKey}\u0000${m.tMs}\u0000${m.sender}\u0000${m.text}")) return false
        trim(seenMessages, maxMessages * 10)
        messages += m
        if (messages.size > maxMessages) {
            messages.sortBy { it.tMs }
            while (messages.size > maxMessages) messages.removeAt(0)
        }
        publishLocked()
        true
    }

    /** A notification that isn't a chat: counted once per notification, never read (W4). */
    fun countOther(notificationKey: String) = synchronized(lock) {
        if (!active || !otherKeys.add(notificationKey)) return
        trim(otherKeys, 1_000)
        publishLocked()
    }

    fun snapshot(): Snapshot = _state.value

    private fun clearLocked() {
        messages.clear(); seenMessages.clear(); otherKeys.clear()
        publishLocked()
    }

    private fun publishLocked() {
        _state.value = Snapshot(active, messages.sortedBy { it.tMs }, otherKeys.size)
    }

    private fun trim(set: LinkedHashSet<String>, max: Int) {
        val it = set.iterator()
        while (set.size > max && it.hasNext()) { it.next(); it.remove() }
    }

    companion object {
        /** One per process, shared by the capture service, the trip service and the alarm screen. */
        val instance = MessageStore()
    }
}
