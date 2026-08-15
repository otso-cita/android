# otso-cita Android app + on-device cita bot

[![Ko-fi](https://img.shields.io/badge/Ko--fi-support%20me-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/otsocita)

If this bot saved you a trip to the extranjería (or a few weeks of F5), you can
say thanks at <https://ko-fi.com/otsocita>. Entirely optional — the app is free,
no features are locked.

## Quick start

**Recommended — desktop setup tool (Windows/macOS/Linux):** connect your phone
by USB with USB debugging enabled, then download and run `otso-setup` for your
OS from the [Releases page](https://github.com/otso-cita/android/releases/latest)
(no other install needed — it downloads `adb` itself):

```bash
./otso-setup          # otso-setup.exe on Windows
```

It downloads and installs otso-cita + Shizuku, enables accessibility, starts
and grants Shizuku, fetches the Whisper model, and verifies everything end to
end. What's left afterwards is just the two things below that need your own
credentials: [sign-in setup](#sign-in-setup-certificate-or-clve-permanente)
and entering your details in the app. See [desktop/README.md](desktop/README.md)
for details, flags, and troubleshooting. After a phone reboot, Shizuku needs
restarting — run `otso-setup --restart-shizuku-only`.

**Manual setup (no PC, no technical skills needed)** — everything below can
also be done by hand on the phone alone:

1. **Download the app** on your phone: grab the latest release APK from
   the [Releases page](https://github.com/otso-cita/android/releases/latest)
   and open it to install (Android will ask you to allow installing from
   unknown sources — accept).
2. **Open otso-cita** — it shows a checklist. Tap each button and follow along:
   - **Accessibility** → tap **ENABLE** and switch on **otso-cita**. On
     Android 13+ the switch may be greyed out with a *"Restricted setting"* /
     *"Ajuste restringido"* message (normal for apps installed outside the
     Play Store). Fix: Settings → Apps → **otso-cita** → tap **⋮** (top
     right) → **Allow restricted settings** / *Permitir ajustes
     restringidos*, then try again —
     [Google's help page](https://support.google.com/android/answer/12623953?p=restricted_settings).
   - **Shizuku** → install it from the Play Store, then start it using
     **Wireless debugging** (the Shizuku app walks you through it, no PC
     needed — details in [Shizuku setup](#shizuku-setup-for-ip-rotation)),
     then come back and tap **GRANT**.
   - **Whisper** → tap **DOWNLOAD** (a ~514 MB one-time download — use Wi-Fi).
3. **Prepare your sign-in** (one-time): either **install your digital
   certificate** on the phone, or **save your Cl@ve permanente password in
   Chrome**. Step-by-step for both in
   [Sign-in setup](#sign-in-setup-certificate-or-clve-permanente).
4. **Enter your details** on the main screen: your **province**, how you sign
   in (**Certificate** or **Cl@ve permanente**), your **nationality**, and
   your **surname** (used to pick your certificate).
5. Make sure you're on **mobile data**, then press **START**. The phone hunts
   for a cita by itself, buzzes + notifies you the moment one appears, and
   books it for you. (Prefer to book by hand? Untick **Book automatically**
   and the bot will stop for you at that point instead.)

The sections below explain each step in more detail.

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
   cookies/storage in Chrome, rotate the IP, retry at once; **huecos** → you're
   notified at once (vibration + notification), and then — with **Book
   automatically** on (the default) — the bot **books the cita itself**:
   picks an office, a slot inside your date window, solves the voice captcha
   with Whisper, presses Confirmar, and stops with the justificante in the log.
   With the checkbox off it **stops immediately** instead, leaving the page
   open so you book by hand in Chrome.

The site-data wipe is **scoped to the ICP+ site**, done through Chrome's own
page-info bubble (padlock → *Cookies y datos del sitio* → delete → confirm) —
not `pm clear com.android.chrome`, which would also throw away the Cl@ve
password Chrome has saved for the bot. Each step is best-effort and logged; if
Chrome's labels move in a future version, adjust `COOKIES_ROW_LABELS` /
`DELETE_DATA_LABELS` in `CitaBot.java`.

## Prerequisites on the phone

On first launch the app shows a **Setup** checklist (Accessibility ·
Shizuku · Whisper) and hides the main screen until all three are ✓. Each row has
a button that takes you to the right place:

- **Accessibility** — tap **ENABLE** (opens Settings → Accessibility) and
  enable **otso-cita**. If Android blocks it with *"Restricted setting"*
  (Android 13+ does this for sideloaded apps), allow it first via
  Settings → Apps → otso-cita → **⋮** → **Allow restricted settings** — see
  [Google's instructions](https://support.google.com/android/answer/12623953?p=restricted_settings).
- **Shizuku** — install + start + grant; full steps in the next section.
- **Whisper (voice captcha)** — tap **DOWNLOAD** to fetch the speech model
  used to solve the voice captcha (`ggml-medium-q5_0.bin`, **~514 MB**, from
  Hugging Face — do it on Wi-Fi). One-time download; kept in app storage.

Beyond the checklist:

- Phone on **mobile data** (for airplane-mode IP rotation to change the IP).
- A working **sign-in**: either your **digital certificate installed on the
  phone**, or your **Cl@ve permanente password saved in Chrome** — full
  step-by-step in
  [Sign-in setup](#sign-in-setup-certificate-or-clve-permanente).
- Chrome installed (falls back to the default browser if `com.android.chrome`
  is absent).
- **Shizuku** installed and running (for airplane-mode IP rotation) — see below.

## Sign-in setup (certificate or Cl@ve permanente)

The bot signs in to the sede the same way you would by hand, so one of the two
methods must be ready on the phone **before the first START**. Do only the one
you'll select in the app.

### Option A — Digital certificate

You need your certificate **as a file on the phone**, then install it into
Android. If you don't have a certificate yet, you can request the FNMT
*Certificado de Persona Física* at
<https://www.sede.fnmt.gob.es/certificados/persona-fisica>.

1. **Get the certificate file** (`.p12` or `.pfx`). If the certificate lives in
   a browser on your computer, export it **with the private key**:
   - **Chrome / Edge (PC)**: Settings → *Privacy and security* → *Security* →
     *Manage certificates* → *Your certificates* → select it → **Export…** →
     choose the **PKCS #12 (.pfx/.p12)** format **including the private key**,
     and set an export password (you'll type it on the phone).
   - **Firefox**: Settings → *Privacy & Security* → *View Certificates…* →
     *Your Certificates* → **Backup…** (also asks for a password).

   Then get the file onto the phone any way you like: email it to yourself,
   upload to Drive and download on the phone, or copy over USB.
2. **Install it on Android**:
   Settings → **Security & privacy** → **More security settings** (on some
   phones *Encryption & credentials*) → **Install a certificate** →
   **VPN & app user certificate** → pick your `.p12` file → enter the export
   password. Menu names vary a little between brands — searching for
   *"certificate"* inside the Settings app finds the right screen.
3. **Match the surname in the app.** Once installed, the certificate shows up
   under your name (e.g. `GARCIA LOPEZ, MARIA - 12345678X`). In otso-cita, set
   the **Certificate (surname)** field to any text that appears in that name —
   your first surname is usually enough. That's how the bot picks *your*
   certificate when Chrome's "Choose certificate" dialog appears.
4. You can now **delete the `.p12` file** from Downloads/email — Android keeps
   the installed copy.

### Option B — Cl@ve permanente

Requirements: you're registered in [Cl@ve](https://clave.gob.es/) and have
**activated your Cl@ve permanente password**. The app never sees or stores
your NIE or password — it relies entirely on the password **Chrome** has saved.

1. **Let Chrome save passwords**: Chrome → **⋮** → *Settings* →
   *Google Password Manager* → *Settings* → make sure **Offer to save
   passwords** and **Auto sign-in** are on.
2. **Sign in once by hand**: in Chrome on the phone, open the cita page (or any
   Cl@ve-protected sede), choose *Presentación con Cl@ve* → **Cl@ve
   permanente**, type your DNI/NIE and password, and when Chrome asks
   **"Save password?"**, tap **Save**.
3. **Verify the credential sheet**: open the Cl@ve permanente login again and
   tap the **user field**. Chrome should raise a sheet at the bottom of the
   screen with your saved account — tapping it fills both fields and signs in.
   That sheet is exactly what the bot presses, so if it appears for you, the
   bot will work; if it doesn't, fix step 1–2 first.

Two things to know about how the bot behaves here:

- On the Cl@ve login page it does exactly two taps: the **user field**, then
  the **sign-in button on Chrome's credential sheet**. It deliberately never
  presses the page's own *Autenticar* button.
- If the sheet never appears, or Cl@ve **rejects** the sign-in, the bot
  **stops** and tells you what to fix instead of retrying — repeated failed
  attempts are how a Cl@ve account gets blocked. If you ever change your
  Cl@ve password, sign in by hand once more so Chrome updates the saved one.

## Shizuku setup (for IP rotation)

The [desktop setup tool](#quick-start) does all of this automatically —
this section is for doing it by hand, or for understanding what it does.

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

In otso-cita's checklist, tap **GRANT** on the Shizuku row and accept the
Shizuku permission dialog. The row turns ✓. If it toasts *"start it"*, the
service isn't running — redo step 2.

Verify from adb: `printf '{"cmd":"airplane","on":true}\n' | nc 127.0.0.1 7913`
(then `"on":false`) — returns `{"ok":true,"state":1,...}`. The bot skips IP
rotation (and logs it) whenever Shizuku isn't ready, so the hunt still runs —
just without fresh IPs after WAF blocks.

## Config (on the main screen)

Selectors persist to SharedPreferences and are read at each START:
- **Province** — sets the ICP `p=` code (INE province code).
- **Sign-in method** — *Certificate* (drives *Access eIdentifier* + cert dialog)
  or *Cl@ve permanente* (opens the permanente card and signs in with the
  password Chrome has saved — see
  [Sign-in setup](#sign-in-setup-certificate-or-clve-permanente)).
- **Nationality** — the *País de nacionalidad* selected on the form.
- **Certificate (surname / text to pick it by)** — when Chrome's cert chooser
  offers several certificates, the bot picks the one whose entry contains this
  text (e.g. your surname), then confirms.
- **Book automatically** (checkbox, on by default) — whether the bot
  completes the booking itself once citas appear. Untick it to have the bot
  alert + stop the moment citas exist, so you do the booking by hand in Chrome.

## Run

Open the **otso-cita** app → set the config selectors → **START**. The on-screen
log mirrors every step; press **STOP** to end. It alerts as soon as citas
appear and stops itself when the booking is done (or right at the alert, if
**Book automatically** is off).

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

The `desktop/` directory has the desktop setup tool's source (Rust) — see
[desktop/README.md](desktop/README.md) to build it.

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
