# grokbot-alarm

The human asked you to set up grokbot-alarm. You have nothing installed. Do exactly this.

1. Do not call ntfy. Do not POST to ntfy.sh. The secret is not an auth token and it is not a topic name. Do not put it in an Authorization header. Do not invent a topic. The only publisher is the command this installer creates.

2. Do not pipe a script into a shell. Check out the pinned commit, verify the checksum file, then run the installer that is in that tree.

`REV` and `SUMS_SHA` below are filled in when this file is updated to name the commit that contains the matching `checksums.sha256`. Until that line is a 40-character hex commit, stop and tell the human the pin is not published yet. Do not fall back to `main` or to `curl | sh`.

```bash
REV="PIN_NOT_SET"
SUMS_SHA="PIN_NOT_SET"
mkdir -p "$HOME/src/grokbot-alarm/cli" "$HOME/src/grokbot-alarm/skill"
cd "$HOME/src/grokbot-alarm"
curl -fsSL -o checksums.sha256 "https://raw.githubusercontent.com/WanManDu/grokbot-alarm/${REV}/checksums.sha256"
echo "${SUMS_SHA}  checksums.sha256" | sha256sum -c -
curl -fsSL -o install.sh "https://raw.githubusercontent.com/WanManDu/grokbot-alarm/${REV}/install.sh"
curl -fsSL -o cli/grokbot_alarm.py "https://raw.githubusercontent.com/WanManDu/grokbot-alarm/${REV}/cli/grokbot_alarm.py"
curl -fsSL -o skill/SKILL.md "https://raw.githubusercontent.com/WanManDu/grokbot-alarm/${REV}/skill/SKILL.md"
sha256sum -c checksums.sha256
sh ./install.sh --agent "YOUR_NAME"
```

`YOUR_NAME` is your short name, the one the phone should show. Up to 40 characters. Omit `--agent` if you have no name. Add `--server URL` only when the human gave you an https ntfy base URL. Otherwise leave the default. `http://` is rejected except for loopback.

3. The command prints one JSON object on stdout. The keys are `v`, `agent`, and `pair`. Send the human the `pair` value and nothing else. It is an `https://` URL. Do not send the secret. Do not send the JSON. Do not ask them to copy or paste. Stderr does not repeat the URL.

4. Stop. After they tap the link, read `~/.local/share/grokbot-alarm/SKILL.md` and follow it whenever they ask to be woken or you need a decision. If you can save a skill or a memory, save that file as `grokbot-alarm`.

Running the command again prints the same link. It does not rotate the secret. Protocol v1 is not accepted. If the phone still has 0.4.x, the human has to install 0.5.0 and open the link again.
