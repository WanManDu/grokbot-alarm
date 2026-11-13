package works.gauss.grokbotalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayDeque

object ListenState {
    val connected = mutableStateOf(false)
    val pending = mutableStateOf<List<PendingWake>>(emptyList())

    fun replacePending(items: List<PendingWake>) {
        pending.value = items
    }
}

class ListenService : Service() {
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private val queue = ArrayDeque<AlarmPayload>()
    private var active: AlarmPayload? = null
    private var restored = false

    @Volatile private var generation = 0
    @Volatile private var configStamp = ""
    @Volatile private var connection: HttpURLConnection? = null
    private var loop: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        generation++
        connection?.disconnect()
        loop?.interrupt()
        ListenState.connected.value = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        val listening = listeningNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                LISTEN_ID,
                listening,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(LISTEN_ID, listening)
        }
        if (!restored) {
            restored = true
            restoreOpen()
        }
        when (intent?.action) {
            ACTION_ANSWERED -> intent.getStringExtra(EXTRA_ID)?.let { onAnswered(it) }
            ACTION_SILENCE -> intent.getStringExtra(EXTRA_ID)?.let { Ringer.stop(it) }
        }
        ensureLoop()
        return START_STICKY
    }

    private fun ensureLoop() {
        val stamp = prefs.server + "|" + prefs.secret
        if (loop?.isAlive == true && stamp == configStamp) return
        configStamp = stamp
        val gen = ++generation
        connection?.disconnect()
        loop?.interrupt()
        val thread = Thread({ runLoop(gen) }, "ntfy-listen")
        loop = thread
        thread.start()
    }

    private fun runLoop(gen: Int) {
        var backoff = 1000L
        while (gen == generation) {
            try {
                readStream(gen)
                backoff = 1000L
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (gen != generation) break
                main.post {
                    if (gen == generation) ListenState.connected.value = false
                }
                Log.w(TAG, "listen", e)
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun readStream(gen: Int) {
        val secret = prefs.secret
        val server = prefs.server
        if (!validSecret(secret) || !validServer(server)) {
            ListenState.connected.value = false
            Thread.sleep(5_000)
            return
        }
        val since = URLEncoder.encode(prefs.lastId.ifBlank { "12h" }, "UTF-8")
        val url = URL(server.trimEnd('/') + "/" + inboxTopic(secret) + "/json?since=" + since)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Accept", "application/x-ndjson")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "grokbot-alarm/0.1")
        connection = conn
        conn.connect()
        main.post {
            if (gen == generation) ListenState.connected.value = true
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (gen == generation) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleLine(line)
            }
        }
        conn.disconnect()
    }

    private fun handleLine(line: String) {
        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        val event = obj.optString("event")
        if (event == "open" || event == "keepalive") return
        if (event.isNotEmpty() && event != "message") return
        if (event != "message") return
        val id = obj.optString("id")
        val body = obj.optString("message")
        if (body.isEmpty()) {
            if (id.isNotEmpty()) prefs.lastId = id
            return
        }
        // Advance the cursor only after the message is applied, so a crash retries it.
        main.post {
            dispatch(body)
            if (id.isNotEmpty()) prefs.lastId = id
        }
    }

    private fun dispatch(body: String) {
        when (val incoming = parseIncoming(body)) {
            is Incoming.Register -> {
                prefs.addPending(incoming.wake)
                ListenState.replacePending(prefs.pending())
            }
            is Incoming.Alarm -> handleAlarm(incoming.payload)
            null -> Unit
        }
    }

    private fun handleAlarm(payload: AlarmPayload) {
        synchronized(gate) {
            if (prefs.isAnswered(payload.id)) return
            if (active?.id == payload.id || queue.any { it.id == payload.id }) return
            prefs.removePending(payload.id)
            ListenState.replacePending(prefs.pending())
            if (active == null) {
                active = payload
                persistOpen()
                showActive()
            } else {
                queue.add(payload)
                persistOpen()
            }
        }
    }

    private fun restoreOpen() {
        synchronized(gate) {
            val open = prefs.openAlarms().filter { !prefs.isAnswered(it.id) }
            if (open.isEmpty()) return
            active = open.first()
            queue.clear()
            queue.addAll(open.drop(1))
            showActive()
        }
    }

    private fun onAnswered(id: String) {
        synchronized(gate) {
            queue.removeAll { it.id == id }
            val wasActive = active?.id == id
            if (wasActive) {
                Ringer.stop(id)
                getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
                active = if (queue.isEmpty()) null else queue.removeFirst()
            }
            persistOpen()
            prefs.markAnswered(id)
            if (wasActive && active != null) showActive()
        }
    }

    private fun persistOpen() {
        val items = ArrayList<AlarmPayload>()
        active?.let { items.add(it) }
        items.addAll(queue)
        prefs.setOpen(items)
    }

    private fun showActive() {
        val payload = active ?: return
        Ringer.start(this, payload.id)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALARM_ID, alarmNotification(payload))
        val launch = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmActivity.EXTRA, payload.toJson())
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "full screen activity blocked; notification remains", e)
        }
    }

    private fun listeningNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(getString(R.string.listen_title))
            .setContentText(getString(R.string.listen_body))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun alarmNotification(payload: AlarmPayload): Notification {
        val launch = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmActivity.EXTRA, payload.toJson())
        }
        val pi = PendingIntent.getActivity(
            this,
            7,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setColor(0xFFFFB020.toInt())
            .setContentTitle(payload.bot)
            .setContentText(payload.task)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val TAG = "GrokbotAlarm"
        const val ACTION_ANSWERED = "works.gauss.grokbotalarm.ANSWERED"
        const val ACTION_SILENCE = "works.gauss.grokbotalarm.SILENCE"
        const val EXTRA_ID = "id"
        private const val LISTEN_ID = 1001
        private const val ALARM_ID = 2001
        const val CHANNEL_LISTEN = "listen"
        const val CHANNEL_ALARM = "alarm"

        fun start(context: Context) {
            val intent = Intent(context, ListenService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun signal(context: Context, action: String, id: String) {
            val intent = Intent(context, ListenService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "signal $action", e)
                if (action == ACTION_ANSWERED) Prefs(context).markAnswered(id)
            }
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val listen = NotificationChannel(
                CHANNEL_LISTEN,
                context.getString(R.string.listen_title),
                NotificationManager.IMPORTANCE_LOW,
            )
            listen.setShowBadge(false)
            val alarm = NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.listen_title),
                NotificationManager.IMPORTANCE_HIGH,
            )
            alarm.setSound(null, null)
            alarm.enableVibration(false)
            alarm.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            try {
                alarm.setBypassDnd(true)
            } catch (_: Exception) {
            }
            manager.createNotificationChannel(listen)
            manager.createNotificationChannel(alarm)
        }
    }
}
