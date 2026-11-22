#!/usr/bin/env python3
"""grokbot-alarm CLI, protocol v2.

The phone and this CLI share a long random secret. The secret is a key, not a
topic name. Topics are one-way HMAC derivations. Each message is encrypted
with HMAC-SHA256 in counter mode and authenticated with a second HMAC, so the
relay sees ciphertext, a timestamp, and a topic id.

  inbox  gba || HMAC(secret, "grokbot-alarm.v2.topic.inbox")[:20]
  reply  gbr || HMAC(secret, "grokbot-alarm.v2.topic.reply")[:20]

The reply topic is derived locally. A `replyTopic` field in a payload is ignored.
Unsigned v1 messages are rejected. Re-pair (or re-run init on the same secret
after upgrading both sides) is the migration.

Stdout is for the bot. `init` prints only the pair URL the human must open.
`register` prints the alarm id. `wait` / `poll` print one JSON object.
Diagnostics go to stderr and never include the secret or the topic ids.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import stat
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import parse_qsl, quote, urlsplit

USER_AGENT = "grokbot-alarm/0.5"
DEFAULT_SERVER = "https://ntfy.sh"
MAX_TIMEOUT = 24 * 60 * 60
PROTOCOL = 2
MAX_AGE_MS = 24 * 60 * 60 * 1000
MAX_FUTURE_MS = 5 * 60 * 1000
NONCE_LEN = 16
TOPIC_MAC_LEN = 20
MAX_CIPHER = 8192
HKDF_SALT = b"grokbot-alarm.v2"
MAC_PREFIX = b"gba2"
TOPIC_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
# Fragment only. The page never receives s, srv, or n in the query string.
PAIR_PAGE = "https://wanmandu.github.io/grokbot-alarm/pair"
# Per-agent ceiling. The phone enforces the same numbers.
RATE_MAX_EVENTS = 8
RATE_WINDOW_MS = 60_000
RATE_MAX_RINGING = 4


def _hmac(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def _hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return _hmac(salt, ikm)


def _hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    out = b""
    prev = b""
    counter = 1
    while len(out) < length:
        prev = _hmac(prk, prev + info + bytes([counter]))
        out += prev
        counter += 1
        if counter > 255:
            raise RuntimeError("hkdf expand failed")
    return out[:length]


def _keys(secret: str) -> tuple[bytes, bytes]:
    prk = _hkdf_extract(HKDF_SALT, secret.encode("utf-8"))
    return _hkdf_expand(prk, b"enc", 32), _hkdf_expand(prk, b"mac", 32)


def _b64e(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64d(text: str) -> bytes:
    if not text or any(ch.isspace() for ch in text):
        raise ValueError("bad base64")
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def _keystream(key: bytes, nonce: bytes, size: int) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < size:
        out += _hmac(key, nonce + counter.to_bytes(4, "big"))
        counter += 1
        if counter > 0x10000:
            raise RuntimeError("message too large")
    return bytes(out[:size])


def _xor(left: bytes, right: bytes) -> bytes:
    return bytes(a ^ b for a, b in zip(left, right))


def inbox_topic(secret: str) -> str:
    digest = _hmac(secret.encode("utf-8"), b"grokbot-alarm.v2.topic.inbox")
    return "gba" + digest[:TOPIC_MAC_LEN].hex()


def reply_topic(secret: str) -> str:
    digest = _hmac(secret.encode("utf-8"), b"grokbot-alarm.v2.topic.reply")
    return "gbr" + digest[:TOPIC_MAC_LEN].hex()


def reply_destination(secret: str, claimed: str | None = None) -> str:
    """Reply topic for this secret. `claimed` is never consulted."""
    del claimed
    return reply_topic(secret)


def seal(
    secret: str,
    plaintext: str,
    *,
    nonce: bytes | None = None,
    now_ms: int | None = None,
) -> str:
    if nonce is None:
        nonce = secrets.token_bytes(NONCE_LEN)
    if len(nonce) != NONCE_LEN:
        raise ValueError("nonce must be 16 bytes")
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    stamp = int(now_ms)
    if stamp < 0:
        raise ValueError("timestamp")
    raw = plaintext.encode("utf-8")
    if len(raw) > MAX_CIPHER:
        raise ValueError("message too large")
    enc_key, mac_key = _keys(secret)
    ciphertext = _xor(raw, _keystream(enc_key, nonce, len(raw)))
    mac = _hmac(mac_key, MAC_PREFIX + nonce + stamp.to_bytes(8, "big") + ciphertext)
    return '{{"v":2,"t":{t},"n":"{n}","c":"{c}","m":"{m}"}}'.format(
        t=stamp,
        n=_b64e(nonce),
        c=_b64e(ciphertext),
        m=_b64e(mac),
    )


def unseal(secret: str, wire: str, now_ms: int | None = None) -> tuple[dict, str, int] | None:
    """Return (payload, nonce hex, timestamp ms) or None.

    Rejects a bad MAC, a non-v2 envelope, and a timestamp outside
    [now - 24h, now + 5min]. Does not record the nonce; callers do that so a
    failed MAC is not treated as a replay slot.
    """
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    try:
        obj = json.loads(wire)
    except json.JSONDecodeError:
        return None
    if not isinstance(obj, dict) or obj.get("v") != PROTOCOL:
        return None
    stamp = obj.get("t")
    if isinstance(stamp, bool) or not isinstance(stamp, int):
        return None
    if stamp < 0 or stamp > now_ms + MAX_FUTURE_MS or stamp < now_ms - MAX_AGE_MS:
        return None
    nonce_text, cipher_text, mac_text = obj.get("n"), obj.get("c"), obj.get("m")
    if not all(isinstance(item, str) for item in (nonce_text, cipher_text, mac_text)):
        return None
    try:
        nonce = _b64d(nonce_text)
        ciphertext = _b64d(cipher_text)
        mac = _b64d(mac_text)
    except Exception:
        return None
    if len(nonce) != NONCE_LEN or len(mac) != 32 or len(ciphertext) > MAX_CIPHER:
        return None
    _enc_key, mac_key = _keys(secret)
    expected = _hmac(mac_key, MAC_PREFIX + nonce + stamp.to_bytes(8, "big") + ciphertext)
    if not hmac.compare_digest(expected, mac):
        return None
    enc_key, _mac_key = _keys(secret)
    try:
        payload = json.loads(_xor(ciphertext, _keystream(enc_key, nonce, len(ciphertext))).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    payload.pop("replyTopic", None)
    return payload, nonce.hex(), stamp


class ReplayWindow:
    """Nonces of authenticated messages still inside the acceptance window."""

    def __init__(self, max_size: int = 4096) -> None:
        self.max_size = max_size
        self.seen: dict[str, int] = {}

    def prune(self, now_ms: int) -> None:
        cutoff = now_ms - MAX_AGE_MS
        self.seen = {nonce: stamp for nonce, stamp in self.seen.items() if stamp >= cutoff}

    def accept(self, nonce: str, timestamp_ms: int, now_ms: int) -> bool:
        self.prune(now_ms)
        if timestamp_ms > now_ms + MAX_FUTURE_MS or timestamp_ms < now_ms - MAX_AGE_MS:
            return False
        if nonce in self.seen:
            return False
        if len(self.seen) >= self.max_size:
            return False
        self.seen[nonce] = timestamp_ms
        return True

    def load(self, items: object) -> None:
        if not isinstance(items, list):
            return
        for item in items:
            if not isinstance(item, dict):
                continue
            nonce = item.get("n")
            stamp = item.get("t")
            if isinstance(nonce, str) and isinstance(stamp, int) and not isinstance(stamp, bool):
                self.seen[nonce] = stamp

    def dump(self) -> list[dict]:
        return [{"n": nonce, "t": stamp} for nonce, stamp in self.seen.items()]


class RateGate:
    """Per-agent accept window and a cap on simultaneous ringing alarms.

    The phone enforces this. The class lives here so the numbers and the
    window arithmetic have one testable definition; keep Protocol.kt in step.
    A hit at exactly `now - window` is still inside the window.
    """

    def __init__(
        self,
        max_events: int = RATE_MAX_EVENTS,
        window_ms: int = RATE_WINDOW_MS,
        max_ringing: int = RATE_MAX_RINGING,
    ) -> None:
        self.max_events = max_events
        self.window_ms = window_ms
        self.max_ringing = max_ringing
        self.hits: dict[str, list[int]] = {}

    def allow(self, agent_id: str, now_ms: int) -> bool:
        cutoff = now_ms - self.window_ms
        window = [stamp for stamp in self.hits.get(agent_id, []) if stamp >= cutoff]
        if len(window) >= self.max_events:
            self.hits[agent_id] = window
            return False
        window.append(now_ms)
        self.hits[agent_id] = window
        return True

    def under_ringing_cap(self, ringing_now: int) -> bool:
        return ringing_now < self.max_ringing


def config_root() -> Path:
    override = os.environ.get("GROKBOT_ALARM_HOME")
    if override:
        return Path(override)
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg else Path.home() / ".config"
    return base / "grokbot-alarm"


def _secure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    os.chmod(path, 0o700)


def ensure_private_dirs() -> Path:
    root = config_root()
    _secure_dir(root)
    agents = root / "agents"
    _secure_dir(agents)
    return agents


def agent_slug(name: str) -> str:
    chars = []
    for ch in name.strip().lower():
        if ch.isalnum() or ch in "-_":
            chars.append(ch)
        elif ch.isspace() or ch in "/":
            chars.append("-")
    slug = "".join(chars).strip("-")
    return (slug or "agent")[:40]


def agents_dir() -> Path:
    return config_root() / "agents"


def agent_config_path(name: str) -> Path:
    return agents_dir() / f"{agent_slug(name)}.json"


def agent_state_path(name: str) -> Path:
    return agents_dir() / f"{agent_slug(name)}.state.json"


def valid_secret(secret: str) -> bool:
    if not 16 <= len(secret) <= 40:
        return False
    return all(c.isalnum() or c in "-_" for c in secret)


def _loopback(host: str | None) -> bool:
    if not host:
        return False
    return host.lower().strip("[]") in {"localhost", "127.0.0.1", "::1"}


def valid_server(server: str) -> bool:
    if any(c.isspace() for c in server):
        return False
    parts = urlsplit(server)
    if parts.username or parts.password or not parts.hostname:
        return False
    if parts.scheme == "https":
        return True
    if parts.scheme == "http" and _loopback(parts.hostname):
        return True
    return False


def _endpoint(parts) -> tuple[str, str, int]:
    host = (parts.hostname or "").lower()
    if parts.scheme == "https":
        port = parts.port or 443
    elif parts.scheme == "http":
        port = parts.port or 80
    else:
        port = parts.port or 0
    return parts.scheme, host, port


class _SameHostRedirect(urllib.request.HTTPRedirectHandler):
    """Follow a redirect only when it stays on the same scheme, host, and port."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        if code not in (301, 302, 303, 307, 308):
            return None
        old = urlsplit(req.full_url)
        new = urlsplit(newurl)
        if new.scheme not in ("https", "http"):
            raise RuntimeError("refusing redirect")
        if _endpoint(old) != _endpoint(new):
            raise RuntimeError("refusing redirect off the ntfy host")
        if new.scheme != "https" and not _loopback(new.hostname):
            raise RuntimeError("refusing cleartext redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(_SameHostRedirect)


def pairing_link(server: str, secret: str, agent: str) -> str:
    """https URL whose fragment is the only copy of the pairing data."""
    fragment = "&".join(
        f"{key}={quote(value, safe='')}"
        for key, value in (("s", secret), ("srv", server), ("n", agent))
    )
    return f"{PAIR_PAGE}#{fragment}"


def parse_pairing_link(url: str) -> dict | None:
    """Read secret, server, and name from a pair link, custom scheme, or intent URL."""
    query = _pair_query(url)
    if query is None:
        return None
    fields = {key: value for key, value in parse_qsl(query, keep_blank_values=True)}
    secret = fields.get("s", "")
    server = fields.get("srv", "").strip().rstrip("/")
    name = " ".join(fields.get("n", "").split())[:40] or "AI"
    if not valid_secret(secret) or not valid_server(server):
        return None
    return {"secret": secret, "server": server, "name": name}


def _pair_query(url: str) -> str | None:
    text = url.strip()
    lower = text.lower()
    if lower.startswith("intent://") or lower.startswith("grokbot-alarm://"):
        if "?" not in text:
            return None
        return text.split("?", 1)[1].split("#", 1)[0]
    if lower.startswith("https://") or lower.startswith("http://"):
        parts = urlsplit(text)
        if parts.hostname != "wanmandu.github.io":
            return None
        if parts.path.rstrip("/") != "/grokbot-alarm/pair":
            return None
        return parts.fragment or None
    return None


def clean_text(value: str, limit: int) -> str:
    text = " ".join(value.split())
    if not text:
        raise ValueError("empty text")
    if len(text) > limit:
        raise ValueError(f"text is {len(text)} chars; limit is {limit}")
    return text


def _read_json(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def list_agents() -> list[dict]:
    found: list[dict] = []
    directory = agents_dir()
    if directory.is_dir():
        for path in sorted(directory.glob("*.json")):
            if path.name.endswith(".state.json"):
                continue
            data = _read_json(path)
            if data.get("secret"):
                data.setdefault("agent", "default")
                found.append(data)
    legacy = config_root() / "config.json"
    if not found and legacy.exists():
        data = _read_json(legacy)
        if data.get("secret"):
            data.setdefault("agent", "default")
            found.append(data)
    return found


def resolve_agent_name(explicit: str | None) -> str:
    raw = explicit if explicit not in (None, "") else os.environ.get("GROKBOT_ALARM_AGENT")
    if raw:
        return clean_text(str(raw), 40)
    agents = list_agents()
    if len(agents) == 1:
        return str(agents[0].get("agent") or "default")
    if not agents:
        raise RuntimeError(
            "no pairing config; clone the pinned revision in BOT.md and run install.sh"
        )
    names = ", ".join(str(item.get("agent") or "?") for item in agents)
    raise RuntimeError(f"multiple agents ({names}); pass --agent or set GROKBOT_ALARM_AGENT")


def load_config(explicit_agent: str | None = None) -> dict:
    agent = resolve_agent_name(explicit_agent)
    data: dict = {}
    path = agent_config_path(agent)
    if path.exists():
        data = _read_json(path)
    else:
        for item in list_agents():
            if str(item.get("agent") or "") == agent:
                data = item
                break
    server = os.environ.get("GROKBOT_ALARM_SERVER") or data.get("server") or ""
    secret = os.environ.get("GROKBOT_ALARM_SECRET") or data.get("secret") or ""
    if not server or not secret:
        raise RuntimeError(f"no pairing config for {agent}; run install.sh for that agent")
    if not valid_server(server):
        raise RuntimeError("server must be https")
    if not valid_secret(secret):
        raise RuntimeError("secret must be 16-40 chars of letters, digits, '_' or '-'")
    return {"server": server.rstrip("/"), "secret": secret, "agent": agent}


def load_state(agent: str) -> dict:
    path = agent_state_path(agent)
    if not path.exists():
        return {"alarms": {}, "nonces": []}
    data = _read_json(path)
    data.setdefault("alarms", {})
    data.setdefault("nonces", [])
    return data


def save_state(agent: str, state: dict) -> None:
    ensure_private_dirs()
    path = agent_state_path(agent)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    tmp.replace(path)
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)


def _write_config(path: Path, payload: dict) -> None:
    ensure_private_dirs()
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    tmp.replace(path)
    os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)


