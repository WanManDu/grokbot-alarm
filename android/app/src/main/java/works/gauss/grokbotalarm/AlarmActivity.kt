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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

private val WakeLines = listOf(
    "Up. The work isn't.",
    "You dozed. It didn't.",
    "Eyes open. Decide.",
    "Nap's over. Pick one.",
    "Still here. You in?",
    "Clock's running. Choose.",
    "Wake up. Ship it.",
    "Dream later. This is the call.",
)

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
    val question = payload.question.ifBlank { "…" }
    val questionCd = stringResource(R.string.cd_question)

    fun silence() {
        Ringer.stop(payload.id)
        if (!payload.test) {
            ListenService.signal(context, ListenService.ACTION_SILENCE, payload.id)
        }
    }

    fun send(instruction: String) {
        // Typos, emoji, any language. The only quiet no-op is a blank field.
        val text = instruction.trim().take(1500)
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
            .background(AlarmPalette.Black)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (step == 1) {
            WakeLine()
        } else {
            Text(
                stringResource(R.string.your_move),
                color = AlarmPalette.Accent,
                fontFamily = AlarmFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Text(
            text = payload.bot.ifBlank { "—" },
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            lineHeight = 32.sp,
        )
        Text(
            text = payload.task.ifBlank { "—" },
            color = AlarmPalette.Mute,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 24.sp,
        )
        Text(
            text = question,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$questionCd. $question" },
            color = AlarmPalette.White,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 36.sp,
            lineHeight = 42.sp,
        )

        if (step == 1) {
            payload.options.forEach { option ->
                ChoiceButton(option) {
                    choice = option
                    silence()
                    step = 2
                }
            }
        } else {
            if (choice.isNotEmpty()) {
                Text(
                    choice,
                    color = AlarmPalette.White,
                    fontFamily = AlarmFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
            }
            payload.next.forEach { option ->
                ChoiceButton(option, enabled = !sending) { send(option) }
            }
            if (failed) {
                Text(
                    stringResource(R.string.didnt_land),
                    color = AlarmPalette.White,
                    fontFamily = AlarmFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1500) },
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AlarmPalette.White,
                        fontFamily = AlarmFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 22.sp,
                    ),
                    placeholder = {
                        Text(
                            stringResource(R.string.next_hint),
                            color = AlarmPalette.Mute,
                            fontFamily = AlarmFont,
                            fontSize = 18.sp,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(draft) }),
                    enabled = !sending,
                    colors = fieldColors(),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { send(draft) },
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.size(84.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AlarmPalette.White,
                        contentColor = AlarmPalette.Black,
                        disabledContainerColor = AlarmPalette.Hairline,
                        disabledContentColor = AlarmPalette.Mute,
                    ),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = AlarmPalette.Black,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_send),
                            contentDescription = stringResource(R.string.cd_send),
                            modifier = Modifier.size(28.dp),
                            tint = Color.Unspecified,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeLine() {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3400)
            index = (index + 1) % WakeLines.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(420)) },
        label = "wake-line",
    ) { i ->
        Text(
            WakeLines[i],
            color = AlarmPalette.Accent,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun ChoiceButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AlarmPalette.White,
            contentColor = AlarmPalette.Black,
            disabledContainerColor = AlarmPalette.Hairline,
            disabledContentColor = AlarmPalette.Mute,
        ),
    ) {
        Text(
            text = label,
            color = AlarmPalette.Black,
            fontFamily = AlarmFont,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AlarmPalette.White,
    unfocusedTextColor = AlarmPalette.White,
    disabledTextColor = AlarmPalette.Mute,
    cursorColor = AlarmPalette.Accent,
    focusedBorderColor = AlarmPalette.White,
    unfocusedBorderColor = AlarmPalette.Hairline,
    focusedContainerColor = AlarmPalette.Black,
    unfocusedContainerColor = AlarmPalette.Black,
)
