---
name: grokbot-alarm
description: Wake the user on their phone when you need a decision or they asked to be woken, then continue from the one answer they send back.
---

# grokbot-alarm

You are one Connected AI on a phone alarm. The human is the bottleneck. When you need them to approve something or tell you the next step, this tool puts that on their phone. If they asked to be woken, or they do not answer a quiet ask, the phone rings until they solve one real decision and send the next instruction. You then continue immediately. Do not ask the same question again.

This file is the whole manual. You do not need the git repo.

If this platform can save a skill or a memory, save this file as one now, under the name grokbot-alarm, so the next session still has it.

## Do not touch ntfy yourself

Do not call ntfy. Do not use the secret as an auth token. Do not invent a topic like `grokbot-alarm-<secret>`. The only topics are `gba<secret>` and `gbr<secret>`, and only the `grokbot-alarm` command publishes them. A hand-rolled HTTP call will not reach the phone.

## Where the command is

```text
~/.local/bin/grokbot-alarm
```

If that is not on PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

python3 from the standard library is enough. There is no package to pip install.

## Your config is yours alone

Several agents share this Linux machine. Each Connected AI has its own secret and its own file:

```text
~/.config/grokbot-alarm/agents/<name>.json
```

Always pass your paired name, or export it once per session:

```bash
export GROKBOT_ALARM_AGENT="NAME"
```

`NAME` is the `--agent` value from the setup line the user pasted. Never read or edit another agent's file. If a command says multiple agents, you forgot `--agent`.

The installer already ran `init`. Do not invent a new secret. Run `init` again only when the user pastes a new setup line for you.

## When to use it

Use it when the user asks to be woken when you are done, or they are going to sleep or step away, or you are blocked on a decision only they should make.

Do not use it for an ordinary question while they are at the keyboard. Do not use it as a captcha, a math quiz, or an "are you awake?" check.

## Flow

Do this at the start of the work, before the long part, so the phone can show the task as Working.

```bash
grokbot-alarm register --agent "NAME" --bot "NAME" --task "ONE_SHORT_LINE"
```

Stdout is only the alarm id. Save it. Stderr is not the id.

- `NAME`: the paired agent name. Also what the phone shows as the bot, unless you have a shorter label. 1–40 characters.
- `ONE_SHORT_LINE`: what you are about to do, not a log. 1–140 characters.

Register does not ring.

Then do the work. When you need the human, ask. Two speeds:

- They might still be nearby. Notify first. If they ignore it, the phone escalates to the full alarm:

```bash
grokbot-alarm ring --agent "NAME" \
  --id ALARM_ID \
  --question "ONE_DECISION_QUESTION" \
  --option "CONCRETE_CHOICE_A" \
  --option "CONCRETE_CHOICE_B" \
  --next "SHORT_NEXT_INSTRUCTION" \
  --next "ANOTHER_NEXT_INSTRUCTION" \
  --escalate-after 120
```

- They asked to be woken, or it cannot wait. Ring now. Omit `--escalate-after`.

You need 2 to 6 `--option` values and may pass up to 6 `--next` values. Write the question, options, and next instructions in the user's language. The phone shows them unchanged. `--escalate-after` is seconds. Do not send a second ring for the same id while you are waiting.

Then wait:

```bash
grokbot-alarm wait --agent "NAME" --id ALARM_ID --timeout 7200
```

Stdout on success is one JSON object and nothing else:

```json
{"v":1,"type":"answer","id":"...","choice":"...","instruction":"..."}
```

- `choice` is the decision they picked. Apply it.
- `instruction` is the next task. Start it immediately, with no extra confirmation.
- The phone sends one answer. If a duplicate is on the topic anyway, `wait` keeps the earliest one and ignores the rest. Do not ring again to ask them to pick between the two.

Exit code `2` means they have not answered yet (`no answer yet` on stderr). Do not invent an answer and do not ring again. Check later with:

```bash
grokbot-alarm poll --agent "NAME" --id ALARM_ID
```

`poll` checks once. Exit `0` prints the JSON. Exit `2` means still waiting. A later poll prints the stored earliest answer even if the relay has expired.

Exit code `1` is a real failure (network, bad config, rejected input). Tell the user. Do not pretend the alarm rang.

## How to write the one question

The question is the dismiss action. It has to be worth waking up for. The phone already rotates its own short wake-up lines. You do not write those. You write the real decision.

Voice: direct, a little witty, high-energy, short. Like a sharp coworker who wants a call so they can ship. Not preachy, not corporate, not a lecture, not a riddle. In the user's language. The phone shows your words unchanged, in large white type above the buttons.

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

The CLI rejects empty text, newlines inside a field, and over-long fields. Keep the task to one line and each option to one short phrase.

Ring each id once. Register each wake once. Do not loop `ring` if `wait` times out.
