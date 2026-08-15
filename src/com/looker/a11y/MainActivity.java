package com.looker.a11y;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int SHIZUKU_REQ = 4711;

    private static final int OK = Color.parseColor("#2e7d32");
    private static final int WARN = Color.parseColor("#e65100");
    private static final int BAD = Color.parseColor("#c62828");
    private static final int MUTED = Color.parseColor("#5f6368");
    private static final int CARD_BG = Color.parseColor("#f1f3f4");
    private static final int LOG_BG = Color.parseColor("#fafafa");
    private static final int LINE = Color.parseColor("#20000000");

    // Spanish provinces (INE code = ICP `p=` param), INE order.
    private static final String[] PROV_NAMES = {
            "Araba/Álava", "Albacete", "Alicante", "Almería", "Ávila", "Badajoz", "Illes Balears",
            "Barcelona", "Burgos", "Cáceres", "Cádiz", "Castellón", "Ciudad Real", "Córdoba",
            "A Coruña", "Cuenca", "Girona", "Granada", "Guadalajara", "Gipuzkoa", "Huelva", "Huesca",
            "Jaén", "León", "Lleida", "La Rioja", "Lugo", "Madrid", "Málaga", "Murcia", "Navarra",
            "Ourense", "Asturias", "Palencia", "Las Palmas", "Pontevedra", "Salamanca",
            "Santa Cruz de Tenerife", "Cantabria", "Segovia", "Sevilla", "Soria", "Tarragona",
            "Teruel", "Toledo", "Valencia", "Valladolid", "Bizkaia", "Zamora", "Zaragoza",
            "Ceuta", "Melilla"};

    private static final String[] COUNTRIES = {
            "ALEMANIA", "ARGELIA", "ARGENTINA", "ARMENIA", "BANGLADESH", "BIELORRUSIA O BELARUS",
            "BOLIVIA", "BRASIL", "CHILE", "CHINA", "COLOMBIA", "CUBA", "ECUADOR", "ESTADOS UNIDOS",
            "FILIPINAS", "FRANCIA", "GEORGIA", "HONDURAS", "INDIA", "ITALIA", "KAZAJSTAN",
            "MARRUECOS", "MEXICO", "NIGERIA", "PAKISTAN", "PARAGUAY", "PERU", "PORTUGAL",
            "REINO UNIDO", "REPUBLICA DOMINICANA", "RUMANIA", "RUSIA", "SENEGAL", "TURQUIA",
            "UCRANIA", "URUGUAY", "VENEZUELA"};

    private static final String[] AUTH_LABELS = {"Certificate", "Cl@ve permanente"};

    // Setup steps that have their own notice (accessibility, Shizuku, Whisper)
    // are NOT repeated here: the notice appears only while that piece is missing.
    private static final String HELP_TEXT =
            "1) Phone on MOBILE DATA (airplane-mode IP rotation needs it).\n"
            + "2) Cl@ve permanente: sign in ONCE by hand in Chrome and let it SAVE the\n"
            + "   password (Chrome > Passwords + autofill). The bot never stores it:\n"
            + "   it taps the username field and presses the button of Android's\n"
            + "   credential sheet; that sheet fills in and submits the form (the bot\n"
            + "   never touches the page's own button).\n"
            + "3) Press START and leave the phone on. It alerts when a cita appears.\n"
            + "Anything still unconfigured shows up below with its own button.";

    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout logPane;
    private TextView logTitle;
    private TextView logToBottom;
    private boolean logOpen;
    private String lastLog = "";
    private TextView status;
    private LinearLayout checklistScreen; // shown ALONE until a11y+Shizuku+Whisper are ready
    private LinearLayout mainScreen;      // the normal app UI; hidden while checklistScreen shows
    private LinearLayout prepSection; // "Preparación" header inside checklistScreen
    private LinearLayout setupBox;   // the gating checklist: a11y, Shizuku, Whisper
    private View divider;
    private Check a11yCheck;
    private Check shizukuCheck;
    private Check whisperCheck;
    private Button startBtn;
    private Button stopBtn;
    private EditText certEdit;
    private TextView certLabel;      // hidden together with certEdit for Cl@ve
    private EditText emailEdit;
    private EditText phoneEdit;
    private TextView helpBody;
    private TextView helpHeader;
    private boolean helpOpen;
    private TextView statsHeader;
    private LinearLayout statsList;
    private boolean statsOpen;
    private int lastCitasShown = -1;   // rebuild statsList only when the count changes
    // Appointment-date window, stored as YYYY-MM-DD (empty = no bound on that
    // side). The fields only *display* it; the calendar dialog is the only way in.
    private String minIso = "";
    private String maxIso = "";
    private EditText minDateEdit;
    private EditText maxDateEdit;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Shizuku permission result -> refresh + toast. Lambda (not an anonymous
    // class): d8 8.2.2 crashes on anonymous inner classes from modern javac.
    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> toast(grantResult == 0
                    ? "Shizuku permission granted" : "Shizuku permission denied");

    // Setup checklist: each item shows ✓ when done, or ○ + a fix button while
    // pending. refresh() re-evaluates every item once a second.
    private final Runnable refresh = () -> {
        LookerAccessibilityService svc = LookerAccessibilityService.getInstance();
        boolean connected = svc != null;
        boolean running = connected && svc.citaRunning();
        status.setText(running ? "Cita bot: RUNNING"
                : connected ? "Cita bot: stopped" : "Cita bot: stopped — accessibility is off");
        status.setTextColor(running ? OK : (connected ? MUTED : BAD));
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);

        a11yCheck.set(connected, "Turn it on in Settings › Accessibility › otso-cita.");

        boolean shInstalled = Priv.shizukuInstalled(this);
        boolean shAlive = shInstalled && Priv.shizukuAlive();
        boolean shOk = shAlive && Priv.shizukuPermitted();
        shizukuCheck.set(shOk, !shInstalled ? "Not installed."
                        : shAlive ? "Permission not granted yet."
                        : "Not running (start it via wireless debugging or adb).",
                !shInstalled ? "INSTALL" : "GRANT");

        String ws = Whisper.status();
        boolean wOk = Whisper.modelPresent(this) && !ws.contains("failed");
        whisperCheck.set(wOk, "Speech model for the voice captcha (~514 MB, one-time): " + ws + ".");

        // a11y + Shizuku + Whisper are one-time setup: while any is missing,
        // the checklist is the ONLY thing on screen.
        boolean coreDone = connected && shOk && wOk;
        checklistScreen.setVisibility(coreDone ? View.GONE : View.VISIBLE);
        mainScreen.setVisibility(coreDone ? View.VISIBLE : View.GONE);
        divider.setVisibility(coreDone ? View.VISIBLE : View.GONE);
        logPane.setVisibility(coreDone ? View.VISIBLE : View.GONE);

        updateStats();
        updateLog();
        handler.postDelayed(this.refresh, 1000);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Two independently scrollable panes: the form on top (everything is
        // reachable on a phone screen now) and the log pinned at the bottom.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(16));

        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(true);
        formScroll.addView(form);
        root.addView(formScroll, weighted(3f));

        // ---- checklistScreen (a11y, Shizuku, Whisper) and mainScreen are
        // mutually exclusive: refresh() shows exactly one, based on whether
        // that one-time setup is complete. ----
        checklistScreen = new LinearLayout(this);
        checklistScreen.setOrientation(LinearLayout.VERTICAL);
        form.addView(checklistScreen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainScreen = new LinearLayout(this);
        mainScreen.setOrientation(LinearLayout.VERTICAL);
        mainScreen.setVisibility(View.GONE); // corrected by refresh() before first draw
        form.addView(mainScreen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHelp(mainScreen);

        // ---- config selectors ----
        addSection(mainScreen, "Cita");

        addLabel(mainScreen, "Province");
        Spinner provSpin = new Spinner(this);
        provSpin.setAdapter(adapter(PROV_NAMES));
        int provIdx = indexOf(PROV_NAMES, Cfg.provinceName(this));
        provSpin.setSelection(provIdx < 0 ? 27 : provIdx);  // fallback: Madrid
        provSpin.setOnItemSelectedListener(new SaveSel(pos -> {
            Cfg.set(this, Cfg.K_PROV_NAME, PROV_NAMES[pos]);
            Cfg.set(this, Cfg.K_PROV_CODE, String.valueOf(pos + 1)); // INE code = index+1
        }));
        mainScreen.addView(provSpin);

        addLabel(mainScreen, "Sign-in method");
        Spinner authSpin = new Spinner(this);
        authSpin.setAdapter(adapter(AUTH_LABELS));
        authSpin.setSelection(Cfg.auth(this).equals("clave") ? 1 : 0);
        authSpin.setOnItemSelectedListener(new SaveSel(pos -> {
            Cfg.set(this, Cfg.K_AUTH, pos == 1 ? "clave" : "cert");
            showCertField(pos == 0);
        }));
        mainScreen.addView(authSpin);

        addLabel(mainScreen, "Nationality");
        Spinner ctrySpin = new Spinner(this);
        ctrySpin.setAdapter(adapter(COUNTRIES));
        int ctryIdx = indexOf(COUNTRIES, Cfg.country(this));
        ctrySpin.setSelection(ctryIdx < 0 ? 0 : ctryIdx);
        ctrySpin.setOnItemSelectedListener(new SaveSel(pos ->
                Cfg.set(this, Cfg.K_COUNTRY, COUNTRIES[pos])));
        mainScreen.addView(ctrySpin);

        certLabel = addLabel(mainScreen, "Certificate (surname / text to pick it by)");
        certEdit = new EditText(this);
        certEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        certEdit.setSingleLine(true);
        certEdit.setHint("e.g. your surname");
        certEdit.setText(Cfg.certMatch(this));
        mainScreen.addView(certEdit);
        // Only meaningful for the certificate path; Cl@ve permanente signs in with
        // the password the browser has saved (nothing to configure here).
        showCertField(!Cfg.auth(this).equals("clave"));

        CheckBox autoBookBox = new CheckBox(this);
        autoBookBox.setText("Book automatically");
        autoBookBox.setChecked(Cfg.autoBook(this));
        autoBookBox.setOnCheckedChangeListener((btn, checked) ->
                Cfg.setBool(this, Cfg.K_AUTO_BOOK, checked));
        mainScreen.addView(autoBookBox);
        TextView autoBookHint = new TextView(this);
        autoBookHint.setText("Checked: the bot completes the booking by itself (office, slot, "
                + "captcha, confirm). Unchecked: as soon as citas appear it alerts, stops, "
                + "and leaves the page open so you book by hand.");
        autoBookHint.setTextColor(MUTED);
        autoBookHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mainScreen.addView(autoBookHint);

        addSection(mainScreen, "Contact");

        addLabel(mainScreen, "Email");
        emailEdit = new EditText(this);
        emailEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailEdit.setSingleLine(true);
        emailEdit.setHint("you@example.com");
        emailEdit.setText(Cfg.email(this));
        mainScreen.addView(emailEdit);

        addLabel(mainScreen, "Phone");
        phoneEdit = new EditText(this);
        phoneEdit.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneEdit.setSingleLine(true);
        phoneEdit.setHint("600123456");
        phoneEdit.setText(Cfg.phone(this));
        mainScreen.addView(phoneEdit);

        // Appointment-date window: the bot only books a cita whose day is within
        // [desde, hasta] (inclusive). Empty = no bound on that side.
        addSection(mainScreen, "Acceptable dates");
        TextView dateHint = new TextView(this);
        dateHint.setText("Only books citas within this window. Empty = no limit.");
        dateHint.setTextColor(MUTED);
        dateHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mainScreen.addView(dateHint);

        addLabel(mainScreen, "Earliest date");
        minDateEdit = addDateRow(mainScreen, true);
        addLabel(mainScreen, "Latest date");
        maxDateEdit = addDateRow(mainScreen, false);
        setDate(true, Cfg.minDate(this));
        setDate(false, Cfg.maxDate(this));

        // ---- status card ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD_BG);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        status = new TextView(this);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(status);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(20);
        mainScreen.addView(card, cardLp);

        // ---- actions ----
        LinearLayout mainRow = row(mainScreen, dp(12));
        startBtn = new Button(this);
        startBtn.setText("START");
        startBtn.setOnClickListener(v -> withService(svc -> {
            saveFields();
            svc.startCita();
            toast("cita bot started");
        }));
        stopBtn = new Button(this);
        stopBtn.setText("STOP");
        stopBtn.setOnClickListener(v -> withService(svc -> {
            svc.stopCita();
            toast("cita bot stopped");
        }));
        mainRow.addView(startBtn, weightedWide());
        mainRow.addView(stopBtn, weightedWide());

        // ---- stats: uptime / intentos / rotaciones IP / citas encontradas,
        // with the found-citas list tucked behind a tap (same collapsible
        // pattern as "Cómo se usa" / the log pane). ----
        addSection(mainScreen, "Statistics");
        addStats(mainScreen);

        // ---- setup checklist: accesibilidad · Shizuku · Whisper. One-time setup
        // that gates the whole screen — see refresh()'s coreDone check. Each row
        // shows ✓ when done, or ○ + a fix button. ----
        prepSection = addSection(checklistScreen, "Setup");
        setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        checklistScreen.addView(setupBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a11yCheck = addCheck(setupBox, "Accessibility", "ENABLE",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        shizukuCheck = addCheck(setupBox, "Shizuku (IP rotation)", "GRANT",
                v -> {
                    if (!Priv.shizukuInstalled(this)) {
                        openPlayStore(Priv.SHIZUKU_PACKAGE);
                        return;
                    }
                    if (!Priv.shizukuAlive()) {
                        toast("Install the Shizuku app and start it, then retry");
                        return;
                    }
                    Priv.requestPermission(SHIZUKU_REQ);
                });

        whisperCheck = addCheck(setupBox, "Whisper (voice captcha)", "DOWNLOAD",
                v -> {
                    toast("Downloading the Whisper model (~514 MB)…");
                    new Thread(() -> Whisper.ensureLoaded(this)).start();
                });

        // ---- log pane (own scroller, so the form above stays reachable).
        // Hidden by default: only the small "Log" bar shows at the bottom;
        // tapping it expands/collapses the log. ----
        divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        logPane = new LinearLayout(this);
        logPane.setOrientation(LinearLayout.VERTICAL);
        logPane.setBackgroundColor(LOG_BG);
        logPane.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.setOnClickListener(v -> {
            logOpen = !logOpen;
            Cfg.setBool(this, Cfg.K_LOG_OPEN, logOpen);
            applyLogState();
        });
        logTitle = new TextView(this);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(MUTED);
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logTitle.setPadding(0, dp(4), 0, dp(4));
        logHeader.addView(logTitle, weightedWide());
        logToBottom = new TextView(this);
        logToBottom.setText("bottom ↓");
        logToBottom.setTextColor(MUTED);
        logToBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logToBottom.setPadding(dp(8), dp(4), 0, dp(4));
        logToBottom.setOnClickListener(v -> logScroll.fullScroll(View.FOCUS_DOWN));
        logHeader.addView(logToBottom);
        logPane.addView(logHeader);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        logPane.addView(logScroll, weighted(1f));
        root.addView(logPane, weighted(2f));
        logOpen = Cfg.logOpen(this);
        applyLogState();

        setContentView(root);
        // No auto-jump to the accessibility settings any more: the notice above
        // says what is missing and why, and START still takes you there.
    }

    /** One setup-checklist row: ○/✓ title [ACTION], with a muted why/how line
     *  under the title. The row itself always stays visible, even once done —
     *  only the now-pointless action button and why line hide, and the icon
     *  flips to a green ✓. Named (not anonymous) inner class — d8 8.2.2
     *  crashes on anonymous inner classes. */
    private final class Check {
        private final TextView icon;
        private final Button button;
        private final TextView sub;

        Check(TextView icon, Button button, TextView sub) {
            this.icon = icon;
            this.button = button;
            this.sub = sub;
        }

        /** done -> green ✓, action + why line hidden; pending -> orange ○, both shown. */
        void set(boolean done, String why) {
            set(done, why, null);
        }

        /** Same as {@link #set(boolean, String)}, plus relabels the action
         *  button (e.g. "INSTALAR" vs "DAR PERMISO") when actionText != null —
         *  for rows whose fix depends on which of several pending states they're in. */
        void set(boolean done, String why, String actionText) {
            icon.setText(done ? "✓" : "○");
            icon.setTextColor(done ? OK : WARN);
            if (actionText != null) button.setText(actionText);
            button.setVisibility(done ? View.GONE : View.VISIBLE);
            sub.setText(why);
            sub.setVisibility(done ? View.GONE : View.VISIBLE);
        }
    }

    /** Build a checklist row (icon + title + action button + why line) and add it
     *  to {@code target}. refresh() drives its state via {@link Check#set}. */
    private Check addCheck(LinearLayout target, String title, String actionText, View.OnClickListener action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText("○");
        icon.setTextColor(WARN);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        icon.setWidth(dp(32));
        row.addView(icon);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(titleTv, weightedWide());

        Button b = new Button(this);
        b.setText(actionText);
        b.setOnClickListener(action);
        row.addView(b);

        box.addView(row);

        TextView sub = new TextView(this);
        sub.setTextColor(MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(dp(32), 0, 0, 0);
        box.addView(sub);

        target.addView(box);
        return new Check(icon, b, sub);
    }

    // ---- help (collapsible: it is one-time setup text, it does not need to
    // push the actual controls off the first screen forever) ----

    private void addHelp(LinearLayout parent) {
        helpOpen = Cfg.helpOpen(this);
        helpHeader = new TextView(this);
        helpHeader.setTypeface(Typeface.DEFAULT_BOLD);
        helpHeader.setPadding(0, dp(4), 0, dp(4));
        helpHeader.setOnClickListener(v -> {
            helpOpen = !helpOpen;
            Cfg.setBool(this, Cfg.K_HELP_OPEN, helpOpen);
            applyHelpState();
        });
        parent.addView(helpHeader);

        helpBody = new TextView(this);
        helpBody.setText(HELP_TEXT);
        helpBody.setTextColor(MUTED);
        helpBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        helpBody.setTextIsSelectable(true);
        parent.addView(helpBody);
        applyHelpState();
    }

    private void applyHelpState() {
        helpHeader.setText(helpOpen ? "▾  How to use" : "▸  How to use");
        helpBody.setVisibility(helpOpen ? View.VISIBLE : View.GONE);
    }

    // ---- stats: uptime / intentos / rotaciones IP / citas encontradas. Tap
    // the summary line to expand the list of every cita found this session. ----

    private void addStats(LinearLayout parent) {
        statsOpen = Cfg.statsOpen(this);
        statsHeader = new TextView(this);
        statsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        statsHeader.setPadding(0, dp(4), 0, dp(4));
        statsHeader.setOnClickListener(v -> {
            statsOpen = !statsOpen;
            Cfg.setBool(this, Cfg.K_STATS_OPEN, statsOpen);
            applyStatsState();
        });
        parent.addView(statsHeader);

        statsList = new LinearLayout(this);
        statsList.setOrientation(LinearLayout.VERTICAL);
        parent.addView(statsList);
        applyStatsState();
    }

    private void applyStatsState() {
        statsList.setVisibility(statsOpen ? View.VISIBLE : View.GONE);
    }

    /** Refreshed once a second from {@code refresh}: repaints the summary line
     *  and — only when the count changed — rebuilds the found-citas rows. */
    private void updateStats() {
        long ms = CitaBot.totalRunMs();
        long totalMin = ms / 60000;
        String uptime = totalMin < 60 ? totalMin + "m" : (totalMin / 60) + "h " + (totalMin % 60) + "m";
        int attempts = CitaBot.totalAttempts();
        int ipReloads = CitaBot.totalIpReloads();
        java.util.List<CitaBot.FoundCita> citas = CitaBot.foundCitas();

        statsHeader.setText((statsOpen ? "▾  " : "▸  ") + uptime + " running · " + attempts
                + " attempts · " + ipReloads + " IP rotations · " + citas.size() + " citas found");

        if (citas.size() == lastCitasShown) return;
        lastCitasShown = citas.size();
        statsList.removeAllViews();
        if (citas.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No citas found yet.");
            empty.setTextColor(MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            statsList.addView(empty);
            return;
        }
        SimpleDateFormat when = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        // Most recent first.
        for (int i = citas.size() - 1; i >= 0; i--) {
            CitaBot.FoundCita c = citas.get(i);
            TextView row = new TextView(this);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setPadding(0, dp(2), 0, dp(2));
            row.setTextColor(c.inRange ? OK : MUTED);
            row.setText(when.format(new Date(c.whenMs)) + " · " + c.office
                    + (c.dateStr.isEmpty() ? "" : " · " + c.dateStr)
                    + (c.time.isEmpty() ? "" : " " + c.time)
                    + (c.inRange ? "" : " (out of range)"));
            statsList.addView(row);
        }
    }

    // ---- dates ----

    /** Read-only field that opens a calendar dialog; ✕ clears the bound. */
    private EditText addDateRow(LinearLayout parent, boolean isMin) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setFocusable(false);
        e.setFocusableInTouchMode(false);
        e.setKeyListener(null);          // typing is impossible: calendar only
        e.setClickable(true);
        e.setHint("any date — tap to pick");
        e.setOnClickListener(v -> pickDate(isMin));
        r.addView(e, weightedWide());

        Button clear = new Button(this);
        clear.setText("✕");
        clear.setContentDescription("Clear the date");
        clear.setOnClickListener(v -> setDate(isMin, ""));
        r.addView(clear);

        parent.addView(r);
        return e;
    }

    private void pickDate(boolean isMin) {
        String cur = isMin ? minIso : maxIso;
        // Bounds: never before today, and keep desde ≤ hasta.
        long today = startOfToday();
        long lower = today;
        long upper = 0;
        boolean hasUpper = false;
        if (isMin && !maxIso.isEmpty()) {
            upper = Math.max(today, millisOf(maxIso));
            hasUpper = true;
        }
        if (!isMin && !minIso.isEmpty()) {
            lower = Math.max(today, millisOf(minIso));
        }

        // Open on the current value; an empty field opens on the first allowed
        // day, so "hasta" starts where "desde" left off instead of at today.
        long init = cur.isEmpty() ? lower : millisOf(cur);
        if (hasUpper && init > upper) init = upper;
        // A value stored earlier can now sit outside the bounds — widen rather
        // than crash: DatePicker throws if the initial date is out of range.
        lower = Math.min(lower, init);
        if (hasUpper) upper = Math.max(upper, init);

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(init);
        DatePickerDialog d = new DatePickerDialog(this,
                (view, y, m, day) -> setDate(isMin, iso(y, m + 1, day)),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMinDate(lower);
        if (hasUpper) d.getDatePicker().setMaxDate(upper);

        d.setTitle(isMin ? "Earliest date" : "Latest date");
        d.setButton(DialogInterface.BUTTON_NEUTRAL, "No limit",
                (dlg, which) -> setDate(isMin, ""));
        d.show();
    }

    /** Store + display one end of the window (empty string = no bound). */
    private void setDate(boolean isMin, String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty() && parseIso(v) == null) v = "";   // ignore junk from prefs
        if (isMin) {
            minIso = v;
            minDateEdit.setText(pretty(v));
            Cfg.set(this, Cfg.K_MIN_DATE, v);
        } else {
            maxIso = v;
            maxDateEdit.setText(pretty(v));
            Cfg.set(this, Cfg.K_MAX_DATE, v);
        }
        // A newly picked bound can invalidate the other one; drop it rather than
        // silently keeping an empty window (min > max would never match a cita).
        if (!minIso.isEmpty() && !maxIso.isEmpty() && minIso.compareTo(maxIso) > 0) {
            if (isMin) {
                maxIso = "";
                maxDateEdit.setText("");
                Cfg.set(this, Cfg.K_MAX_DATE, "");
            } else {
                minIso = "";
                minDateEdit.setText("");
                Cfg.set(this, Cfg.K_MIN_DATE, "");
            }
            toast("The other date fell outside the range and was cleared");
        }
    }

    /** "2026-08-15" -> "sáb 15 ago 2026" (empty stays empty -> hint shows). */
    private static String pretty(String isoDate) {
        int[] ymd = parseIso(isoDate);
        if (ymd == null) return "";
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(ymd[0], ymd[1] - 1, ymd[2]);
        return new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(new Date(c.getTimeInMillis()));
    }

    private static String iso(int y, int m, int d) {
        return String.format(Locale.US, "%04d-%02d-%02d", y, m, d);
    }

    /** Strict YYYY-MM-DD -> {y, m, d}, or null. */
    private static int[] parseIso(String s) {
        if (s == null || s.length() != 10 || s.charAt(4) != '-' || s.charAt(7) != '-') return null;
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int m = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10));
            if (m < 1 || m > 12 || d < 1 || d > 31) return null;
            return new int[]{y, m, d};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long millisOf(String isoDate) {
        int[] ymd = parseIso(isoDate);
        if (ymd == null) return startOfToday();
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(ymd[0], ymd[1] - 1, ymd[2]);
        return c.getTimeInMillis();
    }

    private static long startOfToday() {
        return dayMillis(Calendar.getInstance());
    }

    /** Midnight of the calendar's day (DatePicker bounds are day-granular). */
    private static long dayMillis(Calendar src) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH));
        return c.getTimeInMillis();
    }

    // ---- log ----

    /** Repaint only when the text changed (setText every second would kill the
     *  selection), and follow the tail only while the user is already at it. */
    private void updateLog() {
        String text = CitaBot.logText();
        if (text.equals(lastLog)) return;
        lastLog = text;
        boolean atBottom = isLogAtBottom();
        logView.setText(text);
        if (atBottom) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Collapsed: just the tappable "Log" bar at the bottom. Expanded: the
     *  scrolling log takes its weighted share of the screen back. */
    private void applyLogState() {
        logTitle.setText(logOpen ? "▾  Log" : "▸  Log");
        logToBottom.setVisibility(logOpen ? View.VISIBLE : View.GONE);
        logScroll.setVisibility(logOpen ? View.VISIBLE : View.GONE);
        logPane.setLayoutParams(logOpen ? weighted(2f) : new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (logOpen) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean isLogAtBottom() {
        View content = logScroll.getChildAt(0);
        if (content == null) return true;
        int slack = content.getHeight() - logScroll.getHeight() - logScroll.getScrollY();
        return slack <= dp(24);
    }

    // ---- small helpers ----

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    /** Equal-share child of a horizontal row. */
    private static LinearLayout.LayoutParams weightedWide() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout row(LinearLayout parent, int topMargin) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        parent.addView(r, lp);
        return r;
    }

    /** @return the section header (rule + title), so a caller can hide it
     *  entirely (e.g. the "Preparación" checklist header once nothing is
     *  pending). */
    private LinearLayout addSection(LinearLayout parent, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.topMargin = dp(20);
        box.addView(line, lineLp);

        TextView t = new TextView(this);
        t.setText(title.toUpperCase(Locale.getDefault()));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(8), 0, dp(2));
        box.addView(t);

        parent.addView(box);
        return box;
    }

    private TextView addLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(10), 0, dp(2));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t);
        return t;
    }

    /** Show/hide the certificate-surname row (the spinner fires this before the
     *  views exist on the first layout pass, hence the null checks). */
    private void showCertField(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        if (certLabel != null) certLabel.setVisibility(vis);
        if (certEdit != null) certEdit.setVisibility(vis);
    }

    /** Persist the free-text fields (dates persist as they are picked). */
    private void saveFields() {
        Cfg.set(this, Cfg.K_CERT_MATCH, certEdit.getText().toString().trim());
        Cfg.set(this, Cfg.K_EMAIL, emailEdit.getText().toString().trim());
        Cfg.set(this, Cfg.K_PHONE, phoneEdit.getText().toString().trim());
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    /** Named (not anonymous — d8-safe) spinner listener that runs a lambda on
     *  selection. onNothingSelected is a no-op. */
    private static final class SaveSel implements AdapterView.OnItemSelectedListener {
        interface Pick {
            void at(int pos);
        }

        private final Pick pick;

        SaveSel(Pick p) {
            this.pick = p;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
            pick.at(pos);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private interface SvcAction {
        void run(LookerAccessibilityService svc);
    }

    private void withService(SvcAction action) {
        LookerAccessibilityService svc = LookerAccessibilityService.getInstance();
        if (svc == null) {
            toast("Enable the otso-cita accessibility service first");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        action.run(svc);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** Open the Play Store app's page (falls back to the web page if the Play
     *  Store app itself isn't present, and to a toast if neither can handle it). */
    private void openPlayStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (ActivityNotFoundException e2) {
                toast("Neither Play Store nor a browser found. Install " + pkg + " manually.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFields();   // don't lose typed text if the app is left without START
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(refresh);
    }
}
