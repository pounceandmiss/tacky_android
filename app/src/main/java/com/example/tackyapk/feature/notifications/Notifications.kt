package com.example.tackyapk.feature.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.example.tackyapk.MainActivity
import com.example.tackyapk.R
import com.example.tackyapk.TackydService

/**
 * The app's notification center, modelled on Conversations' NotificationService:
 * owns the channels and posts/clears the three user-facing notifications the
 * daemon's events drive - per-chat messages (MessagingStyle, inline reply +
 * mark-read), a ringing incoming call (CallStyle + full-screen intent), and a
 * missed call. The foreground/ongoing-call notification stays with
 * [TackydService] because it is tied to startForeground.
 *
 * Action buttons fan out by target: anything that needs the daemon (decline,
 * reply, mark-read) is a service intent [TackydService] handles off its stdin;
 * anything that needs the UI (open a chat, answer a call) is an activity intent
 * [MainActivity] resolves as a deep link. App-scoped and single-instance so the
 * per-chat MessagingStyle history survives across events.
 */
class Notifications(context: Context) {
    private val app = context.applicationContext
    private val compat = NotificationManagerCompat.from(app)
    private val self = Person.Builder().setName(app.getString(R.string.notif_self)).build()

    /** Wall-clock at construction; messages older than this are MAM/catchup
     *  backfill from before launch and don't ring (avoids a reconnect storm). */
    val startedAtMillis: Long = System.currentTimeMillis()

    // Short MessagingStyle thread per chat, so a notification shows recent context.
    private val history = HashMap<ChatKey, ArrayDeque<Line>>()
    private data class Line(val text: String, val time: Long)

    init {
        val mgr = app.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(
                CHAN_MESSAGES, app.getString(R.string.chan_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = app.getString(R.string.chan_messages_desc) })

        mgr.createNotificationChannel(
            NotificationChannel(
                CHAN_INCOMING, app.getString(R.string.chan_incoming),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = app.getString(R.string.chan_incoming_desc)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })

        mgr.createNotificationChannel(
            NotificationChannel(
                CHAN_MISSED, app.getString(R.string.chan_missed),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = app.getString(R.string.chan_missed_desc) })
    }

    // --- Messages -----------------------------------------------------------

