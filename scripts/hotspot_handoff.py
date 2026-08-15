#!/usr/bin/env python3
"""Demo: toggle airplane mode on/off, turn on the phone's Wi-Fi hotspot,
read its credentials, and connect this computer to it.

All phone interaction goes through the Looker accessibility service
(dump/click/tap/global) -- no raw `adb shell settings`/`am` commands.
Connecting *this computer* to the resulting network uses nmcli, since
that part is local, not phone manipulation.

Usage:
    python3 hotspot_handoff.py [--no-airplane] [--no-hotspot] [--no-connect]
"""
import argparse
import subprocess
import sys
import time

from looker_client import LookerClient


def log(msg):
    print(f"[hotspot_handoff] {msg}", flush=True)


def toggle_airplane_mode(lk, want_on):
    """Airplane mode lives on Quick Settings page 1. Its tile ignores
    ACTION_CLICK on this device (custom touch handling), so we tap its
    bounds directly instead -- looked up fresh each time in case layout
    shifts."""
    log(f"opening quick settings to set airplane mode {'ON' if want_on else 'OFF'}")
    lk.global_action("quick_settings")
    time.sleep(1)

    tree = lk.dump()
    node = lk.find_by_desc(tree, "Airplane mode")
    if node is None:
        raise RuntimeError("Airplane mode tile not found in Quick Settings")

    if bool(node.get("checked")) == want_on:
        log(f"airplane mode already {'ON' if want_on else 'OFF'}")
        return

    x, y = lk.center(node)
    lk.tap(x, y)
    time.sleep(1.5)

    tree = lk.dump()
    node = lk.find_by_desc(tree, "Airplane mode")
    state = bool(node.get("checked")) if node else None
    if state != want_on:
        raise RuntimeError(f"airplane mode toggle failed, expected {want_on}, got {state}")
    log(f"airplane mode is now {'ON' if want_on else 'OFF'}")


def open_settings_app(lk):
    log("opening Settings app")
    lk.global_action("quick_settings")
    time.sleep(1)
    lk.click("Open settings.")
    time.sleep(1)


def navigate_to_wifi_hotspot(lk):
    open_settings_app(lk)

    # scroll to the very top in case the settings list kept a scroll
    # position from a previous session
    for _ in range(3):
        lk.scroll(direction="backward")
    time.sleep(0.5)

    log("Settings > Network & internet")
    lk.click("Network & internet")
    time.sleep(1)

    log("Network & internet > Hotspot & tethering")
    lk.click("Hotspot & tethering")
    time.sleep(1)

    log("Hotspot & tethering > Wi-Fi hotspot")
    lk.click("Wi‑Fi hotspot")  # non-breaking hyphen, matches device string
    time.sleep(1)


def enable_wifi_hotspot(lk):
    navigate_to_wifi_hotspot(lk)

    tree = lk.dump()
    switch = lk.find_text(tree, "OFF")
    if switch is None:
        log("hotspot switch already ON (no OFF switch found)")
    else:
        lk.click("OFF")
        time.sleep(2)

    tree = lk.dump()
    ssid = _find_summary_after_title(tree, "Hotspot name")
    if ssid is None:
        raise RuntimeError("could not read hotspot SSID")
    log(f"hotspot SSID: {ssid}")

    log("opening Advanced > Hotspot password")
    lk.click("Advanced")
    time.sleep(1)
    lk.click("Hotspot password")
    time.sleep(1)

    tree = lk.dump()
    password = None
    for entry in _flatten(tree):
        if entry.get("class") == "android.widget.EditText" and entry.get("editable"):
            password = entry.get("text")
            break
    if password is None:
        raise RuntimeError("could not read hotspot password")

    lk.click("CANCEL")
    time.sleep(0.5)
    lk.global_action("home")

    log(f"hotspot password: {password}")
    return ssid, password


def _flatten(tree):
    out = []

    def walk(n):
        out.append(n)
        for c in n.get("children", []):
            walk(c)
    for w in tree.get("windows", []):
        walk(w)
    return out


def _find_summary_after_title(tree, title_text):
    flat = _flatten(tree)
    for i, n in enumerate(flat):
        if n.get("res_id") == "android:id/title" and n.get("text") == title_text:
            # summary is typically the next sibling in document order
            for m in flat[i + 1:i + 4]:
                if m.get("res_id") == "android:id/summary":
                    return m.get("text")
    return None


def connect_computer(ssid, password, retries=100, retry_delay=0.1):
    log(f"connecting this computer to '{ssid}' via nmcli")
    result = None
    for attempt in range(1, retries + 1):
        result = subprocess.run(
            ["nmcli", "device", "wifi", "connect", ssid, "password", password],
            capture_output=True, text=True, timeout=30,
        )
        if result.returncode == 0:
            break
        log(f"nmcli connect attempt {attempt}/{retries} failed, retrying in {retry_delay}s")
        time.sleep(retry_delay)

    print(result.stdout.strip())
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        raise RuntimeError("nmcli connect failed")
    log("connected")

    try:
        ip_result = subprocess.run(
            ["curl", "-s", "-m", "5", "https://api.ipify.org"],
            capture_output=True, text=True, timeout=10,
        )
        if ip_result.returncode == 0 and ip_result.stdout.strip():
            log(f"public IP: {ip_result.stdout.strip()}")
        else:
            log("could not determine public IP")
    except Exception as e:  # noqa: BLE001
        log(f"could not determine public IP: {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-airplane", action="store_true", help="skip the airplane-mode on/off demo")
    ap.add_argument("--no-hotspot", action="store_true", help="skip enabling the hotspot")
    ap.add_argument("--no-connect", action="store_true", help="don't connect this computer to the hotspot")
    ap.add_argument("--device", help="adb device serial (default: first attached device)")
    args = ap.parse_args()

    lk = LookerClient(device=args.device)

    if not args.no_airplane:
        toggle_airplane_mode(lk, want_on=True)
        time.sleep(1)
        toggle_airplane_mode(lk, want_on=False)

    if not args.no_hotspot:
        ssid, password = enable_wifi_hotspot(lk)
        if not args.no_connect:
            connect_computer(ssid, password)


if __name__ == "__main__":
    main()
