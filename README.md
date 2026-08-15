# otso-cita Android app + on-device cita bot

[![Ko-fi](https://img.shields.io/badge/Ko--fi-support%20me-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/otsocita)

If this bot saved you a trip to the extranjería (or a few weeks of F5), you can
say thanks at <https://ko-fi.com/otsocita>. Entirely optional — the app is free,
no features are locked.

Two things ship in this APK:

1. **otso-cita accessibility service** — reads the screen and drives apps via the
   Android accessibility tree, exposed over a local socket
   (`localabstract:looker_a11y`) for `adb`-driven control from a PC.
2. **Cita bot** (`CitaBot.java`) — an **on-device** "toma de huellas" (ICP+)
   appointment bot. It runs the whole hunt (province configurable in the app)
   on the phone itself: no PC, no adb, no Python at runtime.

## What the cita bot does, each attempt

1. **Run the flow** (see below); IP is rotated **after** a failed attempt, before
   the next retry — the first attempt uses the current IP immediately.
2. **Rotate IP** — airplane mode **ON → OFF** via **Shizuku**
   (`cmd connectivity airplane-mode enable/disable`), then logs the public IP
   before/after so the change is visible. *Only changes the IP on **mobile
   data**.* A normal app cannot toggle airplane mode; Shizuku is required (see
   Setup). If Shizuku isn't ready, rotation is skipped (logged).
3. **Open Chrome** at the ICP entry URL (`ACTION_VIEW`).
3. **Run the state machine** — read the a11y tree → `classify()` the page →
   act: province → oficina/trámite → Presentación con Cl@ve → certificate →
   form (Copiar datos + País = la nacionalidad configurada) → **Solicitar Cita**.
4. **Result**: no citas → **repeat immediately** (no wait, no IP rotation), back
   at the entry URL; a WAF block → **no waiting**: wipe the ICP+ site's
   cookies/storage in Chrome, rotate the IP, retry at once; **huecos** →
   notification + vibration, and **stop** so you book by hand in Chrome.

The site-data wipe is **scoped to the ICP+ site**, done through Chrome's own
page-info bubble (padlock → *Cookies y datos del sitio* → delete → confirm) —
not `pm clear com.android.chrome`, which would also throw away the Cl@ve
password Chrome has saved for the bot. Each step is best-effort and logged; if
Chrome's labels move in a future version, adjust `COOKIES_ROW_LABELS` /
`DELETE_DATA_LABELS` in `CitaBot.java`.

## Prerequisites on the phone

On first launch the app shows a **Preparación** checklist (Accesibilidad ·
Shizuku · Whisper) and hides the main screen until all three are ✓. Each row has
a button that takes you to the right place:

- **Accesibilidad** — tap **ACTIVAR** (opens Settings → Accessibility) and
  enable **otso-cita**.
- **Shizuku** — install + start + grant; full steps in the next section.
- **Whisper (captcha de voz)** — tap **DESCARGAR** to fetch the speech model
  used to solve the voice captcha (`ggml-medium-q5_0.bin`, **~514 MB**, from
  Hugging Face — do it on Wi-Fi). One-time download; kept in app storage.

Beyond the checklist:

- Phone on **mobile data** (for airplane-mode IP rotation to change the IP).
- Your **Cl@ve digital certificate installed** in Android
  (Settings → Security → Encryption & credentials → Install a certificate). The
  bot picks the certificate whose entry contains the configured surname and
  accepts Chrome's dialog, but the certificate itself must already be on device.
- For **Cl@ve permanente** instead of the certificate: sign in **once by hand**
  in Chrome and let it **save the password** (Chrome → Settings → Passwords, with
  autofill on). The app never stores your NIE or password — on the Cl@ve login
  page the bot does exactly two things: **taps the user field**, then **presses
  the sign-in button of the Android credential sheet** that Chrome raises. That
  sheet fills both fields and submits the form itself, so the bot deliberately
  **never presses the page's own *Autenticar*** (only native views — nodes with
  a `res_id` — are clickable in that step, and web nodes never have one). If the
  sheet never appears, or Cl@ve rejects the sign-in, the bot **stops** and says
  what to fix (retrying a rejected password is how a Cl@ve account gets blocked).
- Chrome installed (falls back to the default browser if `com.android.chrome`
  is absent).
- **Shizuku** installed and running (for airplane-mode IP rotation) — see below.

## Shizuku setup (for IP rotation)

Airplane mode can't be toggled by a normal app on modern Android (blocked API;
a bare secure-setting write doesn't engage the radio). We use
[Shizuku](https://shizuku.rikka.app/) to run `cmd connectivity airplane-mode`
with shell privileges. Three steps: install, **start the service**, grant the
permission to otso-cita.

### 1. Install

Install the **Shizuku** app from the Play Store (or its GitHub releases). The
otso-cita checklist button opens the Play Store page if it's missing.

### 2. Start the Shizuku service

Installing is not enough — the service must be *started* with shell privileges,
and **it dies on every reboot**, so redo this step after restarting the phone.
Two ways:

**A. Wireless debugging (no PC needed)** — Android 11+:

1. Enable **Developer options**: Settings → About phone → tap **Build number**
   7 times.
2. Settings → System → Developer options → enable **Wireless debugging**
   (phone must be on Wi-Fi).
3. Open the Shizuku app → **Start via Wireless debugging** → follow its pairing
   flow (it walks you through *Pair device with pairing code* in Developer
   options). Pairing is one-time; after a reboot you only re-tap **Start**.

**B. From a PC over adb** (USB debugging enabled):

```bash
adb shell "$(pm path moe.shizuku.privileged.api | sed -n 's/.*: *//p' | \
  head -1 | xargs dirname)/lib/arm64/libshizuku.so"
```

Either way, the Shizuku app should then show *"Shizuku is running"*.

### 3. Grant the permission

In otso-cita's checklist, tap **DAR PERMISO** on the Shizuku row and accept the
Shizuku permission dialog. The row turns ✓. If it toasts *"arráncala"*, the
service isn't running — redo step 2.

Verify from adb: `printf '{"cmd":"airplane","on":true}\n' | nc 127.0.0.1 7913`
(then `"on":false`) — returns `{"ok":true,"state":1,...}`. The bot skips IP
rotation (and logs it) whenever Shizuku isn't ready, so the hunt still runs —
just without fresh IPs after WAF blocks.

## Config (on the main screen)

Selectors persist to SharedPreferences and are read at each START:
- **Provincia** — sets the ICP `p=` code (INE province code).
- **Identificador** — *Certificado* (drives *Access eIdentifier* + cert dialog)
  or *Cl@ve permanente* (opens the permanente card and signs in with the
  password Chrome has saved — see Prerequisites).
- **Nacionalidad** — the *País de nacionalidad* selected on the form.
- **Certificado (apellido / subcadena)** — when Chrome's cert chooser offers
  several certificates, the bot picks the one whose entry contains this text
  (e.g. your surname), then confirms.

## Run

Open the **otso-cita** app → set the config selectors → **START**. The on-screen
log mirrors every step; press **STOP** to end. It alerts and stops itself when a
slot appears.

From adb (debugging), the same controls are on the socket:

```bash
adb forward tcp:7913 localabstract:looker_a11y
printf '{"cmd":"cita_start"}\n' | nc 127.0.0.1 7913
printf '{"cmd":"cita_log"}\n'   | nc 127.0.0.1 7913   # running flag + log
printf '{"cmd":"cita_stop"}\n'  | nc 127.0.0.1 7913
```

## Build

No Gradle — plain SDK command-line tools:

```bash
./build.sh          # needs a JDK (javac) + Android SDK (build-tools 34, android-34)
adb install -r build/apk/looker-signed.apk
```

`build.sh` uses `ANDROID_SDK_ROOT` (default `/mnt/dev/android-sdk`). If `javac`
is missing, install a JDK — on Fedora:
`sudo dnf install java-latest-openjdk-devel`.

## Tuning (must be done on a real device against the live site)

The page-driving primitives can only be finished on-device: the WAF-protected
flow can't be exercised from a build box. Watch the in-app log and adjust the
label constants at the top of `CitaBot.java` if a step fails:

- **`selectOption(label, option)`** — taps the `<select>` control by its label,
  then taps the option in Android's native list. Chrome may expose the control
  under different text than the visible label; if a select logs `FAIL`, that's
  the spot to adjust.
- **Cl@ve panel** — `clave_choice` clicks *"Presentación con Cl@ve"* by phrase;
  if both panels share text, fall back to tapping the leftmost by bounds.
- **Certificate dialog** — `CERT_LABELS` / `CERT_OK_LABELS` cover the common
  button texts; add your device's exact wording if needed.

## WAF / anti-bot notes

The site runs an aggressive behavioural WAF with **two layers**:

1. **Session cookies / reCAPTCHA** — reset by wiping the site's cookies/storage
   (which the bot does after a block).
2. **IP reputation** — NOT reset by a cookie wipe. Hammering the site (many full
   flows in a short time) marks the IP and then blocks on the very first
   request; that's what the airplane-mode IP rotation is for.

Practical consequences: **human delays are mandatory** (without them the WAF
blocks on the first submit), and each attempt is ~7 page loads — polling every
~3–4 min is fine, bursts burn the IP for hours. Personal-use tool for your own
appointment; don't saturate the service.
