package com.looker.a11y;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.Shizuku;

/**
 * Privileged / network helpers.
 *
 * Airplane mode can't be toggled by a normal app on modern Android (the public
 * API is blocked and a bare secure-setting write doesn't engage the radio on
 * this device). We go through Shizuku, which runs commands with shell (adb)
 * privileges — `cmd connectivity airplane-mode enable/disable`, the same call
 * that works over adb. State is then read back via the (readable) global
 * setting to confirm.
 */
final class Priv {

    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private Priv() {
    }

    // ---- Shizuku availability ----

    /** Whether the Shizuku app itself is installed (distinct from it being
     *  alive — an uninstalled Shizuku can never be pinged or started). */
    static boolean shizukuInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Shizuku service is running and its binder reached us. */
    static boolean shizukuAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku is alive AND has granted us permission. */
    static boolean shizukuReady() {
        return shizukuAlive() && shizukuPermitted();
    }

    static boolean shizukuPermitted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
        }
    }

    // ---- privileged shell via Shizuku ----

    /** Run `sh -c cmd` with Shizuku's (shell/root) privileges; returns the exit
     *  code, or -1 if Shizuku isn't usable. Shizuku.newProcess is @hide/private,
     *  so it's reached by reflection (the well-known pattern). */
    static int shell(String cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Object procObj = m.invoke(null, new Object[]{
                    new String[]{"sh", "-c", cmd}, null, null});
            Process p = (Process) procObj;
            drain(p.getInputStream());
            drain(p.getErrorStream());
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) { /* discard */ }
        } catch (Throwable ignored) {
        }
    }

    static boolean setAirplane(boolean on) {
        return shell("cmd connectivity airplane-mode " + (on ? "enable" : "disable")) == 0;
    }

    /** Turn Wi-Fi on/off via Shizuku (`svc wifi`). Needed on a WAF block: the
     *  airplane-mode IP rotation only changes the IP on MOBILE DATA, so Wi-Fi
     *  must be off for a fresh IP to take effect. */
    static boolean setWifi(boolean on) {
        return shell("svc wifi " + (on ? "enable" : "disable")) == 0;
    }

    /** Current airplane state: 1 on, 0 off, -1 unknown. Readable without perms. */
    static int airplaneState(Context ctx) {
        try {
            return Settings.Global.getInt(
                    ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- public IP (for rotation logging) ----

    static String publicIp() {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://api.ipify.org");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String ip = r.readLine();
            r.close();
            return ip == null ? null : ip.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