    /** Post (or update) the notification for one incoming message. [whenMillis]
     *  is the message's own time so the thread sorts naturally. */
    fun postMessage(key: ChatKey, contactName: String, body: String, whenMillis: Long) {
        val sender = Person.Builder().setName(contactName).setKey(key.jid).build()
        val lines = history.getOrPut(key) { ArrayDeque() }
        lines.addLast(Line(body, whenMillis))
        while (lines.size > MAX_LINES) lines.removeFirst()

        val style = NotificationCompat.MessagingStyle(self)
        lines.forEach { style.addMessage(it.text, it.time, sender) }

        val n = NotificationCompat.Builder(app, CHAN_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(whenMillis)
            .setAutoCancel(true)
            .setContentIntent(openChat(key, contactName))
            .addAction(replyAction(key))
            .addAction(markReadAction(key))
            .build()
        notify(key.tag(), MESSAGE_ID, n)
    }

    /** Drop a chat's notification and its thread (on open, mark-read, or reply). */
    fun clearChat(key: ChatKey) {
        history.remove(key)
        compat.cancel(key.tag(), MESSAGE_ID)
    }

    private fun openChat(key: ChatKey, name: String): PendingIntent {
        val i = Intent(app, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ACC, key.acc)
            .putExtra(EXTRA_JID, key.jid)
            .putExtra(EXTRA_NAME, name)
        return PendingIntent.getActivity(app, code(key, 1), i, PI_IMMUTABLE)
    }

    private fun replyAction(key: ChatKey): NotificationCompat.Action {
        val remote = RemoteInput.Builder(KEY_REPLY)
            .setLabel(app.getString(R.string.reply)).build()
        val i = Intent(app, TackydService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_ACC, key.acc)
            .putExtra(EXTRA_JID, key.jid)
        val pi = PendingIntent.getService(app, code(key, 2), i, PI_MUTABLE)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, app.getString(R.string.reply), pi)
            .addRemoteInput(remote)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun markReadAction(key: ChatKey): NotificationCompat.Action {
        val i = Intent(app, TackydService::class.java)
            .setAction(ACTION_MARK_READ)
            .putExtra(EXTRA_ACC, key.acc)
            .putExtra(EXTRA_JID, key.jid)
        val pi = PendingIntent.getService(app, code(key, 3), i, PI_IMMUTABLE)
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, app.getString(R.string.mark_read), pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    // --- Calls --------------------------------------------------------------

    /** Ring a ringing incoming call: CallStyle with answer/decline plus a
     *  full-screen intent so it takes over the screen (or heads-up if denied). */
    fun postIncomingCall(acc: String, sid: String, peer: String) {
        val person = Person.Builder().setName(peerName(peer)).build()
        val answer = PendingIntent.getActivity(
            app, REQ_ANSWER,
            Intent(app, MainActivity::class.java)
                .setAction(ACTION_ANSWER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ACC, acc).putExtra(EXTRA_SID, sid),
            PI_IMMUTABLE)
        val decline = PendingIntent.getService(
            app, REQ_DECLINE,
            Intent(app, TackydService::class.java)
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_ACC, acc).putExtra(EXTRA_SID, sid),
            PI_IMMUTABLE)
        val full = PendingIntent.getActivity(
            app, REQ_FULLSCREEN,
            Intent(app, MainActivity::class.java)
                .setAction(ACTION_OPEN_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PI_IMMUTABLE)

        val n = NotificationCompat.Builder(app, CHAN_INCOMING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setFullScreenIntent(full, true)
            .build()
        notify(null, CALL_ID, n)
    }

    fun clearIncomingCall() = compat.cancel(CALL_ID)

    /** A one-shot missed-call note when an incoming call ends before it's answered. */
    fun postMissedCall(peer: String) {
        val name = peerName(peer)
        val open = PendingIntent.getActivity(
            app, REQ_MISSED,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PI_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CHAN_MISSED)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(app.getString(R.string.missed_call))
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(null, MISSED_ID, n)
    }

    // --- internals ----------------------------------------------------------

    private fun notify(tag: String?, id: Int, n: Notification) {
        try {
            compat.notify(tag, id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; nothing to show.
        }
    }

    private fun peerName(peer: String) = peer.substringBefore('/').ifEmpty { peer }

    // Distinct request codes per (chat, action) - PendingIntents compare equal
    // ignoring extras, so colliding codes would clobber each other's targets.
    private fun code(key: ChatKey, salt: Int) = key.tag().hashCode() * 8 + salt

    companion object {
        const val CHAN_MESSAGES = "messages"
        const val CHAN_INCOMING = "calls_incoming"
        const val CHAN_MISSED = "calls_missed"

        // Service-targeted (handled in TackydService.onStartCommand).
        const val ACTION_HANGUP = "com.example.tackyapk.action.HANGUP"
        const val ACTION_DECLINE = "com.example.tackyapk.action.DECLINE"
        const val ACTION_REPLY = "com.example.tackyapk.action.REPLY"
        const val ACTION_MARK_READ = "com.example.tackyapk.action.MARK_READ"
        // Activity-targeted deep links (handled in MainActivity).
        const val ACTION_OPEN_CHAT = "com.example.tackyapk.action.OPEN_CHAT"
        const val ACTION_ANSWER = "com.example.tackyapk.action.ANSWER"
        const val ACTION_OPEN_CALL = "com.example.tackyapk.action.OPEN_CALL"

        const val EXTRA_ACC = "acc"
        const val EXTRA_SID = "sid"
        const val EXTRA_JID = "jid"
        const val EXTRA_NAME = "name"
        const val KEY_REPLY = "reply_text"

        // Reserved notification ids. The FGS/ongoing-call notification (id 1) lives
        // in TackydService; messages use a per-chat tag with this shared id.
        const val MESSAGE_ID = 100
        private const val CALL_ID = 2
        private const val MISSED_ID = 3

        // Fixed request codes for the single-instance call notifications.
        private const val REQ_ANSWER = 10
        private const val REQ_DECLINE = 11
        private const val REQ_FULLSCREEN = 12
        private const val REQ_MISSED = 13

        private const val MAX_LINES = 6

        private val PI_IMMUTABLE =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val PI_MUTABLE =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    }
}
