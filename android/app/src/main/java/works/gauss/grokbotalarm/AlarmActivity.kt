package works.gauss.grokbotalarm

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOnOverLock()
        val payload = AlarmPayload.fromJson(intent.getStringExtra(EXTRA) ?: "")
        if (payload == null) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )
        Ringer.start(this, payload.id)
        setContent {
            AlarmTheme {
                AlarmScreen(payload)
            }
        }
    }

    private fun turnScreenOnOverLock() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA = "payload"
    }
}

@Composable
private fun AlarmScreen(payload: AlarmPayload) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(1) }
    var choice by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun silence() {
        Ringer.stop(payload.id)
        if (!payload.test) {
            ListenService.signal(context, ListenService.ACTION_SILENCE, payload.id)
        }
    }

    fun send(instruction: String) {
        val text = instruction.trim()
        if (text.isEmpty() || sending) return
        if (payload.test) {
            (context as? Activity)?.finish()
            return
        }
        sending = true
        failed = false
        val server = Prefs(context).server
        val body = JSONObject()
            .put("v", 1)
            .put("type", "answer")
            .put("id", payload.id)
            .put("choice", choice)
            .put("instruction", text)
            .toString()
        Thread {
            val ok = runCatching {
                Ntfy.publish(server, payload.replyTopic, body, 4)
            }.isSuccess
            Handler(Looper.getMainLooper()).post {
                val activity = context as? Activity
                if (activity == null || activity.isDestroyed) return@post
                sending = false
                if (!ok) {
                    failed = true
                    return@post
                }
                ListenService.signal(context, ListenService.ACTION_ANSWERED, payload.id)
                activity.finish()
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (step == 1) {
            val pulse = rememberInfiniteTransition(label = "bell")
            val scale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                label = "bell-scale",
            )
            Icon(
                painterResource(R.drawable.ic_stat_bell),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(72.dp)
                    .scale(scale)
                    .align(Alignment.CenterHorizontally),
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.cd_ok),
                tint = Color(0xFF3DDC97),
                modifier = Modifier
                    .size(84.dp)
                    .align(Alignment.CenterHorizontally),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_bot),
                contentDescription = stringResource(R.string.cd_bot),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(payload.bot, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painterResource(R.drawable.ic_task),
                contentDescription = stringResource(R.string.cd_task),
                modifier = Modifier.size(22.dp).padding(top = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(payload.task, fontSize = 18.sp, color = Color(0xFFA9A49A), lineHeight = 24.sp)
        }

        if (step == 1) {
            Text(payload.question, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
            payload.options.forEach { option ->
                ChoiceButton(option) {
                    choice = option
                    silence()
                    step = 2
                }
            }
        } else {
            if (choice.isNotEmpty()) {
                Text(choice, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            }
            payload.next.forEach { option ->
                ChoiceButton(option, enabled = !sending) { send(option) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 140) draft = it.replace("\n", " ") },
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 22.sp),
                    placeholder = { Text(stringResource(R.string.next_hint), fontSize = 22.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(draft) }),
                    enabled = !sending,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { send(draft) },
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.size(84.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_send),
                            contentDescription = stringResource(R.string.cd_send),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            textAlign = TextAlign.Center,
        )
    }
}
