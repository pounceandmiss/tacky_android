package com.example.tackyapk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.example.tackyapk.feature.notifications.Notifications
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.math.min

/**
 * Hosts the tackyd-json backend as a child process and exposes its wire as flows:
 * [frames] carries incoming JSON frames, [state] tracks lifecycle. A single
 * supervisor coroutine spawns the child, pumps its stdout until it exits, then
 * respawns it with backoff - so an OOM-reaped or crashed backend comes back on
 * its own. The child persists to SQLite (-transient 0), so a respawn reloads its
 * accounts and reconnects rather than coming back empty.
 */
class TackydService : Service(), Transport {

    inner class LocalBinder : Binder() {
        val service: TackydService get() = this@TackydService
    }

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    // replay so a client that binds shortly after start still sees early frames.
    private val _frames = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 256)
    override val frames: SharedFlow<String> = _frames.asSharedFlow()

    private val _state = MutableStateFlow("stopped")
    override val state: StateFlow<String> = _state.asStateFlow()

    @Volatile private var child: Process? = null
    @Volatile private var childIn: OutputStream? = null
    @Volatile private var shuttingDown = false
    private var supervisor: Job? = null

    // Whether the microphone foreground-service type is currently attached.
    @Volatile private var micForeground = false

    // Set while a call is live; drives the ongoing-call notification + its hang-up
    // action. Null reverts the foreground notification to the plain backend one.
    private data class CallNotif(val acc: String, val sid: String, val peer: String)
    @Volatile private var activeCall: CallNotif? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHAN, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW))
        // Boot as a plain dataSync FGS. The microphone type is added on demand
        // (see [setMicrophoneForeground]) only while a call is live, because it
        // requires RECORD_AUDIO and Android rejects the type if it isn't granted.
        goForeground(microphone = false)
        // Spawn the daemon here, not just in onStartCommand: when startForegroundService()
        // is rejected (app not yet in an allowed state at Application-create time) the
        // service is still created via bindService(BIND_AUTO_CREATE), which delivers
        // onCreate/onBind but never onStartCommand. Without this the supervisor would
        // never start and the backend would hang at "connecting". Guarded + idempotent.
        if (supervisor?.isActive != true) supervisor = startSupervisor()
    }

    private fun buildNotification(): Notification {
        val call = activeCall
            ?: return NotificationCompat.Builder(this, CHAN)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        val openCall = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val hangUp = PendingIntent.getService(
            this, 1,
            Intent(this, TackydService::class.java)
                .setAction(Notifications.ACTION_HANGUP)
                .putExtra(Notifications.EXTRA_ACC, call.acc)
                .putExtra(Notifications.EXTRA_SID, call.sid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder()
            .setName(call.peer.substringBefore('/').ifEmpty { call.peer })
            .build()
        return NotificationCompat.Builder(this, CHAN)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openCall)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .build()
    }

    /** Switch the foreground notification to the ongoing-call style (with a hang-up
     *  action) while a call is live, or back to the plain backend one. Idempotent. */
    fun setOngoingCall(acc: String?, sid: String?, peer: String?) {
        val next = if (acc != null && sid != null) CallNotif(acc, sid, peer ?: "") else null
        if (next == activeCall) return
        activeCall = next
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification())
        } catch (e: RuntimeException) {
            Log.e(TAG, "setOngoingCall update failed", e)
        }
    }

    /** (Re)enter the foreground with dataSync, optionally plus microphone. The
     *  typed overload is API 29+; older devices fall back to the untyped form. */
    private fun goForeground(microphone: Boolean) {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * Attach or drop the microphone foreground-service type around a live call, so
     * the daemon's native mic capture is allowed to run while the app is
     * backgrounded (Android 14+). The type needs RECORD_AUDIO granted or the
     * platform rejects it, so we skip it when the permission is missing - the call
     * just has no audio rather than crashing the service. Idempotent.
     */
    fun setMicrophoneForeground(on: Boolean) {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val want = on && granted
        if (want == micForeground) return
        try {
            goForeground(microphone = want)
            micForeground = want
        } catch (e: RuntimeException) {
            Log.e(TAG, "setMicrophoneForeground($on) failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleAction(intent)
        if (supervisor?.isActive != true) supervisor = startSupervisor()
        return START_STICKY
    }

    /** Notification action buttons land here as service intents (the service owns
     *  the daemon's stdin). Each maps to one wire frame; UI-facing actions
     *  (open chat, answer) are activity deep links handled in MainActivity. */
    private fun handleAction(intent: Intent?) {
        val acc = intent?.getStringExtra(Notifications.EXTRA_ACC)
        when (intent?.action) {
            Notifications.ACTION_HANGUP ->
                intent.getStringExtra(Notifications.EXTRA_SID)?.let { sid ->
                    if (acc != null) callFrame("hangup", acc, sid)
                }
            Notifications.ACTION_DECLINE ->
                intent.getStringExtra(Notifications.EXTRA_SID)?.let { sid ->
                    if (acc != null) callFrame("reject", acc, sid)
                }
            Notifications.ACTION_REPLY -> {
                val jid = intent.getStringExtra(Notifications.EXTRA_JID)
                val body = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifications.KEY_REPLY)?.toString()
                if (acc != null && jid != null && !body.isNullOrBlank()) {
                    sendMessage(acc, jid, body)
                    clearChatNotification(acc, jid)
                }
            }
            Notifications.ACTION_MARK_READ -> {
                val jid = intent.getStringExtra(Notifications.EXTRA_JID)
                if (acc != null && jid != null) clearChatNotification(acc, jid)
            }
        }
    }

    /** Send a calls control frame (hangup/reject) straight to the daemon. */
    private fun callFrame(method: String, acc: String, sid: String) {
        val args = JsonObject().apply {
            addProperty("acc", acc)
            addProperty("sid", sid)
        }
        send(JsonArray().apply { add("calls"); add(method); add(args) }.toString())
    }

    /** Send a chat reply frame on behalf of the notification's inline reply. */
    private fun sendMessage(acc: String, jid: String, body: String) {
        val args = JsonObject().apply {
            addProperty("acc", acc)
            addProperty("chat_jid", jid)
            addProperty("body", body)
        }
        send(JsonArray().apply { add("message"); add("send"); add(args) }.toString())
    }

    private fun clearChatNotification(acc: String, jid: String) {
        NotificationManagerCompat.from(this).cancel("$acc $jid", Notifications.MESSAGE_ID)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Length-prefixed write to the child's stdin. Synchronized: callers may send
     *  from any thread, and a respawn reassigns [childIn] underneath us. */
    @Synchronized
    override fun send(json: String) {
        val out = childIn ?: return
        try {
            val payload = json.toByteArray(Charsets.UTF_8)
            out.write("${payload.size}\n".toByteArray(Charsets.UTF_8))
            out.write(payload)
            out.flush()
        } catch (e: IOException) {
            Log.e(TAG, "send failed", e)
        }
    }

    /** Spawn, pump the child's stdout until it exits, back off, repeat - until shutdown. */
    private fun startSupervisor(): Job = scope.launch {
        var delayMs = 0
        while (isActive && !shuttingDown) {
            val spawnedAt = SystemClock.elapsedRealtime()
            try {
                runChild()
            } catch (e: IOException) {
                Log.e(TAG, "spawn failed", e)
                _state.value = "error: ${e.message}"
            }
            if (shuttingDown) break
            _state.value = "stopped"
            val uptime = SystemClock.elapsedRealtime() - spawnedAt
            delayMs = if (uptime >= STABLE_MS) BASE_DELAY
                      else min(if (delayMs <= 0) BASE_DELAY else delayMs * 2, MAX_DELAY)
            Log.i(TAG, "respawning in ${delayMs}ms")
            _state.value = "restarting in ${delayMs}ms"
            delay(delayMs.toLong())
        }
        _state.value = "stopped"
    }

    /** Spawn the child and pump its stdout until it exits (suspends here). */
    private suspend fun runChild() {
        val nativeDir = applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libtackyd_json.so")
        val dataDir = File(filesDir, "tackyd-home")
        // -transient 0 persists to SQLite under these dirs, so make sure they
        // exist before the backend opens its database.
        val configHome = File(dataDir, ".config")
        val cacheHome = File(dataDir, ".cache")
        val dataHome = File(dataDir, ".local/share")
        listOf(dataDir, configHome, cacheHome, dataHome).forEach { it.mkdirs() }

        // No --debug-file: tackyd-json writes logs to stderr, which drainStderr
        // mirrors into logcat. Nothing touches disk; inspect with `adb logcat`.
        val pb = ProcessBuilder(
            binary.absolutePath, "-transient", "0",
            "-debug-level", "verbose",
            "-libdatachannel-debug-level", "verbose",
            "-rtcma-debug-level", "verbose",
        )
        pb.redirectErrorStream(false)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", nativeDir)
            put("HOME", dataDir.absolutePath)
            put("XDG_CONFIG_HOME", configHome.absolutePath)
            put("XDG_CACHE_HOME", cacheHome.absolutePath)
            put("XDG_DATA_HOME", dataHome.absolutePath)
        }
        pb.directory(dataDir)

        Log.i(TAG, "spawning ${binary.absolutePath} HOME=$dataDir")
        val proc = pb.start()
        child = proc
        childIn = proc.outputStream
        _state.value = "running"

        // Drain stderr to logcat + a fake frame so Tcl/process errors are visible.
        val errJob = scope.launch { drainStderr(proc.errorStream) }
        try {
            readLoop(proc.inputStream)
        } finally {
            errJob.cancel()
            childIn = null
            proc.destroy()
        }
    }

    private suspend fun readLoop(input: InputStream) {
        while (true) {
            val lenLine = readFrameLine(input) ?: break
            val len = lenLine.trim().toIntOrNull()
            if (len == null) {
                Log.w(TAG, "bad length line: $lenLine")
                continue
            }
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = input.read(buf, read, len - read)
                if (r < 0) return
                read += r
            }
            _frames.emit(String(buf, Charsets.UTF_8))
        }
    }

    private fun readFrameLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("UTF-8")
            if (b == '\n'.code) return bos.toString("UTF-8")
            bos.write(b)
        }
    }

    private suspend fun drainStderr(err: InputStream) {
        val br = BufferedReader(InputStreamReader(err))
        try {
            while (true) {
                val line = br.readLine() ?: break
                Log.w(TAG, "stderr: $line")
                val escaped = line.replace("\\", "\\\\").replace("\"", "\\\"")
                _frames.emit("[\"stderr\",\"$escaped\"]")
            }
        } catch (e: IOException) {
            Log.w(TAG, "stderr drain ended", e)
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        child?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TackydService"
        private const val CHAN = "tackyd"
        private const val NOTIF_ID = 1

        // Respawn backoff: start fast, grow on repeated quick failures, cap, and
        // reset once the child has stayed up long enough to count as stable.
        private const val BASE_DELAY = 1000
        private const val MAX_DELAY = 30000
        private const val STABLE_MS = 60000L
    }
}
