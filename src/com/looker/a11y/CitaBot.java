package com.looker.a11y;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Random;

/**
 * On-device "toma de huellas" (ICP+) appointment bot.
 *
 * Runs the whole appointment hunt (province configurable in the UI) on the
 * phone itself, driving the REAL system Chrome through this app's own
 * accessibility service (no PC, no adb, no Python at runtime):
 *
 *   loop:
 *     1. rotateIp()      -> airplane mode ON then OFF (carrier hands a new
 *                           public IP; only meaningful when the phone is on
 *                           MOBILE DATA, not Wi-Fi).
 *     2. openEntry()     -> ACTION_VIEW the ICP entry URL in Chrome.
 *     3. runAttempt()    -> 3-step state machine:
 *                           read the a11y tree -> classify(page) -> act(page).
 *     4. on "no citas"   -> repeat immediately (no wait, no IP rotation);
 *        on huecos       -> alert (notification + vibration) and STOP;
 *        on WAF block    -> clearSiteData() (the ICP+ site's cookies/storage in
 *                           Chrome) + rotateIp(), then retry straight away.
 *
 * All decisions run against the Android accessibility tree returned by
 * {@link LookerAccessibilityService}.
 *
 * TUNING NOTE: the page-driving primitives (which node a label maps to, how a
 * native <select> option list is dismissed) can only be finished against the
 * live site on a real device. Every such spot logs generously; watch the
 * in-app log (or `adb ... cita_log`) and adjust the label constants below.
 */
class CitaBot implements Runnable {

    private static final String TAG = "CitaBot";

    // ====================== CONFIG ======================
    // Chrome: it exposes <select> options to the accessibility tree (so the bot
    // can pick trámite/nationality), unlike Firefox/GeckoView which renders the
    // native <select> popup invisibly to a11y. The ICP server's incomplete TLS
    // chain (missing RapidSSL intermediate) is handled out-of-band by installing
    // that intermediate as a user CA on the phone (see android/README.md).
    static final String CHROME_PKG = "com.android.chrome";
    static final String ENTRY_BASE =
            "https://icp.administracionelectronica.gob.es/icpplustieb/citar?p=";

    static final String OFFICE = "Cualquier oficina";
    static final String TRAMITE = "TOMA DE HUELLAS";   // unique substring of the trámite

    // Runtime config (loaded from Cfg / SharedPreferences at each start()).
    private String provinceCode = "28";                // p= INE code (28 = Madrid)
    private String provinceName = "Madrid";
    private String country = "";
    private String authMethod = "cert";                // "cert" | "clave"
    private String certMatch = "";                     // surname/substring of the cert to pick
    private String email = "";
    private String phone = "";
    // Acceptable appointment-date window as yyyymmdd ints (0 = no bound).
    private int minDate = 0;
    private int maxDate = 0;
    // Book the cita automatically (office → slot → captcha → Confirmar). When
    // false, alert + stop as soon as citas appear so the user books by hand.
    private boolean autoBook = true;
    private String entryUrl = ENTRY_BASE + "28&locale=es";

    // Candidate field labels for the solicitante form (Chrome exposes inputs by
    // their <label>). Tuned live if a field isn't filled.
    static final String[] EMAIL_LABELS = {"Correo electrónico", "Correo electronico",
            "Correo", "Email", "E-mail"};
    static final String[] EMAIL2_LABELS = {"Repetir correo", "Repita el correo",
            "Confirmar correo", "Vuelva a introducir"};
    static final String[] PHONE_LABELS = {"Teléfono móvil", "Telefono movil",
            "Teléfono", "Telefono", "Móvil"};

    // Cl@ve platform entry buttons, the Cl@ve-permanente entry, and the confirm
    // button of the native "select a certificate" dialog Chrome pops afterwards.
    static final String[] CERT_LABELS = {"Access eIdentifier", "eIdentifier", "Certificado", "DNIe"};
    // ONLY the permanente card: Cl@ve PIN would need a one-time PIN from the app/SMS,
    // which the bot can't supply — clicking it would just derail the flow.
    // The Cl@ve gateway (pasarela.clave.gob.es) renders in whatever UI language
    // is set, so match the "permanent Cl@ve" access button in BOTH Spanish and
    // English ("Access Permanent Clave"). It's the last card — below the fold —
    // so it must be scrolled into view, not blind-tapped.
    static final String[] CLAVE_PERM_LABELS = {"Access Permanent Clave", "Acceder Clave Permanente",
            "Clave Permanente", "Cl@ve permanente", "Permanent Clave", "Cl@ve Permanente"};
    // eIdentifier (certificate) access button, ES + EN.
    static final String[] EIDENTIFIER_LABELS = {"Access eIdentifier", "Acceder eIdentificador",
            "eIdentificador", "eIdentifier"};
    static final String[] CERT_OK_LABELS = {"Aceptar", "OK", "Sí", "Si", "Yes", "Continuar",
            "Permitir", "Allow", "Continue", "Seleccionar", "Select"};

    // Cl@ve Permanente login form (clave.gob.es). Credentials are NEVER stored by
    // this app, and the bot does exactly TWO things on this page: tap the user
    // field, then press the sign-in button of the Android credential sheet that
    // Chrome raises. The sheet fills BOTH fields and submits the form itself, so
    // the site's own "Autenticar" is never pressed (there is deliberately no
    // code left here that could press it).
    //
    // The sheet is a NATIVE view, so every one of its nodes carries a res_id;
    // web-page nodes never do (cf. webFields). That single property is what
    // keeps the click inside the sheet and off the page.
    static final String[] SHEET_SIGNIN_LABELS = {"iniciar sesión", "iniciar sesion", "sign in",
            "continuar", "continue", "acceder", "usar contraseña", "use password"};
    // The credential ROW of the sheet, used when the sheet has no separate
    // button (one-tap variants): its text is the saved username, unknown here,
    // so it is matched by view-id — Chrome's touch-to-fill / dropdown or the
    // system Autofill dataset picker — or by the sheet's own helper text.
    static final String[] AUTOFILL_RESID_HINTS = {"touch_to_fill", "dropdown", "autofill",
            "dataset", "credential", "suggest"};
    static final String[] AUTOFILL_TEXT_HINTS = {"contraseña guardada", "saved password",
            "usar contraseña", "iniciar sesión como", "sign in as", "autocompletar", "autofill",
            "rellenar", "password manager", "gestor de contraseñas"};
    // "Rellene los campos" form: the button that copies the certificate holder's
    // data into the applicant block. Lowercase — matching is case-insensitive
    // substring on the a11y text/desc.
    static final String[] COPY_APPLICANT_LABELS = {"copiar a datos solicitante",
            "copiar a datos del solicitante", "copiar datos solicitante", "copiar a datos"};
    // Chrome's page-info bubble, used to wipe the ICP+ site's cookies/storage
    // after a WAF block. Labels move between Chrome versions and UI languages,
    // so each step matches a set; res-ids are matched by suffix because the
    // browser package is not always com.android.chrome.
    static final String[] PAGE_INFO_ICON_RESIDS = {":id/location_bar_status_icon",
            ":id/security_button", ":id/location_bar_status"};
    static final String[] COOKIES_ROW_LABELS = {"cookies y datos del sitio",
            "cookies and site data", "cookies y datos de sitios", "datos del sitio",
            "site data", "cookies"};
    static final String[] DELETE_DATA_LABELS = {"eliminar datos del sitio", "delete site data",
            "borrar datos del sitio", "clear site data", "administrar datos del sitio",
            "manage on-device site data", "eliminar y restablecer", "borrar y restablecer",
            "clear & reset"};
    // Matched EXACTLY (these are dialog buttons): a substring match would also
    // hit the row that opened the dialog.
    static final String[] CONFIRM_DELETE_LABELS = {"eliminar", "delete", "borrar", "clear",
            "aceptar", "ok"};
    // Blind-tap positions (% of screen height) for the two Cl@ve pages Chrome
    // sometimes refuses to expose to a11y: the permanente card on the method page
    // (below the certificate one, cf. tapEIdentifier) and the user field on the
    // login form. Tune against the live pages if a tap lands wrong.
    private static final int CLAVE_PERM_TAP_PCT = 85;
    private static final int CLAVE_USER_FIELD_PCT = 45;

    // Human delays (ms). The site runs an aggressive behavioural WAF; without
    // these it blocks on the first submit (see README). Do not slash them.
    private static final int[] D_MICRO = {350, 750};        // between micro-actions on a page
    private static final int[] D_PAGELOAD = {1600, 2600};   // after an action that changes page
    private static final int[] RETRY_NO_CITA_MS = {160_000, 260_000}; // ~3-4 min between attempts
    // No back-off after a WAF block: the two things the block actually keys on
    // are wiped instead — the site's cookies/storage (clearSiteData) and the IP
    // (rotateIp) — and the next attempt starts immediately.

    private static final int MAX_STEPS_PER_ATTEMPT = 24;    // anti-loop firewall
    // ============================================================================

    private final LookerAccessibilityService svc;
    private final Random rnd = new Random();
    private volatile boolean running = false;
    private Thread thread;

    // Office currently being booked (set at the acCitar office pick), so the slot
    // page can attribute every offered date/time to an oficina when recording.
    private String lastOffice = "";
    // Dedup key of every cita already recorded this run, so the same offered slot
    // isn't written again on each poll of the (slow) slot page.
    private final java.util.Set<String> recordedCitas = new java.util.HashSet<>();

    // Shared, bounded log the MainActivity mirrors on screen.
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final int LOG_MAX = 300;

    // ---- session stats, surfaced by MainActivity's "Estadísticas" panel.
    // In-memory only (reset when the app process dies, not on every stop/start
    // cycle) — good enough for "how's it doing" at a glance. ----
    private static volatile long runStartMs = 0;   // 0 while stopped
    private static volatile long accumRunMs = 0;    // running time before the current run
    private static final java.util.concurrent.atomic.AtomicInteger TOTAL_ATTEMPTS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger TOTAL_IP_RELOADS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger TOTAL_CITAS_FOUND =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ArrayDeque<FoundCita> FOUND_CITAS = new ArrayDeque<>();
    private static final int FOUND_CITAS_MAX = 200;

    /** One row of the found-citas list shown (expandable) in MainActivity. */
    static final class FoundCita {
        final long whenMs;
        final String office;
        final String dateStr;
        final String time;
        final boolean inRange;

        FoundCita(long whenMs, String office, String dateStr, String time, boolean inRange) {
            this.whenMs = whenMs;
            this.office = office;
            this.dateStr = dateStr;
            this.time = time;
            this.inRange = inRange;
        }
    }

    CitaBot(LookerAccessibilityService svc) {
        this.svc = svc;
    }

    // ---------------- lifecycle ----------------

    synchronized void start() {
        // Refuse while a previous run is still winding down (a stopped thread can
        // linger inside the non-interruptible ~40s whisper call). Starting anyway
        // would put TWO driver threads on Chrome at once — the doubled-log,
        // fighting-over-the-page pathology.
        if (running || (thread != null && thread.isAlive())) {
            log(running ? "already running"
                    : "run anterior aún terminando (transcripción en curso); reintenta en unos segundos");
            return;
        }
        running = true;
        thread = new Thread(this, "cita-bot");
        thread.start();
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        if (thread != null) thread.interrupt();
        log("stop requested");
    }

    boolean isRunning() {
        return running;
    }

    static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            for (String l : LOG) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Total time the bot has spent running (this app process), including the
     *  run in progress if any. */
    static long totalRunMs() {
        long start = runStartMs;
        return accumRunMs + (start == 0 ? 0 : System.currentTimeMillis() - start);
    }

