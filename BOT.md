# grokbot-alarm

The human asked you to set up grokbot-alarm. You have nothing installed. Do exactly this.

1. Do not call ntfy. Do not POST to ntfy.sh. The secret is not an auth token and it is not a topic name. Do not put it in an Authorization header. Do not invent a topic. The only publisher is the command this installer creates.

2. Do not pipe a script into a shell. Check out the pinned commit, verify the checksum file, then run the installer that is in that tree.

`REV` is a full git commit (40 hex characters). `SUMS_SHA` is the sha256 of `checksums.sha256` in that commit (64 hex characters). Do not fall back to `main` or to `curl | sh`. If either value does not match that length, stop.

```bash
REV="b778e50fdb4ff91a84ae3e02b5f509b8d2dea71f"
SUMS_SHA="db581c373f0a77cf25294d204ffe7a4e1c87851d9bc1dc60fd3a2e79616a271e"
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
