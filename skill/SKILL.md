---
name: grokbot-alarm
description: Wake the user on their phone when a long task finishes, with one real decision question, then continue immediately from the instruction they send back.
---

# grokbot-alarm

Use this when the user asks to be woken when you are done, or they are going to sleep or step away and want an alarm the moment the task finishes so they can give the next instruction.

Do not use it for ordinary questions while they are at the keyboard. Do not use it as a captcha, a math quiz, or an "are you awake?" check.

The phone rings until they answer one real decision about the task. The alarm then stops, they pick or type the next instruction, and that answer is printed to you. Continue immediately. Do not ask the same question again.

## Setup

The phone app and this machine share one secret. Config lives in `~/.config/grokbot-alarm/config.json` (or `$GROKBOT_ALARM_HOME`).

If that file is missing, stop and tell the user to open the Alarm app, copy the secret (or scan the QR and read the `secret` parameter), then run:

```bash
python3 cli/grokbot_alarm.py init --secret PASTED_SECRET
```

Run the CLI from the `grokbot-alarm` repo, or with an absolute path to `cli/grokbot_alarm.py`. `python3` and the standard library are enough. Do not invent a secret that is not on the phone.

Optional: `--server https://ntfy.example` if they are not using the default `https://ntfy.sh`.

## When the user asks to be woken

Do this at the start, before the long work, so the phone can show that a wake is armed.

```bash
python3 cli/grokbot_alarm.py register --bot "BOT_NAME" --task "ONE_SHORT_LINE"
```

Stdout is only the alarm id. Save it. Stderr is not the id.

- `BOT_NAME`: a short name the user will recognize on the lock screen (your name). 1–40 characters.
- `ONE_SHORT_LINE`: what you are about to do, not a log. 1–140 characters.

Register is silent. It does not ring.

Then do the work. When the task has reached a real decision only the user should make, ring once:

```bash
python3 cli/grokbot_alarm.py ring \
  --id ALARM_ID \
  --question "ONE_DECISION_QUESTION" \
  --option "CONCRETE_CHOICE_A" \
  --option "CONCRETE_CHOICE_B" \
  --next "SHORT_NEXT_INSTRUCTION" \
  --next "ANOTHER_NEXT_INSTRUCTION"
```

You need 2 to 6 `--option` values and may pass up to 6 `--next` values. Write the question, options, and next instructions in the user's language. The phone shows them unchanged.

Then wait:

```bash
python3 cli/grokbot_alarm.py wait --id ALARM_ID --timeout 7200
```

Stdout on success is one JSON object and nothing else:

```json
{"v":1,"type":"answer","id":"...","choice":"...","instruction":"..."}
```

- `choice` is the decision they picked. Apply it.
- `instruction` is the next task. Start it immediately, with no extra confirmation.

Exit code `2` means they have not answered yet (`no answer yet` on stderr). Do not invent an answer and do not ring again. The phone keeps ringing until they answer. Check later with:

```bash
python3 cli/grokbot_alarm.py poll --id ALARM_ID
```

`poll` checks once (same as `wait --timeout 0`). Exit `0` prints the JSON. Exit `2` means still waiting. A later `wait` or `poll` prints a stored answer even if the relay has expired.

Exit code `1` is a real failure (network, bad config, rejected input). Tell the user. Do not pretend the alarm rang.

## How to write the one question

The question is the dismiss action. It has to be worth waking up for.

- One question only. It must be a decision about the task you just finished, and one you cannot safely make yourself.
- Options are mutually exclusive concrete actions, short enough for a large button (aim under 40 characters, hard max 80).
- Not a puzzle, not trivia, not "2 + 2", not "tap to dismiss".
- `--next` values are short imperatives you can start the moment they arrive (open a PR, run the tests, stop). The user can also type their own sentence; treat that text the same way.

Example, after a session-store migration that can go two ways:

- question: `Keep server sessions or switch to signed tokens?`
- options: `Keep sessions`, `Switch to signed tokens`
- next: `Open a PR`, `Run the tests`, `Stop`

If they answer `choice = Switch to signed tokens` and `instruction = Open a PR`, switch the design to signed tokens and open the PR. Do both.

## Limits

The CLI rejects empty text, newlines, and over-long fields. Keep the task to one line and each option to one short phrase so the buttons stay huge for someone who just woke up.

Ring each id once. Register each wake once. Do not loop `ring` if `wait` times out.