def publish(server: str, topic: str, message: str, priority: int, timeout: float = 30) -> None:
    if not valid_server(server):
        raise RuntimeError("server must be https")
    if not TOPIC_RE.fullmatch(topic):
        raise RuntimeError("bad topic")
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
        with _opener().open(req, timeout=timeout) as resp:
            resp.read()
            if resp.status not in (200, 201, 202):
                raise RuntimeError(f"ntfy status {resp.status}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"ntfy status {exc.code}: {body}") from exc


def poll_messages(server: str, topic: str, since: int, timeout: float = 30) -> list[dict]:
    if not valid_server(server):
        raise RuntimeError("server must be https")
    if not TOPIC_RE.fullmatch(topic):
        raise RuntimeError("bad topic")
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
        with _opener().open(req, timeout=timeout) as resp:
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


def _publish_sealed(cfg: dict, inner: dict, priority: int) -> None:
    body = seal(cfg["secret"], json.dumps(inner, ensure_ascii=False, separators=(",", ":")))
    publish(cfg["server"], inbox_topic(cfg["secret"]), body, priority)


def extract_answer(
    messages: list[dict],
    alarm_id: str,
    secret: str,
    now_ms: int,
    replay: ReplayWindow,
) -> dict | None:
    """Return the earliest authenticated answer for this id.

    A forged body, a bad MAC, a stale timestamp, a replayed nonce, or a
    `replyTopic` field does not become an instruction. The smallest ntfy
    ``time`` wins. Messages with no time keep first-seen order and lose to
    any timestamped message.
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
        opened = unseal(secret, body, now_ms)
        if opened is None:
            continue
        payload, nonce, stamp = opened
        if not replay.accept(nonce, stamp, now_ms):
            continue
        if payload.get("type") != "answer" or payload.get("id") != alarm_id:
            continue
        choice = " ".join(str(payload.get("choice") or "").split())
        instruction = " ".join(str(payload.get("instruction") or "").split())
        if not instruction:
            continue
        answer = {
            "v": PROTOCOL,
            "type": "answer",
            "id": alarm_id,
            "choice": choice,
            "instruction": instruction,
        }
        raw_time = msg.get("time")
        if isinstance(raw_time, bool) or not isinstance(raw_time, int):
            key = (1, index, index)
        else:
            key = (0, raw_time, index)
        if best_key is None or key < best_key:
            best = answer
            best_key = key
    return best


def _relay_card(server: str, secret: str, agent: str) -> dict:
    return {"v": PROTOCOL, "agent": agent, "pair": pairing_link(server, secret, agent)}


def cmd_init(args: argparse.Namespace) -> int:
    try:
        if args.agent:
            agent = clean_text(args.agent, 40)
        else:
            agents = list_agents()
            agent = str(agents[0].get("agent") or "default") if len(agents) == 1 else "default"
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    path = agent_config_path(agent)
    existing: dict = {}
    if path.exists():
        existing = _read_json(path)
    untouched = path.exists() and not args.force and not args.secret and not args.server
    if untouched:
        secret = existing.get("secret") or ""
        server = str(existing.get("server") or "").rstrip("/")
        if not valid_server(server) or not valid_secret(secret):
            print("stored pairing config is not usable; run init --force", file=sys.stderr)
            return 1
    else:
        server = (args.server or existing.get("server") or DEFAULT_SERVER).rstrip("/")
        secret = args.secret or (None if args.force else existing.get("secret")) or secrets.token_hex(16)
        if not valid_server(server):
            print("server must be https", file=sys.stderr)
            return 1
        if not valid_secret(secret):
            print("secret must be 16-40 chars of letters, digits, '_' or '-'", file=sys.stderr)
            return 1
        _write_config(path, {"server": server, "secret": secret, "agent": agent, "v": PROTOCOL})
    inner = {"v": PROTOCOL, "type": "hello", "agent": agent}
    try:
        _publish_sealed({"server": server, "secret": secret}, inner, priority=3)
    except Exception as exc:  # noqa: BLE001
        print(str(exc), file=sys.stderr)
        return 1
    card = _relay_card(server, secret, agent)
    print(json.dumps(card, ensure_ascii=False))
    print(f"config: {path}", file=sys.stderr)
    print("relay the pair URL on stdout to the human", file=sys.stderr)
    return 0


def cmd_register(args: argparse.Namespace) -> int:
    try:
        cfg = load_config(args.agent)
        bot = clean_text(args.bot, 40)
        task = clean_text(args.task, 140)
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    alarm_id = secrets.token_hex(8)
    message = {"v": PROTOCOL, "type": "register", "id": alarm_id, "bot": bot, "task": task}
    if cfg.get("agent"):
        message["agent"] = cfg["agent"]
    try:
        _publish_sealed(cfg, message, priority=3)
    except Exception as exc:  # noqa: BLE001
        print(str(exc), file=sys.stderr)
        return 1
    state = load_state(cfg["agent"])
    state["alarms"][alarm_id] = {
        "bot": bot,
        "task": task,
        "since": int(time.time()) - 30,
        "answer": None,
    }
    save_state(cfg["agent"], state)
    print(alarm_id)
    return 0


def cmd_ring(args: argparse.Namespace) -> int:
    try:
        cfg = load_config(args.agent)
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
    state = load_state(cfg["agent"])
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
    message = {
        "v": PROTOCOL,
        "type": "needs" if escalate > 0 else "alarm",
        "id": args.id,
        "bot": bot,
        "task": task,
        "question": question,
        "options": options,
        "next": nxt,
    }
    if cfg.get("agent"):
        message["agent"] = cfg["agent"]
    if escalate > 0:
        message["escalateAfter"] = escalate
    try:
        _publish_sealed(cfg, message, priority=4 if escalate > 0 else 5)
    except Exception as exc:  # noqa: BLE001
        print(str(exc), file=sys.stderr)
        return 1
    state["alarms"][args.id] = {
        "bot": bot,
        "task": task,
        "since": int(time.time()) - 30,
        "answer": None,
    }
    save_state(cfg["agent"], state)
    if escalate > 0:
        print(f"needs you {args.id}; alarm in {escalate}s", file=sys.stderr)
    else:
        print(f"ringing {args.id}", file=sys.stderr)
    return 0


def cmd_wait(args: argparse.Namespace, single: bool) -> int:
    try:
        cfg = load_config(getattr(args, "agent", None))
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    timeout = 0 if single else args.timeout
    if timeout < 0 or timeout > MAX_TIMEOUT:
        print(f"timeout must be between 0 and {MAX_TIMEOUT}", file=sys.stderr)
        return 1
    state = load_state(cfg["agent"])
    record = state["alarms"].get(args.id)
    if record is None:
        print("unknown id; register it on this machine first", file=sys.stderr)
        return 1
    cached = record.get("answer")
    if isinstance(cached, dict) and cached.get("instruction"):
        print(json.dumps(cached, ensure_ascii=False))
        return 0
    reply = reply_destination(cfg["secret"], record.get("reply_topic"))
    since = int(record.get("since") or 0)
    deadline = time.time() + timeout
    last_error = None
    replay = ReplayWindow()
    replay.load(state.get("nonces"))
    while True:
        now_ms = int(time.time() * 1000)
        try:
            messages = poll_messages(cfg["server"], reply, since)
            last_error = None
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            messages = []
        answer = extract_answer(messages, args.id, cfg["secret"], now_ms, replay)
        state["nonces"] = replay.dump()
        if answer:
            record["answer"] = answer
            state["alarms"][args.id] = record
            save_state(cfg["agent"], state)
            print(json.dumps(answer, ensure_ascii=False))
            return 0
        save_state(cfg["agent"], state)
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
    init.add_argument(
        "--secret",
        help="optional; generated when omitted. Pass one only if the phone already created it",
    )
    init.add_argument("--agent", help="name of this AI, shown on the phone")
    init.add_argument("--force", action="store_true", help="rotate the secret")

    reg = sub.add_parser("register", help="arm a silent wake request")
    reg.add_argument("--agent", help="paired AI name, or set GROKBOT_ALARM_AGENT")
    reg.add_argument("--bot", required=True, help="short name shown on the phone")
    reg.add_argument("--task", required=True, help="one short line: what you are about to do")

    ring = sub.add_parser("ring", help="start the alarm")
    ring.add_argument("--agent", help="paired AI name, or set GROKBOT_ALARM_AGENT")
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
    wait.add_argument("--agent", help="paired AI name, or set GROKBOT_ALARM_AGENT")
    wait.add_argument("--id", required=True)
    wait.add_argument("--timeout", type=int, default=600, help="seconds; 0 checks once (default 600)")

    poll = sub.add_parser("poll", help="check once for an answer")
    poll.add_argument("--agent", help="paired AI name, or set GROKBOT_ALARM_AGENT")
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
