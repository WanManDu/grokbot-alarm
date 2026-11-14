package works.gauss.grokbotalarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Loops an alarm tone on USAGE_ALARM (the stream that still plays when the ringer
 * is silent), ramps the track volume up, vibrates, and holds a wake lock.
 * Do Not Disturb is lifted only when the user has granted notification-policy access.
 */
object Ringer {
    private const val TAG = "GrokbotAlarm"
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()

    private var token: String? = null
    private var stopped = true
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var savedAlarmVolume: Int = -1
    private var raisedVolume = false
    private var notificationManager: NotificationManager? = null
    private var savedFilter: Int = -1
    private var changedFilter = false
    private var gain = 0.08f

    private val ramp = object : Runnable {
        override fun run() {
            val current = synchronized(gate) { if (stopped) null else track }
            if (current == null) return
            gain = (gain + 0.03f).coerceAtMost(1f)
            try {
                current.setVolume(gain)
            } catch (_: Exception) {
            }
            if (gain < 1f) main.postDelayed(this, 1000)
        }
    }

    fun start(context: Context, alarmToken: String) {
        val app = context.applicationContext
        synchronized(gate) {
            if (!stopped && token == alarmToken) return
            stopLocked()
            token = alarmToken
            stopped = false
            gain = 0.08f
            acquireWakeLock(app)
            raiseAlarmVolume(app)
            openDnd(app)
            vibrate(app)
            startTone()
            main.post(ramp)
        }
    }

    fun stop(alarmToken: String) {
        synchronized(gate) {
            if (token != alarmToken) return
            stopLocked()
        }
    }

    private fun stopLocked() {
        stopped = true
        token = null
        main.removeCallbacks(ramp)
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        val am = audioManager
        val focusRequest = focus
        if (am != null && focusRequest != null) {
            try {
                am.abandonAudioFocusRequest(focusRequest)
            } catch (_: Exception) {
            }
        }
        focus = null
        if (raisedVolume && am != null && savedAlarmVolume >= 0) {
            try {
                val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val target = (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8f).toInt()
                if (now == target) {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                }
            } catch (_: Exception) {
            }
        }
        raisedVolume = false
        savedAlarmVolume = -1
        audioManager = null
        if (changedFilter && notificationManager != null && savedFilter >= 0) {
            try {
                notificationManager?.setInterruptionFilter(savedFilter)
            } catch (_: Exception) {
            }
        }
        changedFilter = false
        savedFilter = -1
        notificationManager = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun acquireWakeLock(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grokbot-alarm:ring")
        lock.setReferenceCounted(false)
        lock.acquire(15 * 60 * 1000L)
        wakeLock = lock
    }

    private fun raiseAlarmVolume(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        audioManager = am
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            savedAlarmVolume = cur
            if (max > 0 && cur < max / 2) {
                val target = (max * 0.8f).toInt().coerceIn(1, max)
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                raisedVolume = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "alarm volume", e)
        }
        val attrs = alarmAttributes()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .build()
        focus = request
        try {
            am.requestAudioFocus(request)
        } catch (_: Exception) {
        }
    }

    private fun openDnd(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        notificationManager = nm
        try {
            if (nm.isNotificationPolicyAccessGranted) {
                savedFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                changedFilter = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "dnd", e)
        }
    }

    private fun vibrate(context: Context) {
        val vib = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = vib
        if (!vib.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 450, 180, 450, 180, 450, 700),
            0,
        )
        vib.vibrate(effect)
    }

    private fun startTone() {
        val sampleRate = 22050
        val pcm = beep(sampleRate)
        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(alarmAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "audiotrack", e)
            return
        }
        val wrote = built.write(pcm, 0, pcm.size)
        if (wrote != pcm.size) {
            built.release()
            Log.w(TAG, "short audio write $wrote")
            return
        }
        // Loop the whole buffer. Some devices reject the end frame and accept end - 1.
        val frames = pcm.size
        if (built.setLoopPoints(0, frames, -1) != AudioTrack.SUCCESS && frames > 1) {
            built.setLoopPoints(0, frames - 1, -1)
        }
        track = built
        built.setVolume(gain)
        built.play()
    }

    private fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun beep(sampleRate: Int): ShortArray {
        val samples = (sampleRate * 1.6f).toInt()
        val out = ShortArray(samples)
        fun tone(startSec: Double, endSec: Double, freq: Double) {
            val start = (startSec * sampleRate).toInt()
            val end = (endSec * sampleRate).toInt().coerceAtMost(samples)
            for (i in start until end) {
                val t = (i - start).toDouble() / sampleRate
                val attack = min(1.0, (i - start) / 180.0)
                val release = min(1.0, (end - i) / 360.0)
                val sample = sin(2.0 * PI * freq * t) * 0.7 * attack * release
                out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
        }
        tone(0.0, 0.18, 880.0)
        tone(0.28, 0.48, 988.0)
        return out
    }
}
