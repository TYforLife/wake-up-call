package app.nodstop.notify

import app.nodstop.brief.CapturedMessage

/**
 * Decides what a posted notification is for While You Slept (DESIGN §10, W4): a chat (read its
 * messages), something else (count it, never read it), or nothing (ignore). Plain Kotlin, so it is
 * tested on the JVM; [CaptureService] only copies fields out of the Android notification.
 */
object NotificationMapper {
    data class RawMessage(val sender: CharSequence?, val text: CharSequence?, val tMs: Long)

    data class Raw(
        val pkg: String,
        val ownPkg: String,
        val category: String?,
        val template: String?,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
        val title: CharSequence?,
        val conversationTitle: CharSequence?,
        val isGroupConversation: Boolean,
        val text: CharSequence?,
        val bigText: CharSequence?,
        val postTimeMs: Long,
        val messages: List<RawMessage>,
    )

    sealed interface Result {
        data class Chat(val messages: List<CapturedMessage>) : Result
        data object Other : Result
        data object Ignore : Result
    }

    const val CATEGORY_MESSAGE = "msg"

    fun map(r: Raw): Result {
        if (r.pkg == r.ownPkg || r.isGroupSummary || r.isOngoing) return Result.Ignore
        val isChat = r.category == CATEGORY_MESSAGE || r.template?.endsWith("MessagingStyle") == true || r.messages.isNotEmpty()
        if (!isChat) return Result.Other
        val conversation = (r.conversationTitle ?: r.title)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: r.pkg
        val msgs = if (r.messages.isNotEmpty()) {
            r.messages.mapNotNull { m ->
                val text = m.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // In a one-to-one chat with no sender given, the other person is the conversation.
                val sender = m.sender?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: conversation.takeIf { !r.isGroupConversation }
                CapturedMessage(r.pkg, conversation, sender, text, if (m.tMs > 0) m.tMs else r.postTimeMs)
            }
        } else {
            val text = (r.bigText ?: r.text)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            listOfNotNull(text?.let { CapturedMessage(r.pkg, conversation, r.title?.toString()?.trim(), it, r.postTimeMs) })
        }
        return if (msgs.isEmpty()) Result.Ignore else Result.Chat(msgs)
    }
}
