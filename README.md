# grokbot-alarm

An alarm for people building with [Grok Bot](https://x.ai) who keep working until they fall asleep. The bot registers a wake, rings the phone when the task actually finishes, and does not stop ringing until the person answers **one real decision** about that task. They then tap or type the next instruction. That answer goes back to the same bot, which continues immediately.

The UI is icon-first. The few English words are permission names and a test button. Questions and options are shown in whatever language the bot sent.

```
phone app                         ntfy                          bot CLI
ListenService  -- subscribe ---->  gba<secret>  <--- register / ring
AlarmActivity  -- post answer -->  gbr<secret>  ---> wait / poll
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

Register (silent; the phone lists it, it does not ring):

```json
{"v":1,"type":"register","id":"<id>","bot":"coder","task":"Split the session store"}
```

Ring:

```json
{"v":1,"type":"alarm","id":"<id>","bot":"coder","task":"Split the session store","question":"Keep server sessions or switch to signed tokens?","options":["Keep sessions","Switch to signed tokens"],"next":["Open a PR","Stop"],"replyTopic":"gbr<secret>"}
```

Answer (stdout of `wait` / `poll`):

```json
{"v":1,"type":"answer","id":"<id>","choice":"Switch to signed tokens","instruction":"Open a PR"}
```

`choice` is the decision that stopped the alarm. `instruction` is the next task, either a suggested option or free text.

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
6. Tap **Copy**. The clipboard gets one line: `Set up grokbot-alarm with secret <secret> on server <server>`. Paste that into the bot chat. The bot runs `init` with those two values. **Save** writes the same fields without copying. You can also paste a secret into **Secret** and a URL into **Server**, then Save.

**Test** plays a local alarm with no network. The question is `2 + 2`. Tap `4` (the sound stops) then `OK`. Nothing is sent.

The alarm screen shows the bot name and the task, then the question as large buttons. Answering stops the sound, vibration, and wake lock. The next screen is suggested instructions plus a one-line field. One tap sends a suggestion. The send icon sends typed text. Back does not dismiss the alarm.

If the alarm stream volume is under half, the app raises it for the duration of the ring and restores it afterward if it is still at the level the app set. Track volume ramps from quiet to full over about 30 seconds. The tone loops and the vibration pattern repeats until the question is answered.

minSdk 26, targetSdk 37. A force-stop in system settings kills the listener until the app is opened again. That is an Android rule, not a setting in this app.

### Self-host

Run ntfy, put its base URL in **Server**, tap **Save**, then **Copy** and paste the line into the bot chat. `http://` is allowed for a LAN server. Topics stay open; the secret is the only access control. Anyone who has the secret can ring the phone and read answers.

## Skill and CLI install

On the bot machine, from a clone of this repo, either paste the phone's Copy line into the bot (it should run the command below) or run it yourself:

```bash
python3 cli/grokbot_alarm.py init --secret SECRET --server https://ntfy.sh
```

That writes `~/.config/grokbot-alarm/config.json` (mode 0600). `GROKBOT_ALARM_HOME` overrides the directory. `GROKBOT_ALARM_SERVER` and `GROKBOT_ALARM_SECRET` override the file for one process.

Give the bot `skill/SKILL.md` the same way its other skills are installed (copy the file into the skills directory the bot reads). The skill tells it when to register, how to write the one decision question, and to continue from `choice` and `instruction` without asking again.

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
3. Tap **Screen**, **Battery**, and **DND**, and accept each system page. Screen has to show a check or the activity cannot come up over a sleeping lock screen. Battery keeps the listener alive. DND lets the sound play through Do Not Disturb.
4. Wait until the dot is green. Leave the Alarm notification in the shade.
5. Tap **Copy** and paste the line into the bot chat. It reads `Set up grokbot-alarm with secret … on server …` and runs `init --secret … --server …`. Or run that yourself. Then:

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

6. Lock the phone and turn the screen off. It should wake and ring, getting louder, including while the ringer is silent. On screen, in this order: a short wake-up line, the bot name, the task, then the question in large white type, then the answer buttons. If you only see the buttons, the build is wrong.
7. Tap one answer. The sound stops. Tap a next instruction, or type a short one and tap the send icon.
8. The CLI prints one JSON line with `choice` and `instruction`. A bot following `skill/SKILL.md` applies the choice and starts the instruction without asking again.

Register on its own does not ring. The setup screen lists the armed bot and task until the ring arrives. **Test** checks the ringer locally if you want to confirm sound and the two steps before involving the network.

If a second ring arrives while the first alarm is still up, it waits and starts after the first answer is sent.

## Build

```bash
cd android
./gradlew assembleDebug
```

The debug APK is signed with `android/debug.keystore` (password `android`, alias `androiddebugkey`). It is a public debug key so CI upgrades install over each other. It is not a release identity.

## License

MIT, copyright GaussWorks.