    static int totalAttempts() {
        return TOTAL_ATTEMPTS.get();
    }

    static int totalIpReloads() {
        return TOTAL_IP_RELOADS.get();
    }

    static int totalCitasFound() {
        return TOTAL_CITAS_FOUND.get();
    }

    /** Every cita found this app session, most recent last. */
    static java.util.List<FoundCita> foundCitas() {
        synchronized (FOUND_CITAS) {
            return new java.util.ArrayList<>(FOUND_CITAS);
        }
    }

    private void log(String msg) {
        String line = String.format(Locale.US, "%tT %s", System.currentTimeMillis(), msg);
        Log.i(TAG, msg);
        synchronized (LOG) {
            LOG.addLast(line);
            while (LOG.size() > LOG_MAX) LOG.removeFirst();
        }
    }

    private void sleep(int[] range) {
        sleepMs(range[0] + rnd.nextInt(Math.max(1, range[1] - range[0])));
    }

    /** Sleep up to {@code ms}, but wake within ~200ms of {@link #stop()} clearing
     *  {@code running}. A plain Thread.sleep isn't enough: stop() interrupts the
     *  thread, but the interrupt often lands during a preceding network call
     *  (e.g. {@link Priv#publicIp()}), whose {@code catch (Throwable)} swallows
     *  the InterruptedException and clears the flag — so the next Thread.sleep
     *  would run its full multi-minute duration, keeping the thread alive and
     *  making START refuse ("run anterior aún terminando"). Polling `running`
     *  fixes that regardless of the flag's state. */
    private void sleepMs(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return;
            try {
                Thread.sleep(Math.min(left, 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------------- main loop ----------------

    private void loadConfig() {
        provinceCode = Cfg.provinceCode(svc);
        provinceName = Cfg.provinceName(svc);
        country = Cfg.country(svc);
        authMethod = Cfg.auth(svc);
        certMatch = Cfg.certMatch(svc);
        email = Cfg.email(svc);
        phone = Cfg.phone(svc);
        minDate = parseIsoDate(Cfg.minDate(svc));
        maxDate = parseIsoDate(Cfg.maxDate(svc));
        autoBook = Cfg.autoBook(svc);
        entryUrl = ENTRY_BASE + provinceCode + "&locale=es";
    }

    /** "YYYY-MM-DD" -> yyyymmdd int (e.g. 20260815), or 0 if blank/invalid. */
    private static int parseIsoDate(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{4})\\D(\\d{1,2})\\D(\\d{1,2})").matcher(s);
        if (!m.find()) return 0;
        return Integer.parseInt(m.group(1)) * 10000
                + Integer.parseInt(m.group(2)) * 100 + Integer.parseInt(m.group(3));
    }

    /** True if yyyymmdd falls within the configured [minDate, maxDate] window
     *  (either bound may be 0 = open). */
    private boolean inDateRange(int yyyymmdd) {
        if (yyyymmdd <= 0) return true;   // couldn't parse a date -> don't exclude
        if (minDate > 0 && yyyymmdd < minDate) return false;
        if (maxDate > 0 && yyyymmdd > maxDate) return false;
        return true;
    }

    private boolean dateWindowSet() {
        return minDate > 0 || maxDate > 0;
    }

    @Override
    public void run() {
        runStartMs = System.currentTimeMillis();
        try {
            runLoop();
        } finally {
            accumRunMs += System.currentTimeMillis() - runStartMs;
            runStartMs = 0;
        }
    }

    private void runLoop() {
        loadConfig();
        recordedCitas.clear();   // fresh run: re-list whatever citas are out there
        log("cita bot started (toma de huellas · " + provinceName + ") auth=" + authMethod
                + (certMatch.isEmpty() ? "" : " cert~'" + certMatch + "'") + " país=" + country
                + (dateWindowSet() ? " fechas=[" + (minDate > 0 ? minDate : "…") + ".."
                        + (maxDate > 0 ? maxDate : "…") + "]" : "")
                + (autoBook ? "" : " reserva=manual"));
        int attempt = 0;
        while (running) {
            attempt++;
            TOTAL_ATTEMPTS.incrementAndGet();
            log("===== Intento #" + attempt + " =====");
            try {
                // The first attempt runs on the current IP immediately; IP is
                // rotated AFTER a failed procedure, before the next retry — so
                // the fresh IP is what the next attempt uses, and we skip the
                // airplane dance when we actually found a cita.
                String result = runAttempt();
                log("resultado intento: " + result);
                if (!running) break;
                if (result.equals("cita")) {
                    alertCita();
                    running = false;          // stop and let the user book by hand
                    break;
                }
                // Cl@ve sign-in problems are NOT retryable: retrying a rejected
                // password just walks the account into a block, and a missing saved
                // password won't appear on its own. Stop and say what to fix.
                if (result.equals("clave_error") || result.equals("clave_nocreds")) {
                    log(result.equals("clave_error")
                            ? "⛔ Cl@ve rechazó el acceso (usuario/contraseña incorrectos o cuenta "
                            + "bloqueada). Bot parado: entra a mano, arréglalo y vuelve a guardar la "
                            + "contraseña en Chrome."
                            : "⛔ El navegador no rellenó Cl@ve. Bot parado: entra UNA vez a mano en "
                            + "Cl@ve desde Chrome, guarda usuario+contraseña en el gestor y activa "
                            + "el autocompletado.");
                    running = false;
                    break;
                }
                // WAF block: no waiting. Wipe what the block hangs off — the
                // site's cookies/storage in Chrome and the public IP — and go
                // straight into the next attempt.
                if (result.equals("waf")) {
                    log("⛔ WAF. Limpio los datos del sitio en Chrome y roto la IP; reintento ya.");
                    clearSiteData();
                    if (!running) break;
                    // Wi-Fi off first: airplane-mode rotation only changes the IP
                    // on mobile data, so Wi-Fi would pin the same (blocked) IP.
                    if (Priv.shizukuReady()) {
                        boolean off = Priv.setWifi(false);
                        log("  Wi-Fi OFF (para forzar datos móviles) -> " + (off ? "ok" : "FAIL"));
                    }
                    if (!rotateIp()) {
                        // Same IP as before (Shizuku off, Wi-Fi, or the carrier
                        // handed the same address): retrying instantly would be
                        // blocked instantly, in a tight loop. Pause as if there
                        // were no citas instead.
                        long w = RETRY_NO_CITA_MS[0]
                                + rnd.nextInt(RETRY_NO_CITA_MS[1] - RETRY_NO_CITA_MS[0]);
                        log("  la IP no cambió; espero " + (w / 60000f)
                                + " min antes de reintentar");
                        sleepMs(w);
                    }
                    continue;
                }
                // No citas: no wait, no IP rotation — go straight back to the
                // entry URL for the next attempt.
                if (result.equals("no_cita")) {
                    log("Sin citas. Reintento inmediato desde la URL de entrada.");
                    continue;
                }
                // Any other non-cita outcome (stuck/expired/sslerror) -> rotate
                // IP, then wait, then retry.
                rotateIp();
                if (!running) break;
                long wait = RETRY_NO_CITA_MS[0]
                        + rnd.nextInt(RETRY_NO_CITA_MS[1] - RETRY_NO_CITA_MS[0]);
                log("Sin citas (" + result + "). Espera " + (wait / 60000f) + " min.");
                sleepMs(wait);
            } catch (Exception e) {
                log("Error: " + e);
                sleepMs(30_000);
            }
        }
        log("cita bot stopped");
    }

    // ---------------- IP rotation via airplane mode ----------------

    /** Cut the radio and restore it via Shizuku (`cmd connectivity
     *  airplane-mode enable/disable`): the carrier hands out a fresh public IP.
     *  Only effective on MOBILE DATA (a Wi-Fi public IP won't change). Logs the
     *  public IP before and after so the rotation is visible.
     *
     *  Returns true only when the public IP actually CHANGED — the WAF path uses
     *  that to decide whether retrying at once is safe. */
    private boolean rotateIp() {
        if (!Priv.shizukuAlive()) {
            log("rotación IP omitida: Shizuku no está activo (instala/arranca Shizuku)");
            return false;
        }
        if (!Priv.shizukuPermitted()) {
            log("rotación IP omitida: falta permiso Shizuku (concédelo desde la app)");
            return false;
        }
        String ipBefore = Priv.publicIp();
        log("rotando IP (Shizuku): modo avión ON→OFF. IP actual: " + ipBefore);
        boolean on = Priv.setAirplane(true);
        log("  modo avión ON -> " + (on ? "ok" : "FAIL") + " (estado=" + Priv.airplaneState(svc) + ")");
        sleepMs(3000);
        boolean off = Priv.setAirplane(false);
        log("  modo avión OFF -> " + (off ? "ok" : "FAIL") + " (estado=" + Priv.airplaneState(svc) + ")");
        // Wait for mobile data to reattach, then read the new public IP.
        String ipAfter = null;
        for (int i = 0; i < 4 && running; i++) {
            sleepMs(5000);
            ipAfter = Priv.publicIp();
            if (ipAfter != null) break;
        }
        boolean changed = ipAfter != null && !ipAfter.equals(ipBefore);
        log("  IP tras rotación: " + ipAfter + (changed ? " (cambiada ✓)"
                : ipAfter == null ? " (sin conectividad aún)" : " (sin cambio)"));
        if (changed) TOTAL_IP_RELOADS.incrementAndGet();
        return changed;
    }

    // ---------------- Chrome site-data wipe (after a WAF block) ----------------

    /** Wipe Chrome's storage FOR THE ICP+ SITE ONLY (cookies, localStorage…),
     *  through the page-info bubble of the page Chrome is showing — which after
     *  a block is the "Request Rejected" page, i.e. the same origin: padlock /
     *  tune icon in the omnibox -> "Cookies y datos del sitio" -> delete ->
     *  confirm.
     *
     *  Site-scoped on purpose: `pm clear com.android.chrome` would also throw
     *  away the Cl@ve password Chrome has saved, which the bot depends on.
     *  Every step is best-effort and logged — a miss costs a dirtier retry, it
     *  never leaves the bot stuck (the bubble/settings stack is backed out of at
     *  the end either way). */
    private void clearSiteData() {
        JSONObject dump = svc.botDump();
        JSONObject icon = null;
        for (String rid : PAGE_INFO_ICON_RESIDS) {
            icon = findByResIdSuffix(dump, rid);
            if (icon != null) break;
        }
        if (icon == null) {
            log("  no encontré el icono de info de página en Chrome; sigo sin limpiar");
            return;
        }
        int[] c = center(icon);
        if (c == null) return;
        svc.botTap(c[0], c[1]);
        log("  Chrome: abro la info de la página");
        sleepMs(1200);

        if (!tapNative(COOKIES_ROW_LABELS, false)) {
            log("  no encontré 'Cookies y datos del sitio'; cierro la burbuja");
            backOut(2);
            return;
        }
        sleepMs(1200);
        // Newer Chrome puts the actual delete one level deeper ("Administrar
        // datos del sitio en el dispositivo" -> "Eliminar"), so allow two hops.
        boolean confirmed = false;
        for (int hop = 0; hop < 2 && running && !confirmed; hop++) {
            if (!tapNative(DELETE_DATA_LABELS, false)) break;
            sleepMs(1000);
            confirmed = tapNative(CONFIRM_DELETE_LABELS, true);
            sleepMs(800);
        }
        log(confirmed ? "  datos del sitio ICP+ borrados ✓"
                : "  borrado sin diálogo de confirmación (puede haber sido directo)");
        backOut(3);
    }

    /** Leave whatever Chrome UI the wipe opened, back to the page. */
    private void backOut(int times) {
        for (int i = 0; i < times && running; i++) {
            svc.botGlobal("back");
            sleepMs(500);
        }
    }

    /** Tap a NATIVE node (one with a res_id) whose label matches. Web-page nodes
     *  have no res_id, so this can never click the ICP+ page itself. `exact`
     *  compares the node's own text; otherwise the subtree text is searched for
     *  a substring and the tightest match wins. */
    private boolean tapNative(String[] labels, boolean exact) {
        java.util.List<JSONObject> hits = new java.util.ArrayList<>();
        collect(svc.botDump(), n -> {
            if (!n.optBoolean("enabled", true) || area(n) <= 0) return false;
            if (n.optString("res_id", "").isEmpty()) return false;
            if (exact) {
                String own = (n.optString("text", "") + " " + n.optString("desc", ""))
                        .trim().toLowerCase(Locale.ROOT);
                for (String l : labels) if (own.equals(l)) return true;
                return false;
            }
            return containsAny(labelOf(n), labels);
        }, hits);
        JSONObject best = smallest(hits);
        if (best == null) return false;
        int[] c = center(best);
        if (c == null) return false;
        boolean ok = svc.botTap(c[0], c[1]);
        log("    Chrome: toco " + q(best.optString("text", best.optString("res_id", "?")))
                + " -> " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    // ---------------- entry / navigation ----------------

    private void openEntry() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(entryUrl));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage(CHROME_PKG);
        try {
            svc.startActivity(i);
        } catch (ActivityNotFoundException e) {
            log("  Chrome no encontrado; abro con el navegador por defecto");
            i.setPackage(null);
            try {
                svc.startActivity(i);
            } catch (ActivityNotFoundException e2) {
                log("  no hay navegador para abrir la URL: " + e2);
            }
        }
        sleep(D_PAGELOAD);
    }

    // ---------------- one full attempt ----------------

    /** Returns "no_cita" | "cita" | "waf" | "expired" | "sslerror" | "stuck" |
     *  "clave_error" | "clave_nocreds" (the last two stop the bot). */
    private String runAttempt() {
        openEntry();
        int unknowns = 0;
        int sslerrors = 0;
        int claveLogins = 0;
        boolean citaFound = false;   // alert the user once, as soon as citas appear
        for (int step = 0; step < MAX_STEPS_PER_ATTEMPT && running; step++) {
            // Wait for the page to SETTLE into a recognised state instead of
            // reloading — mobile loads can take 10-25s, and reloading a
            // still-loading page is the "refreshes many times" pathology.
            String url = currentUrl();
            String state = settle();
            log("  paso " + step + ": estado=" + state + " (url=" + shortUrl(url) + ")");

            switch (state) {
                case "waf":
                    return "waf";
                case "no_cita":
                    return "no_cita";
                case "cita_done":
                    logJustificante();
                    return "cita";
                case "slots":
                    return "cita";
                case "expired":
                    openEntry();
                    continue;
                case "clave_error":
                    return "clave_error";
                case "clave_wait":
                    // Transient Cl@ve redirect / still-loading gateway: wait for
                    // it to auto-forward to the next real page (do NOT tap).
                    sleep(D_PAGELOAD);
                    continue;
                case "clave_login":
                    claveLogins++;
                    if (claveLogins > 3) {
                        // Filled but not advancing: ambiguous (submit button missed,
                        // slow page…). Restart the attempt rather than stopping the
                        // bot — a genuinely wrong password comes back as clave_error.
                        log("    el formulario de Cl@ve no avanza tras " + (claveLogins - 1)
                                + " intentos con la hoja de credenciales; reinicio el intento");
                        return "stuck";
                    }
                    if (!claveLogin()) return "clave_nocreds";
                    sleep(D_PAGELOAD);
                    continue;
                case "sslerror":
                    sslerrors++;
                    log("    interstitial TLS del navegador; recargando (" + sslerrors + "/3)");
                    if (sslerrors >= 3) return "sslerror";
                    openEntry();
                    sleep(D_PAGELOAD);
                    continue;
                case "options": {
                    // The definitive availability check.
                    click("Solicitar Cita");
                    sleep(D_PAGELOAD);
                    String rs = settle();   // wait out the (slow) result page
                    log("    resultado Solicitar Cita -> " + rs);
                    if (rs.equals("no_cita")) return "no_cita";
                    if (rs.equals("waf")) return "waf";
                    if (rs.equals("expired")) {
                        openEntry();
                        continue;
                    }
                    // Citas available (acCitar office list) — DON'T stop; let the
                    // loop continue so doStep(cita_office) selects an office and
                    // proceeds with the booking.
                    if (rs.equals("cita_office")) {
                        continue;
                    }
                    // Landed on the acCitar options MENU: pressing "Solicitar
                    // Cita" here just opened it (not the availability result yet).
                    // Let the loop run its cita_menu step, which presses "Solicitar
                    // Cita" again to reach the real result.
                    if (rs.equals("cita_menu")) {
                        continue;
                    }
                    // If the result is instead a KNOWN earlier flow state, the
                    // submit bounced (session/form error) — not a cita; restart.
                    if (rs.equals("office_tramite") || rs.equals("clave_choice")
                            || rs.equals("clave_platform") || rs.equals("clave_login")
                            || rs.equals("form")
                            || rs.equals("province") || rs.equals("options")) {
                        log("    el envío rebotó a " + rs + " (no es cita); reinicio");
                        return "stuck";
                    }
                    // ONLY a genuine appointment page counts as huecos. The old
                    // code returned "cita" for ANYTHING left here — including
                    // "unknown", which is exactly what a SLOW "no hay citas" page
                    // (acCitar URL present, WebView text not yet rendered within
                    // settle()'s window) classifies as. That fired a false
                    // "¡huecos!" alert and stopped the bot on a page that really
                    // said "no hay citas disponibles". Require a real slot/booking
                    // state; otherwise it's not a cita — retry, don't alert.
                    if (rs.equals("slots") || rs.equals("slots_captcha")
                            || rs.equals("cita_form") || rs.equals("cita_confirm")
                            || rs.equals("cita_done")) {
                        return "cita";
                    }
                    if (rs.equals("clave_error")) return "clave_error";
                    if (rs.equals("sslerror")) return "sslerror";
                    // unknown / clave_wait / stuck / anything else: slow or
                    // transient, NOT a cita. Retry immediately from the entry URL.
                    log("    tras 'Solicitar Cita' el estado fue '" + rs
                            + "' (no reconocido como cita); reintento sin avisar");
                    return "no_cita";
                }
                case "unknown":
                    // settle() already waited ~25s, so this is a genuinely
                    // unrecognised page — NOT a slow load. Don't reload on every
                    // unknown (that is the refresh storm); reload at most once,
                    // late, and only then give up.
                    unknowns++;
                    if (unknowns >= 4) return "stuck";
                    if (hasText("Acepto")) {          // dismiss cookie banner
                        click("Acepto");
                        sleep(D_MICRO);
                    } else if (unknowns == 3) {       // single late reload attempt
                        log("    página no reconocida; una recarga");
                        openEntry();
                    } else {
                        sleep(D_PAGELOAD);            // just wait, no reload
                    }
                    continue;
                case "cita_menu":
                    // The acCitar "Opciones de la cita" menu. Pressing "Solicitar
                    // Cita" here is what actually REQUESTS a cita; the result
                    // (no_cita / office list / slots) is settled on the next pass.
                    unknowns = 0;
                    doStep(state);
                    sleep(D_PAGELOAD);
                    break;
                case "cita_office":
                    // Citas exist (the acCitar page shows an office picker only
                    // when there IS availability). With auto-booking off, this is
                    // where the bot hands over: alert + stop, leaving the office
                    // picker on screen for the user to book by hand.
                    unknowns = 0;
                    if (!autoBook) {
                        log("    ¡hay citas! reserva automática desactivada: paro aquí "
                                + "para que la reserves a mano en Chrome");
                        return "cita";
                    }
                    // Alert the user right now — once — so a real cita is never
                    // missed even if the auto-booking below gets stuck. This does
                    // NOT stop the bot: it keeps trying to book while the user is
                    // notified.
                    if (!citaFound) {
                        citaFound = true;
                        notifyCitaFound();
                    }
                    doStep(state);
                    sleep(D_MICRO);
                    break;
                case "cita_form":
                case "slots_captcha":
                case "cita_confirm":
                    // Booking tail. Normally reached only past the cita_office
                    // gate above, but the flow can also land here directly (e.g.
                    // straight from "Solicitar Cita") — honour the manual-booking
                    // choice here too.
                    unknowns = 0;
                    if (!autoBook) {
                        log("    reserva automática desactivada: paro y te dejo la "
                                + "página abierta para reservar a mano");
                        return "cita";
                    }
                    doStep(state);
                    sleep(D_MICRO);
                    break;
                default:
                    unknowns = 0;
                    doStep(state);
                    sleep(D_MICRO);
            }
        }
        return "stuck";
    }

    // ---------------- per-state actions ----------------

    private void doStep(String state) {
        switch (state) {
            case "cita_menu":
                // acCitar "Opciones de la cita" menu — press "Solicitar Cita" to
                // request an appointment. Real-tap first (submit-style button),
                // fall back to the a11y click.
                if (!tapButtonScroll("Solicitar Cita")) {
                    click("Solicitar Cita");
                }
                sleep(D_PAGELOAD);
                break;
            case "province":
                selectOption("PROVINCIAS DISPONIBLES", provinceName);
                sleep(D_MICRO);
                click("Aceptar");
                sleep(D_PAGELOAD);
                break;
            case "office_tramite":
                selectOption("Selecciona Oficina", OFFICE);
                sleep(D_MICRO);
                selectTramite();
                sleep(D_MICRO);
                click("Aceptar");
                sleep(D_PAGELOAD);
                break;
            case "clave_choice":
                // Two panels share the "Cl@ve" label; the LEFT one is
                // "Presentación con Cl@ve". Match the full phrase directly.
                svc.botScroll("forward");
                sleepMs(600);
                if (!click("Presentación con Cl@ve")) {
                    click("con Cl@ve");
                }
                sleep(D_PAGELOAD);
                break;
            case "clave_platform":
                if (authMethod.equals("clave")) {
                    // Cl@ve gateway: choose "Permanent Cl@ve" (username+password
                    // the browser has saved). The card is a11y-exposed but below
                    // the fold and possibly English-labelled, so scroll to it and
                    // real-tap; only if that fails use the blind coordinate tap.
                    if (!tapAccessButton(CLAVE_PERM_LABELS)) tapClavePermanente();
                    sleep(D_PAGELOAD);
                } else {
                    // Certificate path: the "Access eIdentifier" card (ES/EN),
                    // same scroll-into-view + real-tap; blind tap as last resort.
                    if (!tapAccessButton(EIDENTIFIER_LABELS)) tapEIdentifier();
                    sleep(D_MICRO);
                    handleCertDialog();   // pick the surname cert + SELECT
                    sleep(D_PAGELOAD);
                }
                break;
            case "cita_office":
                // Citas available. Pick a random office (only offices WITH citas
                // are listed) and continue the booking. The acCitar page content
                // is NOT exposed to a11y, so select by coordinate (the dropdown
                // tap opens an a11y-readable list) and tap "Siguiente" by
                // position too. (No alert here — that fires only when the cita is
                // actually confirmed at the end.)
                int[] wh = svc.screenSize();
                String office = svc.botSelectRandom("Selecciona Oficina");
                if (office == null) {
                    office = svc.botSelectRandomCoord(wh[0] / 2, wh[1] * 32 / 100);
                }
                lastOffice = office == null ? "(oficina?)" : office;
                log("    oficina con cita (aleatoria): " + office);
                sleep(D_MICRO);
                if (!click("Siguiente")) {
                    svc.botTap(wh[0] / 2, wh[1] * 385 / 1000);   // Siguiente by coord
                    log("    tap 'Siguiente' (coordenada)");
                }
                sleep(D_PAGELOAD);
                break;
            case "cita_form":
                // Paso 2: complementary data — teléfono + correo (repeated).
                fillField(PHONE_LABELS, phone);
                fillEmailPair(email);
                sleep(D_MICRO);
                // The submit button here does NOT respond to ACTION_CLICK (the
                // form's handler only fires on a real touch) — tap it for real.
                tapButtonScroll("Siguiente");
                sleep(D_PAGELOAD);
                break;
            case "slots_captcha":
                handleSlotsCaptcha();
                break;
            case "cita_confirm":
                // acVerificarCita: tick the mandatory "Estoy conforme" consent
                // (NOT the marketing opt-in), then Confirmar (real tap).
                checkConsent();
                sleep(D_MICRO);
                if (!tapButtonScroll("Confirmar")) click("Confirmar");
                sleep(D_PAGELOAD);
                break;
            case "clave_login":
                // Handled in runAttempt() (it needs the "no saved credentials"
                // answer to stop the bot instead of looping).
                break;
            case "form":
                copyApplicantData();
                sleep(D_MICRO);
                selectOption("País de nacionalidad", country);
                sleep(D_MICRO);
                if (!email.isEmpty()) {
                    fillField(EMAIL_LABELS, email);
                    fillField(EMAIL2_LABELS, email);   // confirm-email field, if present
                }
                if (!phone.isEmpty()) {
                    fillField(PHONE_LABELS, phone);
                }
                sleep(D_MICRO);
                // This form's submit handler only fires on a REAL touch (like the
                // cita_form Siguiente): an a11y ACTION_CLICK reports OK but never
                // advances, so the bot would loop re-doing país. Real-tap it.
                if (!tapButtonScroll("Aceptar")) click("Aceptar");
                sleep(D_PAGELOAD);
                break;
            default:
                // nothing
        }
    }

    /** Press "Copiar a datos solicitante" on the *Rellene los campos* form — the
     *  button that copies the certificate holder's data into the applicant
     *  block. Without it the applicant fields stay empty and the form is
     *  rejected, so this does not settle for a single blind click():
     *
     *   1. the page repeats those words on a &lt;label&gt;/legend as well, and
     *      click() takes the FIRST match — a non-clickable one, whose clickable
     *      ancestor is the whole form container, so the press went nowhere.
     *      Pick the smallest CLICKABLE match instead, and real-tap it;
     *   2. if that node is off-screen (the button sits below the fold on a
     *      phone) fall back to the a11y click, which Chrome maps to a DOM click
     *      without scrolling, and then to a scrolled gesture tap;
     *   3. retry the lot, and say so loudly in the log if it never lands. */
    private boolean copyApplicantData() {
        int[] wh = svc.screenSize();
        for (int attempt = 0; attempt < 3 && running; attempt++) {
            java.util.List<JSONObject> hits = new java.util.ArrayList<>();
            collect(svc.botDump(), n -> {
                if (area(n) <= 0 || !n.optBoolean("enabled", true)) return false;
                if (!n.optBoolean("clickable")) return false;
                String txt = (n.optString("text", "") + " " + n.optString("desc", ""))
                        .toLowerCase(Locale.ROOT);
                return containsAny(txt, COPY_APPLICANT_LABELS);
            }, hits);
            JSONObject btn = smallest(hits);
            int[] c = center(btn);
            if (c != null && c[1] > 200 && c[1] < wh[1] - 130 && svc.botTap(c[0], c[1])) {
                log("    'Copiar a datos solicitante' (tap " + c[0] + "," + c[1] + ") -> OK");
                return true;
            }
            for (String lbl : COPY_APPLICANT_LABELS) {
                if (svc.botClick(lbl)) {
                    log("    click " + q(lbl) + " -> OK");
                    return true;
                }
            }
            if (tapButtonScroll(COPY_APPLICANT_LABELS[0])) return true;
            sleepMs(900);
        }
        log("    ⚠ no pude pulsar 'Copiar a datos solicitante': los datos del solicitante se "
                + "quedan vacíos y el formulario será rechazado");
        return false;
    }

    /** Select TOMA DE HUELLAS (a POLICÍA trámite). Two cases:
     *
     *  - Single-select provinces (e.g. Barcelona): Chrome exposes the policía
     *    select in the a11y tree, so pick it by its optgroup heading.
     *  - Two-select provinces (Extranjería + Policía, e.g. Tarragona): Chrome
     *    does NOT expose either select node, so tap the LOWER (policía) dropdown
     *    by coordinate — computed from the "Selecciona trámite" container bounds
     *    — which opens the (a11y-exposed) option list, then pick from it.
     *
     *  Never presses BACK on the page here: a stray BACK closes the Chrome tab. */
    private void selectTramite() {
        // a11y path — only when the select is actually in the tree.
        if (hasText("trámites policía nacional") || hasText("despliega para ver trámites")) {
            for (String anchor : new String[]{"POLICÍA NACIONAL", "Cuerpo Nacional de Policía"}) {
                if (selectOption(anchor, TRAMITE)) return;
            }
        }
        // Coordinate fallback for the two hidden selects.
        JSONObject cont = search(svc.botDump(), n -> {
            String t = n.optString("text", "").toLowerCase(Locale.ROOT);
            return t.contains("selecciona trámite") || t.contains("selecciona tramite");
        });
        if (cont == null) {
            log("    no encontré el contenedor 'Selecciona trámite'");
            return;
        }
        JSONArray b = cont.optJSONArray("bounds");
        if (b == null || b.length() < 4) return;
        int l = b.optInt(0), top = b.optInt(1), r = b.optInt(2), bot = b.optInt(3);
        int x = (l + r) / 2, h = bot - top;
        // Policía is the LOWER select (~80% down); if that list lacks the option,
        // the dropdown is open so BACK safely dismisses IT (not Chrome), then try
        // the upper block.
        if (svc.botSelectCoord(x, top + (h * 80 / 100), TRAMITE)) {
            log("    trámite policía (coordenada) -> OK");
            return;
        }
        svc.botGlobal("back");
        sleepMs(400);
        if (svc.botSelectCoord(x, top + (h * 55 / 100), TRAMITE)) {
            log("    trámite (coordenada alt) -> OK");
            return;
        }
        log("    no pude seleccionar el trámite policía (" + TRAMITE + ")");
    }

    /** Pick a dropdown option purely via accessibility (service `select`
     *  command): it finds the field label, opens the clickable control that
     *  follows it, and ACTION_CLICKs the option by text. */
    private boolean selectOption(String label, String option) {
        boolean ok = svc.botSelect(label, option);
        log("    select " + q(label) + " = " + q(option) + " -> " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    private boolean click(String label) {
        if (label == null) return false;
        boolean ok = svc.botClick(label);
        log("    click " + q(label) + " -> " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    /** Type `value` into the first editable field matching any of `labels`. */
    /** Fill a web form field: web inputs are empty and labelled by a preceding
     *  text node, so target the editable that FOLLOWS the label (botSetTextAfter),
     *  falling back to matching the field's own text. */
    private boolean fillField(String[] labels, String value) {
        if (value == null || value.isEmpty()) return false;
        for (String lbl : labels) {
            if (svc.botSetTextAfter(lbl, value) || svc.botSetText(lbl, value)) {
                log("    campo " + q(lbl) + " = " + q(value) + " -> OK");
                return true;
            }
        }
        log("    no encontré campo para " + q(labels[0]));
        return false;
    }

    /** Fill the "correo" field AND its "repite el correo" partner. The repeat
     *  field's own label ("Repita…"/"Confirme…") varies and isn't reliably
     *  matchable, but the two inputs are consecutive: once the email label is
     *  found, the 1st editable after it is the email and the 2nd is the repeat
     *  (skip=1). Falls back to the old label-based confirm field if there's no
     *  second input after the matched label. */
    private void fillEmailPair(String email) {
        if (email == null || email.isEmpty()) return;
        for (String lbl : EMAIL_LABELS) {
            if (svc.botSetTextAfter(lbl, email, 0)) {
                boolean rep = svc.botSetTextAfter(lbl, email, 1);
                log("    correo rellenado tras " + q(lbl)
                        + (rep ? " + repetición (2º campo)" : " (sin 2º campo aquí)"));
                if (!rep) fillField(EMAIL2_LABELS, email);
                return;
            }
        }
        log("    no encontré el campo de correo " + java.util.Arrays.toString(EMAIL_LABELS));
    }

    /** Log AND persist every appointment slot the offer page shows — oficina ·
     *  fecha · hora — INCLUDING slots outside the configured [minDate,maxDate]
     *  window (which the bot will NOT book). Purely additive: wrapped so any
     *  failure never disturbs the booking flow, and deduplicated for the run so
     *  the slow slot page (re-read on every poll) doesn't append repeats. */
    private void recordFoundCitas(JSONObject dump) {
        if (dump == null) return;
        try {
            // Variant A: "CITA n … Día: DD/MM/YYYY [HH:MM]" radios — date (and
            // often time) live in the row text.
            java.util.List<JSONObject> radios = new java.util.ArrayList<>();
            collect(dump, n -> n.optString("text", "").toUpperCase(Locale.ROOT).contains("CITA ")
                    && n.optString("text", "").contains("/"), radios);
            for (JSONObject r : radios) {
                String raw = r.optString("text", "").replaceAll("\\s+", " ").trim();
                saveFoundCita(lastOffice, parseDmyDate(raw), dmyOf(raw), timeOf(raw), raw);
            }
            // Variant B: month calendar — each clickable day cell IS an available
            // day. Times only show once a day is opened, so record day granularity
            // here; the chosen (in-range) day's LIBRE hour is logged in tapFreeTime.
            int[] my = calendarMonthYear(dump);
            if (my != null) {
                java.util.List<JSONObject> days = new java.util.ArrayList<>();
                collect(dump, n -> n.optBoolean("clickable")
                        && n.optString("text", "").trim().matches("\\d{1,2}"), days);
                for (JSONObject day : days) {
                    int dd = Integer.parseInt(day.optString("text").trim());
                    int date = my[0] * 10000 + my[1] * 100 + dd;
                    String ds = String.format(Locale.US, "%02d/%02d/%04d", dd, my[1], my[0]);
                    saveFoundCita(lastOffice, date, ds, "(día con hueco)", "calendario " + ds);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** First HH:MM in {@code s}, or "". */
    private static String timeOf(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2})").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** First DD/MM/YYYY in {@code s}, or "". */
    private static String dmyOf(String s) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{1,2}/\\d{1,2}/\\d{4})").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** Log one found cita and append it (once per run) to a durable CSV in the
     *  app's external files dir — pullable with
     *  `adb pull /sdcard/Android/data/com.looker.a11y/files/citas_encontradas.csv`
     *  and unaffected by the 300-line in-app log cap. */
    private void saveFoundCita(String office, int yyyymmdd, String dateStr, String time, String raw) {
        String key = office + "|" + yyyymmdd + "|" + time + "|" + raw;
        if (!recordedCitas.add(key)) return;   // already recorded this run
        boolean inRange = inDateRange(yyyymmdd);
        log("    🗓 CITA ENCONTRADA [" + (inRange ? "EN RANGO" : "fuera de rango") + "]: "
                + office + " · " + (dateStr.isEmpty() ? "?" : dateStr)
                + (time.isEmpty() ? "" : " " + time));
        TOTAL_CITAS_FOUND.incrementAndGet();
        synchronized (FOUND_CITAS) {
            FOUND_CITAS.addLast(new FoundCita(System.currentTimeMillis(), office, dateStr, time, inRange));
            while (FOUND_CITAS.size() > FOUND_CITAS_MAX) FOUND_CITAS.removeFirst();
        }
        try {
            java.io.File dir = svc.getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "citas_encontradas.csv");
            boolean fresh = !f.exists();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            if (fresh) {
                w.write("timestamp,oficina,fecha,hora,en_rango,texto\n");
                log("    (guardando citas en " + f.getAbsolutePath() + ")");
            }
            w.write(String.format(Locale.US, "%tF %<tT,%s,%s,%s,%s,%s%n",
                    System.currentTimeMillis(), csv(office), csv(dateStr), csv(time),
                    inRange ? "si" : "no", csv(raw)));
            w.close();
        } catch (Exception e) {
            log("    (no pude guardar la cita en CSV: " + e + ")");
        }
    }

    /** Quote a CSV field if it contains a comma, quote, or newline. */
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Paso 3 (acOfertarCita): pick a slot, solve the captcha from its audio
     *  (download → on-device whisper), submit, and retry via "Recargar Captcha"
     *  if rejected. The b/v audio ambiguity can misread a captcha, so retry. */
    private void handleSlotsCaptcha() {
        // Record EVERY slot this page offers (oficina · fecha · hora) — including
        // ones outside the configured date window, which won't be booked — before
        // choosing, so nothing seen is lost.
        recordFoundCitas(svc.botDump());
        // Choose a slot within the configured date window. Two page layouts:
        //   A) a fixed "CITA 1/2/3" radio list (each carries its Día), or
        //   B) a month calendar + a per-day time table (free rows = "LIBRE").
        if (!selectSlot()) {
            log("    sin hueco dentro del rango de fechas; reintento en el próximo ciclo");
            return;   // no bookable slot here -> caller loops / rotates IP
        }
        sleep(D_MICRO);
        for (int attempt = 1; attempt <= 5 && running; attempt++) {
            String code = obtainCaptchaFromAudio();
            if (code != null && code.length() >= 4) {
                svc.botSetTextAfter("valida el Captcha", code);
                log("    captcha (voz) = " + q(code) + ", enviando…");
                sleep(D_MICRO);
                // Submit needs a REAL tap (ACTION_CLICK doesn't fire the
                // handler), and Siguiente sits below the fold, so scroll to it.
                tapButtonScroll("Siguiente");
                sleep(D_PAGELOAD);
                // A correct captcha pops a "VAS A RESERVAR… ¿Estás seguro?"
                // dialog — accept it. Then the page leaves acOfertarCita and the
                // main loop takes over (cita_confirm -> cita_done).
                handleReservaDialog();
                sleep(D_PAGELOAD);
                if (!currentUrl().toLowerCase(Locale.ROOT).contains("acofertarcita")) {
                    log("    ✓ captcha aceptado — confirmando la reserva");
                    return;
                }
                log("    captcha rechazado (intento " + attempt + "/5), recargando…");
            } else {
                log("    no pude leer el captcha por voz (intento " + attempt + "/5)");
            }
            tapNode("Recargar Captcha", 2);
            sleep(D_PAGELOAD);
        }
        log("    captcha no resuelto tras 5 intentos");
    }

    /** Select a bookable slot within the configured date window. Returns false
     *  when nothing acceptable is offered (all occupied, or every free day is
     *  outside [minDate, maxDate]). Handles both acOfertarCita layouts. */
    private boolean selectSlot() {
        JSONObject dump = svc.botDump();
        // Variant A: fixed "CITA n" radio list, each labelled "CITA n Día: DD/MM/YYYY".
        java.util.List<JSONObject> radios = new java.util.ArrayList<>();
        collect(dump, n -> n.optBoolean("clickable")
                && n.optString("text", "").toUpperCase(Locale.ROOT).contains("CITA ")
                && n.optString("text", "").contains("/"), radios);
        if (!radios.isEmpty()) return selectCitaRadio(radios);
        // Variant B: month calendar + per-day time table.
        return selectCalendarSlot(dump);
    }

    /** Variant A — tap the radio of the earliest CITA whose Día is in range. */
    private boolean selectCitaRadio(java.util.List<JSONObject> radios) {
        JSONObject best = null;
        int bestKey = Integer.MAX_VALUE;
        for (JSONObject r : radios) {
            int d = parseDmyDate(r.optString("text", ""));
            if (!inDateRange(d)) continue;
            int key = d > 0 ? d : Integer.MAX_VALUE - 1;   // undated -> last resort
            if (key < bestKey) {
                bestKey = key;
                best = r;
            }
        }
        if (best == null) {
            log("    lista CITA: ninguna dentro del rango de fechas");
            return false;
        }
        int[] c = center(best);
        if (c != null && svc.botTap(c[0], c[1])) {
            log("    slot elegido (lista): "
                    + q(best.optString("text", "").replaceAll("\\s+", " ").trim()));
            return true;
        }
        return false;
    }

    /** Variant B — open the earliest in-range calendar day that has availability
     *  (day cells are clickable only when free), then tap its first LIBRE hour. */
    private boolean selectCalendarSlot(JSONObject dump) {
        int[] my = calendarMonthYear(dump);   // [year, month] of the shown page
        java.util.List<JSONObject> days = new java.util.ArrayList<>();
        collect(dump, n -> n.optBoolean("clickable")
                && n.optString("text", "").trim().matches("\\d{1,2}"), days);
        JSONObject bestDay = null;
        int bestKey = Integer.MAX_VALUE;
        for (JSONObject d : days) {
            int day = Integer.parseInt(d.optString("text").trim());
            int date = my != null ? my[0] * 10000 + my[1] * 100 + day : 0;
            if (!inDateRange(date)) continue;
            int key = date > 0 ? date : day;
            if (key < bestKey) {
                bestKey = key;
                bestDay = d;
            }
        }
        if (bestDay == null) {
            log("    calendario: ningún día disponible dentro del rango"
                    + (my != null ? " (" + MONTH_ES[my[1] - 1] + " " + my[0] + ")" : ""));
            return false;
        }
        int[] c = center(bestDay);
        if (c == null || !svc.botTap(c[0], c[1])) return false;
        log("    día elegido (calendario): " + bestDay.optString("text")
                + (my != null ? "/" + my[1] + "/" + my[0] : ""));
        sleep(D_PAGELOAD);
        return tapFreeTime();
    }

    /** Tap the first free ("LIBRE") time row of the open day, scrolling the slot
     *  table until that row is on-screen (its off-screen siblings report
     *  degenerate bounds, so a coordinate tap needs it visible). */
    private boolean tapFreeTime() {
        int[] wh = svc.screenSize();
        for (int i = 0; i < 16 && running; i++) {
            JSONObject libre = search(svc.botDump(), n ->
                    "LIBRE".equalsIgnoreCase(n.optString("text", "").trim())
                            && n.optBoolean("clickable"));
            if (libre != null) {
                int[] c = center(libre);
                if (c != null && c[1] >= 260 && c[1] <= wh[1] - 140 && svc.botTap(c[0], c[1])) {
                    log("    hora elegida: primera LIBRE");
                    return true;
                }
                // In the tree but off-screen: ACTION_CLICK by text as a fallback.
                if (svc.botClick("LIBRE")) {
                    log("    hora elegida: LIBRE (a11y click)");
                    return true;
                }
            }
            svc.botSwipe(wh[0] / 2, wh[1] * 3 / 4, wh[0] / 2, wh[1] / 4, 350);
            sleepMs(600);
        }
        log("    calendario: sin hora LIBRE visible para el día elegido");
        return false;
    }

    private static final String[] MONTH_ES = {"enero", "febrero", "marzo", "abril", "mayo",
            "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    /** Parse the Spanish calendar header ("Agosto 2026") -> [year, monthNum]
     *  (1-based), or null. Tolerates the NBSP the site uses as a separator. */
    private int[] calendarMonthYear(JSONObject dump) {
        JSONObject hdr = search(dump, n -> {
            String t = headerText(n);
            for (String mo : MONTH_ES) if (t.matches(mo + "\\s+\\d{4}")) return true;
            return false;
        });
        if (hdr == null) return null;
        String t = headerText(hdr);
        for (int i = 0; i < MONTH_ES.length; i++) {
            if (t.startsWith(MONTH_ES[i])) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})").matcher(t);
                if (m.find()) return new int[]{Integer.parseInt(m.group(1)), i + 1};
            }
        }
        return null;
    }

    private static String headerText(JSONObject n) {
        return n.optString("text", "").toLowerCase(Locale.ROOT).replace('\u00a0', ' ').trim();
    }

    /** Parse "…Día: DD/MM/YYYY…" -> yyyymmdd int, or 0. */
    private static int parseDmyDate(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(s);
        if (!m.find()) return 0;
        return Integer.parseInt(m.group(3)) * 10000
                + Integer.parseInt(m.group(2)) * 100 + Integer.parseInt(m.group(1));
    }

    /** Accept the "VAS A RESERVAR LA CITA SELECCIONADA — ¿Estás seguro?" dialog
     *  that a correct captcha triggers, by tapping its SÍ button. */
    private void handleReservaDialog() {
        String t = collectText().toLowerCase(Locale.ROOT);
        if (!(t.contains("vas a reservar") || t.contains("estás seguro")
                || t.contains("estas seguro"))) {
            return;
        }
        if (tapExact("sí") || tapExact("si") || click("Sí")) {
            log("    diálogo '¿estás seguro?' -> SÍ");
            sleep(D_MICRO);
        }
    }

    /** Tick the mandatory "Estoy conforme con la información" consent on
     *  acVerificarCita. Leaves the marketing opt-in ("Acepto recibir
     *  información…") unchecked. */
    private void checkConsent() {
        JSONObject cb = search(svc.botDump(), n -> {
            String cl = n.optString("class", "");
            String tx = n.optString("text", "").toLowerCase(Locale.ROOT);
            return cl.endsWith("CheckBox") && tx.contains("conforme")
                    && !n.optBoolean("checked", false);
        });
        int[] c = center(cb);
        if (c != null && svc.botTap(c[0], c[1])) {
            log("    consentimiento 'Estoy conforme' marcado");
        } else {
            log("    no encontré (o ya estaba marcado) el checkbox 'Estoy conforme'");
        }
    }

    /** Read and log the booking reference from the acGrabarCita success page
     *  ("Nº de Justificante de cita: XXXXXXXX"). */
    private void logJustificante() {
        String t = collectText();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("justificante[^:]*:?\\s*([A-Z0-9]{6,})", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m.find()) {
            log("🎉 CITA CONFIRMADA — Nº de justificante: " + m.group(1));
        } else {
            log("🎉 CITA CONFIRMADA (justificante en pantalla/correo)");
        }
    }

    /** Real-tap a clickable node whose text EXACTLY equals {@code label}
     *  (case-insensitive, trimmed) — for short, ambiguous labels like "SÍ"
     *  where a substring match would hit unrelated text. */
    private boolean tapExact(String label) {
        final String want = label.toLowerCase(Locale.ROOT);
        JSONObject n = search(svc.botDump(), node -> {
            String tx = node.optString("text", "").trim().toLowerCase(Locale.ROOT);
            String de = node.optString("desc", "").trim().toLowerCase(Locale.ROOT);
            return (tx.equals(want) || de.equals(want))
                    && (node.optBoolean("clickable") || node.optString("class", "").endsWith("Button"));
        });
        int[] c = center(n);
        if (c != null && svc.botTap(c[0], c[1])) {
            log("    tap " + q(label) + " (" + c[0] + "," + c[1] + ")");
            return true;
        }
        return false;
    }

    /** Real-tap a button by label, scrolling it into view first when it sits
     *  below the fold (e.g. Siguiente/Confirmar at the page bottom). ACTION_CLICK
     *  is deliberately avoided — several of these submit buttons only respond to
     *  a genuine touch. */
    private boolean tapButtonScroll(String label) {
        int[] wh = svc.screenSize();
        int top = 260, bottom = wh[1] - 130;   // keep clear of toolbar / nav bar
        for (int i = 0; i < 5 && running; i++) {
            int[] c = center(findButtonNode(label));
            if (c != null && c[1] >= top && c[1] <= bottom) {
                if (svc.botTap(c[0], c[1])) {
                    log("    tap " + q(label) + " (" + c[0] + "," + c[1] + ")");
                    return true;
                }
            }
            // Off-screen or not found yet: scroll down and retry.
            svc.botSwipe(wh[0] / 2, wh[1] * 3 / 4, wh[0] / 2, wh[1] / 4, 350);
            sleepMs(700);
        }
        log("    tap " + q(label) + " -> FAIL (no visible)");
        return false;
    }

    private static final int CAPTCHA_CAPTURE_SECS = 9;

    /** Read the audio captcha by PLAYING it and CAPTURING the browser's playback
     *  with MediaProjection ({@link AudioCaptureService}), then transcribing the
     *  WAV on-device with whisper.
     *
     *  This is browser-agnostic on purpose: Firefox exposes neither a download
     *  button nor the audio's src (the accessibility tree carries no DOM URLs),
     *  so the Chrome-era "download the mp3 via the &lt;audio&gt; overflow menu"
     *  route is gone. Capturing what actually plays needs neither.
     *
     *  Sequence: start the recorder, give it a moment to spin up, tap "Escuchar
     *  pista sonora" so the HTML5 &lt;audio&gt; plays, then wait for the capture
     *  to finish and transcribe it. Needs the one-time MediaProjection consent
     *  (granted in MainActivity); without it there is nothing to capture. */
    private String obtainCaptchaFromAudio() {
        if (!AudioCaptureService.hasConsent()) {
            log("    sin permiso de captura de audio; concédelo en la app (botón 'DAR PERMISO')");
            return null;
        }
        String wav = svc.getCacheDir().getAbsolutePath() + "/captcha.wav";
        AudioCaptureService.lastWav = null;
        AudioCaptureService.lastError = null;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AudioCaptureService.doneLatch = latch;
        Intent i = new Intent(svc, AudioCaptureService.class);
        i.putExtra("path", wav);
        i.putExtra("secs", CAPTCHA_CAPTURE_SECS);
        try {
            svc.startForegroundService(i);
        } catch (Throwable t) {
            log("    no pude arrancar la captura de audio: " + t);
            return null;
        }
        // Let the recorder actually start before the audio plays, or its first
        // second (often the whole short captcha) is lost.
        sleepMs(800);
        if (!tapNode("Escuchar pista sonora", 4)) {
            log("    no encontré 'Escuchar pista sonora' para reproducir el captcha");
        }
        try {
            latch.await(CAPTCHA_CAPTURE_SECS + 8L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (AudioCaptureService.lastError != null) {
            log("    captura de audio falló: " + AudioCaptureService.lastError);
            return null;
        }
        if (AudioCaptureService.lastWav == null) {
            log("    la captura de audio no produjo WAV");
            return null;
        }
        log("    audio captcha capturado (" + CAPTCHA_CAPTURE_SECS + "s) — transcribiendo…");
        return Whisper.readCaptcha(svc, AudioCaptureService.lastWav);
    }

    /** Confirm Chrome's "Download file again? — File name already exists"
     *  dialog. It appears the first time a repeated file name is written; we
     *  only press its confirm button (never touch the "Don't show again"
     *  checkbox — toggling a pre-checked box would make the dialog return). No
     *  dialog on screen -> no-op. The dialog is a native Android view, so
     *  ACTION_CLICK works on its button; a real tap is the fallback. */
    private void dismissDownloadDialog() {
        String t = collectText().toLowerCase(Locale.ROOT);
        if (!containsAny(t, "file name already exists", "download file again",
                "el archivo ya existe", "descargar de nuevo", "descargar el archivo")) {
            return;
        }
        for (String d : new String[]{"Download", "Descargar", "Aceptar", "OK"}) {
            if (click(d)) {
                log("    diálogo 'descargar de nuevo' confirmado (" + d + ")");
                sleepMs(700);
                return;
            }
        }
        if (tapNode("Download", 2) || tapNode("Descargar", 2)) {
            log("    diálogo 'descargar de nuevo' confirmado (tap)");
            sleepMs(700);
        }
    }

    /** First node whose text OR desc contains {@code label} (case-insensitive),
     *  across the merged dump. */
    private JSONObject findNode(String label) {
        final String want = label.toLowerCase(Locale.ROOT);
        return search(svc.botDump(), node -> {
            String tx = node.optString("text", "").toLowerCase(Locale.ROOT);
            String de = node.optString("desc", "").toLowerCase(Locale.ROOT);
            return tx.contains(want) || de.contains(want);
        });
    }

    /** Best TAPPABLE node matching {@code label} — a clickable node or a Button
     *  whose text/desc contains the label and whose bounds are real. This is the
     *  finder for submit buttons, and it deliberately differs from
     *  {@link #findNode}: it skips nodes with degenerate (zero-area) bounds and
     *  prefers a clickable/Button over a plain TextView.
     *
     *  Why it matters: the ICP "Rellene los campos" page carries an instructional
     *  paragraph — "…rellene todos los campos obligatorios y pulse aceptar" — as a
     *  ZERO-HEIGHT TextView that comes BEFORE the real "Aceptar" button
     *  (res_id btnEnviar) in document order. findNode() returned that paragraph,
     *  center() rejected its degenerate bounds, and the form never got tapped
     *  (nor did the a11y-click fallback, which walked the paragraph up to the
     *  form container and no-op'd) — so the flow looped on the form until it hit
     *  the step ceiling. Matching a real button avoids that trap. */
    private JSONObject findButtonNode(String label) {
        final String want = label.toLowerCase(Locale.ROOT);
        java.util.List<JSONObject> any = new java.util.ArrayList<>();
        collect(svc.botDump(), n -> {
            if (area(n) <= 0) return false;
            String tx = (n.optString("text", "") + " " + n.optString("desc", ""))
                    .toLowerCase(Locale.ROOT);
            return tx.contains(want);
        }, any);
        java.util.List<JSONObject> buttons = new java.util.ArrayList<>();
        for (JSONObject n : any) {
            if (n.optBoolean("clickable") || n.optString("class", "").endsWith("Button")) {
                buttons.add(n);
            }
        }
        return smallest(buttons.isEmpty() ? any : buttons);
    }

    /** REAL-tap the centre of the node matching {@code label}, re-reading the
     *  tree between tries (Chrome re-lays the media player out, so a node can
     *  briefly move/vanish). A genuine touch — required where ACTION_CLICK is a
     *  no-op or navigates (the media overflow menu). */
    private boolean tapNode(String label, int tries) {
        for (int i = 0; i < tries && running; i++) {
            int[] c = center(findNode(label));
            if (c != null && svc.botTap(c[0], c[1])) {
                log("    tap " + q(label) + " (" + c[0] + "," + c[1] + ")");
                return true;
            }
            sleepMs(500);
        }
        log("    tap " + q(label) + " -> FAIL");
        return false;
    }

    /** Tap a Cl@ve-gateway "Access X" card, matching any of {@code labels}
     *  (Spanish or English — the pasarela follows the browser UI language),
     *  scrolling it into view first (the permanent-Cl@ve card sits below the
     *  fold). Only CLICKABLE nodes / Buttons are considered, so a matching
     *  heading TextView isn't tapped instead of its button. Returns false when
     *  the button isn't on this page (e.g. an intermediate ICP redirect that
     *  isn't exposed) so the caller can fall back to a blind tap. */
    private boolean tapAccessButton(String[] labels) {
        int[] wh = svc.screenSize();
        for (int i = 0; i < 6 && running; i++) {
            JSONObject btn = search(svc.botDump(), n -> {
                if (!(n.optBoolean("clickable") || n.optString("class", "").endsWith("Button"))) {
                    return false;
                }
                String s = (n.optString("text", "") + " " + n.optString("desc", ""))
                        .toLowerCase(Locale.ROOT);
                for (String l : labels) if (s.contains(l.toLowerCase(Locale.ROOT))) return true;
                return false;
            });
            int[] c = center(btn);
            if (c != null && c[1] >= 260 && c[1] <= wh[1] - 140 && svc.botTap(c[0], c[1])) {
                log("    acceso Cl@ve/cert -> "
                        + q(btn.optString("text", btn.optString("desc", "?"))));
                return true;
            }
            // Present but below the fold (or not here yet) -> scroll and retry.
            svc.botSwipe(wh[0] / 2, wh[1] * 3 / 4, wh[0] / 2, wh[1] / 4, 350);
            sleepMs(700);
        }
        return false;
    }

    /** Tap the "Access eIdentifier" button on the Cl@ve method-selection page.
     *  That page's web content is NOT exposed to accessibility, so we tap by
     *  position: middle card of three, ~70% down a top-scrolled page. */
    private void tapEIdentifier() {
        int[] wh = svc.screenSize();
        int x = wh[0] / 2;
        int y = wh[1] * 70 / 100;
        svc.botTap(x, y);
        log("    tap 'Access eIdentifier' (coordenada " + x + "," + y + ")");
    }

    /** Same blind tap for the "Cl@ve Permanente" card (it sits below the
     *  certificate one). Only used when the method page isn't exposed to a11y —
     *  TUNE {@link #CLAVE_PERM_TAP_PCT} against the live page if it lands wrong. */
    private void tapClavePermanente() {
        int[] wh = svc.screenSize();
        int x = wh[0] / 2;
        int y = wh[1] * CLAVE_PERM_TAP_PCT / 100;
        svc.botTap(x, y);
        log("    no encontré la tarjeta Cl@ve permanente en el árbol a11y "
                + java.util.Arrays.toString(CLAVE_PERM_LABELS)
                + "; tap por coordenada (" + x + "," + y + ", "
                + CLAVE_PERM_TAP_PCT + "% de alto — ajusta CLAVE_PERM_TAP_PCT si cae mal)");
    }

    // ------------- Cl@ve Permanente sign-in (Android credential sheet) -------------

    /** Sign in with Cl@ve Permanente WITHOUT this app ever holding the
     *  credentials, in exactly two gestures:
     *
     *    1. tap the user field — a REAL tap (not ACTION_FOCUS): the Android
     *       credential sheet hangs off the touch, not off focus;
     *    2. press that sheet's sign-in button.
     *
     *  The sheet fills user + password and submits the form itself, so the bot
     *  does NOT press the site's "Autenticar" afterwards. The user saves the
     *  credentials once, by hand, in Chrome.
     *
     *  Returns false only when no sheet appeared AND the field is still empty —
     *  a setup problem (no saved password / autofill off) that a retry cannot
     *  fix, so the caller stops the bot instead of hammering the login page. */
    private boolean claveLogin() {
        java.util.List<JSONObject> fields = webFields();
        log("    Cl@ve permanente: " + fields.size() + " campo(s) de la página en el árbol a11y");

        // 1) focus ONE field: the user one (the sheet fills the password too).
        if (!fields.isEmpty()) {
            int[] c = center(fields.get(0));
            svc.botTap(c[0], c[1]);
            log("    toco el campo de usuario (" + c[0] + "," + c[1] + ") y espero la hoja de "
                    + "credenciales de Android");
        } else {
            int[] wh = svc.screenSize();
            int y = wh[1] * CLAVE_USER_FIELD_PCT / 100;
            svc.botTap(wh[0] / 2, y);
            log("    página Cl@ve no expuesta a a11y; toco el campo de usuario por coordenada ("
                    + wh[0] / 2 + "," + y + ")");
        }

        // 2) press the sheet's sign-in button — and nothing else. The sheet can
        //    take a moment to slide up, so look for it a few times.
        boolean signed = false;
        for (int i = 0; i < 4 && running && !signed; i++) {
            sleepMs(1200);
            signed = tapSheetSignIn();
        }
        if (signed) {
            // The sheet submits; the state machine re-reads the next page. The
            // password is masked in the a11y tree, so this is as far as
            // verification can go.
            sleep(D_PAGELOAD);
            return true;
        }

        sleepMs(900);
        fields = webFields();
        boolean userFilled = !fields.isEmpty() && filled(fields.get(0));
        if (userFilled) {
            // Autofill filled the form without offering the sheet. Do NOT press
            // the site's button: wait for the sheet on the next pass (and the
            // clave_login counter restarts the attempt if it never shows).
            log("    el navegador ya rellenó el formulario pero no salió la hoja de "
                    + "credenciales; espero (no pulso el botón de la web)");
            return true;
        }
        log("    ⚠ no salió la hoja de credenciales de Android y el campo sigue vacío. Entra UNA "
                + "vez a mano en Cl@ve desde Chrome, guarda usuario+contraseña en el gestor y "
                + "activa el autocompletado; luego vuelve a arrancar el bot.");
        return false;
    }

    /** Press the sign-in button of the Android credential sheet (Chrome's
     *  touch-to-fill or the system Autofill picker).
     *
     *  Only NATIVE nodes are eligible — a node with a res_id. Web-page nodes
     *  never have one, so the site's own "Autenticar" / "Iniciar sesión" button
     *  can never be picked here, whatever its label says. */
    private boolean tapSheetSignIn() {
        java.util.List<JSONObject> buttons = new java.util.ArrayList<>();
        java.util.List<JSONObject> rows = new java.util.ArrayList<>();
        collect(svc.botDump(), n -> {
            if (!n.optBoolean("enabled", true) || !n.optBoolean("clickable") || area(n) <= 0) {
                return false;
            }
            String rid = n.optString("res_id", "").toLowerCase(Locale.ROOT);
            if (rid.isEmpty()) return false;              // page content — never click it
            String txt = labelOf(n);
            return containsAny(txt, SHEET_SIGNIN_LABELS)
                    || containsAny(rid, AUTOFILL_RESID_HINTS)
                    || containsAny(txt, AUTOFILL_TEXT_HINTS);
        }, buttons);
        // One walk collects both kinds; move the non-labelled hits to `rows`.
        for (java.util.Iterator<JSONObject> it = buttons.iterator(); it.hasNext(); ) {
            JSONObject n = it.next();
            if (!containsAny(labelOf(n), SHEET_SIGNIN_LABELS)) {
                rows.add(n);
                it.remove();
            }
        }
        // The labelled button is the one asked for; the credential row is the
        // fallback for sheets that fill on a single tap with no button.
        JSONObject best = smallest(buttons);
        if (best == null) best = smallest(rows);
        if (best == null) return false;
        int[] c = center(best);
        if (c == null) return false;
        boolean ok = svc.botTap(c[0], c[1]);
        log("    hoja de credenciales: pulso "
                + q(best.optString("text", best.optString("res_id", "?"))) + " -> "
                + (ok ? "OK" : "FAIL"));
        return ok;
    }

    /** Lowercased text of a node AND its descendants: a sheet button is often a
     *  clickable container with the label on a child TextView. Matching the
     *  subtree also matches the container, but smallest() then picks the
     *  tightest node of the ones that matched. */
    private static String labelOf(JSONObject n) {
        StringBuilder b = new StringBuilder();
        appendLabel(n, b, 3);
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendLabel(JSONObject n, StringBuilder out, int depth) {
        if (n == null || depth < 0) return;
        out.append(n.optString("text", "")).append(' ').append(n.optString("desc", "")).append(' ');
        JSONArray ch = n.optJSONArray("children");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) appendLabel(ch.optJSONObject(i), out, depth - 1);
        }
    }

    /** Smallest node of the list — the button/row itself, not its container. */
    private static JSONObject smallest(java.util.List<JSONObject> nodes) {
        JSONObject best = null;
        for (JSONObject n : nodes) if (best == null || area(n) < area(best)) best = n;
        return best;
    }

    /** Handle Chrome's KeyChain "Choose certificate" dialog (this IS exposed to
     *  a11y): select the certificate whose entry contains the configured surname
     *  (certMatch) — which enables the SELECT button — then click SELECT. */
    private void handleCertDialog() {
        for (int i = 0; i < 12 && running; i++) {
            String txt = collectText().toLowerCase(Locale.ROOT);
            boolean isChooser = txt.contains("choose certificate")
                    || txt.contains("select a certificate") || txt.contains("elegir un certificado")
                    || txt.contains("seleccionar un certificado")
                    || (txt.contains("certificad") && (txt.contains("deny") || txt.contains("denegar")));
            if (isChooser) {
                // 1) Select the wanted certificate (by surname/substring).
                if (!certMatch.isEmpty() && txt.contains(certMatch.toLowerCase(Locale.ROOT))) {
                    boolean picked = svc.botClick(certMatch);
                    log("    certificado " + q(certMatch) + " -> " + (picked ? "OK" : "FAIL"));
                    sleepMs(700);
                } else if (certMatch.isEmpty()) {
                    log("    ⚠ sin apellido configurado; no puedo elegir certificado");
                }
                // 2) Confirm. The positive button FIRST by view id: on Android 15
                //    this dialog hands the service a child list without
                //    android:id/button1 in it (DENY/button2 is there), so a
                //    label search never finds "SELECT" and the bot used to sit
                //    here until the loop timed out. By-id goes through the
                //    app-side query and is locale-proof too.
                if (svc.botClickId("android:id/button1")) {
                    log("    diálogo certificado: click botón positivo (android:id/button1)");
                    return;
                }
                for (String ok : new String[]{"SELECT", "Seleccionar", "Aceptar", "OK", "Allow"}) {
                    if (svc.botClick(ok)) {
                        log("    diálogo certificado: click " + q(ok));
                        return;
                    }
                }
                if (tapPositiveButtonBlind()) return;
            }
            sleepMs(1000);
        }
        log("    no apareció diálogo de certificado (¿selección automática?)");
    }

    /** Tap a dialog's POSITIVE button by geometry, for dialogs that hide it from
     *  accessibility.
     *
     *  Measured on Android 15 with the KeyChain "Choose certificate" dialog:
     *  android:id/button1 ("SELECT") is absent from this service's view of the
     *  tree — not by walking it, not via findAccessibilityNodeInfosByViewId, not
     *  by text — with or without a certificate selected, and it never appears,
     *  however long you wait. That button is accessibility-data-sensitive: the
     *  system hides it so an accessibility service cannot grant a certificate on
     *  its own. DENY (android:id/button2) and the panel holding both buttons ARE
     *  exposed, so the positive button is the free space on the other side of
     *  DENY: tap the middle of it (right of DENY on LTR, left on RTL). A REAL
     *  touch still works — only the node is hidden, not the button. */
    private boolean tapPositiveButtonBlind() {
        JSONObject panel = findByResIdSuffix(svc.botDump(), "android:id/buttonPanel");
        JSONObject deny = findByResIdSuffix(svc.botDump(), "android:id/button2");
        if (panel == null || deny == null) {
            log("    no encontré el panel de botones del diálogo");
            return false;
        }
        JSONArray p = panel.optJSONArray("bounds");
        JSONArray d = deny.optJSONArray("bounds");
        if (p == null || d == null || p.length() < 4 || d.length() < 4) return false;
        int y = (d.optInt(1) + d.optInt(3)) / 2;
        int denyW = Math.max(1, d.optInt(2) - d.optInt(0));
        int minGap = Math.max(60, denyW / 3);
        // The buttons are right-aligned, so the WIDE empty half on the left is
        // just padding — the positive button lives in the NARROW gap right of
        // DENY (mirrored on RTL). Try that side first and check whether the
        // dialog actually went away; if it didn't, try the other side.
        int[] xs = new int[2];
        int n = 0;
        if (p.optInt(2) - d.optInt(2) >= minGap) xs[n++] = (d.optInt(2) + p.optInt(2)) / 2;
        if (d.optInt(0) - p.optInt(0) >= minGap) xs[n++] = (p.optInt(0) + d.optInt(0)) / 2;
        if (n == 0) {
            log("    no hay hueco junto a DENY: ¿diálogo de un solo botón?");
            return false;
        }
        for (int i = 0; i < n && running; i++) {
            svc.botTap(xs[i], y);
            sleepMs(900);
            if (findByResIdSuffix(svc.botDump(), "android:id/button2") == null) {
                log("    diálogo certificado: botón positivo por geometría (" + xs[i] + "," + y
                        + ") -> OK");
                return true;
            }
            log("    (" + xs[i] + "," + y + ") no era el botón positivo; el diálogo sigue ahí");
        }
        return false;
    }

    // ---------------- state detection ----------------

    private String classify(String rawText, String rawUrl) {
        String t = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
        String ti = rawUrl == null ? "" : rawUrl.toLowerCase(Locale.ROOT);

        if (ti.contains("request rejected") || ti.contains("403 forbidden") || t.contains("forbidden")
                || t.contains("request rejected") || t.contains("was rejected")
                || t.contains("requested url was rejected")) {
            return "waf";
        }
        // Browser TLS interstitial (seen on cold loads / stale network state).
        // With the RapidSSL intermediate installed as a user CA the ICP cert chain
        // validates, so this should be rare; when it appears, reload and retry.
        if (t.contains("your connection is not private") || t.contains("net::err_cert")
                || t.contains("err_cert_authority_invalid") || t.contains("connection is not private")
                || t.contains("la conexión no es privada") || t.contains("tu conexión no es privada")) {
            return "sslerror";
        }
        if (containsAny(t, "sesión ha caducado", "sesion ha caducado", "sesión ha expirado",
                "sesión ha finalizado", "iniciar de nuevo", "session expired",
                "document expired", "documento expirado") || ti.contains("problem loading")) {
            return "expired";
        }
        // Flow/form pages matched BEFORE the generic "no hay citas" text, because
        // the solicitante form carries a persistent "no hay citas" banner. Only
        // the TERMINAL page (none of these controls) is a real no_cita.
        // Cl@ve Permanente sign-in form. Checked BEFORE clave_platform: these
        // pages' URLs also say "clave", so the platform rule would swallow them.
        //
        // The login form lives on the clave-dninbrt / IPUC2 / AuthByLevelForm
        // host. It must be detected robustly because (a) its user+password
        // inputs sometimes lag the first a11y read (so webFields()>=2 misses on
        // arrival) and (b) the page may render in ENGLISH (so a Spanish-only
        // "contraseña"/"autenticar" test misses). So: login host + ANY sign-in
        // affordance — the fields, the submit button, or the forgot-password /
        // not-registered links (all present in the a11y text). The pasarela
        // (method choice) has none of these, so it stays clave_platform below.
        boolean claveLoginHost = ti.contains("clave-dninbrt") || ti.contains("ipuc2")
                || ti.contains("authbylevelform");
        boolean loginAffordance = webFields().size() >= 2
                || containsAny(t, "olvidado", "forgot my password", "forgot password",
                "no estoy registrado", "not registered", "enviar formulario",
                "contraseña", "autenticar", "acceso clave permanente");
        if ((claveLoginHost && loginAffordance)
                || (containsAny(t, "contraseña", "autenticar")
                && containsAny(t, "dni", "nie", "usuario"))) {
            // Wrong/blocked credentials: STOP rather than retry — Cl@ve blocks the
            // account after a handful of failed sign-ins.
            if (containsAny(t, "no son correctos", "no es correcta", "incorrect", "erróneos",
                    "erroneos", "bloquead", "número máximo de intentos", "no válid", "no valid",
                    "incorrect user", "usuario o contraseña")) {
                return "clave_error";
            }
            return "clave_login";
        }
        // Fallback: a clave URL Chrome exposes only as a couple of form fields.
        if (ti.contains("clave") && !ti.contains("cita") && webFields().size() >= 2) {
            return "clave_login";
        }
        // Cl@ve method-selection gateway — ONLY when its method cards are
        // actually rendered (they name the methods). This is keyed on CONTENT,
        // not the URL, so the intermediate ICP page that lists the Cl@ve options
        // is caught too, while the empty/transient Cl@ve pages below are not.
        boolean methodAffordance = containsAny(t, "access permanent clave", "access eidentifier",
                "acceso clave permanente", "acceso eidentificador", "identification platform",
                "plataforma de identificaci", "identification method", "método de identificaci",
                "eidentifier", "cl@ve móvil", "clave móvil");
        if (methodAffordance) {
            return "clave_platform";
        }
        // Any OTHER clave URL (Proxy2/ServiceRedirect, ResponseRedirect,
        // IPUC2/Autenticacion, or a still-loading gateway) is a transient auto-
        // forwarding page: DON'T tap it (that's the redirect loop) — just wait.
        if (ti.contains("clave") && !ti.contains("cita")) {
            return "clave_wait";
        }
        if (t.contains("presentación con cl@ve") && t.contains("presentación sin cl@ve")) {
            return "clave_choice";
        }
        // acGrabarCita: booking recorded — "CITA CONFIRMADA" + justificante.
        // The terminal success page; matched first so nothing else steals it.
        if (ti.contains("acgrabarcita") || t.contains("cita confirmada")
                || t.contains("justificante de cita")) {
            return "cita_done";
        }
        // acVerificarCita: confirm the assigned appointment (tick "Estoy
        // conforme" + Confirmar) — the step between the captcha and grabarCita.
        if (ti.contains("acverificarcita") || t.contains("debes confirmar los datos de la cita")
                || t.contains("confirmar los datos de la cita asignada")) {
            return "cita_confirm";
        }
        // acCitar: the SAME URL serves three different pages, so it must be
        // classified by CONTENT, not the URL alone (verified live 2026-08-14,
        // Barcelona / toma de huellas):
        //   1. the appointment MENU — "Opciones de la cita": Solicitar Cita /
        //      Consultar Citas / Anular Cita / Salir. Reaching it does NOT mean
        //      citas exist; you must press "Solicitar Cita" to request one.
        //   2. after that press, "En este momento no hay citas disponibles."
        //      (only a "Salir" button) — a real no_cita.
        //   3. when citas DO exist, an office picker ("Selecciona Oficina" +
        //      "Siguiente") — the genuine cita_office.
        // The old code returned cita_office for ALL of these, so the bot hunted
        // a non-existent office dropdown on the menu/no-citas pages and looped.
        if (ti.contains("accitar")) {
            if (containsAny(t, "no hay citas", "no existen citas")) {
                return "no_cita";
            }
            if (t.contains("selecciona oficina") || t.contains("seleccione la oficina")) {
                return "cita_office";
            }
            if (containsAny(t, "solicitar cita", "opciones de la cita", "anular cita",
                    "consultar citas")) {
                return "cita_menu";
            }
            // Still rendering — let settle() poll again rather than guess.
            return "unknown";
        }
        // acOfertarCita: slot list + CAPTCHA (Paso 3 de 5).
        if (ti.contains("acofertarcita") || t.contains("valida el captcha")) {
            return "slots_captcha";
        }
        // acVerFormulario: complementary-data form (teléfono + correo, Paso 2).
        if (ti.contains("acverformulario") || t.contains("información complementaria")) {
            return "cita_form";
        }
        if (t.contains("copiar a datos solicitante") || t.contains("país de nacionalidad")) {
            return "form";
        }
        if (t.contains("solicitar cita")) {
            return "options";
        }
        if (t.contains("provincias disponibles") && t.contains("aceptar")) {
            return "province";
        }
        if (t.contains("selecciona oficina") || t.contains("trámites policía nacional")) {
            return "office_tramite";
        }
        if (containsAny(t, "seleccione", "calendario", "franja horaria", "horario",
                "elija hora", "días disponibles", "dia disponible")) {
            return "slots";
        }
        if (t.contains("no hay citas") || t.contains("no existen citas")) {
            return "no_cita";
        }
        return "unknown";
    }

    // ---------------- accessibility-tree helpers ----------------

    /** Poll the page until it classifies as something recognised, or a ceiling
     *  is hit — the fix for slow mobile loads. Re-reads every ~1.5s for up to
     *  ~25s WITHOUT reloading. A transient WAF/expired reading is confirmed by a
     *  second read so a mid-load flash doesn't trigger a needless back-off. */
    private String settle() {
        long deadline = System.currentTimeMillis() + 25_000;
        String state = classify(collectText(), currentUrl());
        while (running) {
            if (!state.equals("unknown")) {
                // Confirm sticky "bad" states so a load-time flash isn't trusted.
                if (state.equals("waf") || state.equals("expired") || state.equals("sslerror")) {
                    sleepMs(1200);
                    String again = classify(collectText(), currentUrl());
                    if (!again.equals(state)) {
                        state = again;
                        continue;   // re-evaluate the new reading
                    }
                }
                return state;
            }
            if (System.currentTimeMillis() >= deadline) return "unknown";
            sleepMs(1500);
            state = classify(collectText(), currentUrl());
        }
        return state;
    }

    private String collectText() {
        JSONObject dump = svc.botDump();
        StringBuilder sb = new StringBuilder();
        if (dump != null) {
            JSONArray windows = dump.optJSONArray("windows");
            if (windows != null) {
                for (int i = 0; i < windows.length(); i++) {
                    walkText(windows.optJSONObject(i), sb);
                }
            }
        }
        return sb.toString();
    }

    private void walkText(JSONObject node, StringBuilder sb) {
        if (node == null) return;
        String t = node.optString("text", "");
        String d = node.optString("desc", "");
        if (!t.isEmpty()) sb.append(t).append('\n');
        if (!d.isEmpty()) sb.append(d).append('\n');
        JSONArray ch = node.optJSONArray("children");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) walkText(ch.optJSONObject(i), sb);
        }
    }

    /** Chrome's address bar text, used as the "title" for classify(). Falls back
     *  to the empty string when the bar isn't in the tree (fullscreen, dialog). */
    private String currentUrl() {
        JSONObject dump = svc.botDump();
        if (dump == null) return "";
        JSONObject bar = findByResId(dump, CHROME_PKG + ":id/url_bar");
        if (bar == null) bar = findByResId(dump, CHROME_PKG + ":id/location_bar_status");
        return bar == null ? "" : bar.optString("text", "");
    }

    private boolean hasText(String needle) {
        return collectText().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static JSONObject findByDesc(JSONObject dump, String desc) {
        return search(dump, n -> desc.equals(n.optString("desc", null)));
    }

    private static JSONObject findByResId(JSONObject dump, String resId) {
        return search(dump, n -> resId.equals(n.optString("res_id", null)));
    }

    /** Same, by res-id SUFFIX (":id/url_bar"), for ids whose package varies. */
    private static JSONObject findByResIdSuffix(JSONObject dump, String suffix) {
        return search(dump, n -> n.optString("res_id", "").endsWith(suffix));
    }

    private interface NodePred {
        boolean test(JSONObject n);
    }

    private static JSONObject search(JSONObject dump, NodePred pred) {
        if (dump == null) return null;
        JSONArray windows = dump.optJSONArray("windows");
        if (windows == null) return null;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject r = walkFind(windows.optJSONObject(i), pred);
            if (r != null) return r;
        }
        return null;
    }

    private static void collect(JSONObject dump, NodePred pred, java.util.List<JSONObject> out) {
        if (dump == null) return;
        JSONArray windows = dump.optJSONArray("windows");
        if (windows == null) return;
        for (int i = 0; i < windows.length(); i++) {
            walkCollect(windows.optJSONObject(i), pred, out);
        }
    }

    private static void walkCollect(JSONObject node, NodePred pred, java.util.List<JSONObject> out) {
        if (node == null) return;
        if (pred.test(node)) out.add(node);
        JSONArray ch = node.optJSONArray("children");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) walkCollect(ch.optJSONObject(i), pred, out);
        }
    }

    /** Editable fields OF THE PAGE, in document order. Chrome exposes web inputs
     *  without a view-id, while its own widgets (the URL bar above all) carry
     *  one — so the res_id test is what keeps the browser's chrome out. */
    private java.util.List<JSONObject> webFields() {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        collect(svc.botDump(), n -> n.optBoolean("editable") && !n.has("res_id")
                && n.optBoolean("enabled", true) && area(n) > 0, out);
        return out;
    }

    private static boolean filled(JSONObject n) {
        return n != null && !n.optString("text", "").trim().isEmpty();
    }

    private static int[] center(JSONObject n) {
        JSONArray b = n == null ? null : n.optJSONArray("bounds");
        if (b == null || b.length() < 4) return null;
        int l = b.optInt(0), top = b.optInt(1), r = b.optInt(2), bot = b.optInt(3);
        if (r <= l || bot <= top) return null;
        return new int[]{(l + r) / 2, (top + bot) / 2};
    }

    private static long area(JSONObject n) {
        JSONArray b = n == null ? null : n.optJSONArray("bounds");
        if (b == null || b.length() < 4) return 0;
        long w = b.optInt(2) - b.optInt(0), h = b.optInt(3) - b.optInt(1);
        return w > 0 && h > 0 ? w * h : 0;
    }

    private static JSONObject walkFind(JSONObject node, NodePred pred) {
        if (node == null) return null;
        if (pred.test(node)) return node;
        JSONArray ch = node.optJSONArray("children");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) {
                JSONObject r = walkFind(ch.optJSONObject(i), pred);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------------- alert ----------------

    private void alertCita() {
        log("🎉 ¡Hay huecos de cita! Revisa Chrome y reserva a mano AHORA.");
        notify("¡CITA disponible!",
                "Toma de huellas · " + provinceName + " — reserva ahora en Chrome");
        vibrateAlert();
    }

    /** Fire once the moment citas appear (acCitar), before the auto-booking runs.
     *  Unlike {@link #alertCita()} this does NOT stop the bot — it's an early
     *  heads-up so a real cita is never missed if the booking later stalls. */
    private void notifyCitaFound() {
        log("🔔 ¡Citas disponibles! Intentando reservar automáticamente — revisa Chrome por si acaso.");
        notify("¡Citas disponibles!",
                "Toma de huellas · " + provinceName + " — reservando… revisa Chrome");
        vibrateAlert();
    }

    private void notify(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "cita";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        ch, "Cita", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            Notification n = new Notification.Builder(svc, ch)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1, n);
        } catch (Exception e) {
            log("  no pude postear notificación: " + e);
        }
    }

    private void vibrateAlert() {
        try {
            Vibrator v = (Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 400, 200, 400, 200, 400};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- misc ----------------

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static String q(String s) {
        return "'" + s + "'";
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.length() > 60 ? u.substring(0, 60) + "…" : u;
    }
}
