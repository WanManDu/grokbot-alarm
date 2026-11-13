#!/usr/bin/env python3
"""grokbot-alarm CLI: register a wake, ring the phone, wait for the answer.

The phone app and this CLI share a long random secret. That secret names two
ntfy topics (the shared secret). No account and no extra server.

  inbox  gba<secret>   bot publishes register + ring, the phone subscribes
  reply  gbr<secret>   the phone publishes the answer, this CLI polls it

Stdout is for the bot: `register` prints the alarm id, `wait`/`poll` print
one JSON object. Diagnostics go to stderr.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

USER_AGENT = "grokbot-alarm/0.1"
DEFAULT_SERVER = "https://ntfy.sh"
MAX_TIMEOUT = 24 * 60 * 60


def home_dir() -> Path:
    override = os.environ.get("GROKBOT_ALARM_HOME")
    if override:
        return Path(override)
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg else Path.home() / ".config"
    return base / "grokbot-alarm"


def config_path() -> Path:
    return home_dir() / "config.json"


def state_path() -> Path:
    return home_dir() / "state.json"


def inbox_topic(secret: str) -> str:
    return "gba" + secret


def reply_topic(secret: str) -> str:
    return "gbr" + secret


def valid_secret(secret: str) -> bool:
    if not 16 <= len(secret) <= 40:
        return False
    return all(c.isalnum() or c in "-_" for c in secret)


def valid_server(server: str) -> bool:
    if not (server.startswith("https://") or server.startswith("http://")):
        return False
    rest = server.split("://", 1)[1]
    if not rest or any(c.isspace() for c in server):
        return False
    return True


def clean_text(value: str, limit: int) -> str:
    text = " ".join(value.split())
    if not text:
        raise ValueError("empty text")
    if len(text) > limit:
        raise ValueError(f"text is {len(text)} chars; limit is {limit}")
    return text


def load_config() -> dict:
    data: dict = {}
    path = config_path()
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
    server = os.environ.get("GROKBOT_ALARM_SERVER") or data.get("server") or ""
    secret = os.environ.get("GROKBOT_ALARM_SECRET") or data.get("secret") or ""
    agent = os.environ.get("GROKBOT_ALARM_AGENT") or data.get("agent") or ""
    if not server or not secret:
        raise RuntimeError("no pairing config; run: grokbot-alarm init --secret <from the phone>")
    if not valid_server(server):
        raise RuntimeError(f"bad server URL: {server}")
    if not valid_secret(secret):
        raise RuntimeError("secret must be 16-40 chars of letters, digits, '_' or '-'")
    return {"server": server.rstrip("/"), "secret": secret, "agent": str(agent).strip()}


def load_state() -> dict:
    path = state_path()
    if not path.exists():
        return {"alarms": {}}
    data = json.loads(path.read_text(encoding="utf-8"))
    data.setdefault("alarms", {})
    return data


def save_state(state: dict) -> None:
    path = state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def publish(server: str, topic: str, message: str, priority: int, timeout: float = 30) -> None:
    if len(message.encode("utf-8")) > 3800:
        raise RuntimeError("message is too large for ntfy")
    url = server.rstrip("/") + "/" + topic
    req = urllib.request.Request(
        url,
        data=message.encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "text/plain; charset=utf-8",
            "Title": "grokbot-alarm",
            "Priority": str(priority),
            "User-Agent": USER_AGENT,
            "Accept-Encoding": "identity",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            if resp.status not in (200, 201, 202):
                raise RuntimeError(f"ntfy status {resp.status}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"ntfy status {exc.code}: {body}") from exc


def poll_messages(server: str, topic: str, since: int, timeout: float = 30) -> list[dict]:
    url = f"{server.rstrip('/')}/{topic}/json?poll=1&since={since}"
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/x-ndjson",
            "Accept-Encoding": "identity",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"ntfy status {exc.code}: {body}") from exc
    messages = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            messages.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return messages


def extract_answer(messages: list[dict], alarm_id: str) -> dict | None:
    """Return the earliest answer for this id.

    A ring is supposed to produce one answer. If duplicates are on the topic,
    the smallest ntfy ``time`` wins. Messages with no time keep first-seen
    order and lose to any timestamped message. The caller caches the result
    and does not replace it on a later poll.
    """
    best = None
    best_key = None
    for index, msg in enumerate(messages):
        event = msg.get("event")
        if event not in (None, "message"):
            continue
        body = msg.get("message")
        if not isinstance(body, str):
            continue
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            continue
        if not isinstance(payload, dict):
            continue
        if payload.get("type") != "answer" or payload.get("id") != alarm_id:
            continue
        choice = " ".join(str(payload.get("choice") or "").split())
        instruction = " ".join(str(payload.get("instruction") or "").split())
        if not instruction:
            continue
        answer = {
            "v": 1,
            "type": "answer",
            "id": alarm_id,
            "choice": choice,
            "instruction": instruction,
        }
        raw_time = msg.get("time")
        # Timestamped answers sort before untimestamped ones, then by time.
        key = (0, raw_time, index) if isinstance(raw_time, int) else (1, index, index)
        if best_key is None or key < best_key:
            best = answer
            best_key = key
    return best


def cmd_init(args: argparse.Namespace) -> int:
    path = config_path()
    existing: dict = {}
    if path.exists():
        existing = json.loads(path.read_text(encoding="utf-8"))
    untouched = path.exists() and not args.force and not args.secret and not args.server and not args.agent
    if untouched:
        secret = existing["secret"]
        server = existing["server"].rstrip("/")
        agent = str(existing.get("agent") or "")
    else:
        server = (args.server or existing.get("server") or DEFAULT_SERVER).rstrip("/")
        secret = args.secret or (None if args.force else existing.get("secret")) or secrets.token_hex(16)
        if args.agent is not None:
            try:
                agent = clean_text(args.agent, 40)
            except ValueError as exc:
                print(str(exc), file=sys.stderr)
                return 1
        else:
            agent = str(existing.get("agent") or "")
        if not valid_server(server):
            print("server must be an http(s) URL", file=sys.stderr)
            return 1
        if not valid_secret(secret):
            print("secret must be 16-40 chars of letters, digits, '_' or '-'", file=sys.stderr)
            return 1
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"server": server, "secret": secret}
        if agent:
            payload["agent"] = agent
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        os.chmod(tmp, 0o600)
        tmp.replace(path)
    printed = {
        "server": server,
        "secret": secret,
        "inbox": inbox_topic(secret),
        "reply": reply_topic(secret),
    }
    if agent:
        printed["agent"] = agent
    print(json.dumps(printed))
    print(f"config: {path}", file=sys.stderr)
    return 0


def cmd_register(args: argparse.Namespace) -> int:
    try:
        cfg = load_config()
        bot = clean_text(args.bot, 40)
        task = clean_text(args.task, 140)
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    alarm_id = secrets.token_hex(8)
    message = {"v": 1, "type": "register", "id": alarm_id, "bot": bot, "task": task}
    if cfg.get("agent"):
        message["agent"] = cfg["agent"]
    body = json.dumps(message, ensure_ascii=False)
    try:
        publish(cfg["server"], inbox_topic(cfg["secret"]), body, priority=3)
    except Exception as exc:  # noqa: BLE001 - surface any transport failure
        print(str(exc), file=sys.stderr)
        return 1
    state = load_state()
    state["alarms"][alarm_id] = {
        "bot": bot,
        "task": task,
        "since": int(time.time()) - 30,
        "reply_topic": reply_topic(cfg["secret"]),
        "answer": None,
    }
    save_state(state)
    print(alarm_id)
    return 0


def cmd_ring(args: argparse.Namespace) -> int:
    try:
        cfg = load_config()
        question = clean_text(args.question, 200)
        options = [clean_text(item, 80) for item in args.options]
        nxt = [clean_text(item, 80) for item in (args.next_options or [])]
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if not 2 <= len(options) <= 6:
        print("ring needs 2 to 6 --option values", file=sys.stderr)
        return 1
    if len(nxt) > 6:
        print("ring accepts at most 6 --next values", file=sys.stderr)
        return 1
    state = load_state()
    record = state["alarms"].get(args.id)
    if record is None and (not args.bot or not args.task):
        print("unknown id; register first, or pass --bot and --task", file=sys.stderr)
        return 1
    try:
        bot = clean_text(args.bot, 40) if args.bot else record["bot"]
        task = clean_text(args.task, 140) if args.task else record["task"]
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    escalate = args.escalate_after
    if escalate < 0 or escalate > MAX_TIMEOUT:
        print(f"--escalate-after must be between 0 and {MAX_TIMEOUT}", file=sys.stderr)
        return 1
    reply = reply_topic(cfg["secret"])
    message = {
        "v": 1,
        "type": "needs" if escalate > 0 else "alarm",
        "id": args.id,
        "bot": bot,
        "task": task,
        "question": question,
        "options": options,
        "next": nxt,
        "replyTopic": reply,
    }
    if cfg.get("agent"):
        message["agent"] = cfg["agent"]
    if escalate > 0:
        message["escalateAfter"] = escalate
    body = json.dumps(message, ensure_ascii=False)
    try:
        publish(cfg["server"], inbox_topic(cfg["secret"]), body, priority=4 if escalate > 0 else 5)
    except Exception as exc:  # noqa: BLE001
        print(str(exc), file=sys.stderr)
        return 1
    state["alarms"][args.id] = {
        "bot": bot,
        "task": task,
        "since": int(time.time()) - 30,
        "reply_topic": reply,
        "answer": None,
    }
    save_state(state)
    if escalate > 0:
        print(f"needs you {args.id}; alarm in {escalate}s", file=sys.stderr)
    else:
        print(f"ringing {args.id}", file=sys.stderr)
    return 0


def cmd_wait(args: argparse.Namespace, single: bool) -> int:
    try:
        cfg = load_config()
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    timeout = 0 if single else args.timeout
    if timeout < 0 or timeout > MAX_TIMEOUT:
        print(f"timeout must be between 0 and {MAX_TIMEOUT}", file=sys.stderr)
        return 1
    state = load_state()
    record = state["alarms"].get(args.id)
    if record is None:
        print("unknown id; register it on this machine first", file=sys.stderr)
        return 1
    cached = record.get("answer")
    if isinstance(cached, dict) and cached.get("instruction"):
        print(json.dumps(cached, ensure_ascii=False))
        return 0
    reply = record.get("reply_topic") or reply_topic(cfg["secret"])
    since = int(record.get("since") or 0)
    deadline = time.time() + timeout
    last_error = None
    while True:
        try:
            messages = poll_messages(cfg["server"], reply, since)
            last_error = None
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            messages = []
        answer = extract_answer(messages, args.id)
        if answer:
            record["answer"] = answer
            state["alarms"][args.id] = record
            save_state(state)
            print(json.dumps(answer, ensure_ascii=False))
            return 0
        if single or time.time() >= deadline:
            if last_error is not None and not messages:
                print(str(last_error), file=sys.stderr)
                return 1
            print("no answer yet", file=sys.stderr)
            return 2
        time.sleep(min(2.0, max(0.2, deadline - time.time())))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="grokbot-alarm", description="Wake a phone when a Grok Bot task finishes.")
    sub = parser.add_subparsers(dest="cmd", required=True)

    init = sub.add_parser("init", help="write the pairing config")
    init.add_argument("--server", help=f"ntfy base URL (default {DEFAULT_SERVER})")
    init.add_argument("--secret", help="secret copied from the phone, or any 16-40 char token")
    init.add_argument("--agent", help="name of this AI, shown on the phone")
    init.add_argument("--force", action="store_true", help="rotate the secret")

    reg = sub.add_parser("register", help="arm a silent wake request")
    reg.add_argument("--bot", required=True, help="short name shown on the phone")
    reg.add_argument("--task", required=True, help="one short line: what you are about to do")

    ring = sub.add_parser("ring", help="start the alarm")
    ring.add_argument("--id", required=True)
    ring.add_argument("--question", required=True)
    ring.add_argument("--option", action="append", required=True, dest="options")
    ring.add_argument("--next", action="append", dest="next_options")
    ring.add_argument("--bot")
    ring.add_argument("--task")
    ring.add_argument(
        "--escalate-after",
        type=int,
        default=0,
        help="seconds to wait as a notification before the full alarm; 0 rings now",
    )

    wait = sub.add_parser("wait", help="block until the phone answers")
    wait.add_argument("--id", required=True)
    wait.add_argument("--timeout", type=int, default=600, help="seconds; 0 checks once (default 600)")

    poll = sub.add_parser("poll", help="check once for an answer")
    poll.add_argument("--id", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.cmd == "init":
        return cmd_init(args)
    if args.cmd == "register":
        return cmd_register(args)
    if args.cmd == "ring":
        return cmd_ring(args)
    if args.cmd == "wait":
        return cmd_wait(args, single=False)
    if args.cmd == "poll":
        return cmd_wait(args, single=True)
    parser.error(f"unknown command {args.cmd}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
