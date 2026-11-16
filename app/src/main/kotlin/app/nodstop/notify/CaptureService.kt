package app.nodstop.notify

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * While You Slept capture (DESIGN §10): the system binds this once the rider grants notification
 * access. It reads chats only while a trip runs, into [MessageStore] (memory only, W3). No network,
 * no logs, no trace (W6, enforced by `NotifyIsolationTest`).
 */
class CaptureService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = MessageStore.instance
        if (!store.isActive) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: Bundle.EMPTY
        val raw = NotificationMapper.Raw(
            pkg = sbn.packageName,
            ownPkg = packageName,
            category = n.category,
            template = ex.getString(Notification.EXTRA_TEMPLATE),
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            title = ex.getCharSequence(Notification.EXTRA_TITLE),
            conversationTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            isGroupConversation = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            text = ex.getCharSequence(Notification.EXTRA_TEXT),
            bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
            postTimeMs = sbn.postTime,
            messages = messages(ex),
        )
        when (val r = NotificationMapper.map(raw)) {
            is NotificationMapper.Result.Chat -> r.messages.forEach(store::add)
            NotificationMapper.Result.Other -> store.countOther(sbn.key)
            NotificationMapper.Result.Ignore -> Unit
        }
    }

    /** MessagingStyle's message bundles: "text", "time", and "sender_person" or "sender". */
    @Suppress("DEPRECATION")
    private fun messages(ex: Bundle): List<NotificationMapper.RawMessage> {
        val arr: Array<Parcelable> = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val person = runCatching { b.getParcelable<Person>("sender_person") }.getOrNull()
            NotificationMapper.RawMessage(
                sender = person?.name ?: b.getCharSequence("sender"),
                text = b.getCharSequence("text"),
                tMs = b.getLong("time"),
            )
        }
    }
}
