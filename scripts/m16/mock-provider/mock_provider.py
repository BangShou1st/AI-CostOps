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
- GET /stats -> {"post_chat_completions": N, "mode": M, "held": H}
  (acceptance assertion source; H = streams currently held open by hold mode).
- POST /admin/reset -> {"post_chat_completions": 0}; resets the counter,
  restores mode ok, releases every held stream and closes every hold barrier.
- POST /admin/mode {"mode": "ok"|"http500"|"timeout"|"hold"} selects the
  failure profile for failure-injection scenarios (default "ok"). "timeout"
  sleeps 70s (beyond the 60s header timeout) to simulate a hung upstream.
  "hold" sends SSE response headers immediately, then blocks on a barrier
  until POST /admin/release: deterministic stream occupancy for the B04
  active-stream ceiling proof (no sleeps in the harness).
- POST /admin/release -> releases every stream held by hold mode.
- Every POST /v1/chat/completions increments the counter exactly once,
  including injected-failure calls, so Provider-operation counting stays
  exact under failure injection.

Threading: ThreadingHTTPServer so 100-way concurrent replay is served
concurrently; the counter, mode, barrier generation, and held count are
guarded by a lock.
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
_hold_release = threading.Event()
_hold_release.set()
_hold_generation = 0
_held_streams = 0


def _bump() -> int:
    global _post_count
    with _lock:
        _post_count += 1
        return _post_count


def _snapshot() -> int:
    with _lock:
        return _post_count


def _held() -> int:
    with _lock:
        return _held_streams


def _reset() -> None:
    global _post_count, _hold_generation
    with _lock:
        _post_count = 0
        _hold_generation += 1
    # A set event releases every stream blocked in hold mode across all
    # generations; a cleared event (set by _mode_set("hold")) starts a new
    # hold barrier. Ordering: bump the generation first so a concurrent
    # holder observes the new generation and waits on the cleared event.
    _hold_release.set()


def _mode_get() -> str:
    with _lock:
        return _mode


def _mode_set(value: str) -> None:
    global _mode, _hold_generation
    with _lock:
        _mode = value
        if value == "hold":
            _hold_generation += 1
            _hold_release.clear()


def _mode_and_generation() -> tuple:
    with _lock:
        return _mode, _hold_generation


def _release_held() -> int:
    global _hold_generation
    with _lock:
        _hold_generation += 1
        held = _held_streams
    _hold_release.set()
    return held


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
            self._send_json(200, {
                "post_chat_completions": _snapshot(),
                "mode": _mode_get(),
                "held": _held(),
            })
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
            if mode not in ("ok", "http500", "timeout", "hold"):
                self._send_json(400, {"error": "unknown mode"})
                return
            _mode_set(mode)
            self._send_json(200, {"mode": mode})
            return
        if path == "/admin/release":
            held = _release_held()
            self._send_json(200, {"released": held})
            return
        if path != "/v1/chat/completions":
            self._send_json(404, {"error": "not found"})
            return
        try:
            payload = json.loads(raw.decode("utf-8") or "{}")
        except ValueError:
            payload = {}
        stream = payload.get("stream") is True or "text/event-stream" in (
            self.headers.get("Accept") or ""
        )
        mode, generation = _mode_and_generation()
        _bump()
        if mode == "http500":
            self._send_json(500, {"error": {"message": "m16 injected failure", "type": "server_error"}})
            return
        if mode == "timeout":
            time.sleep(70)
            return
        if mode == "hold" and stream:
            # Deterministic B04 occupancy: headers first (so the Gateway
            # commits the stream and holds its Semaphore permit), then block
            # on the release barrier; the harness polls /stats.held ==
            # ceiling instead of sleeping. After release, complete normally.
            global _held_streams
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write((CHUNK_FRAMES[0] + "\n\n").encode("utf-8"))
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            with _lock:
                if _mode == "hold":
                    _held_streams += 1
                    hold_active = True
                else:
                    hold_active = False
            try:
                while True:
                    if _hold_release.wait(timeout=5):
                        break
                    with _lock:
                        if _mode != "hold":
                            break
            finally:
                with _lock:
                    if hold_active:
                        _held_streams -= 1
            if generation != _mode_and_generation()[1]:
                return
            try:
                rest = "".join(frame + "\n\n" for frame in CHUNK_FRAMES[1:]).encode("utf-8")
                self.wfile.write(rest)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
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
