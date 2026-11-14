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
import kotlin.math.abs

object ListenState {
    val connected = mutableStateOf(false)
    val agents = mutableStateOf<List<Agent>>(emptyList())
    val tasks = mutableStateOf<List<TaskItem>>(emptyList())

    fun reload(prefs: Prefs) {
        agents.value = prefs.agents()
        tasks.value = prefs.tasks()
    }
}

class ListenService : Service() {
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private val queue = ArrayDeque<AlarmPayload>()
    private val escalations = HashMap<String, Runnable>()
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
        escalations.values.forEach { main.removeCallbacks(it) }
        escalations.clear()
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
            restore()
        }
        when (intent?.action) {
            ACTION_ANSWERED -> intent.getStringExtra(EXTRA_ID)?.let { onAnswered(it) }
            ACTION_SILENCE -> intent.getStringExtra(EXTRA_ID)?.let { Ringer.stop(it) }
        }
        ensureLoop()
        return START_STICKY
    }

    private fun ensureLoop() {
        val stamp = prefs.server + "|" + prefs.agents().joinToString(",") { it.secret }
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
        val agents = prefs.agents()
        val server = prefs.server
        if (agents.isEmpty() || !validServer(server)) {
            main.post { if (gen == generation) ListenState.connected.value = false }
            Thread.sleep(5_000)
            return
        }
        val topics = agents.joinToString(",") { inboxTopic(it.secret) }
        // Message id is an exclusive cursor. Seen ids catch a server that still
        // replays that message (same timestamp) after a reconnect.
        val since = URLEncoder.encode(prefs.sinceQuery(), "UTF-8")
        val url = URL(server.trimEnd('/') + "/" + topics + "/json?since=" + since)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Accept", "application/x-ndjson")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "grokbot-alarm/0.2")
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
        val id = obj.optString("id")
        val topic = obj.optString("topic")
        val body = obj.optString("message")
        val time = obj.optLong("time")
        if (id.isNotEmpty() && prefs.hasSeen(id)) return
        if (body.isEmpty()) {
            if (id.isNotEmpty()) prefs.markSeen(id, time)
            return
        }
        main.post {
            if (!prefs.hasSeen(id)) dispatch(topic, body)
            if (id.isNotEmpty()) prefs.markSeen(id, time)
        }
    }

    private fun dispatch(topic: String, body: String) {
        val agent = prefs.agentForInbox(topic) ?: return
        prefs.touch(agent.id)
        when (val incoming = parseIncoming(body)) {
            is Incoming.Register -> prefs.noteWorking(agent.id, incoming.id, incoming.bot, incoming.task)
            is Incoming.Needs -> onNeeds(agent, incoming)
            is Incoming.Alarm -> onAlarm(agent, incoming.payload.copy(agent = agent.name))
            null -> Unit
        }
        ListenState.reload(prefs)
    }

    private fun onNeeds(agent: Agent, incoming: Incoming.Needs) {
        if (prefs.isAnswered(incoming.payload.id)) return
        val payload = incoming.payload.copy(agent = agent.name)
        if (incoming.escalateAfterSec <= 0) {
            onAlarm(agent, payload)
            return
        }
        val at = System.currentTimeMillis() + incoming.escalateAfterSec * 1000L
        if (!prefs.noteWaiting(agent.id, payload, escalateAt = at, ringing = false)) return
        notifyNeeds(agent, payload)
        schedule(payload.id, at)
        ListenState.reload(prefs)
    }

    private fun onAlarm(agent: Agent, payload: AlarmPayload) {
        if (prefs.isAnswered(payload.id)) return
        cancelEscalation(payload.id)
        cancelNeeds(payload.id)
        if (!prefs.noteWaiting(agent.id, payload, escalateAt = 0, ringing = true)) return
        handleAlarm(payload)
        ListenState.reload(prefs)
    }

    private fun schedule(id: String, at: Long) {
        cancelEscalation(id)
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val run = Runnable {
            escalations.remove(id)
            escalate(id)
        }
        escalations[id] = run
        main.postDelayed(run, delay)
    }

    private fun cancelEscalation(id: String) {
        escalations.remove(id)?.let { main.removeCallbacks(it) }
    }

    private fun escalate(id: String) {
        val task = prefs.tasks().find { it.id == id } ?: return
        if (task.state != STATE_WAITING || task.ringing || prefs.isAnswered(id)) return
        val name = prefs.agent(task.agentId)?.name.orEmpty()
        val payload = task.toPayload(name)
        cancelNeeds(id)
        if (!prefs.noteWaiting(task.agentId, payload, escalateAt = 0, ringing = true)) return
        handleAlarm(payload)
        ListenState.reload(prefs)
    }

    private fun restore() {
        val now = System.currentTimeMillis()
        val ringing = prefs.tasks().filter { it.state == STATE_WAITING && it.ringing && !prefs.isAnswered(it.id) }
        synchronized(gate) {
            ringing.forEach { task ->
                val payload = task.toPayload(prefs.agent(task.agentId)?.name.orEmpty())
                if (active == null) {
                    active = payload
                    showActive()
                } else {
                    queue.add(payload)
                }
            }
        }
        prefs.tasks().filter { it.state == STATE_WAITING && !it.ringing && it.escalateAt > 0 }.forEach { task ->
            if (task.escalateAt <= now) escalate(task.id) else schedule(task.id, task.escalateAt)
        }
        ListenState.reload(prefs)
    }

    private fun handleAlarm(payload: AlarmPayload) {
        synchronized(gate) {
            if (prefs.isAnswered(payload.id)) return
            if (active?.id == payload.id || queue.any { it.id == payload.id }) return
            if (active == null) {
                active = payload
                showActive()
            } else {
                queue.add(payload)
            }
        }
    }

    private fun onAnswered(id: String) {
        cancelEscalation(id)
        cancelNeeds(id)
        synchronized(gate) {
            queue.removeAll { it.id == id }
            val wasActive = active?.id == id
            if (wasActive) {
                Ringer.stop(id)
                getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
                active = if (queue.isEmpty()) null else queue.removeFirst()
            }
            prefs.markDone(id)
            ListenState.reload(prefs)
            if (wasActive && active != null) showActive()
        }
    }

    private fun showActive() {
        val payload = active ?: return
        if (prefs.isAnswered(payload.id)) {
            active = null
            getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
            return
        }
        Ringer.start(this, payload.id)
        getSystemService(NotificationManager::class.java).notify(ALARM_ID, alarmNotification(payload))
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

    private fun notifyNeeds(agent: Agent, payload: AlarmPayload) {
        val launch = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmActivity.EXTRA, payload.toJson())
            putExtra(AlarmActivity.EXTRA_QUIET, true)
        }
        val pi = PendingIntent.getActivity(
            this,
            payload.id.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_NEEDS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(agent.name)
            .setContentText(payload.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(needsId(payload.id), notification)
    }

    private fun cancelNeeds(id: String) {
        getSystemService(NotificationManager::class.java).cancel(needsId(id))
    }

    private fun needsId(id: String) = 4000 + abs(id.hashCode() % 20000)

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
        val title = payload.agent.ifBlank { payload.bot }
        return NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setColor(0xFF1D9BF0.toInt())
            .setContentTitle(title)
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
        const val CHANNEL_NEEDS = "needs"
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
                if (action == ACTION_ANSWERED) Prefs(context).markDone(id)
            }
        }

        /** Persist the answer first, then stop sound and notifications. Safe if the activity is already gone. */
        fun markAnswered(context: Context, id: String) {
            val app = context.applicationContext
            Prefs(app).markDone(id)
            Ringer.stop(id)
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.cancel(ALARM_ID)
            nm.cancel(4000 + abs(id.hashCode() % 20000))
            signal(app, ACTION_ANSWERED, id)
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val listen = NotificationChannel(
                CHANNEL_LISTEN,
                context.getString(R.string.listen_title),
                NotificationManager.IMPORTANCE_LOW,
            )
            listen.setShowBadge(false)
            val needs = NotificationChannel(
                CHANNEL_NEEDS,
                context.getString(R.string.needs_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
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
            manager.createNotificationChannel(needs)
            manager.createNotificationChannel(alarm)
        }
    }
}
