package com.looker.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class LookerAccessibilityService extends AccessibilityService {
    private static final String TAG = "LookerA11y";
    private static final String SOCK_NAME = "looker_a11y";
    private volatile boolean running = false;

    // Set while the service is connected so the launcher UI (and the in-process
    // CitaBot orchestrator) can reach the live instance.
    private static volatile LookerAccessibilityService instance;
    private CitaBot bot;
    private final java.util.Random rndService = new java.util.Random();

    static LookerAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        instance = this;
        bot = new CitaBot(this);
        startServer();
        Log.i(TAG, "onServiceConnected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (bot != null) bot.stop();
        instance = null;
        return super.onUnbind(intent);
    }

    // ---- CitaBot orchestrator control (used by MainActivity and socket cmds) ----

    void startCita() {
        if (bot != null) bot.start();
    }

    void stopCita() {
        if (bot != null) bot.stop();
    }

    boolean citaRunning() {
        return bot != null && bot.isRunning();
    }

    // ---- in-process wrappers the CitaBot uses (reuse the proven cmd* logic) ----

    JSONObject botDump() {
        try {
            return cmdDump();
        } catch (JSONException e) {
            return null;
        }
    }

    boolean botClick(String text) {
        try {
            return cmdClick(new JSONObject().put("text", text)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    /** Click by view id (e.g. "android:id/button1"): locale-proof, and it finds
     *  nodes that walking the visible tree misses. */
    boolean botClickId(String resId) {
        try {
            return cmdClick(new JSONObject().put("res_id", resId)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    boolean botSetText(String label, String value) {
        try {
            return cmdSetText(new JSONObject().put("text", label).put("value", value)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    boolean botSetTextAfter(String label, String value) {
        return botSetTextAfter(label, value, 0);
    }

    boolean botSetTextAfter(String label, String value, int skip) {
        try {
            return cmdSetTextAfter(new JSONObject().put("label", label).put("value", value)
                    .put("skip", skip)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    boolean botSelect(String label, String value) {
        try {
            return cmdSelect(new JSONObject().put("label", label).put("value", value)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    boolean botSelectCoord(int x, int y, String value) {
        try {
            return cmdSelectCoord(new JSONObject().put("x", x).put("y", y).put("value", value))
                    .optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    /** Returns the chosen option text, or null if none selected. */
    String botSelectRandom(String label) {
        try {
            JSONObject r = cmdSelectRandom(new JSONObject().put("label", label));
            return r.optBoolean("ok") ? r.optString("chosen", "?") : null;
        } catch (JSONException e) {
            return null;
        }
    }

    String botSelectRandomCoord(int x, int y) {
        try {
            JSONObject r = cmdSelectRandomCoord(new JSONObject().put("x", x).put("y", y));
            return r.optBoolean("ok") ? r.optString("chosen", "?") : null;
        } catch (JSONException e) {
            return null;
        }
    }

    boolean botScroll(String direction) {
        try {
            return cmdScroll(new JSONObject().put("direction", direction)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    boolean botGlobal(String action) {
        try {
            return cmdGlobal(new JSONObject().put("action", action)).optBoolean("ok");
        } catch (JSONException e) {
            return false;
        }
    }

    boolean botTap(int x, int y) {
        return tap(x, y, 50);
    }

    boolean botSwipe(int x1, int y1, int x2, int y2, long durationMs) {
        return swipe(x1, y1, x2, y2, durationMs);
    }

    /** Capture the screen via the accessibility screenshot API (API 30+),
     *  returned as a software ARGB_8888 bitmap. Null on failure. */
    Bitmap captureScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        final Bitmap[] out = new Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult sr) {
                        try {
                            HardwareBuffer hb = sr.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, sr.getColorSpace());
                            if (hw != null) out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hb.close();
                        } catch (Throwable ignored) {
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        latch.countDown();
                    }
                });
        try {
            latch.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    /** Full physical screen size {width, height} for coordinate taps on content
     *  Chrome doesn't expose to a11y (e.g. the Cl@ve method-selection page). */
    int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void startServer() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                LocalServerSocket server = new LocalServerSocket(SOCK_NAME);
                Log.i(TAG, "listening on abstract socket " + SOCK_NAME);
                while (running) {
                    LocalSocket client = server.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (IOException e) {
                Log.e(TAG, "server error", e);
                running = false;
            }
        }).start();
    }

    private void handleClient(LocalSocket client) {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = client.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                String resp;
                try {
                    JSONObject req = new JSONObject(line);
                    resp = dispatch(req).toString();
                } catch (Exception e) {
                    JSONObject err = new JSONObject();
                    try {
                        err.put("ok", false);
                        err.put("error", String.valueOf(e));
                    } catch (JSONException ignored) {
                    }
                    resp = err.toString();
                }
                out.write((resp + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "client error", e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private JSONObject dispatch(JSONObject req) throws JSONException {
        String cmd = req.optString("cmd", "");
        switch (cmd) {
            case "dump":
                return cmdDump();
            case "click":
                return cmdClick(req);
            case "set_text":
                return cmdSetText(req);
            case "set_text_after":
                return cmdSetTextAfter(req);
            case "select":
                return cmdSelect(req);
            case "select_coord":
                return cmdSelectCoord(req);
            case "select_random":
                return cmdSelectRandom(req);
            case "select_random_coord":
                return cmdSelectRandomCoord(req);
            case "whisper_load": {
                boolean ok = Whisper.ensureLoaded(this);
                JSONObject r = new JSONObject();
                r.put("ok", ok);
                r.put("status", Whisper.status());
                return r;
            }
            case "whisper_status": {
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("status", Whisper.status());
                r.put("model_present", Whisper.modelPresent(this));
                return r;
            }
            case "whisper_stt": {
                String p = req.optString("path", "/sdcard/Download/download.mp3");
                String txt = Whisper.readCaptcha(this, p);
                JSONObject r = new JSONObject();
                r.put("ok", txt != null);
                r.put("text", txt);
                r.put("status", Whisper.status());
                return r;
            }
            case "scroll":
                return cmdScroll(req);
            case "global":
                return cmdGlobal(req);
            case "tap":
                return cmdTap(req);
            case "swipe":
                return cmdSwipe(req);
            case "cita_start":
                startCita();
                return okStatus();
            case "cita_stop":
                stopCita();
                return okStatus();
            case "cita_status":
                return okStatus();
            case "cfg_set": {
                String key = req.optString("key", "");
                Cfg.set(this, key, req.optString("value", ""));
                JSONObject r = new JSONObject();
                r.put("ok", true);
                return r;
            }
            case "airplane": {           // debug: toggle airplane via Shizuku
                boolean on = req.optBoolean("on", false);
                boolean ok = Priv.setAirplane(on);
                JSONObject r = new JSONObject();
                r.put("ok", ok);
                r.put("state", Priv.airplaneState(this));
                r.put("shizuku_ready", Priv.shizukuReady());
                return r;
            }
            case "cita_log": {
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("running", citaRunning());
                r.put("log", CitaBot.logText());
                return r;
            }
            case "citas_found": {           // dump the saved citas CSV
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("path", new java.io.File(getExternalFilesDir(null),
                        "citas_encontradas.csv").getAbsolutePath());
                r.put("csv", readCitasCsv());
                return r;
            }
            default:
                JSONObject r = new JSONObject();
                r.put("ok", false);
                r.put("error", "unknown cmd: " + cmd);
                return r;
        }
    }

    private JSONObject okStatus() throws JSONException {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("running", citaRunning());
        return r;
    }

    /** Contents of the citas-found CSV, or "" if none written yet. */
    private String readCitasCsv() {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "citas_encontradas.csv");
            if (!f.exists()) return "";
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Every window root worth searching.
     *
     *  getWindows() can silently drop the focused app window (its getRoot()
     *  comes back null on some devices — e.g. the Settings app here), leaving
     *  only the status bar, so the active-window root is merged in whenever its
     *  window wasn't already covered. EVERY node lookup goes through this — a
     *  finder that walked getWindows() alone would miss exactly the dialog the
     *  bot needs to answer. */
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<>();
        java.util.Set<Integer> seenWindowIds = new java.util.HashSet<>();
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) {
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) {
                    out.add(root);
                    seenWindowIds.add(w.getId());
                }
            }
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !seenWindowIds.contains(active.getWindowId())) {
            out.add(active);
        }
        return out;
    }

    private JSONObject cmdDump() throws JSONException {
        JSONObject r = new JSONObject();
        JSONArray windows = new JSONArray();
        for (AccessibilityNodeInfo root : roots()) windows.put(nodeToJson(root));
        r.put("ok", true);
        r.put("windows", windows);
        return r;
    }

    /** Ask the OWNING APP for nodes matching `text`/`viewId`, instead of walking
     *  the tree we can see.
     *
     *  Walking is not equivalent: on Android 15 the KeyChain "Choose
     *  certificate" dialog hands this service a parent whose child list is
     *  missing the positive button (android:id/button1 — "SELECT"), while DENY
     *  (button2) is right there, so no amount of tree walking finds it and the
     *  bot sat on that dialog until it timed out. These two calls query the app
     *  process directly and do return it. */
    private AccessibilityNodeInfo queryByText(String needle, boolean requireEditable) {
        if (needle == null) return null;
        String lower = needle.toLowerCase();
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(needle);
            if (hits == null) continue;
            for (AccessibilityNodeInfo n : hits) {
                if (n == null) continue;
                if (requireEditable && !n.isEditable()) continue;
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                boolean matches = (t != null && t.toString().toLowerCase().contains(lower))
                        || (d != null && d.toString().toLowerCase().contains(lower));
                if (matches) return n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByViewId(String viewId) {
        if (viewId == null || viewId.isEmpty()) return null;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(viewId);
            if (hits == null) continue;
            for (AccessibilityNodeInfo n : hits) if (n != null) return n;
        }
        return null;
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("class", String.valueOf(node.getClassName()));
        CharSequence text = node.getText();
        if (text != null) o.put("text", text.toString());
        CharSequence desc = node.getContentDescription();
        if (desc != null) o.put("desc", desc.toString());
        String resId = node.getViewIdResourceName();
        if (resId != null) o.put("res_id", resId);
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        o.put("bounds", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
        o.put("clickable", node.isClickable());
        o.put("scrollable", node.isScrollable());
        o.put("editable", node.isEditable());
        o.put("enabled", node.isEnabled());
        o.put("checked", node.isChecked());
        JSONArray children = new JSONArray();
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) children.put(nodeToJson(c));
        }
        o.put("children", children);
        return o;
    }

    private AccessibilityNodeInfo findByText(String needle, boolean requireEditable) {
        if (needle == null) return null;
        String lower = needle.toLowerCase();
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo found = searchNode(root, lower, requireEditable);
            if (found != null) return found;
        }
        // Nothing in the tree we can walk: ask the apps themselves (see
        // queryByText — some nodes are only reachable that way).
        return queryByText(needle, requireEditable);
    }

    private AccessibilityNodeInfo searchNode(AccessibilityNodeInfo node, String lowerNeedle, boolean requireEditable) {
        if (node == null) return null;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        boolean matches = (t != null && t.toString().toLowerCase().contains(lowerNeedle))
                || (d != null && d.toString().toLowerCase().contains(lowerNeedle));
        if (matches && (!requireEditable || node.isEditable())) {
            return node;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo found = searchNode(c, lowerNeedle, requireEditable);
            if (found != null) return found;
        }
        return null;
    }

    private JSONObject cmdClick(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String text = req.optString("text", null);
        String resId = req.optString("res_id", null);
        boolean longClick = req.optBoolean("long", false);
        // res_id wins when given: it is locale-proof (android:id/button1 is the
        // positive button whatever it is labelled) and it goes through the
        // app-side query, which finds nodes the walked tree can miss.
        AccessibilityNodeInfo node = resId != null && !resId.isEmpty()
                ? findByViewId(resId) : findByText(text, false);
        if (node == null) {
            r.put("ok", false);
            r.put("error", "not found: " + (resId != null && !resId.isEmpty() ? resId : text));
            return r;
        }
        AccessibilityNodeInfo target = node;
        while (target != null && !(longClick ? target.isLongClickable() : target.isClickable())) {
            target = target.getParent();
        }
        if (target != null) {
            int action = longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
            boolean ok = target.performAction(action);
            r.put("ok", ok);
            if (!ok) r.put("error", "performAction failed");
            return r;
        }
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        boolean ok = tap(b.centerX(), b.centerY(), longClick ? 600 : 50);
        r.put("ok", ok);
        if (!ok) r.put("error", "no " + (longClick ? "long-" : "") + "clickable ancestor and gesture tap failed");
        return r;
    }

    /** Select an <option> in a native/HTML dropdown purely via accessibility:
     *  find the field LABEL, take the first clickable control that follows it in
     *  document order (the combobox — its own text is the current value or a
     *  prompt, NOT the label), ACTION_CLICK to open the option list, then
     *  ACTION_CLICK the option by text (exact match preferred over substring, so
     *  e.g. RUSIA beats BIELORRUSIA). No coordinate guessing. */
    private JSONObject cmdSelect(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String label = req.optString("label", null);
        String value = req.optString("value", "");
        if (label == null || label.isEmpty()) {
            r.put("ok", false);
            r.put("error", "missing label");
            return r;
        }
        List<AccessibilityNodeInfo> nodes = flattenNodes();
        String ll = label.toLowerCase();
        int li = -1;
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            CharSequence t = n.getText(), d = n.getContentDescription();
            String s = ((t == null ? "" : t) + " " + (d == null ? "" : d)).toLowerCase();
            if (s.contains(ll)) {
                li = i;
                break;
            }
        }
        if (li < 0) {
            r.put("ok", false);
            r.put("error", "label not found: " + label);
            return r;
        }
        AccessibilityNodeInfo control = null;
        for (int i = li; i < nodes.size(); i++) {
            if (nodes.get(i).isClickable()) {
                control = nodes.get(i);
                break;
            }
        }
        if (control == null) {
            r.put("ok", false);
            r.put("error", "no clickable control after label: " + label);
            return r;
        }
        boolean opened = clickNode(control);
        boolean picked = pickOption(value);
        r.put("ok", picked);
        r.put("opened", opened);
        if (!picked) r.put("error", "option not found/clicked: " + value);
        return r;
    }

    /** Open a dropdown by TAPPING a screen coordinate, then pick the option.
     *  For selects Chrome does NOT expose in the a11y tree (e.g. the two
     *  Extranjería/Policía trámite dropdowns on some provinces), tapping their
     *  position still opens the native option list, which IS exposed. */
    private JSONObject cmdSelectCoord(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        int x = req.optInt("x", -1);
        int y = req.optInt("y", -1);
        String value = req.optString("value", "");
        if (x < 0 || y < 0) {
            r.put("ok", false);
            r.put("error", "missing x/y");
            return r;
        }
        tap(x, y, 50);
        boolean picked = pickOption(value);
        r.put("ok", picked);
        if (!picked) r.put("error", "option not found/clicked: " + value);
        return r;
    }

    /** After a dropdown is open, find + click the option by text. An EXACT match
     *  is preferred over ANY substring match ANYWHERE in the list — the option
     *  list is virtualised (only visible items are in the a11y tree), so we must
     *  scroll the WHOLE list looking for an exact match before settling for a
     *  substring. Otherwise "RUSIA" would wrongly match the earlier-listed
     *  "BIELORRUSIA O BELARUS" that happens to be visible first. */
    private boolean pickOption(String value) {
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
        }
        String v = value.toLowerCase().trim();
        // Phase 1: scroll the whole list, click only on an EXACT match.
        boolean sawSubstring = false;
        for (int k = 0; k < 40; k++) {
            AccessibilityNodeInfo ex = findOptionMatch(v, true);
            if (ex != null) return clickNode(ex);
            if (findOptionMatch(v, false) != null) sawSubstring = true;
            AccessibilityNodeInfo sc = findFirstScrollable();
            if (sc == null) break;
            boolean scrolled = sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            sleepQuiet(300);
            if (!scrolled) break;   // bottom reached, no exact match anywhere
        }
        if (!sawSubstring) return false;
        // Phase 2: no exact match exists; take the first SUBSTRING match. Scroll
        // back to the top first so "first" is deterministic.
        AccessibilityNodeInfo top = findFirstScrollable();
        for (int i = 0; i < 40 && top != null; i++) {
            if (!top.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break;
            sleepQuiet(120);
            top = findFirstScrollable();
        }
        for (int k = 0; k < 40; k++) {
            AccessibilityNodeInfo sub = findOptionMatch(v, false);
            if (sub != null) return clickNode(sub);
            AccessibilityNodeInfo sc = findFirstScrollable();
            if (sc == null) break;
            boolean scrolled = sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            sleepQuiet(300);
            if (!scrolled) break;
        }
        return false;
    }

    /** Open the dropdown after `label` and click a RANDOM real option (skipping
     *  placeholder/"Seleccionar…"/"Cualquier oficina" entries). Returns the
     *  chosen text, or null. Used to pick among the offices that have citas. */
    private JSONObject cmdSelectRandom(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String label = req.optString("label", null);
        if (label == null || label.isEmpty()) {
            r.put("ok", false);
            r.put("error", "missing label");
            return r;
        }
        List<AccessibilityNodeInfo> nodes = flattenNodes();
        String ll = label.toLowerCase();
        int li = -1;
        for (int i = 0; i < nodes.size(); i++) {
            CharSequence t = nodes.get(i).getText(), d = nodes.get(i).getContentDescription();
            String s = ((t == null ? "" : t) + " " + (d == null ? "" : d)).toLowerCase();
            if (s.contains(ll)) {
                li = i;
                break;
            }
        }
        if (li < 0) {
            r.put("ok", false);
            r.put("error", "label not found");
            return r;
        }
        AccessibilityNodeInfo control = null;
        for (int i = li; i < nodes.size(); i++) {
            if (nodes.get(i).isClickable()) {
                control = nodes.get(i);
                break;
            }
        }
        if (control == null) {
            r.put("ok", false);
            r.put("error", "no control");
            return r;
        }
        clickNode(control);
        return pickRandomOption();
    }

    /** Coordinate variant: tap (x,y) to open a dropdown Chrome doesn't expose as
     *  a node (e.g. the acCitar office select), then pick a random option. */
    private JSONObject cmdSelectRandomCoord(JSONObject req) throws JSONException {
        int x = req.optInt("x", -1);
        int y = req.optInt("y", -1);
        if (x < 0 || y < 0) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", "missing x/y");
            return r;
        }
        tap(x, y, 50);
        return pickRandomOption();
    }

    /** After a dropdown is open, click a RANDOM real option (skipping
     *  placeholder / "Seleccionar…" / "Cualquier oficina"). */
    private JSONObject pickRandomOption() throws JSONException {
        JSONObject r = new JSONObject();
        sleepQuiet(700);
        List<AccessibilityNodeInfo> opts = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo n : flattenNodes()) {
            if (!String.valueOf(n.getClassName()).contains("CheckedTextView")) continue;
            CharSequence t = n.getText();
            if (t == null) continue;
            String s = t.toString().trim(), sl = s.toLowerCase();
            if (s.isEmpty() || sl.contains("seleccionar") || sl.contains("despliega")
                    || sl.equals("cualquier oficina") || sl.startsWith("elegir")) continue;
            opts.add(n);
        }
        if (opts.isEmpty()) {
            r.put("ok", false);
            r.put("error", "no options");
            return r;
        }
        AccessibilityNodeInfo pick = opts.get(rndService.nextInt(opts.size()));
        String chosen = String.valueOf(pick.getText());
        boolean ok = clickNode(pick);
        r.put("ok", ok);
        r.put("chosen", chosen);
        return r;
    }

    private AccessibilityNodeInfo findOptionMatch(String vLower, boolean exact) {
        if (vLower.isEmpty()) return null;
        for (AccessibilityNodeInfo n : flattenNodes()) {
            CharSequence t = n.getText();
            if (t == null) continue;
            String s = t.toString().trim().toLowerCase();
            if (exact ? s.equals(vLower) : s.contains(vLower)) return n;
        }
        return null;
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** Click a node the accessibility way: ACTION_CLICK on it or its nearest
     *  clickable ancestor; gesture-tap its centre only as a last resort. */
    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        return tap(b.centerX(), b.centerY(), 50);
    }

    private List<AccessibilityNodeInfo> flattenNodes() {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<>();
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null && !ws.isEmpty()) {
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) dfs(root, out);
            }
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) dfs(root, out);
        }
        return out;
    }

    private void dfs(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        out.add(n);
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) dfs(n.getChild(i), out);
    }

    /** Set text into the first EDITABLE field that FOLLOWS a label in document
     *  order — for web forms whose inputs are empty (so can't be matched by
     *  their own text) but sit right after a &lt;label&gt; (e.g. the acVerFormulario
     *  Teléfono / Correo fields). ACTION_SET_TEXT, so no keyboard/scroll issues. */
    private JSONObject cmdSetTextAfter(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String label = req.optString("label", null);
        String value = req.optString("value", "");
        if (label == null || label.isEmpty()) {
            r.put("ok", false);
            r.put("error", "missing label");
            return r;
        }
        List<AccessibilityNodeInfo> nodes = flattenNodes();
        String ll = label.toLowerCase();
        int li = -1;
        for (int i = 0; i < nodes.size(); i++) {
            CharSequence t = nodes.get(i).getText(), d = nodes.get(i).getContentDescription();
            String s = ((t == null ? "" : t) + " " + (d == null ? "" : d)).toLowerCase();
            if (s.contains(ll)) {
                li = i;
                break;
            }
        }
        if (li < 0) {
            r.put("ok", false);
            r.put("error", "label not found: " + label);
            return r;
        }
        // `skip` selects the Nth editable AFTER the label (0 = first). Used to
        // fill a "repeat email" field, which follows the email field but whose
        // own label may not be reliably matchable — the two inputs are simply
        // consecutive, so skip=1 targets the second.
        int skip = req.optInt("skip", 0);
        AccessibilityNodeInfo field = null;
        int seen = 0;
        for (int i = li; i < nodes.size(); i++) {
            if (nodes.get(i).isEditable()) {
                if (seen++ == skip) {
                    field = nodes.get(i);
                    break;
                }
            }
        }
        if (field == null) {
            r.put("ok", false);
            r.put("error", "no editable field after: " + label + " (skip=" + skip + ")");
            return r;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        // Fire blur so the page's JS validation registers the new value (a bare
        // ACTION_SET_TEXT doesn't dispatch the input/change events forms rely on).
        field.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
        r.put("ok", ok);
        return r;
    }

    private JSONObject cmdSetText(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String text = req.optString("text", null);
        String value = req.optString("value", "");
        AccessibilityNodeInfo node = findByText(text, true);
        if (node == null) {
            r.put("ok", false);
            r.put("error", "editable field not found: " + text);
            return r;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        r.put("ok", ok);
        return r;
    }

    private JSONObject cmdScroll(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String text = req.optString("text", null);
        String dir = req.optString("direction", "forward");
        AccessibilityNodeInfo node;
        if (text != null && text.length() > 0) {
            AccessibilityNodeInfo match = findByText(text, false);
            AccessibilityNodeInfo scrollable = match;
            while (scrollable != null && !scrollable.isScrollable()) {
                scrollable = scrollable.getParent();
            }
            node = scrollable;
        } else {
            node = findFirstScrollable();
        }
        if (node == null) {
            r.put("ok", false);
            r.put("error", "no scrollable node found");
            return r;
        }
        int action = "backward".equals(dir)
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        boolean ok = node.performAction(action);
        r.put("ok", ok);
        return r;
    }

    private AccessibilityNodeInfo findFirstScrollable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        return searchScrollable(root);
    }

    private AccessibilityNodeInfo searchScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo r = searchScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private JSONObject cmdTap(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        int x = req.optInt("x", -1);
        int y = req.optInt("y", -1);
        boolean longClick = req.optBoolean("long", false);
        if (x < 0 || y < 0) {
            r.put("ok", false);
            r.put("error", "missing x/y");
            return r;
        }
        boolean ok = tap(x, y, longClick ? 600 : 50);
        r.put("ok", ok);
        return r;
    }

    private JSONObject cmdSwipe(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        int x1 = req.optInt("x1", -1);
        int y1 = req.optInt("y1", -1);
        int x2 = req.optInt("x2", -1);
        int y2 = req.optInt("y2", -1);
        long duration = req.optLong("duration_ms", 300);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            r.put("ok", false);
            r.put("error", "missing x1/y1/x2/y2");
            return r;
        }
        boolean ok = swipe(x1, y1, x2, y2, duration);
        r.put("ok", ok);
        return r;
    }

    private JSONObject cmdGlobal(JSONObject req) throws JSONException {
        JSONObject r = new JSONObject();
        String action = req.optString("action", "");
        int a;
        switch (action) {
            case "back":
                a = GLOBAL_ACTION_BACK;
                break;
            case "home":
                a = GLOBAL_ACTION_HOME;
                break;
            case "recents":
                a = GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                a = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quick_settings":
                a = GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "power_dialog":
                a = GLOBAL_ACTION_POWER_DIALOG;
                break;
            case "screenshot":
                a = GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            default:
                r.put("ok", false);
                r.put("error", "unknown global action: " + action);
                return r;
        }
        boolean ok = performGlobalAction(a);
        r.put("ok", ok);
        return r;
    }

    private boolean tap(int x, int y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        TapCallback callback = new TapCallback();
        dispatchGesture(gesture, callback, null);
        synchronized (callback.lock) {
            try {
                callback.lock.wait(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return callback.result;
    }

    private boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        TapCallback callback = new TapCallback();
        dispatchGesture(gesture, callback, null);
        synchronized (callback.lock) {
            try {
                callback.lock.wait(2000);
            } catch (InterruptedException ignored) {
            }
        }
        return callback.result;
    }

    private static class TapCallback extends AccessibilityService.GestureResultCallback {
        final Object lock = new Object();
        volatile boolean result = false;

        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            result = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            result = false;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }
}
