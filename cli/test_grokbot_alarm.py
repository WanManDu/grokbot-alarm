#!/usr/bin/env python3
"""Local end-to-end test for the CLI against a fake ntfy server."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "cli" / "grokbot_alarm.py"

STORE: dict[str, list[str]] = {}
LOCK = threading.Lock()


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
        since = 0
        qs = parse_qs(parsed.query)
        if "since" in qs:
            try:
                since = int(qs["since"][0])
            except ValueError:
                since = 0
        del since  # the fake keeps the whole cache; the CLI filters by id
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

    def test_register_ring_and_answer(self) -> None:
        init = self.run_cli("init", "--server", self.base)
        self.assertEqual(init.returncode, 0, init.stderr)
        paired = json.loads(init.stdout)
        self.assertTrue(paired["inbox"].startswith("gba"))
        self.assertTrue(paired["reply"].startswith("gbr"))

        registered = self.run_cli("register", "--bot", "coder", "--task", "Split the session store")
        self.assertEqual(registered.returncode, 0, registered.stderr)
        alarm_id = registered.stdout.strip()
        self.assertEqual(len(alarm_id), 16)

        with LOCK:
            register_body = json.loads(STORE[paired["inbox"]][-1])
        self.assertEqual(register_body["type"], "register")
        self.assertEqual(register_body["bot"], "coder")

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
        with LOCK:
            alarm = json.loads(STORE[paired["inbox"]][-1])
        self.assertEqual(alarm["type"], "alarm")
        self.assertEqual(alarm["replyTopic"], paired["reply"])
        self.assertEqual(alarm["options"], ["Keep sessions", "Switch to signed tokens"])

        missing = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(missing.returncode, 2, missing.stderr)
        self.assertEqual(missing.stdout, "")

        answer = json.dumps(
            {
                "v": 1,
                "type": "answer",
                "id": alarm_id,
                "choice": "Switch to signed tokens",
                "instruction": "Open a PR",
            }
        )
        req = urllib.request.Request(
            f"{self.base}/{paired['reply']}",
            data=answer.encode(),
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)

        waited = self.run_cli("wait", "--id", alarm_id, "--timeout", "5")
        self.assertEqual(waited.returncode, 0, waited.stderr)
        got = json.loads(waited.stdout)
        self.assertEqual(got["choice"], "Switch to signed tokens")
        self.assertEqual(got["instruction"], "Open a PR")

        polled = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(polled.returncode, 0, polled.stderr)
        self.assertEqual(json.loads(polled.stdout)["instruction"], "Open a PR")

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
        registered = self.run_cli("register", "--bot", "coder", "--task", "Ship the flag")
        alarm_id = registered.stdout.strip()
        with LOCK:
            body = json.loads(STORE[paired["inbox"]][-1])
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
        with LOCK:
            needs = json.loads(STORE[paired["inbox"]][-1])
        self.assertEqual(needs["type"], "needs")
        self.assertEqual(needs["escalateAfter"], 90)
        self.assertEqual(needs["agent"], "coder")

    def test_first_answer_wins_when_duplicates_arrive(self) -> None:
        init = self.run_cli("init", "--server", self.base)
        self.assertEqual(init.returncode, 0, init.stderr)
        paired = json.loads(init.stdout)
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

        def post(instruction: str) -> None:
            body = json.dumps(
                {
                    "v": 1,
                    "type": "answer",
                    "id": alarm_id,
                    "choice": "Yes",
                    "instruction": instruction,
                }
            )
            req = urllib.request.Request(
                f"{self.base}/{paired['reply']}",
                data=body.encode(),
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                self.assertEqual(resp.status, 200)

        post("잠깐 멈춰")
        post("계속 개발해")
        waited = self.run_cli("wait", "--id", alarm_id, "--timeout", "5")
        self.assertEqual(waited.returncode, 0, waited.stderr)
        self.assertEqual(json.loads(waited.stdout)["instruction"], "잠깐 멈춰")
        polled = self.run_cli("poll", "--id", alarm_id)
        self.assertEqual(json.loads(polled.stdout)["instruction"], "잠깐 멈춰")


if __name__ == "__main__":
    unittest.main()
