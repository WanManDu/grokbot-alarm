# grokbot-alarm

Tell your bot:

```text
set up grokbot-alarm
```

The bot needs nothing installed. It follows [BOT.md](BOT.md) (the same steps are on [the project page](https://wanmandu.github.io/grokbot-alarm/)): check out the pinned commit, verify `checksums.sha256`, run `install.sh`, and reply with one `https://` link. Tap it. The phone asks **Connect NAME?** and shows the server host. That AI shows **Waiting for bot** until the bot's hello lands, then **Connected**. No copy, no paste.

The link looks like `https://wanmandu.github.io/grokbot-alarm/pair#s=…&srv=…&n=…`. The secret is in the fragment, so it is not sent to the web server. The page clears that fragment from history (`history.replaceState`) and sets `no-referrer`. It opens the app with a package-locked intent (`package=works.gauss.grokbotalarm`), not a bare `grokbot-alarm://` URL another app could steal. **Open app** and an install link stay on the page if the app is not there yet.

An alarm for when an AI is stuck on a human. The bot pairs itself; the human only taps the link. That AI registers tasks. When it needs an approval or the next step, it posts a needs-you. If the human doesn't answer in time, the phone escalates to a full alarm and will not stop ringing until they solve **one real decision** about that task, then send the next instruction. The answer goes back to that AI, which continues immediately.

The home screen is the list of AIs. Each AI has its own secret, so one chat cannot see another's tasks. Questions and options are shown in whatever language the AI sent. **Add** and **Connect bot** are the fallback when a chat app cannot receive the link.

```
phone                                 ntfy                              AI CLI
ListenService -- subscribe all ---->  gba + HMAC(secret)  <--- sealed register / needs / ring
AlarmActivity -- sealed answer ----->  gbr + HMAC(secret) ---> wait / poll
```

## Why ntfy, not FCM

The relay is [ntfy](https://ntfy.sh) (the public server by default, any self-hosted server if you change the URL).

FCM would need a Firebase project, a `google-services.json` that each person has to create, and a Google account before the first test. That fails the constraint this project is built around: no account, no paid infrastructure, something you can try the same day, and a server anyone can run themselves.

ntfy is HTTPS in both directions. The shared secret is a key. Topic ids are a one-way HMAC of that key, so seeing a topic does not reveal the secret. The phone keeps a foreground subscription because a self-hosted (or public) ntfy server does not deliver through FCM to a third-party app. The ongoing "Alarm" notification is that subscription. Exempt the app from battery optimization so the socket survives Doze. Debug APKs are signed with the committed debug key (`android` / `androiddebugkey`) so later debug builds install as upgrades. Release builds never use that key. See [Privacy and security](#privacy-and-security) and [Release signing](#release-signing).

Tradeoff, stated plainly: the phone must be allowed to keep that foreground service. There is no clock-time alarm, so the app does not request exact-alarm permission. The bot decides when the work is finished.

## Message protocol

Protocol v2. v1 is unsigned and both sides ignore it. Updating the app without updating the CLI (or the reverse) stops delivery until both are on v2. Re-run `install.sh` and open the pair link again. The same secret still names the same AI. The topics change, because they are no longer the secret with a prefix.

Topics are 43 characters. `gba` or `gbr`, then 40 hex chars of HMAC-SHA256(secret, `grokbot-alarm.v2.topic.inbox` or `…reply`). The secret is not recoverable from the topic.

| Topic | Who publishes | Who reads |
| --- | --- | --- |
| inbox, derived | CLI `register` and `ring` | phone |
| reply, derived | phone, after the next instruction | CLI `wait` / `poll` |

The reply destination is derived on each side. A `replyTopic` field in a payload is ignored.

On the wire, every body is a sealed envelope:

```json
{"v":2,"t":1700000000000,"n":"<base64url nonce>","c":"<base64url ciphertext>","m":"<base64url mac>"}
```

The plaintext is encrypted with HMAC-SHA256 in counter mode under a key from HKDF-SHA256, then authenticated with a second HMAC over the version, nonce, timestamp, and ciphertext. A timestamp older than 24 hours or more than 5 minutes in the future is rejected. Each nonce is remembered until it leaves that window. A full replay cache fails closed instead of forgetting a nonce. The phone also caps each AI at 8 accepted messages per minute and 4 ringing alarms at once.

The plaintext inside, which ntfy does not see, is JSON:

Register (the task shows as Working, no ring):

```json
{"v":2,"type":"register","id":"<id>","agent":"coder","bot":"coder","task":"Split the session store"}
```

Needs-you (notification now, full alarm after `escalateAfter` seconds if still unanswered):

```json
{"v":2,"type":"needs","id":"<id>","agent":"coder","bot":"coder","task":"Split the session store","question":"Sessions or signed tokens?","options":["Keep sessions","Signed tokens"],"next":["Open a PR","Stop"],"escalateAfter":120}
```

Ring now (`type` is `alarm`, no `escalateAfter`):

```json
{"v":2,"type":"alarm","id":"<id>","agent":"coder","bot":"coder","task":"Split the session store","question":"Sessions or signed tokens?","options":["Keep sessions","Signed tokens"],"next":["Open a PR","Stop"]}
```

Answer (stdout of `wait` / `poll`, after the CLI has checked the MAC):

```json
{"v":2,"type":"answer","id":"<id>","choice":"Switch to signed tokens","instruction":"Open a PR"}
```

`choice` is the decision that stopped the alarm. `instruction` is the next task, either a suggested option or free text.

If the reply topic has more than one authentic answer for the same id, the earliest one wins. `wait` and `poll` keep that first answer and ignore later duplicates, replays, and anything that fails the MAC.

## App install

No Android Studio. Every push and pull request uploads a workflow artifact named **grokbot-alarm-debug**, plus `BUILD.txt` that says it is a debug build. A `v*` tag attaches that debug APK to a GitHub Release. A minified non-debuggable release APK is attached only when the release keystore secrets exist. See [Release signing](#release-signing).

1. Download `grokbot-alarm-debug.apk` from the artifact (or from the Release, once one is tagged).
2. On the phone, open the APK and allow install from the browser or files app.
3. Open **Alarm**.
4. Grant the four gates. Each row opens the system page. **On** means granted, **Needed** means it is not.
   - **Alerts** — notifications, including the ongoing listener.
   - **Screen** — full-screen intents, so the alarm can appear over the lock screen with the screen off (Android 14+).
   - **Battery** — ignore battery optimizations, so the listener is not frozen while the phone sleeps.
   - **DND** — notification policy access. While the alarm is ringing the app switches interruption filter to all, which is as far as stock Android lets a normal app play through Do Not Disturb. The alarm stream (`USAGE_ALARM`) already plays when the ringer is silent.
5. The round dot at the top turns green when the listener is connected. An ongoing notification titled Alarm stays in the shade. That is expected.
6. With no AIs yet, the home screen says **Tell your bot: set up grokbot-alarm**. Say that to the bot. Tap the link it sends back, then **Connect**. **Add** is the small fallback: it still shares a setup block into another app. **Phone** holds Server, Alerts, Screen, Battery, DND, and Test.

**Test** plays a local alarm with no network. The question is `2 + 2`. Tap `4` (the sound stops) then `OK`. Nothing is sent.

The alarm screen shows the bot name and the task, then the question as large buttons. Answering stops the sound, vibration, and wake lock. The next screen is suggested instructions plus a one-line field. One tap sends a suggestion. The send icon sends typed text. Back does not dismiss the alarm.

If the alarm stream volume is under half, the app raises it for the duration of the ring and restores it afterward if it is still at the level the app set. Track volume ramps from quiet to full over about 30 seconds. The tone loops and the vibration pattern repeats until the question is answered.

minSdk 26, targetSdk 37. A force-stop in system settings kills the listener until the app is opened again. That is an Android rule, not a setting in this app.

### Self-host

Tell the bot the base URL. It passes `--server` to `install.sh`. The URL has to be `https://` (loopback `http://127.0.0.1` or `localhost` is the only cleartext exception, for a local test relay). The confirm screen shows the host. The first **Connect** adopts that URL and locks it. A later link does not switch the server. **Use host** on that screen is the explicit switch. Anyone who has the secret, including anyone who opens the pair link, can ring the phone and decrypt answers. The relay cannot.

### Pair page

GitHub Pages serves `docs/` at `https://wanmandu.github.io/grokbot-alarm/`. One setting, once: the repo **Settings → Pages → Deploy from a branch → `main` → `/docs`**. The pair page is `docs/pair/index.html`. It reads the fragment, then `history.replaceState` drops it, and it sends `Referrer-Policy: no-referrer`.

`docs/.well-known/assetlinks.json` carries the debug key fingerprint (`EF:36:26:2F:00:B4:CB:41:CE:0E:D2:AA:16:17:AE:A0:40:3E:0E:E3:8B:E4:0F:E3:E2:60:7F:73:A0:15:21:B4`, package `works.gauss.grokbotalarm`). Android only treats the https link as an App Link when that file is at `https://wanmandu.github.io/.well-known/assetlinks.json`. A project site cannot serve the host root. Until the owner publishes the same JSON there (and adds the release certificate fingerprint next to the debug one, after the release keystore exists), the https link opens the page, and the page opens a package-locked intent so a different app cannot register `grokbot-alarm://` and take the secret.

## Skill and CLI install

The human says `set up grokbot-alarm`. The bot follows [BOT.md](BOT.md): download the pinned commit, check `checksums.sha256` (and the checksum of that file, which BOT.md records), then `sh ./install.sh --agent "YOUR_NAME"`. There is no `curl | sh` from a moving branch.

`install.sh` copies the CLI and `SKILL.md` (or downloads them from a 40-character commit and rejects a checksum mismatch), puts `~/.local/bin/grokbot-alarm` on PATH, and runs `init` with no `--secret`. `init` generates the secret, publishes a sealed hello, and prints JSON with `v`, `agent`, and `pair` only. The secret and the topic ids are not printed. Stderr does not repeat the pair URL. The bot sends that URL.

Each AI gets its own file, `~/.config/grokbot-alarm/agents/<name>.json` (mode 0600). The config directory and `agents/` are mode 0700. Pass `--agent` or set `GROKBOT_ALARM_AGENT`. `GROKBOT_ALARM_HOME` overrides the config root. `GROKBOT_ALARM_SERVER` and `GROKBOT_ALARM_SECRET` override that agent's file for one process. `init` again reprints the same link.

The installed skill tells the bot when to register, how to write the one decision question, and to continue from `choice` and `instruction` without asking again. It also tells the bot to save the file as a persistent skill if the platform allows that.

Commands:

| Command | Stdout | Exit |
| --- | --- | --- |
| `init` | JSON with `v`, `agent`, `pair` only | 0, or 1 if the secret/URL is rejected |
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

Against the public relay, from the repo root (the "phone" here posts a sealed answer):

```bash
export GROKBOT_ALARM_HOME="$(mktemp -d)"
python3 cli/grokbot_alarm.py init >/dev/null
ID=$(python3 cli/grokbot_alarm.py register --bot demo --task "MVP check")
python3 cli/grokbot_alarm.py ring --id "$ID" \
  --question "Ship the split behind a flag?" \
  --option "Yes, flag it" --option "No, ship it" \
  --next "Run the tests" --next "Stop"
python3 - << 'PY'
import json, os, sys, urllib.request
from pathlib import Path
sys.path.insert(0, "cli")
import grokbot_alarm as gba
home = Path(os.environ["GROKBOT_ALARM_HOME"])
secret = json.loads((home / "agents" / "default.json").read_text())["secret"]
alarm = os.environ["ID"]
body = gba.seal(secret, json.dumps({"v":2,"type":"answer","id":alarm,"choice":"Yes, flag it","instruction":"Run the tests"}, separators=(",", ":")))
urllib.request.urlopen(urllib.request.Request("https://ntfy.sh/" + gba.reply_topic(secret), data=body.encode(), method="POST"), timeout=30).read()
PY
python3 cli/grokbot_alarm.py wait --id "$ID" --timeout 30
```

`wait` prints the answer JSON. `poll --id "$ID"` prints it again from the local cache. An unsigned body is ignored.

## Test on a real Android phone

1. Install the debug APK from the **grokbot-alarm-debug** artifact (Actions → the Android workflow → `android` → Artifacts). `BUILD.txt` next to it says it is a debug build. Allow the browser or Files app to install it. The confirm screen shows the server host under **Connect NAME?**.
2. Open the app and accept **Alerts** when asked.
3. Open **Phone**. Tap **Screen**, **Battery**, and **DND**, and accept each system page. **On** means granted. Screen has to be On or the activity cannot come up over a sleeping lock screen. Battery keeps the listener alive. DND lets the sound play through Do Not Disturb. **Test** on this screen fires a local alarm.
4. Tell the bot `set up grokbot-alarm`. Tap the https link it replies with and confirm **Connect NAME?**. The row stays **Waiting for bot** until that install's hello (or the first `register`) arrives, then it shows **Connected**. **Add → Connect bot** is the fallback when the chat cannot open the link. Then, for a quiet ask that escalates, use `--escalate-after 120` on `ring`. Omit it to ring immediately:

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

## Privacy and security

After protocol v2 the relay sees topic ids, message sizes, timestamps, and that someone published. It does not see the secret, the task name, the question, the options, or the answer. It cannot forge a message the other side will accept, and it cannot point the phone at a reply topic of its choosing. The phone and the CLI derive that topic from the secret.

What the relay, and anyone who can subscribe to a topic id, can still do: notice that an AI is talking, measure how often, and drop or delay ciphertext. Topic ids are not secret from the relay. They are secret from someone who does not already know them, and they are not the pairing secret.

The pair URL's fragment is the secret. The page host does not receive it. The page removes it from browser history and does not send a referrer. Anyone the human forwards that URL to can pair as that AI. Treat the URL like a password and do not paste it into logs.

The phone stores the secret with EncryptedSharedPreferences, backed by a Keystore key. A root user who can use the app's keys can still read it. The config file on the bot machine is mode 0600 and its directory is mode 0700.

Cleartext HTTP is off by default. The network security config allows it only for `localhost` and `127.0.0.1`. `validServer` matches that. A pair link cannot silently replace a server the phone already locked. The confirm screen shows the host. **Use host** is the explicit switch.

The phone drops unauthenticated, stale, and replayed messages. It also stops accepting messages for one AI after 8 in a minute, keeps at most 8 open tasks for that AI, and will not ring more than 4 alarms for that AI at once.

Report vulnerabilities through GitHub private vulnerability reporting. See [SECURITY.md](SECURITY.md). Do not file a public issue for a security bug.

## Release signing

Debug and release are different builds.

| | Debug | Release |
| --- | --- | --- |
| When | every CI run, and `./gradlew assembleDebug` | only when the four Actions secrets below are set |
| Debuggable | yes | no |
| Minify | no | yes, with resource shrinking |
| Key | committed `android/debug.keystore` (`android` / `androiddebugkey`) | a keystore that is never in git |
| Artifact | `grokbot-alarm-debug.apk` plus `BUILD.txt` saying it is a debug build | `grokbot-alarm-release.apk` |

The release build type does not use the debug keystore. If the secrets are absent, CI still builds the debug APK and the label says so. A tag in that state attaches the debug APK and says it is not a release build.

Create the keystore on a machine you control. Do not commit it.

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias grokbot \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.keystore
```

GitHub → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 from the command above, one line |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | `grokbot`, or whichever alias you used |
| `ANDROID_KEY_PASSWORD` | the key password |

After the first signed release, add that certificate's SHA-256 fingerprint to `docs/.well-known/assetlinks.json` next to the debug fingerprint, and publish the file at the host root. That step is on the owner. This repository cannot turn on GitHub Pages or App Links by itself.

## Build

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
```

`assembleRelease` without `ANDROID_KEYSTORE_PATH` produces an unsigned release APK. It is still minified and not debuggable, and it is not signed with the debug key.

## Support

The home screen shows **Fuel the bot.** It opens [GitHub Sponsors for WanManDu](https://github.com/sponsors/WanManDu). No in-app purchases. The Sponsors profile may 404 until GitHub approves it. The same link is `DONATE_URL` in `android/gradle.properties` and `github: [WanManDu]` in `.github/FUNDING.yml`. Set `DONATE_URL` empty to hide the button.

## License

MIT, copyright GaussWorks.
