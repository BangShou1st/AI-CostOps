"""HC-01 deterministic mock Provider (MiMo/OpenAI-compatible wire format).

Derived from the frozen M16 mock Provider protocol (scripts/m16/mock-provider)
so HC-01 exercises the same Gateway adapter path without modifying the M16
acceptance asset. Zero third-party dependencies: Python standard library only.

Modes (POST /admin/mode {"mode": "...", "delayMs": N}):
- ok             immediate deterministic JSON / SSE completion (default)
- delay          wait delayMs before the first byte (non-stream throughput
                 under controlled upstream latency)
- slow-stream    SSE only: send one chunk every delayMs, then usage + [DONE]
- disconnect     send a truncated response/stream and close the connection
                 (transport failure after dispatch -> UNKNOWN/INCOMPLETE path)
- http500        deterministic upstream HTTP 500
- timeout        sleep 70s (beyond the Gateway header timeout)
- hold           SSE: send headers + one chunk, then block until
                 /admin/release (deterministic stream-permit occupancy)
- advisor-success  fixed valid Advisor narrative (unchanged M16 mode)

Every POST /v1/chat/completions increments the counter exactly once,
including injected failures. /stats is the acceptance assertion source.
"""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT = int(os.environ.get("MOCK_PROVIDER_PORT", "8089"))

COMPLETION_BODY = {
    "id": "chatcmpl_hc01mock",
    "object": "chat.completion",
    "created": 1788000100,
    "model": "mimo-v2.5-pro",
    "choices": [
        {
            "index": 0,
            "message": {"role": "assistant", "content": "Hello from HC-01 mock"},
            "finish_reason": "stop",
        }
    ],
    "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
}

ADVISOR_COMPLETION_BODY = {
    **COMPLETION_BODY,
    "choices": [
        {
            "index": 0,
            "message": {
                "role": "assistant",
                "content": (
                    '{"summary":"Mock advisor completed.",'
                    '"driversExplanation":"Deterministic mock explanation.",'
                    '"recommendedActions":[],"warnings":[],"factReferences":[]}'
                ),
            },
            "finish_reason": "stop",
        }
    ],
}

CHUNK_FRAMES = [
    'data: {"id":"chatcmpl_hc01sse0","object":"chat.completion.chunk",'
    '"created":1788000200,"model":"mimo-v2.5-pro",'
    '"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}',
    'data: {"id":"chatcmpl_hc01sse1","object":"chat.completion.chunk",'
    '"created":1788000200,"model":"mimo-v2.5-pro",'
    '"choices":[{"index":0,"delta":{"content":" mock"},"finish_reason":null}]}',
    'data: {"id":"chatcmpl_hc01usage","created":1788000200,"model":"mimo-v2.5-pro",'
    '"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}',
    "data: [DONE]",
]

_lock = threading.Lock()
_post_count = 0
_stream_post_count = 0
_hold_start_count = 0
_disconnect_count = 0
_mode = "ok"
_delay_ms = 0
_hold_release = threading.Event()
_hold_release.set()
_hold_generation = 0
_held_streams = 0

VALID_MODES = (
    "ok",
    "delay",
    "slow-stream",
    "disconnect",
    "advisor-success",
    "http500",
    "timeout",
    "hold",
)


def _bump(stream: bool) -> int:
    global _post_count, _stream_post_count
    with _lock:
        _post_count += 1
        if stream:
            _stream_post_count += 1
        return _post_count


def _bump_diagnostic(name: str) -> None:
    global _hold_start_count, _disconnect_count
    with _lock:
        if name == "hold":
            _hold_start_count += 1
        elif name == "disconnect":
            _disconnect_count += 1


def _snapshot() -> int:
    with _lock:
        return _post_count


def _held() -> int:
    with _lock:
        return _held_streams


def _reset() -> None:
    global _post_count, _stream_post_count, _hold_start_count, _disconnect_count, _hold_generation
    with _lock:
        _post_count = 0
        _stream_post_count = 0
        _hold_start_count = 0
        _disconnect_count = 0
        _hold_generation += 1
    _hold_release.set()


def _mode_get() -> str:
    with _lock:
        return _mode


def _delay_get() -> int:
    with _lock:
        return _delay_ms


def _mode_set(value: str, delay_ms: int = 0) -> None:
    global _mode, _delay_ms, _hold_generation
    with _lock:
        _mode = value
        _delay_ms = max(0, int(delay_ms))
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
    server_version = "HC01MockProvider/1.0"

    def log_message(self, *args):
        pass

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _abort_truncated(self) -> None:
        """Send a short body with a lying Content-Length, then close.

        The Gateway observes a transport failure after dispatch, which is the
        path that must preserve UNKNOWN/INCOMPLETE rather than zero cost.
        """
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", "4096")
        self.end_headers()
        try:
            self.wfile.write(b'{"id":"chatcmpl_hc01trunc')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        self.close_connection = True

    def _hold_stream(self) -> None:
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
        try:
            rest = "".join(frame + "\n\n" for frame in CHUNK_FRAMES[1:]).encode("utf-8")
            self.wfile.write(rest)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _slow_stream(self, delay_ms: int) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for frame in CHUNK_FRAMES:
            try:
                self.wfile.write((frame + "\n\n").encode("utf-8"))
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            time.sleep(delay_ms / 1000.0)

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/health":
            self._send_json(200, {"status": "UP"})
        elif path == "/stats":
            with _lock:
                stream_posts = _stream_post_count
                hold_starts = _hold_start_count
                disconnects = _disconnect_count
            self._send_json(200, {
                "post_chat_completions": _snapshot(),
                "stream_posts": stream_posts,
                "hold_starts": hold_starts,
                "disconnect_posts": disconnects,
                "mode": _mode_get(),
                "delay_ms": _delay_get(),
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
                payload = json.loads(raw.decode("utf-8") or "{}")
            except ValueError:
                payload = {}
            mode = payload.get("mode") or "ok"
            delay_ms = payload.get("delayMs") or 0
            if mode not in VALID_MODES:
                self._send_json(400, {"error": "unknown mode"})
                return
            _mode_set(mode, delay_ms)
            self._send_json(200, {"mode": mode, "delay_ms": _delay_get()})
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
        delay_ms = _delay_get()
        _bump(stream)
        if stream and mode == "hold":
            _bump_diagnostic("hold")
        if mode == "disconnect":
            _bump_diagnostic("disconnect")
        if mode == "http500":
            self._send_json(500, {"error": {"message": "hc01 injected failure", "type": "server_error"}})
            return
        if mode == "timeout":
            time.sleep(70)
            return
        if mode == "advisor-success":
            self._send_json(200, ADVISOR_COMPLETION_BODY)
            return
        if mode == "disconnect":
            if stream:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    self.wfile.write((CHUNK_FRAMES[0] + "\n\n").encode("utf-8"))
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass
                self.close_connection = True
            else:
                self._abort_truncated()
            return
        if mode == "hold" and stream:
            self._hold_stream()
            return
        if mode == "slow-stream" and stream:
            self._slow_stream(max(delay_ms, 1))
            return
        if mode == "delay" and delay_ms > 0:
            time.sleep(delay_ms / 1000.0)
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
