"""M16 deterministic mock Provider (MiMo-compatible, OpenAI-style wire format).

Zero third-party dependencies: Python standard library only. Runs inside the
isolated M16 acceptance network as the `mock-provider` Compose service.

Behavior:
- POST /v1/chat/completions (JSON {"stream": true} or SSE Accept) ->
  chunk frames + terminal usage frame + `data: [DONE]`.
- POST /v1/chat/completions (non-stream) ->
  fixed chat.completion JSON with deterministic usage
  (prompt_tokens=5, completion_tokens=3, total_tokens=8).
- GET /health -> {"status": "UP"}.
- GET /stats -> {"post_chat_completions": N} (acceptance assertion source).
- POST /admin/reset -> {"post_chat_completions": 0} and resets the counter.
- POST /admin/mode {"mode": "ok"|"http500"|"timeout"} selects the failure
  profile for failure-injection scenarios (default "ok"). "timeout" sleeps
  70s (beyond the 60s header timeout) to simulate a hung upstream.
- Every POST /v1/chat/completions increments the counter exactly once,
  including injected-failure calls, so Provider-operation counting stays
  exact under failure injection.

Threading: ThreadingHTTPServer so 100-way concurrent replay is served
concurrently; the counter is guarded by a lock.
"""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT = int(os.environ.get("MOCK_PROVIDER_PORT", "8089"))

COMPLETION_BODY = {
    "id": "chatcmpl_m16mock",
    "object": "chat.completion",
    "created": 1788000100,
    "model": "mimo-v2.5-pro",
    "choices": [
        {
            "index": 0,
            "message": {"role": "assistant", "content": "Hello from M16 mock"},
            "finish_reason": "stop",
        }
    ],
    "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
}

CHUNK_FRAMES = [
    'data: {"id":"chatcmpl_m16sse0","object":"chat.completion.chunk",'
    '"created":1788000200,"model":"mimo-v2.5-pro",'
    '"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}',
    'data: {"id":"chatcmpl_m16sse1","object":"chat.completion.chunk",'
    '"created":1788000200,"model":"mimo-v2.5-pro",'
    '"choices":[{"index":0,"delta":{"content":" mock"},"finish_reason":null}]}',
    'data: {"id":"chatcmpl_m16usage","created":1788000200,"model":"mimo-v2.5-pro",'
    '"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}',
    "data: [DONE]",
]

_lock = threading.Lock()
_post_count = 0
_mode = "ok"


def _bump() -> int:
    global _post_count
    with _lock:
        _post_count += 1
        return _post_count


def _snapshot() -> int:
    with _lock:
        return _post_count


def _reset() -> None:
    global _post_count
    with _lock:
        _post_count = 0


def _mode_get() -> str:
    with _lock:
        return _mode


def _mode_set(value: str) -> None:
    global _mode
    with _lock:
        _mode = value


class Handler(BaseHTTPRequestHandler):
    server_version = "M16MockProvider/1.0"

    def log_message(self, *args):  # keep container logs quiet
        pass

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/health":
            self._send_json(200, {"status": "UP"})
        elif path == "/stats":
            self._send_json(200, {"post_chat_completions": _snapshot(), "mode": _mode_get()})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        path = urlsplit(self.path).path
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length > 0 else b""
        if path == "/admin/reset":
            _reset()
            _mode_set("ok")
            self._send_json(200, {"post_chat_completions": 0})
            return
        if path == "/admin/mode":
            try:
                mode = (json.loads(raw.decode("utf-8") or "{}").get("mode") or "ok")
            except ValueError:
                mode = "ok"
            if mode not in ("ok", "http500", "timeout"):
                self._send_json(400, {"error": "unknown mode"})
                return
            _mode_set(mode)
            self._send_json(200, {"mode": mode})
            return
        if path != "/v1/chat/completions":
            self._send_json(404, {"error": "not found"})
            return
        _bump()
        mode = _mode_get()
        if mode == "http500":
            self._send_json(500, {"error": {"message": "m16 injected failure", "type": "server_error"}})
            return
        if mode == "timeout":
            time.sleep(70)
            return
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except ValueError:
            payload = {}
        stream = payload.get("stream") is True or "text/event-stream" in (
            self.headers.get("Accept") or ""
        )
        if not stream:
            self._send_json(200, COMPLETION_BODY)
            return
        frames = "".join(frame + "\n\n" for frame in CHUNK_FRAMES).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.send_header("Content-Length", str(len(frames)))
        self.end_headers()
        self.wfile.write(frames)

    def do_PUT(self) -> None:  # pragma: no cover - guardrail
        self._send_json(405, {"error": "method not allowed"})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
