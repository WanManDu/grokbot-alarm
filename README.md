# grokbot-alarm

An alarm for when an AI is stuck on a human. The person pairs each AI (a Grok Bot, or any other agent on a Linux box). That AI registers tasks. When it needs an approval or the next step, it posts a needs-you. If the human doesn't answer in time, the phone escalates to a full alarm and will not stop ringing until they solve **one real decision** about that task, then send the next instruction. The answer goes back to that AI, which continues immediately.

The home screen is the list of Connected AIs. Each AI has its own secret, so one chat cannot see another's tasks. Questions and options are shown in whatever language the AI sent.

```
phone                                 ntfy                              AI CLI
ListenService -- subscribe all ---->  gba<secret per AI>  <--- register / needs / ring
AlarmActivity -- post answer ------>  gbr<secret per AI>  ---> wait / poll
```

## Why ntfy, not FCM

The relay is [ntfy](https://ntfy.sh) (the public server by default, any self-hosted server if you change the URL).

FCM would need a Firebase project, a `google-services.json` that each person has to create, and a Google account before the first test. That fails the constraint this project is built around: no account, no paid infrastructure, something you can try the same day, and a server anyone can run themselves.

ntfy is HTTP in both directions. A long random topic name is the shared secret. The phone keeps a foreground subscription because a self-hosted (or public) ntfy server does not deliver through FCM to a third-party app. The ongoing "Alarm" notification is that subscription. Exempt the app from battery optimization so the socket survives Doze. The debug APK is signed with the committed debug key (`android` / `androiddebugkey`) so later CI builds can be installed as upgrades.

Tradeoff, stated plainly: the phone must be allowed to keep that foreground service. There is no clock-time alarm, so the app does not request exact-alarm permission. The bot decides when the work is finished.

## Message protocol

Topics, each 1–64 characters, derived from a 32-hex-char secret:

| Topic | Who publishes | Who reads |
| --- | --- | --- |
| `gba<secret>` | CLI `register` and `ring` | phone |
| `gbr<secret>` | phone, after the next instruction | CLI `wait` / `poll` |

Bodies are JSON, version 1.

Register (the task shows as Working, no ring):

```json
{"v":1,"type":"register","id":"<id>","agent":"coder","bot":"coder","task":"Split the session store"}
```

Needs-you (notification now, full alarm after `escalateAfter` seconds if still unanswered):

```json
{"v":1,"type":"needs","id":"<id>","agent":"coder","bot":"coder","task":"Split the session store","question":"Sessions or signed tokens?","options":["Keep sessions","Signed tokens"],"next":["Open a PR","Stop"],"replyTopic":"gbr<secret>","escalateAfter":120}
```

Ring now (same body as needs, `type` is `alarm`, no `escalateAfter`):

```json
{"v":1,"type":"alarm","id":"<id>","agent":"coder","bot":"coder","task":"Split the session store","question":"Sessions or signed tokens?","options":["Keep sessions","Signed tokens"],"next":["Open a PR","Stop"],"replyTopic":"gbr<secret>"}
```

Answer (stdout of `wait` / `poll`):

```json
{"v":1,"type":"answer","id":"<id>","choice":"Switch to signed tokens","instruction":"Open a PR"}
```

`choice` is the decision that stopped the alarm. `instruction` is the next task, either a suggested option or free text.

If the reply topic has more than one answer for the same id, the earliest one wins. `wait` and `poll` keep that first answer and ignore later duplicates, including ones that show up in the same poll.

## App install

No Android Studio. The debug APK is a workflow artifact named **grokbot-alarm-debug** on every push and pull request (Actions → Android → `debug-apk`). A `v*` tag also attaches that APK to a GitHub Release.

1. Download `grokbot-alarm-debug.apk` from the artifact (or from the Release, once one is tagged).
2. On the phone, open the APK and allow install from the browser or files app.
3. Open **Alarm**.
4. Grant the four gates. Each row opens the system page. **On** means granted, **Needed** means it is not.
   - **Alerts** — notifications, including the ongoing listener.
   - **Screen** — full-screen intents, so the alarm can appear over the lock screen with the screen off (Android 14+).
   - **Battery** — ignore battery optimizations, so the listener is not frozen while the phone sleeps.
   - **DND** — notification policy access. While the alarm is ringing the app switches interruption filter to all, which is as far as stock Android lets a normal app play through Do Not Disturb. The alarm stream (`USAGE_ALARM`) already plays when the ringer is silent.
5. The round dot at the top turns green when the listener is connected. An ongoing notification titled Alarm stays in the shade. That is expected.
6. Tap **Add**, name that AI, then **Copy**. The clipboard gets one line: `Set up grokbot-alarm with secret <secret> on server <server> as <name>`. Paste it into that AI's chat. The bot runs `init --secret … --server … --agent …`. Repeat Add for each AI. **Phone** holds Server, Alerts, Screen, Battery, DND, and Test.

**Test** plays a local alarm with no network. The question is `2 + 2`. Tap `4` (the sound stops) then `OK`. Nothing is sent.

The alarm screen shows the bot name and the task, then the question as large buttons. Answering stops the sound, vibration, and wake lock. The next screen is suggested instructions plus a one-line field. One tap sends a suggestion. The send icon sends typed text. Back does not dismiss the alarm.

If the alarm stream volume is under half, the app raises it for the duration of the ring and restores it afterward if it is still at the level the app set. Track volume ramps from quiet to full over about 30 seconds. The tone loops and the vibration pattern repeats until the question is answered.

minSdk 26, targetSdk 37. A force-stop in system settings kills the listener until the app is opened again. That is an Android rule, not a setting in this app.

### Self-host

Run ntfy, put its base URL in **Server**, tap **Save**, then **Copy** and paste the line into the bot chat. `http://` is allowed for a LAN server. Topics stay open; the secret is the only access control. Anyone who has the secret can ring the phone and read answers.

## Skill and CLI install

Paste the phone's **Copy** block into the bot. A bot with no prior context can run it. The block curls `install.sh` from `main`, which downloads the CLI and `SKILL.md`, puts `~/.local/bin/grokbot-alarm` on PATH, and runs `init`. Until this branch is merged, that raw `main` URL 404s. `install.sh` itself then tries this branch. The pasted line does not, so it only works for other people after merge.

Each Connected AI gets its own file, `~/.config/grokbot-alarm/agents/<name>.json` (mode 0600), so bots that share a machine do not overwrite each other. Pass `--agent` or set `GROKBOT_ALARM_AGENT`. `GROKBOT_ALARM_HOME` overrides the config root. `GROKBOT_ALARM_SERVER` and `GROKBOT_ALARM_SECRET` override that agent's file for one process.

The installed skill tells the bot when to register, how to write the one decision question, and to continue from `choice` and `instruction` without asking again. It also tells the bot to save the file as a persistent skill if the platform allows that.

Commands:

| Command | Stdout | Exit |
| --- | --- | --- |
| `init` | JSON with `server`, `secret`, `inbox`, `reply` | 0, or 1 if the secret/URL is rejected |
| `register --bot --task` | alarm id | 0, or 1 on publish failure |
| `ring --id --question --option … --next …` | nothing (a note on stderr) | 0 or 1 |
| `wait --id --timeout SECONDS` | answer JSON | 0 answered, 2 timeout, 1 error |
| `poll --id` | answer JSON | 0 answered, 2 not yet, 1 error |

`wait --timeout 0` is the same as `poll`. Default timeout is 600 seconds, maximum 86400. A later poll prints an answer this machine already received, even if ntfy has dropped it. ntfy.sh keeps messages for about 12 hours, which is also how far back a newly installed phone looks.

## Quick test without a phone

The CLI tests in `cli/test_grokbot_alarm.py` run against a local fake relay:

```bash
python3 -m unittest cli/test_grokbot_alarm.py
```

Against the public relay, from the repo root (this is what a bot will do; the "phone" here is a curl that posts the answer):

```bash
export GROKBOT_ALARM_HOME="$(mktemp -d)"
python3 cli/grokbot_alarm.py init > "$GROKBOT_ALARM_HOME/out.json"
SECRET=$(python3 -c 'import json,os; print(json.load(open(os.environ["GROKBOT_ALARM_HOME"]+"/out.json"))["secret"])')
REPLY=$(python3 -c 'import json,os; print(json.load(open(os.environ["GROKBOT_ALARM_HOME"]+"/out.json"))["reply"])')
ID=$(python3 cli/grokbot_alarm.py register --bot demo --task "MVP check")
python3 cli/grokbot_alarm.py ring --id "$ID" \
  --question "Ship the split behind a flag?" \
  --option "Yes, flag it" --option "No, ship it" \
  --next "Run the tests" --next "Stop"
curl -s -d "{\"v\":1,\"type\":\"answer\",\"id\":\"$ID\",\"choice\":\"Yes, flag it\",\"instruction\":\"Run the tests\"}" \
  "https://ntfy.sh/$REPLY"
python3 cli/grokbot_alarm.py wait --id "$ID" --timeout 30
```

`wait` prints the answer JSON. `poll --id "$ID"` prints it again from the local cache.

## Test on a real Android phone

1. Install the debug APK from the **grokbot-alarm-debug** artifact on this branch (Actions → the Android workflow → `debug-apk` → Artifacts). Allow the browser or Files app to install it.
2. Open the app and accept **Alerts** when asked.
3. Open **Phone**. Tap **Screen**, **Battery**, and **DND**, and accept each system page. **On** means granted. Screen has to be On or the activity cannot come up over a sleeping lock screen. Battery keeps the listener alive. DND lets the sound play through Do Not Disturb. **Test** on this screen fires a local alarm.
4. Back on the home screen, tap **Add**, type a name, tap **Connect bot**, and pick the bot's app in the share sheet. The shared text is the install block (curl `install.sh`). The AI stays **Waiting for bot** until that install's `init` (or the first `register`) reaches the phone, then it shows **Connected**. **Copy** is the small fallback. Then, for a quiet ask that escalates, use `--escalate-after 120` on `ring`. Omit it to ring immediately:

```bash
ID=$(python3 cli/grokbot_alarm.py register --bot "coder" --task "Migrate the session store")
python3 cli/grokbot_alarm.py ring --id "$ID" \
  --question "Keep server sessions or switch to signed tokens?" \
  --option "Keep sessions" \
  --option "Switch to signed tokens" \
  --next "Open a PR" \
  --next "Run the tests" \
  --next "Stop"
python3 cli/grokbot_alarm.py wait --id "$ID" --timeout 7200
```

5. Lock the phone and turn the screen off. It should wake and ring, getting louder, including while the ringer is silent. On screen, in this order: a short wake-up line, the bot name, the task, then the question in large white type, then the answer buttons. If you only see the buttons, the build is wrong.
6. Tap one answer. The sound stops. Tap a next instruction, or type a short one and tap the send icon.
7. The CLI prints one JSON line with `choice` and `instruction`. A bot following `skill/SKILL.md` applies the choice and starts the instruction without asking again.

`register` alone does not ring. The AI's screen lists the task as Working until `ring`. With `--escalate-after`, it shows as Your move and posts a notification first; the alarm starts only if you don't answer. **Test** under Phone checks the ringer with no network.

If a second ring arrives while the first alarm is still up, it waits and starts after the first answer is sent.

## Build

```bash
cd android
./gradlew assembleDebug
```

The debug APK is signed with `android/debug.keystore` (password `android`, alias `androiddebugkey`). It is a public debug key so CI upgrades install over each other. It is not a release identity.

## Support

The home screen shows **Fuel the bot.** It opens [GitHub Sponsors for WanManDu](https://github.com/sponsors/WanManDu). No in-app purchases. The Sponsors profile may 404 until GitHub approves it. The same link is `DONATE_URL` in `android/gradle.properties` and `github: [WanManDu]` in `.github/FUNDING.yml`. Set `DONATE_URL` empty to hide the button.

## License

MIT, copyright GaussWorks.
