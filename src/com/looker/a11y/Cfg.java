package com.looker.a11y;

import android.content.Context;
import android.content.SharedPreferences;

/** User-editable configuration (set from MainActivity, read by CitaBot). */
final class Cfg {
    static final String PREFS = "citacfg";

    static final String K_PROV_CODE = "province_code";
    static final String K_PROV_NAME = "province_name";
    static final String K_AUTH = "auth";          // "cert" | "clave"
    static final String K_COUNTRY = "country";
    static final String K_CERT_MATCH = "cert_match"; // substring (e.g. surname) of the cert to pick
    static final String K_EMAIL = "email";
    static final String K_PHONE = "phone";
    // Acceptable appointment-date window (YYYY-MM-DD, inclusive; empty = no
    // bound). The bot only books a cita whose day falls within [min, max].
    static final String K_MIN_DATE = "min_date";
    static final String K_MAX_DATE = "max_date";
    // UI-only: whether the "cómo se usa" block on MainActivity is expanded.
    static final String K_HELP_OPEN = "help_open";
    // UI-only: whether the log pane at the bottom of MainActivity is expanded.
    static final String K_LOG_OPEN = "log_open";
    // UI-only: whether the found-citas list under "Estadísticas" is expanded.
    static final String K_STATS_OPEN = "stats_open";

    private Cfg() {
    }

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String provinceCode(Context c) {
        return p(c).getString(K_PROV_CODE, "28");
    }

    static String provinceName(Context c) {
        return p(c).getString(K_PROV_NAME, "Madrid");
    }

    static String auth(Context c) {
        return p(c).getString(K_AUTH, "cert");
    }

    static String country(Context c) {
        return p(c).getString(K_COUNTRY, "");
    }

    static String certMatch(Context c) {
        return p(c).getString(K_CERT_MATCH, "");
    }

    static String email(Context c) {
        return p(c).getString(K_EMAIL, "");
    }

    static String phone(Context c) {
        return p(c).getString(K_PHONE, "");
    }

    static String minDate(Context c) {
        return p(c).getString(K_MIN_DATE, "");
    }

    static String maxDate(Context c) {
        return p(c).getString(K_MAX_DATE, "");
    }

    static boolean helpOpen(Context c) {
        return p(c).getBoolean(K_HELP_OPEN, true);
    }

    static boolean logOpen(Context c) {
        return p(c).getBoolean(K_LOG_OPEN, false);
    }

    static boolean statsOpen(Context c) {
        return p(c).getBoolean(K_STATS_OPEN, false);
    }

    static void set(Context c, String key, String value) {
        p(c).edit().putString(key, value).apply();
    }

    static void setBool(Context c, String key, boolean value) {
        p(c).edit().putBoolean(key, value).apply();
    }
}
