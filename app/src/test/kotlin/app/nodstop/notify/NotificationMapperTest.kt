package app.nodstop.notify

import app.nodstop.brief.CapturedMessage
import app.nodstop.notify.NotificationMapper.RawMessage
import app.nodstop.notify.NotificationMapper.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** DESIGN §10 W4: chats are read, everything else is only counted, some things are ignored. */
class NotificationMapperTest {
    private fun raw(
        pkg: String = "com.whatsapp",
        category: String? = "msg",
        template: String? = null,
        title: String? = "Mum",
        conv: String? = null,
        group: Boolean = false,
        text: String? = "Home for dinner?",
        big: String? = null,
        messages: List<RawMessage> = emptyList(),
        summary: Boolean = false,
        ongoing: Boolean = false,
    ) = NotificationMapper.Raw(pkg, "app.nodstop", category, template, summary, ongoing, title, conv, group, text, big, 1_000, messages)

    @Test
    fun `MessagingStyle messages become one captured message each`() {
        val r = NotificationMapper.map(raw(
            template = "android.app.Notification\$MessagingStyle", conv = "Flat group", group = true, title = "Flat group",
            messages = listOf(RawMessage("Jess", "Saturday 7pm?", 10), RawMessage("Sam", " yes ", 20), RawMessage("Ari", "", 30)),
        ))
        assertEquals(Result.Chat(listOf(
            CapturedMessage("com.whatsapp", "Flat group", "Jess", "Saturday 7pm?", 10),
            CapturedMessage("com.whatsapp", "Flat group", "Sam", "yes", 20),
        )), r)
    }

    @Test
    fun `one-to-one message without a sender is from the conversation`() {
        val r = NotificationMapper.map(raw(messages = listOf(RawMessage(null, "call me", 5))))
        assertEquals(Result.Chat(listOf(CapturedMessage("com.whatsapp", "Mum", "Mum", "call me", 5))), r)
    }

    @Test
    fun `chat category without MessagingStyle uses the big text, then the text`() {
        assertEquals(
            Result.Chat(listOf(CapturedMessage("org.sms", "Kai", "Kai", "the long one", 1_000))),
            NotificationMapper.map(raw(pkg = "org.sms", title = "Kai", text = "short", big = "the long one")),
        )
    }

    @Test
    fun `non-chat notifications are only counted, never read`() {
        assertEquals(Result.Other, NotificationMapper.map(raw(category = "email", title = "Your bank", text = "Payment received")))
        assertEquals(Result.Other, NotificationMapper.map(raw(category = null, title = "News", text = "Headline")))
    }

    @Test
    fun `own notifications, group summaries and ongoing ones are ignored`() {
        assertEquals(Result.Ignore, NotificationMapper.map(raw(pkg = "app.nodstop")))
        assertEquals(Result.Ignore, NotificationMapper.map(raw(summary = true)))
        assertEquals(Result.Ignore, NotificationMapper.map(raw(ongoing = true)))
        assertEquals(Result.Ignore, NotificationMapper.map(raw(text = "  ", big = null)))
    }
}
