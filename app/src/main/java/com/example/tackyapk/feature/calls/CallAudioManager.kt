package com.example.tackyapk.feature.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The call audio session: communication mode + audio focus, earpiece/speaker/wired
 * routing via the modern [AudioManager.setCommunicationDevice] API, and a proximity
 * wake lock that blanks the screen when the phone is held to the ear. [start] on a
 * live call, [stop] when it ends - [stop] restores the prior mode/route and abandons
 * focus, handing the mic and route back to the system. Bluetooth is out of scope.
 */
class CallAudioManager(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var focus: AudioFocusRequest? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var active = false
    private var savedMode = AudioManager.MODE_NORMAL

    private val _speakerphone = MutableStateFlow(false)
    val speakerphone: StateFlow<Boolean> = _speakerphone.asStateFlow()

    // Re-apply routing when devices come and go (e.g. a wired headset plugged
    // mid-call), the modern equivalent of an ACTION_HEADSET_PLUG receiver.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = route()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = route()
    }

    fun start() {
        val am = audio ?: return
        if (active) return
        try {
            savedMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            focus = req
            active = true
            am.registerAudioDeviceCallback(deviceCallback, main)
            setSpeakerphone(false) // default to the earpiece (or a wired headset)
        } catch (e: RuntimeException) {
            Log.e(TAG, "start failed", e)
        }
    }

    fun stop() {
        if (!active) return
        active = false
        releaseProximity()
        val am = audio ?: return
        try {
            am.unregisterAudioDeviceCallback(deviceCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            am.mode = savedMode
        } catch (e: RuntimeException) {
            Log.e(TAG, "stop failed", e)
        }
        _speakerphone.value = false
    }

    fun setSpeakerphone(on: Boolean) {
        if (!active) return
        _speakerphone.value = on
        route()
        // The proximity blank only makes sense on the earpiece.
        if (on) releaseProximity() else acquireProximity()
    }

    fun toggleSpeakerphone() = setSpeakerphone(!_speakerphone.value)

    /** Route to the speaker when toggled on, else a wired headset if present, else
     *  the earpiece. */
    private fun route() {
        val am = audio ?: return
        if (!active) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = _speakerphone.value
            return
        }
        val devices = am.availableCommunicationDevices
        val pick = if (_speakerphone.value) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (pick != null) am.setCommunicationDevice(pick)
    }

    private fun acquireProximity() {
        val pm = power ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val lock = proximityLock ?: pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "tacky:call"
        ).also { proximityLock = it }
        if (!lock.isHeld) lock.acquire(MAX_CALL_MS)
    }

    private fun releaseProximity() {
        proximityLock?.let { if (it.isHeld) it.release() }
    }

    private companion object {
        const val TAG = "CallAudioManager"
        // Safety bound so a missed release can't pin the wake lock forever.
        const val MAX_CALL_MS = 4L * 60 * 60 * 1000
    }
}
