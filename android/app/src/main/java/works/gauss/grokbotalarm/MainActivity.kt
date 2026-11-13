package works.gauss.grokbotalarm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private var askedNotifications = false

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        tick.intValue++
        if (Permissions.notificationsGranted(this) && Prefs(this).agents().isNotEmpty()) {
            ListenService.start(this)
        }
    }

    internal fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Permissions.openNotifications(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        prefs.migrate()
        ListenState.reload(prefs)
        setContent {
            AlarmTheme {
                Root(tick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        val prefs = Prefs(this)
        ListenState.reload(prefs)
        if (Permissions.notificationsGranted(this) && prefs.agents().isNotEmpty()) {
            ListenService.start(this)
        } else if (!askedNotifications && Build.VERSION.SDK_INT >= 33) {
            askedNotifications = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun Root(tick: Int) {
    var screen by rememberSaveable { mutableStateOf("home") }
    var selected by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tick) {
        now = System.currentTimeMillis()
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    when (screen) {
        "add" -> AddScreen(onBack = { screen = "home" }, onAdded = {
            selected = it
            screen = "ai"
        })
        "ai" -> AgentScreen(
            agentId = selected,
            now = now,
            onBack = { screen = "home" },
        )
        "phone" -> PhoneScreen(tick, onBack = { screen = "home" })
        else -> HomeScreen(now, onAdd = { screen = "add" }, onOpen = {
            selected = it
            screen = "ai"
        }, onPhone = { screen = "phone" })
    }
}

@Composable
private fun HomeScreen(now: Long, onAdd: () -> Unit, onOpen: (String) -> Unit, onPhone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val agents = ListenState.agents.value
    val tasks = ListenState.tasks.value
    val listening = ListenState.connected.value
    val phoneOk = Permissions.notificationsGranted(context) &&
        Permissions.fullScreenGranted(context) &&
        Permissions.batteryGranted(context) &&
        Permissions.dndGranted(context)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlarmPalette.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val donateUrl = BuildConfig.DONATE_URL
        if (donateUrl.isNotBlank()) {
            DonateBanner(donateUrl)
        }
        StatusRow(listening)
        Text(
            stringResource(R.string.ais),
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
        )
        if (agents.isEmpty()) {
            Text(
                stringResource(R.string.no_ais),
                color = AlarmPalette.Mute,
                fontFamily = AlarmFont,
                fontSize = 16.sp,
            )
        }
        agents.forEach { agent ->
            val mine = tasks.filter { it.agentId == agent.id }
            val waiting = mine.any { it.state == STATE_WAITING }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .clickable { onOpen(agent.id) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        agent.name,
                        color = AlarmPalette.White,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(if (agent.lastSeen <= 0L) R.string.waiting_for_bot else R.string.connected),
                        color = if (agent.lastSeen <= 0L) AlarmPalette.Mute else AlarmPalette.Accent,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
                if (waiting) {
                    Text(
                        stringResource(R.string.waiting),
                        color = AlarmPalette.Accent,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
            HorizontalDivider(color = AlarmPalette.Hairline, thickness = 1.dp)
        }
        Spacer(Modifier.height(8.dp))
        WhiteButton(stringResource(R.string.add), Modifier.fillMaxWidth(), onAdd)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onPhone),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.phone),
                modifier = Modifier.weight(1f),
                color = AlarmPalette.White,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                stringResource(if (phoneOk) R.string.on else R.string.needed),
                color = if (phoneOk) AlarmPalette.Accent else AlarmPalette.White,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun AddScreen(onBack: () -> Unit, onAdded: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlarmPalette.Black)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackRow(onBack)
        Text(
            stringResource(R.string.add),
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
        )
        Text(
            stringResource(R.string.add_hint),
            color = AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontSize = 16.sp,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = AlarmPalette.White, fontFamily = AlarmFont, fontSize = 22.sp),
            label = { Text(stringResource(R.string.name), fontFamily = AlarmFont) },
            colors = fieldColors(),
        )
        var createdId by rememberSaveable { mutableStateOf("") }
        WhiteButton(stringResource(R.string.connect_bot), Modifier.fillMaxWidth()) {
            val agent = ensureAgent(context, createdId, name)
            createdId = agent.id
            shareSetup(context, setupLine(agent.name, agent.secret, Prefs(context).server))
            onAdded(agent.id)
        }
        Text(
            stringResource(R.string.copy),
            modifier = Modifier.clickable {
                val agent = ensureAgent(context, createdId, name)
                createdId = agent.id
                val clip = context.getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(
                    ClipData.newPlainText(
                        "grokbot-alarm",
                        setupLine(agent.name, agent.secret, Prefs(context).server),
                    ),
                )
            },
            color = AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

private fun ensureAgent(context: android.content.Context, createdId: String, name: String): Agent {
    val prefs = Prefs(context)
    val existing = createdId.takeIf { it.isNotEmpty() }?.let { prefs.agent(it) }
    val agent = if (existing == null) {
        prefs.addAgent(name)
    } else {
        prefs.renameAgent(existing.id, name)
        prefs.agent(existing.id) ?: existing
    }
    ListenState.reload(prefs)
    if (Permissions.notificationsGranted(context)) ListenService.start(context)
    return agent
}

private fun shareSetup(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun AgentScreen(agentId: String, now: Long, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    var note by remember { mutableStateOf("") }
    val agent = ListenState.agents.value.find { it.id == agentId }
    val tasks = ListenState.tasks.value.filter { it.agentId == agentId }.sortedByDescending { it.registeredAt }
    LaunchedEffect(note) {
        if (note.isNotEmpty()) {
            delay(1600)
            note = ""
        }
    }
    if (agent == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlarmPalette.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackRow(onBack)
        Text(
            agent.name,
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
        )
        Text(
            stringResource(if (agent.lastSeen <= 0L) R.string.waiting_for_bot else R.string.connected),
            color = if (agent.lastSeen <= 0L) AlarmPalette.Mute else AlarmPalette.Accent,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        val line = setupLine(agent.name, agent.secret, prefs.server)
        if (agent.lastSeen <= 0L) {
            WhiteButton(stringResource(R.string.connect_bot), Modifier.fillMaxWidth()) {
                shareSetup(context, line)
            }
        }
        Text(
            if (note.isNotEmpty()) note else stringResource(R.string.copy),
            modifier = Modifier.clickable {
                val clip = context.getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(ClipData.newPlainText("grokbot-alarm", line))
                note = context.getString(R.string.copied)
            },
            color = AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.no_tasks),
                color = AlarmPalette.Mute,
                fontFamily = AlarmFont,
                fontSize = 16.sp,
            )
        }
        tasks.forEach { task ->
            val state = when (task.state) {
                STATE_WAITING -> stringResource(R.string.waiting)
                STATE_DONE -> stringResource(R.string.done)
                else -> stringResource(R.string.working)
            }
            val stateColor = when (task.state) {
                STATE_WAITING -> AlarmPalette.Accent
                STATE_DONE -> AlarmPalette.Mute
                else -> AlarmPalette.White
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(enabled = task.state == STATE_WAITING) {
                        context.startActivity(
                            Intent(context, AlarmActivity::class.java)
                                .putExtra(AlarmActivity.EXTRA, task.toPayload(agent.name).toJson())
                                .putExtra(AlarmActivity.EXTRA_QUIET, !task.ringing),
                        )
                    }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.bot,
                        modifier = Modifier.weight(1f),
                        color = AlarmPalette.White,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state,
                        color = stateColor,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    task.task,
                    color = AlarmPalette.Mute,
                    fontFamily = AlarmFont,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.question.isNotBlank() && task.state == STATE_WAITING) {
                    Text(
                        task.question,
                        color = AlarmPalette.White,
                        fontFamily = AlarmFont,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    agoLabel(task.registeredAt, now, listening = false),
                    color = AlarmPalette.Mute,
                    fontFamily = AlarmFont,
                    fontSize = 13.sp,
                )
            }
            HorizontalDivider(color = AlarmPalette.Hairline, thickness = 1.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.drop),
            modifier = Modifier.clickable {
                prefs.dropAgent(agent.id)
                ListenState.reload(prefs)
                if (prefs.agents().isNotEmpty() && Permissions.notificationsGranted(context)) {
                    ListenService.start(context)
                }
                onBack()
            },
            color = AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun PhoneScreen(tick: Int, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    var server by remember { mutableStateOf(prefs.server) }
    var note by remember { mutableStateOf("") }
    val notifOk = remember(tick) { Permissions.notificationsGranted(context) }
    val screenOk = remember(tick) { Permissions.fullScreenGranted(context) }
    val batteryOk = remember(tick) { Permissions.batteryGranted(context) }
    val dndOk = remember(tick) { Permissions.dndGranted(context) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlarmPalette.Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackRow(onBack)
        Text(
            stringResource(R.string.phone),
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
        )
        if (note.isNotEmpty()) {
            Text(note, color = AlarmPalette.White, fontFamily = AlarmFont, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = AlarmPalette.White, fontFamily = AlarmFont, fontSize = 18.sp),
            label = { Text(stringResource(R.string.server), fontFamily = AlarmFont) },
            colors = fieldColors(),
        )
        Text(
            stringResource(R.string.save),
            modifier = Modifier.clickable {
                val next = server.trim().trimEnd('/')
                if (!validServer(next)) {
                    note = context.getString(R.string.bad_url)
                } else {
                    prefs.server = next
                    server = prefs.server
                    note = context.getString(R.string.saved)
                    if (prefs.agents().isNotEmpty() && Permissions.notificationsGranted(context)) {
                        ListenService.start(context)
                    }
                }
            },
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Gate(stringResource(R.string.alerts), stringResource(R.string.alerts_hint), notifOk) {
            if (!notifOk && context is MainActivity) context.askNotifications()
            else Permissions.openNotifications(context)
        }
        Gate(stringResource(R.string.screen), stringResource(R.string.screen_hint), screenOk) {
            Permissions.openFullScreen(context)
        }
        Gate(stringResource(R.string.battery), stringResource(R.string.battery_hint), batteryOk) {
            Permissions.openBattery(context)
        }
        Gate(stringResource(R.string.dnd), stringResource(R.string.dnd_hint), dndOk) {
            Permissions.openDnd(context)
        }
        Spacer(Modifier.height(8.dp))
        WhiteButton(stringResource(R.string.test), Modifier.fillMaxWidth()) {
            val payload = AlarmPayload(
                id = "local-test",
                bot = "test",
                task = "local",
                question = "2 + 2",
                options = listOf("4", "5"),
                next = listOf("OK"),
                replyTopic = "",
                test = true,
            )
            context.startActivity(
                Intent(context, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA, payload.toJson()),
            )
        }
    }
}

@Composable
private fun DonateBanner(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, AlarmPalette.Hairline, RoundedCornerShape(14.dp))
            .clickable {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(view) }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.fuel),
            modifier = Modifier.weight(1f),
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Text(
            "→",
            color = AlarmPalette.Accent,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun StatusRow(listening: Boolean) {
    val status = stringResource(if (listening) R.string.ears_on else R.string.not_listening)
    val statusCd = stringResource(if (listening) R.string.cd_status_on else R.string.cd_status_off)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (listening) AlarmPalette.Accent else AlarmPalette.Mute)
                .semantics { contentDescription = statusCd },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            status,
            color = if (listening) AlarmPalette.Accent else AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Text(
        stringResource(R.string.ais),
        modifier = Modifier.clickable(onClick = onBack),
        color = AlarmPalette.Accent,
        fontFamily = AlarmFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
    )
}

@Composable
private fun WhiteButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AlarmPalette.White,
            contentColor = AlarmPalette.Black,
        ),
    ) {
        Text(
            label,
            color = AlarmPalette.Black,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun Gate(label: String, hint: String, ok: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (ok) R.string.on else R.string.needed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = AlarmPalette.White,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Text(
                state,
                color = if (ok) AlarmPalette.Accent else AlarmPalette.White,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Text(hint, color = AlarmPalette.Mute, fontFamily = AlarmFont, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = AlarmPalette.Hairline, thickness = 1.dp)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AlarmPalette.White,
    unfocusedTextColor = AlarmPalette.White,
    cursorColor = AlarmPalette.Accent,
    focusedBorderColor = AlarmPalette.White,
    unfocusedBorderColor = AlarmPalette.Hairline,
    focusedLabelColor = AlarmPalette.White,
    unfocusedLabelColor = AlarmPalette.Mute,
    focusedContainerColor = AlarmPalette.Black,
    unfocusedContainerColor = AlarmPalette.Black,
)
