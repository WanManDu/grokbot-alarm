package works.gauss.grokbotalarm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        if (Permissions.notificationsGranted(this)) ListenService.start(this)
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
        prefs.ensureSecret()
        ListenState.replacePending(prefs.pending())
        setContent {
            AlarmTheme {
                SetupScreen(tick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        when {
            Permissions.notificationsGranted(this) -> ListenService.start(this)
            !askedNotifications && Build.VERSION.SDK_INT >= 33 -> {
                askedNotifications = true
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun SetupScreen(tick: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }
    var secret by remember { mutableStateOf(prefs.secret) }
    var server by remember { mutableStateOf(prefs.server) }
    var note by remember { mutableStateOf("") }
    val notifOk = remember(tick) { Permissions.notificationsGranted(context) }
    val screenOk = remember(tick) { Permissions.fullScreenGranted(context) }
    val batteryOk = remember(tick) { Permissions.batteryGranted(context) }
    val dndOk = remember(tick) { Permissions.dndGranted(context) }
    val connected = ListenState.connected.value
    val pending = ListenState.pending.value
    LaunchedEffect(tick) {
        ListenState.replacePending(prefs.pending())
    }
    LaunchedEffect(note) {
        if (note.isNotEmpty()) {
            delay(1600)
            note = ""
        }
    }

    fun save(): Boolean {
        val nextSecret = secret.trim()
        val nextServer = server.trim().trimEnd('/')
        if (!validSecret(nextSecret)) {
            note = context.getString(R.string.secret_short)
            return false
        }
        if (!validServer(nextServer)) {
            note = context.getString(R.string.bad_url)
            return false
        }
        prefs.savePairing(nextServer, nextSecret)
        secret = prefs.secret
        server = prefs.server
        ListenState.replacePending(prefs.pending())
        if (Permissions.notificationsGranted(context)) ListenService.start(context)
        return true
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            val status = stringResource(if (connected) R.string.ears_on else R.string.not_listening)
            val statusCd = stringResource(if (connected) R.string.cd_status_on else R.string.cd_status_off)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) AlarmPalette.Accent else AlarmPalette.Mute)
                    .semantics { contentDescription = statusCd },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                status,
                color = if (connected) AlarmPalette.Accent else AlarmPalette.Mute,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        if (pending.isEmpty()) {
            Text(
                stringResource(R.string.no_wakes),
                color = AlarmPalette.Mute,
                fontFamily = AlarmFont,
                fontSize = 16.sp,
            )
        } else {
            pending.forEach { wake ->
                Column {
                    Text(
                        wake.bot,
                        color = AlarmPalette.White,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                    )
                    Text(
                        wake.task,
                        color = AlarmPalette.Mute,
                        fontFamily = AlarmFont,
                        fontSize = 16.sp,
                        maxLines = 2,
                    )
                }
            }
        }

        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it.filterNot { ch -> ch.isWhitespace() } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(
                color = AlarmPalette.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            ),
            label = { Text(stringResource(R.string.secret), fontFamily = AlarmFont) },
            colors = fieldColors(),
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(
                color = AlarmPalette.White,
                fontFamily = AlarmFont,
                fontSize = 18.sp,
            ),
            label = { Text(stringResource(R.string.server), fontFamily = AlarmFont) },
            colors = fieldColors(),
        )
        WhiteButton(stringResource(R.string.copy), Modifier.fillMaxWidth()) {
            if (save()) {
                val line = setupLine(prefs.secret, prefs.server)
                val clip = context.getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(ClipData.newPlainText("grokbot-alarm", line))
                note = context.getString(R.string.copied)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (note.isNotEmpty()) note else stringResource(R.string.paste_hint),
                modifier = Modifier.weight(1f),
                color = if (note.isNotEmpty()) AlarmPalette.White else AlarmPalette.Mute,
                fontFamily = AlarmFont,
                fontWeight = if (note.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp,
            )
            Text(
                stringResource(R.string.save),
                modifier = Modifier.clickable {
                    if (save()) note = context.getString(R.string.saved)
                },
                color = AlarmPalette.White,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

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
                Intent(context, AlarmActivity::class.java)
                    .putExtra(AlarmActivity.EXTRA, payload.toJson()),
            )
        }
    }
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
        Text(
            hint,
            color = AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontSize = 15.sp,
        )
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

private fun setupLine(secret: String, server: String): String {
    return "Set up grokbot-alarm with secret $secret on server $server"
}
