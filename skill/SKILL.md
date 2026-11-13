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

Pairing is a sentence the user pastes from the phone. The phone's Copy button puts exactly one line on the clipboard:

```text
Set up grokbot-alarm with secret SECRET on server SERVER
```

When you see that line, in that message or inside a longer one, run init immediately. Do it even if a config file already exists. Do not ask them to retype the secret, and do not invent one.

```bash
python3 cli/grokbot_alarm.py init --secret SECRET --server SERVER
```

`SECRET` is the single token after `secret`. `SERVER` is the URL after `server` (usually `https://ntfy.sh`). Quote both. Run the CLI from the `grokbot-alarm` repo, or with an absolute path to `cli/grokbot_alarm.py`. `python3` and the standard library are enough.

If the file is missing and they have not pasted that line, tell them to open Alarm on the phone, tap Copy, and paste the line here. Then stop.

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

The question is the dismiss action. It has to be worth waking up for. The phone already rotates its own short wake-up lines. You do not write those. You write the real decision.

Voice: direct, a little witty, high-energy, short. Like a sharp coworker who wants a call so they can ship. Not preachy, not corporate, not a lecture, not a riddle. In the user's language. The phone shows your words unchanged, in large white type above the buttons, so a missing question is a broken alarm.

- One question only. A decision about the task you just finished, and one you cannot safely make yourself.
- Options are mutually exclusive concrete actions, short enough for a huge button (aim under 40 characters, hard max 80).
- Not a puzzle, not trivia, not "2 + 2", not "tap to dismiss".
- `--next` values are short imperatives you can start the moment they arrive. The user can also type anything, typos and all, in any language. Do what they meant. Do not ask them to retype it cleanly.

Example, after a session-store migration:

- question: `Sessions or signed tokens. Pick. I'm not guessing.`
- options: `Keep sessions`, `Signed tokens`
- next: `Open a PR`, `Run the tests`, `Stop`

If they answer `choice = Signed tokens` and `instruction = Open a PR` (or `open teh pr`, or a messy sentence in another language), switch to signed tokens and open the PR. Do both. Don't stall.

## Limits

The CLI rejects empty text, newlines, and over-long fields. Keep the task to one line and each option to one short phrase so the buttons stay huge for someone who just woke up.

Ring each id once. Register each wake once. Do not loop `ring` if `wait` times out.
