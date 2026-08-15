# otso-setup

Desktop setup tool for [otso-cita](../README.md) — downloads `adb`, installs
otso-cita + Shizuku on a USB-connected phone, and configures both end to end.
Plain console tool, no GUI. Works on Windows, macOS, and Linux.

## Usage

Connect the phone by USB with USB debugging enabled (Settings → About phone →
tap **Build number** 7 times → Developer options → **USB debugging**), then:

```bash
./otso-setup
```

The phone doesn't need to be unlocked yet, but you'll need to unlock it and
accept the "Allow USB debugging?" prompt when it appears.

What it does, in order:
1. Finds `adb` on `PATH`, or downloads Google's official platform-tools for
   your OS into a per-user cache directory.
2. Waits for exactly one authorized device.
3. Downloads the latest otso-cita and Shizuku APKs from GitHub Releases.
4. Installs both — can take a minute or two on older phones, this is normal.
5. Enables the accessibility service headlessly (`adb shell settings put
   secure enabled_accessibility_services ...`) — no on-phone tap needed, and
   this isn't blocked by Android 13+'s "Restricted settings" (that only
   blocks the Settings-app UI toggle, not a raw settings write).
6. Starts Shizuku over USB (same command the main README documents by hand).
7. Grants the Shizuku permission to otso-cita by driving the phone's UI
   through the same accessibility-service control socket the app already
   exposes for `adb`-driven automation (`localabstract:looker_a11y`) — taps
   the app's GRANT button, then the resulting system permission dialog.
   Best-effort: if the app's UI has changed since this tool was built, it
   falls back to printing the manual steps instead of hanging.
8. Triggers and polls the Whisper speech-model download (~514 MB).
9. Verifies the whole chain by toggling airplane mode once through the app.

What's left for you to do by hand (can't be automated — involves your own
credentials): installing your Cl@ve certificate or saving your Cl@ve
permanente password in Chrome, and entering your details in the app. See the
main [README](../README.md#sign-in-setup-certificate-or-clve-permanente).

## Flags

- `--device <SERIAL>` — target a specific device instead of auto-detecting.
- `--skip-whisper` — skip the Whisper model download.
- `--restart-shizuku-only` — Shizuku's service dies on every phone reboot;
  this just re-runs the "start Shizuku" step and re-verifies, without
  reinstalling anything.

## Troubleshooting

- **Install step (4) hangs indefinitely** (not just "slow", genuinely stuck
  for minutes with no progress): don't run two copies of this tool — or this
  tool alongside a manual `adb install`/`adb shell` — against the *same
  device* at the same time. `adb`'s daemon can deadlock when two installs to
  one device race each other. Fix: close the other copy, then
  `adb kill-server && adb start-server` to reset the connection, and re-run.

## Build

Needs a Rust toolchain ([rustup.rs](https://rustup.rs)):

```bash
cargo build --release
./target/release/otso-setup
```
