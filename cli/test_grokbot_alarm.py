#!/usr/bin/env python3
"""Local tests for protocol v2 and the CLI, including a fake ntfy relay."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "cli" / "grokbot_alarm.py"
sys.path.insert(0, str(ROOT / "cli"))
import grokbot_alarm as gba  # noqa: E402

STORE: dict[str, list[str]] = {}
LOCK = threading.Lock()
SECRET = "0123456789abcdef0123456789abcdef"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        topic = urlparse(self.path).path.strip("/")
        with LOCK:
            STORE.setdefault(topic, []).append(body)
        payload = b"ok"
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        topic = parsed.path.strip("/")
        if topic.endswith("/json"):
            topic = topic[: -len("/json")]
        qs = parse_qs(parsed.query)
        if "since" in qs:
            try:
                int(qs["since"][0])
            except ValueError:
                pass
        with LOCK:
            bodies = list(STORE.get(topic, []))
        lines = []
        for index, body in enumerate(bodies):
            lines.append(
                json.dumps(
                    {
                        "id": f"m{index}",
                        "time": index + 1,
                        "event": "message",
                        "topic": topic,
                        "message": body,
                    }
                )
            )
        data = ("\n".join(lines) + ("\n" if lines else "")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt: str, *args) -> None:
        return


class RedirectHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self) -> None:  # noqa: N802
        self.send_response(302)
        self.send_header("Location", "https://evil.example/stolen")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt: str, *args) -> None:
        return


class ProtocolTest(unittest.TestCase):
    def test_topic_derivation_is_one_way_and_stable(self) -> None:
        inbox = gba.inbox_topic(SECRET)
        reply = gba.reply_topic(SECRET)
        self.assertEqual(inbox, gba.inbox_topic(SECRET))
        self.assertEqual(reply, gba.reply_topic(SECRET))
        self.assertNotEqual(inbox, reply)
        self.assertEqual(len(inbox), 43)
        self.assertEqual(len(reply), 43)
        self.assertTrue(inbox.startswith("gba"))
        self.assertTrue(reply.startswith("gbr"))
        self.assertNotIn(SECRET, inbox)
        self.assertNotIn(SECRET, reply)
        self.assertNotEqual("gba" + SECRET, inbox)
        self.assertNotEqual("gbr" + SECRET, reply)
        other = gba.inbox_topic(SECRET[:-1] + "0")
        self.assertNotEqual(inbox, other)

    def test_seal_round_trip_and_known_vector(self) -> None:
        nonce = bytes(range(1, 17))
        stamp = 1_700_000_000_000
        plain = '{"v":2,"type":"hello","agent":"coder"}'
        wire = gba.seal(SECRET, plain, nonce=nonce, now_ms=stamp)
        opened = gba.unseal(SECRET, wire, stamp)
        self.assertIsNotNone(opened)
        assert opened is not None
        payload, nonce_hex, ts = opened
        self.assertEqual(payload["agent"], "coder")
        self.assertEqual(nonce_hex, nonce.hex())
        self.assertEqual(ts, stamp)
        self.assertEqual(
            wire,
            '{"v":2,"t":1700000000000,"n":"AQIDBAUGBwgJCgsMDQ4PEA",'
                + '"c":"ZQSaCqI9-DHbsSkrCR42FKry2eV2x0TaTOik6UPWQ6PudBy6bkg",'
                + '"m":"CtsVsNkvQrBRvRYDYrmQb35kDKQTKlWQ6d_2-R7qKiw"}',
        )

    def test_rejects_tampered_mac_ciphertext_and_wrong_key(self) -> None:
        now = 1_700_000_000_000
        wire = gba.seal(SECRET, '{"v":2,"type":"hello"}', nonce=bytes(range(1, 17)), now_ms=now)
        obj = json.loads(wire)
        flipped = bytearray(gba._b64d(obj["c"]))
        flipped[0] ^= 0x01
        obj["c"] = gba._b64e(bytes(flipped))
        tampered = json.dumps(obj, separators=(",", ":"))
        self.assertIsNone(gba.unseal(SECRET, tampered, now))
        obj = json.loads(wire)
        mac = bytearray(gba._b64d(obj["m"]))
        mac[0] ^= 0x01
        obj["m"] = gba._b64e(bytes(mac))
        self.assertIsNone(gba.unseal(SECRET, json.dumps(obj, separators=(",", ":")), now))
        self.assertIsNone(gba.unseal("ffffffffffffffffffffffffffffffff", wire, now))
        self.assertIsNone(gba.unseal(SECRET, '{"v":1,"type":"alarm","id":"x"}', now))

    def test_rejects_stale_future_and_replayed_nonce(self) -> None:
        now = 1_700_000_000_000
        nonce = bytes(range(1, 17))
        fresh = gba.seal(SECRET, '{"v":2,"type":"hello"}', nonce=nonce, now_ms=now)
        stale = gba.seal(
            SECRET,
            '{"v":2,"type":"hello"}',
            nonce=bytes(range(2, 18)),
            now_ms=now - gba.MAX_AGE_MS - 1,
        )
        future = gba.seal(
            SECRET,
            '{"v":2,"type":"hello"}',
            nonce=bytes(range(3, 19)),
            now_ms=now + gba.MAX_FUTURE_MS + 1,
        )
        self.assertIsNone(gba.unseal(SECRET, stale, now))
        self.assertIsNone(gba.unseal(SECRET, future, now))
        opened = gba.unseal(SECRET, fresh, now)
        self.assertIsNotNone(opened)
        assert opened is not None
        window = gba.ReplayWindow()
        self.assertTrue(window.accept(opened[1], opened[2], now))
        self.assertFalse(window.accept(opened[1], opened[2], now))
        tiny = gba.ReplayWindow(max_size=1)
        self.assertTrue(tiny.accept("aa", now, now))
        self.assertFalse(tiny.accept("bb", now, now))

    def test_forged_reply_topic_is_ignored(self) -> None:
        self.assertEqual(gba.reply_destination(SECRET, "attacker"), gba.reply_topic(SECRET))
        self.assertNotEqual(gba.reply_destination(SECRET, "attacker"), "attacker")
        now = 1_700_000_000_000
        inner = json.dumps(
            {
                "v": 2,
                "type": "alarm",
                "id": "abc",
                "replyTopic": "attacker",
                "question": "Ship?",
            },
            separators=(",", ":"),
        )
        wire = gba.seal(SECRET, inner, nonce=bytes(range(4, 20)), now_ms=now)
        opened = gba.unseal(SECRET, wire, now)
        self.assertIsNotNone(opened)
        assert opened is not None
        self.assertNotIn("replyTopic", opened[0])
        messages = [
            {
                "event": "message",
                "time": 1,
                "message": gba.seal(
                    SECRET,
                    json.dumps(
                        {
                            "v": 2,
                            "type": "answer",
                            "id": "abc",
                            "choice": "Yes",
                            "instruction": "Go",
                            "replyTopic": "attacker",
                        },
                        separators=(",", ":"),
                    ),
                    nonce=bytes(range(5, 21)),
                    now_ms=now,
                ),
            }
        ]
        window = gba.ReplayWindow()
        answer = gba.extract_answer(messages, "abc", SECRET, now, window)
        self.assertEqual(answer["instruction"], "Go")
        self.assertNotIn("replyTopic", answer)

    def test_rate_limit_is_per_agent_and_ring_cap(self) -> None:
        gate = gba.RateGate(max_events=8, window_ms=60_000, max_ringing=4)
        start = 1_000_000
        for _ in range(8):
            self.assertTrue(gate.allow("a", start))
        self.assertFalse(gate.allow("a", start + 1_000))
        self.assertTrue(gate.allow("b", start))
        self.assertFalse(gate.allow("a", start + 60_000))
        self.assertTrue(gate.allow("a", start + 60_001))
        self.assertTrue(gate.under_ringing_cap(3))
        self.assertFalse(gate.under_ringing_cap(4))

    def test_publish_rejects_bad_topics_and_cleartext(self) -> None:
        with self.assertRaises(RuntimeError):
            gba.publish("https://ntfy.sh", "../secret", "x", 3)
        with self.assertRaises(RuntimeError):
            gba.publish("http://10.0.0.8", "abc", "x", 3)
        with self.assertRaises(RuntimeError):
            gba.publish("https://user:pass@ntfy.sh", "abc", "x", 3)
        self.assertFalse(gba.valid_server("http://ntfy.sh"))
        self.assertTrue(gba.valid_server("https://ntfy.sh"))
        self.assertTrue(gba.valid_server("http://127.0.0.1:9"))


class CliTest(unittest.TestCase):
    def setUp(self) -> None:
        STORE.clear()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.base = f"http://{host}:{port}"
        self.tmp = tempfile.TemporaryDirectory()
        self.home = self.tmp.name

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def run_cli(self, *args: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["GROKBOT_ALARM_HOME"] = self.home
        env.pop("GROKBOT_ALARM_SECRET", None)
        env.pop("GROKBOT_ALARM_SERVER", None)
        env.pop("GROKBOT_ALARM_AGENT", None)
        return subprocess.run(
            [sys.executable, str(CLI), *args],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def secret_of(self, agent: str = "default") -> str:
        path = Path(self.home) / "agents" / f"{gba.agent_slug(agent)}.json"
        return json.loads(path.read_text(encoding="utf-8"))["secret"]

    def topics(self, agent: str = "default") -> tuple[str, str]:
        secret = self.secret_of(agent)
        return gba.inbox_topic(secret), gba.reply_topic(secret)

    def last_inner(self, topic: str, secret: str) -> dict:
        with LOCK:
            body = STORE[topic][-1]
        opened = gba.unseal(secret, body, int(time.time() * 1000) + 5_000)
        self.assertIsNotNone(opened, body)
        assert opened is not None
        return opened[0]

    def post_answer(self, secret: str, topic: str, alarm_id: str, instruction: str, choice: str = "Yes") -> None:
        inner = json.dumps(
            {"v": 2, "type": "answer", "id": alarm_id, "choice": choice, "instruction": instruction},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        wire = gba.seal(secret, inner)
        req = urllib.request.Request(f"{self.base}/{topic}", data=wire.encode(), method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)

    def test_register_ring_and_answer(self) -> None:
        init = self.run_cli("init", "--server", self.base)
        self.assertEqual(init.returncode, 0, init.stderr)
        paired = json.loads(init.stdout)
        self.assertEqual(set(paired), {"v", "agent", "pair"})
        secret = self.secret_of()
        inbox, reply = self.topics()
        self.assertNotIn(secret, init.stderr)
        self.assertNotIn(inbox, init.stdout)
        self.assertNotIn(reply, init.stdout)
        self.assertNotIn(inbox, init.stderr)
        self.assertNotIn(reply, init.stderr)
        self.assertNotIn(paired["pair"], init.stderr)
        self.assertNotIn(secret, init.stdout.replace(paired["pair"], ""))
        mode = stat.S_IMODE(Path(self.home).stat().st_mode)
        self.assertEqual(mode, 0o700)
        self.assertEqual(stat.S_IMODE((Path(self.home) / "agents").stat().st_mode), 0o700)
        cfg_mode = stat.S_IMODE((Path(self.home) / "agents" / "default.json").stat().st_mode)
        self.assertEqual(cfg_mode, 0o600)

        registered = self.run_cli("register", "--bot", "coder", "--task", "Split the session store")
        self.assertEqual(registered.returncode, 0, registered.stderr)
        alarm_id = registered.stdout.strip()
        self.assertEqual(len(alarm_id), 16)

        register_body = self.last_inner(inbox, secret)
        self.assertEqual(register_body["type"], "register")
        self.assertEqual(register_body["bot"], "coder")
        self.assertNotIn("replyTopic", register_body)

        ring = self.run_cli(
            "ring",
            "--id",
            alarm_id,
            "--question",
            "Keep server sessions or switch to signed tokens?",
            "--option",
            "Keep sessions",
            "--option",
            "Switch to signed tokens",
            "--next",
            "Open a PR",
            "--next",
            "Stop",
        )
        self.assertEqual(ring.returncode, 0, ring.stderr)
        alarm = self.last_inner(inbox, secret)
        self.assertEqual(alarm["type"], "alarm")
        self.assertNotIn("replyTopic", alarm)
        self.assertEqual(alarm["options"], ["Keep sessions", "Switch to signed tokens"])

        missing = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(missing.returncode, 2, missing.stderr)
        self.assertEqual(missing.stdout, "")

        self.post_answer(secret, reply, alarm_id, "Open a PR", "Switch to signed tokens")
        waited = self.run_cli("wait", "--id", alarm_id, "--timeout", "5")
        self.assertEqual(waited.returncode, 0, waited.stderr)
        got = json.loads(waited.stdout)
        self.assertEqual(got["choice"], "Switch to signed tokens")
        self.assertEqual(got["instruction"], "Open a PR")
        self.assertEqual(got["v"], 2)

        polled = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(polled.returncode, 0, polled.stderr)
        self.assertEqual(json.loads(polled.stdout)["instruction"], "Open a PR")

    def test_unsigned_and_replayed_answers_are_ignored(self) -> None:
        init = self.run_cli("init", "--server", self.base)
        self.assertEqual(init.returncode, 0, init.stderr)
        secret = self.secret_of()
        _inbox, reply = self.topics()
        registered = self.run_cli("register", "--bot", "coder", "--task", "A task")
        alarm_id = registered.stdout.strip()
        ring = self.run_cli(
            "ring",
            "--id",
            alarm_id,
            "--question",
            "Ship it?",
            "--option",
            "Yes",
            "--option",
            "No",
        )
        self.assertEqual(ring.returncode, 0, ring.stderr)
        forged = json.dumps(
            {"v": 1, "type": "answer", "id": alarm_id, "choice": "Yes", "instruction": "Stolen"}
        )
        req = urllib.request.Request(f"{self.base}/{reply}", data=forged.encode(), method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
        missing = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(missing.returncode, 2, missing.stderr)

        inner = json.dumps(
            {"v": 2, "type": "answer", "id": alarm_id, "choice": "Yes", "instruction": "Real"},
            separators=(",", ":"),
        )
        wire = gba.seal(secret, inner, nonce=bytes(range(8, 24)))
        for _ in range(2):
            req = urllib.request.Request(f"{self.base}/{reply}", data=wire.encode(), method="POST")
            with urllib.request.urlopen(req, timeout=5) as resp:
                self.assertEqual(resp.status, 200)
        waited = self.run_cli("wait", "--id", alarm_id, "--timeout", "5")
        self.assertEqual(waited.returncode, 0, waited.stderr)
        self.assertEqual(json.loads(waited.stdout)["instruction"], "Real")

    def test_forged_reply_topic_does_not_move_the_poll(self) -> None:
        self.run_cli("init", "--server", self.base)
        secret = self.secret_of()
        inbox, reply = self.topics()
        registered = self.run_cli("register", "--bot", "coder", "--task", "A task")
        alarm_id = registered.stdout.strip()
        self.run_cli(
            "ring",
            "--id",
            alarm_id,
            "--question",
            "Ship it?",
            "--option",
            "Yes",
            "--option",
            "No",
        )
        state_path = Path(self.home) / "agents" / "default.state.json"
        state = json.loads(state_path.read_text(encoding="utf-8"))
        state["alarms"][alarm_id]["reply_topic"] = "attacker"
        state_path.write_text(json.dumps(state), encoding="utf-8")
        urllib.request.urlopen(
            urllib.request.Request(
                f"{self.base}/attacker",
                data=gba.seal(
                    secret,
                    json.dumps(
                        {"v": 2, "type": "answer", "id": alarm_id, "choice": "No", "instruction": "Pwned"},
                        separators=(",", ":"),
                    ),
                ).encode(),
                method="POST",
            ),
            timeout=5,
        )
        self.post_answer(secret, reply, alarm_id, "Keep going", "Yes")
        waited = self.run_cli("wait", "--id", alarm_id, "--timeout", "5")
        self.assertEqual(waited.returncode, 0, waited.stderr)
        self.assertEqual(json.loads(waited.stdout)["instruction"], "Keep going")
        self.assertNotIn("replyTopic", self.last_inner(inbox, secret))

    def test_rejects_a_single_option(self) -> None:
        self.assertEqual(self.run_cli("init", "--server", self.base).returncode, 0)
        registered = self.run_cli("register", "--bot", "coder", "--task", "A task")
        alarm_id = registered.stdout.strip()
        ring = self.run_cli(
            "ring",
            "--id",
            alarm_id,
            "--question",
            "Pick one?",
            "--option",
            "Only",
        )
        self.assertEqual(ring.returncode, 1)
        self.assertIn("2 to 6", ring.stderr)

    def test_agent_name_and_escalation(self) -> None:
        init = self.run_cli("init", "--server", self.base, "--agent", "coder")
        self.assertEqual(init.returncode, 0, init.stderr)
        paired = json.loads(init.stdout)
        self.assertEqual(paired["agent"], "coder")
        secret = self.secret_of("coder")
        inbox, _reply = self.topics("coder")
        hello = self.last_inner(inbox, secret)
        self.assertEqual(hello["type"], "hello")
        self.assertEqual(hello["agent"], "coder")
        registered = self.run_cli("register", "--bot", "coder", "--task", "Ship the flag")
        alarm_id = registered.stdout.strip()
        body = self.last_inner(inbox, secret)
        self.assertEqual(body["agent"], "coder")
        self.assertEqual(body["type"], "register")
        ring = self.run_cli(
            "ring",
            "--id",
            alarm_id,
            "--question",
            "Flag it?",
            "--option",
            "Yes",
            "--option",
            "No",
            "--escalate-after",
            "90",
        )
        self.assertEqual(ring.returncode, 0, ring.stderr)
        needs = self.last_inner(inbox, secret)
        self.assertEqual(needs["type"], "needs")
        self.assertEqual(needs["escalateAfter"], 90)
        self.assertEqual(needs["agent"], "coder")
        self.assertNotIn("replyTopic", needs)

    def test_first_answer_wins_when_duplicates_arrive(self) -> None:
        self.run_cli("init", "--server", self.base)
        secret = self.secret_of()
        _inbox, reply = self.topics()
        registered = self.run_cli("register", "--bot", "coder", "--task", "A task")
        alarm_id = registered.stdout.strip()
        ring = self.run_cli(
            "ring",
            "--id",
            alarm_id,
            "--question",
            "Ship it?",
            "--option",
            "Yes",
            "--option",
            "No",
        )
        self.assertEqual(ring.returncode, 0, ring.stderr)
        self.post_answer(secret, reply, alarm_id, "잠깐 멈춰", "Yes")
        self.post_answer(secret, reply, alarm_id, "계속 개발해", "Yes")
        waited = self.run_cli("wait", "--id", alarm_id, "--timeout", "5")
        self.assertEqual(waited.returncode, 0, waited.stderr)
        self.assertEqual(json.loads(waited.stdout)["instruction"], "잠깐 멈춰")
        polled = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(json.loads(polled.stdout)["instruction"], "잠깐 멈춰")

    def test_redirect_to_another_host_is_refused(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), RedirectHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        host, port = server.server_address
        with self.assertRaises(RuntimeError) as caught:
            gba.publish(f"http://{host}:{port}", "abc", "hello", 3)
        self.assertIn("redirect", str(caught.exception))

    def test_install_script_generates_a_link_and_still_rings(self) -> None:
        home_dir = tempfile.TemporaryDirectory()
        self.addCleanup(home_dir.cleanup)
        home = home_dir.name
        env = os.environ.copy()
        env["HOME"] = home
        env["GROKBOT_ALARM_SRC"] = str(ROOT)
        env.pop("GROKBOT_ALARM_HOME", None)
        env.pop("GROKBOT_ALARM_SECRET", None)
        env.pop("GROKBOT_ALARM_SERVER", None)
        env.pop("GROKBOT_ALARM_AGENT", None)
        env.pop("XDG_CONFIG_HOME", None)

        def install() -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                ["sh", str(ROOT / "install.sh"), "--server", self.base, "--agent", "Coder One"],
                capture_output=True,
                text=True,
                env=env,
                check=False,
            )

        first = install()
        self.assertEqual(first.returncode, 0, first.stderr)
        paired = json.loads(first.stdout)
        self.assertEqual(paired["agent"], "Coder One")
        self.assertNotIn("secret", paired)
        cfg_path = Path(home) / ".config" / "grokbot-alarm" / "agents" / "coder-one.json"
        stored = json.loads(cfg_path.read_text(encoding="utf-8"))
        secret = stored["secret"]
        self.assertNotIn(secret, paired["pair"].split("#", 1)[0])
        self.assertNotIn(secret, first.stderr)
        self.assertNotIn(paired["pair"], first.stderr)
        self.assertEqual(stat.S_IMODE(cfg_path.parent.stat().st_mode), 0o700)
        parsed = gba.parse_pairing_link(paired["pair"])
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(parsed["secret"], secret)
        self.assertEqual(parsed["server"], self.base)
        self.assertEqual(parsed["name"], "Coder One")

        again = install()
        self.assertEqual(again.returncode, 0, again.stderr)
        again_cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        self.assertEqual(again_cfg["secret"], secret)

        wrapper = str(Path(home) / ".local" / "bin" / "grokbot-alarm")
        registered = subprocess.run(
            [wrapper, "register", "--agent", "Coder One", "--bot", "Coder One", "--task", "MVP check"],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )
        self.assertEqual(registered.returncode, 0, registered.stderr)
        alarm_id = registered.stdout.strip()
        ring = subprocess.run(
            [
                wrapper,
                "ring",
                "--agent",
                "Coder One",
                "--id",
                alarm_id,
                "--question",
                "Ship it?",
                "--option",
                "Yes",
                "--option",
                "No",
            ],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )
        self.assertEqual(ring.returncode, 0, ring.stderr)
        reply = gba.reply_topic(secret)
        inner = json.dumps(
            {"v": 2, "type": "answer", "id": alarm_id, "choice": "Yes", "instruction": "Keep going"},
            separators=(",", ":"),
        )
        req = urllib.request.Request(
            f"{self.base}/{reply}",
            data=gba.seal(secret, inner).encode(),
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
        waited = subprocess.run(
            [wrapper, "wait", "--agent", "Coder One", "--id", alarm_id, "--timeout", "5"],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )
        self.assertEqual(waited.returncode, 0, waited.stderr)
        self.assertEqual(json.loads(waited.stdout)["instruction"], "Keep going")

    def test_installer_refuses_an_unpinned_download(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            script = Path(tmp) / "install.sh"
            script.write_bytes((ROOT / "install.sh").read_bytes())
            script.chmod(0o755)
            env = os.environ.copy()
            env["HOME"] = tmp
            env.pop("GROKBOT_ALARM_SRC", None)
            env.pop("GROKBOT_ALARM_REV", None)
            result = subprocess.run(
                ["sh", str(script), "--agent", "Nope"],
                capture_output=True,
                text=True,
                env=env,
                check=False,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unpinned", result.stderr)
        self.assertNotIn("secret", result.stdout)

    def test_checksum_file_matches_cli_and_skill(self) -> None:
        cli_hash = hashlib.sha256((ROOT / "cli" / "grokbot_alarm.py").read_bytes()).hexdigest()
        skill_hash = hashlib.sha256((ROOT / "skill" / "SKILL.md").read_bytes()).hexdigest()
        install_hash = hashlib.sha256((ROOT / "install.sh").read_bytes()).hexdigest()
        sums = (ROOT / "checksums.sha256").read_text(encoding="utf-8")
        install = (ROOT / "install.sh").read_text(encoding="utf-8")
        self.assertIn(cli_hash, sums)
        self.assertIn(skill_hash, sums)
        self.assertIn(install_hash, sums)
        self.assertIn(cli_hash, install)
        self.assertIn(skill_hash, install)


class PairParseTest(unittest.TestCase):
    def test_fragment_scheme_and_intent(self) -> None:
        secret = SECRET
        link = gba.pairing_link("https://ntfy.sh", secret, "Coder One")
        self.assertEqual(
            link,
            "https://wanmandu.github.io/grokbot-alarm/pair"
            f"#s={secret}&srv=https%3A%2F%2Fntfy.sh&n=Coder%20One",
        )
        got = gba.parse_pairing_link(link)
        self.assertEqual(got, {"secret": secret, "server": "https://ntfy.sh", "name": "Coder One"})
        query = "&".join(
            (
                f"s={secret}",
                "srv=" + quote("http://127.0.0.1:8080", safe=""),
                "n=" + quote("코더", safe=""),
            )
        )
        for url in (
            f"grokbot-alarm://pair?{query}",
            f"intent://pair?{query}#Intent;scheme=grokbot-alarm;package=works.gauss.grokbotalarm;end",
        ):
            parsed = gba.parse_pairing_link(url)
            self.assertEqual(parsed["server"], "http://127.0.0.1:8080")
            self.assertEqual(parsed["name"], "코더")
        self.assertIsNone(gba.parse_pairing_link(f"https://example.com/pair#s={secret}&srv=https%3A%2F%2Fntfy.sh"))
        self.assertIsNone(
            gba.parse_pairing_link(
                "https://wanmandu.github.io/grokbot-alarm/pair#s=short&srv=https%3A%2F%2Fntfy.sh&n=A"
            )
        )
        self.assertIsNone(
            gba.parse_pairing_link(
                f"https://wanmandu.github.io/grokbot-alarm/pair?s={secret}&srv=https%3A%2F%2Fntfy.sh&n=A"
            )
        )
        public_http = "&".join(
            (f"s={secret}", "srv=" + quote("http://10.0.0.8", safe=""), "n=A")
        )
        self.assertIsNone(gba.parse_pairing_link(f"grokbot-alarm://pair?{public_http}"))


if __name__ == "__main__":
    unittest.main()
