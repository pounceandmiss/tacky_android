package com.example.tackyapk

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.example.tackyapk.data.TackyRepository
import com.example.tackyapk.feature.calls.CallAudioManager
import com.example.tackyapk.feature.calls.CallRepository
import com.example.tackyapk.feature.calls.CallStatus
import com.example.tackyapk.feature.chatlist.ChatListRepository
import com.example.tackyapk.feature.conversation.Message
import com.example.tackyapk.feature.notifications.ChatKey
import com.example.tackyapk.feature.notifications.ChatPresence
import com.example.tackyapk.feature.notifications.Notifications
import com.example.tackyapk.feature.omemo.OmemoRepository
import com.example.tackyapk.feature.profile.ProfileRepository
import com.example.tackyapk.model.decodeOrNull
import com.example.tackyapk.model.str
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The bound dependency graph, built once the service connects. */
class AppDeps(
    val client: TackyClient,
    val repo: TackyRepository,
    val chatList: ChatListRepository,
    val avatars: AvatarCache,
    val files: FileCache,
    val profile: ProfileRepository,
    val omemo: OmemoRepository,
    val calls: CallRepository,
    val callAudio: CallAudioManager,
    val notifications: Notifications,
    val state: StateFlow<String>,
)

/**
 * Owns the backend client for the whole process - the Android analogue of the
 * desktop GUI's single long-lived `::tacky`. The foreground service + client live
 * at Application scope, so an Activity recreation (rotation, dark mode, return
 * from background) never tears them down: [deps] stays non-null and the UI just
 * re-reads it. It is null only during the one-time async connect after a cold
 * process start, which the UI covers with a brief loading state.
 */
class TackyApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _deps = MutableStateFlow<AppDeps?>(null)
    val deps: StateFlow<AppDeps?> = _deps.asStateFlow()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? TackydService.LocalBinder)?.service ?: return
            val client = TackyClient(svc, scope).apply { start() }
            val avatars = AvatarCache(client, scope).apply { start() }
            val files = FileCache(client, scope).apply { start() }
            val repo = TackyRepository(client, svc.state, scope).apply { start() }
            val chatList = ChatListRepository(client, svc.state, scope).apply { start() }
            val profile = ProfileRepository(client, svc.state, scope).apply { start() }
            val omemo = OmemoRepository(client, scope)
            val calls = CallRepository(client, scope)
            val callAudio = CallAudioManager(this@TackyApp)
            val notifications = Notifications(this@TackyApp)
            driveCallNotifications(svc, calls, callAudio, notifications)
            notifyIncomingMessages(client, notifications)
            _deps.value = AppDeps(
                client, repo, chatList, avatars, files, profile, omemo, calls, callAudio,
                notifications, svc.state)
        }

        // Only fires if the (in-process) service is destroyed, which takes the
        // whole process with it; nothing to rebuild here.
        override fun onServiceDisconnected(name: ComponentName?) {
            _deps.value = null
        }
    }

    /**
     * Map the one in-flight call to its notifications and audio session. A ringing
     * incoming call gets the full-screen CallStyle notification but no mic/audio
     * yet; an ongoing call (outgoing/answered) takes the mic + VoIP audio session
     * and the service's ongoing notification. A terminal incoming call that never
     * went active is a missed call.
     */
    private fun driveCallNotifications(
        svc: TackydService,
        calls: CallRepository,
        callAudio: CallAudioManager,
        notifications: Notifications,
    ) = scope.launch {
        var curSid: String? = null
        var reachedActive = false

        fun standDown() {
            notifications.clearIncomingCall()
            svc.setOngoingCall(null, null, null)
            svc.setMicrophoneForeground(false)
            callAudio.stop()
        }

        calls.call.collect { c ->
            if (c == null) {
                standDown()
                curSid = null
                reachedActive = false
                return@collect
            }
            if (c.sid != curSid) {
                curSid = c.sid
                reachedActive = false
            }
            if (c.status == CallStatus.ACTIVE) reachedActive = true

            when {
                c.terminal -> {
                    if (c.incoming && !reachedActive) notifications.postMissedCall(c.peer)
                    standDown()
                }
                c.incoming && c.status == CallStatus.INCOMING -> {
                    // Ringing in: ring, but don't seize the mic until answered.
                    notifications.postIncomingCall(c.acc, c.sid, c.peer)
                    svc.setOngoingCall(null, null, null)
                    svc.setMicrophoneForeground(false)
                    callAudio.stop()
                }
                else -> {
                    // Outgoing/ringing/active: full call audio session.
                    notifications.clearIncomingCall()
                    svc.setOngoingCall(c.acc, c.sid, c.peer)
                    svc.setMicrophoneForeground(true)
                    callAudio.start()
                }
            }
        }
    }

    /** Post a message notification for each live inbound message whose chat isn't
     *  on screen, skipping our own echoes and pre-launch MAM/catchup backfill. */
    private fun notifyIncomingMessages(client: TackyClient, notifications: Notifications) =
        scope.collectModule(client, "message") { ev ->
            if (ev.event != "<Received>") return@collectModule
            val o = ev.args as? JsonObject ?: return@collectModule
            val acc = o.str("acc") ?: return@collectModule
            val jid = o.str("jid") ?: return@collectModule
            val msg = o.get("message").decodeOrNull<Message>() ?: return@collectModule
            if (msg.is_outgoing) return@collectModule

            val key = ChatKey(acc, jid)
            if (ChatPresence.isShowing(key)) return@collectModule

            val whenMs = if (msg.timestamp > 0) msg.timestamp / 1000 else System.currentTimeMillis()
            if (whenMs < notifications.startedAtMillis - 1000) return@collectModule

            val text = msg.body.ifBlank {
                if (msg.attachments.isNotEmpty()) getString(R.string.attachment) else ""
            }
            if (text.isBlank()) return@collectModule
            val name = jid.substringBefore('/').substringBefore('@').ifEmpty { jid }
            notifications.postMessage(key, name, text, whenMs)
        }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundTracker)
        val svc = Intent(this, TackydService::class.java)
        // Allowed from a user launch (foreground); an OS-driven background restart
        // can reject it, but BIND_AUTO_CREATE still (re)creates the service, which
        // promotes itself to foreground in its own onStartCommand.
        runCatching { startForegroundService(svc) }
        bindService(svc, conn, Context.BIND_AUTO_CREATE)
    }

    /** Tracks whether any Activity is started, i.e. the app is in the foreground,
     *  so [ChatPresence] can suppress notifications for the visible chat. */
    private object ForegroundTracker : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            started++
            ChatPresence.foreground = true
        }
        override fun onActivityStopped(activity: Activity) {
            if (--started <= 0) {
                started = 0
                ChatPresence.foreground = false
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
