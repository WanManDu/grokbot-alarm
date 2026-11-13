package works.gauss.grokbotalarm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder

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
    val notifOk = remember(tick) { Permissions.notificationsGranted(context) }
    val screenOk = remember(tick) { Permissions.fullScreenGranted(context) }
    val batteryOk = remember(tick) { Permissions.batteryGranted(context) }
    val dndOk = remember(tick) { Permissions.dndGranted(context) }
    val connected = ListenState.connected.value
    val pending = ListenState.pending.value
    LaunchedEffect(tick) {
        ListenState.replacePending(prefs.pending())
    }

    fun save() {
        val nextSecret = secret.trim()
        val nextServer = server.trim().trimEnd('/')
        if (!validSecret(nextSecret) || !validServer(nextServer)) return
        prefs.savePairing(nextServer, nextSecret)
        secret = prefs.secret
        server = prefs.server
        ListenState.replacePending(prefs.pending())
        if (Permissions.notificationsGranted(context)) ListenService.start(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_stat_bell),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.weight(1f))
            val statusCd = stringResource(if (connected) R.string.cd_status_on else R.string.cd_status_off)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (connected) Color(0xFF3DDC97) else Color(0xFF5C584F))
                    .semantics { contentDescription = statusCd },
            )
        }

        pending.forEach { wake ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_moon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(wake.bot, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(wake.task, fontSize = 16.sp, color = Color(0xFFA9A49A), maxLines = 2)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it.filterNot { ch -> ch.isWhitespace() } },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = secret.isNotEmpty() && !validSecret(secret),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                label = { Text(stringResource(R.string.secret)) },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    save()
                    val clip = context.getSystemService(ClipboardManager::class.java)
                    clip.setPrimaryClip(ClipData.newPlainText("secret", prefs.secret))
                },
                modifier = Modifier.size(72.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.cd_copy),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        val qrText = if (validSecret(secret.trim()) && validServer(server.trim())) {
            pairingUri(server.trim().trimEnd('/'), secret.trim())
        } else {
            null
        }
        if (qrText != null) {
            val image = remember(qrText) { qrBitmap(qrText) }
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.secret),
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.CenterHorizontally),
            )
            SelectionContainer {
                Text(
                    text = inboxTopic(secret.trim()) + "\n" + replyTopic(secret.trim()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFA9A49A),
                )
            }
        }

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = server.isNotBlank() && !validServer(server.trim()),
            textStyle = TextStyle(fontSize = 18.sp),
            label = { Text(stringResource(R.string.server)) },
        )
        Button(
            onClick = { save() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.cd_save),
                modifier = Modifier.size(28.dp),
            )
        }

        Gate(R.drawable.ic_stat_bell, stringResource(R.string.alerts), notifOk) {
            if (!notifOk && context is MainActivity) context.askNotifications()
            else Permissions.openNotifications(context)
        }
        Gate(R.drawable.ic_screen, stringResource(R.string.screen), screenOk) {
            Permissions.openFullScreen(context)
        }
        Gate(R.drawable.ic_battery, stringResource(R.string.battery), batteryOk) {
            Permissions.openBattery(context)
        }
        Gate(R.drawable.ic_moon, stringResource(R.string.dnd), dndOk) {
            Permissions.openDnd(context)
        }

        Button(
            onClick = {
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
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_stat_bell),
                contentDescription = stringResource(R.string.test),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.test), fontSize = 24.sp)
        }
    }
}

@Composable
private fun Gate(icon: Int, label: String, ok: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (ok) Color(0xFF243028) else MaterialTheme.colorScheme.primary,
            contentColor = if (ok) Color(0xFF3DDC97) else MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 22.sp, modifier = Modifier.weight(1f))
        if (ok) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.cd_ok),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun pairingUri(server: String, secret: String): String {
    val encoded = URLEncoder.encode(server, "UTF-8")
    return "gba://pair?v=1&server=$encoded&secret=$secret"
}

private fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val size = 512
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) 0xFF1A1200.toInt() else 0xFFFFB020.toInt())
        }
    }
    return bitmap.asImageBitmap()
}
