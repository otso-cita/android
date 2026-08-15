"""Minimal client for the Looker Android accessibility-service control socket.

Requires: `adb forward tcp:<port> localabstract:looker_a11y` to a device
running the Looker a11y service (see android/README or build.sh).
"""
import json
import socket
import subprocess
import time


class LookerClient:
    def __init__(self, device=None, port=7913):
        self.device = device or self._first_device()
        self.port = port
        self._forward()

    def _adb(self, *args, timeout=15):
        cmd = ["adb"]
        if self.device:
            cmd += ["-s", self.device]
        cmd += list(args)
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

    def _first_device(self):
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=15).stdout
        for line in out.splitlines()[1:]:
            line = line.strip()
            if line and line.endswith("device"):
                return line.split()[0]
        raise RuntimeError("no adb device found")

    def _forward(self):
        self._adb("forward", f"tcp:{self.port}", "localabstract:looker_a11y")

    def _reconnect(self):
        subprocess.run(["adb", "kill-server"], capture_output=True, timeout=10)
        time.sleep(1)
        subprocess.run(["adb", "start-server"], capture_output=True, timeout=15)
        self._adb("wait-for-device", timeout=15)
        self._forward()
        time.sleep(0.5)

    def _raw_send(self, cmd, timeout):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        s.sendall((json.dumps(cmd) + "\n").encode())
        s.settimeout(timeout)
        buf = b""
        while b"\n" not in buf:
            chunk = s.recv(200000)
            if not chunk:
                break
            buf += chunk
        s.close()
        return buf.decode(errors="replace").strip()

    def send(self, cmd, timeout=10, retries=4):
        """Send one JSON command, e.g. {"cmd": "dump"}. Retries through USB/adb flakiness."""
        last_err = None
        for _ in range(retries):
            try:
                result = self._raw_send(cmd, timeout)
                if not result:
                    raise RuntimeError("empty response (connection dropped)")
                return json.loads(result)
            except Exception as e:  # noqa: BLE001 - want to retry on anything transient
                last_err = e
                self._reconnect()
        raise RuntimeError(f"send failed after {retries} retries: {last_err}")

    # --- convenience wrappers over the service's JSON protocol ---

    def dump(self):
        return self.send({"cmd": "dump"})

    def click(self, text, long=False):
        return self.send({"cmd": "click", "text": text, "long": long})

    def tap(self, x, y, long=False):
        return self.send({"cmd": "tap", "x": x, "y": y, "long": long})

    def swipe(self, x1, y1, x2, y2, duration_ms=300):
        return self.send({"cmd": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms})

    def set_text(self, text, value):
        return self.send({"cmd": "set_text", "text": text, "value": value})

    def scroll(self, text=None, direction="forward"):
        cmd = {"cmd": "scroll", "direction": direction}
        if text:
            cmd["text"] = text
        return self.send(cmd)

    def global_action(self, action):
        return self.send({"cmd": "global", "action": action})

    # --- tree helpers ---

    @staticmethod
    def find_by_desc(tree, desc):
        def walk(n):
            if n.get("desc") == desc:
                return n
            for c in n.get("children", []):
                r = walk(c)
                if r:
                    return r
            return None
        for w in tree.get("windows", []):
            r = walk(w)
            if r:
                return r
        return None

    @staticmethod
    def find_text(tree, needle, require_editable=False):
        needle = needle.lower()

        def walk(n):
            t = (n.get("text") or "")
            d = (n.get("desc") or "")
            if needle in t.lower() or needle in d.lower():
                if not require_editable or n.get("editable"):
                    return n
            for c in n.get("children", []):
                r = walk(c)
                if r:
                    return r
            return None
        for w in tree.get("windows", []):
            r = walk(w)
            if r:
                return r
        return None

    @staticmethod
    def center(node):
        l, t, r, b = node["bounds"]
        return (l + r) // 2, (t + b) // 2
