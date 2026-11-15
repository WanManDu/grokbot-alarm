# grokbot-alarm

The human asked you to set up grokbot-alarm. You have nothing installed. Do exactly this.

1. Do not call ntfy. Do not POST to ntfy.sh. The secret is not an auth token. Do not put it in an Authorization header. Do not invent a topic. The only publisher is the command this installer creates.

2. Run:

```bash
curl -fsSL https://raw.githubusercontent.com/WanManDu/grokbot-alarm/main/install.sh | sh -s -- --agent "YOUR_NAME"
```

`YOUR_NAME` is your short name, the one the phone should show. Up to 40 characters. Omit `--agent` if you have no name. Add `--server URL` only when the human gave you an ntfy base URL. Otherwise leave the default.

3. The command prints one JSON object on stdout. Send the human the `pair` value and nothing else. It is an `https://` URL. Do not send the secret. Do not send the JSON. Do not ask them to copy or paste.

4. Stop. After they tap the link, read `~/.local/share/grokbot-alarm/SKILL.md` and follow it whenever they ask to be woken or you need a decision. If you can save a skill or a memory, save that file as `grokbot-alarm`.

Running the command again prints the same link. It does not rotate the secret.
