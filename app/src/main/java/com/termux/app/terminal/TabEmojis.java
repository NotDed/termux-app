package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/** Emojis shown as the whole tab label: one per SSH host, and a default for local sessions. */
public final class TabEmojis {

    private static final String PREFS_NAME = "tab_emojis";
    private static final String KEY_DEFAULT = "default";
    private static final String KEY_HOST_PREFIX = "host:";
    /** Long enough for flags and joined emoji sequences, short enough to stay a label. */
    private static final int MAX_LENGTH = 16;

    private TabEmojis() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    public static String getHost(Context context, String host) {
        return host == null ? null : prefs(context).getString(KEY_HOST_PREFIX + host, null);
    }

    public static void setHost(Context context, String host, @Nullable String emoji) {
        if (host != null) put(context, KEY_HOST_PREFIX + host, emoji);
    }

    @Nullable
    public static String getDefault(Context context) {
        return prefs(context).getString(KEY_DEFAULT, null);
    }

    public static void setDefault(Context context, @Nullable String emoji) {
        put(context, KEY_DEFAULT, emoji);
    }

    private static void put(Context context, String key, @Nullable String emoji) {
        String value = emoji == null ? "" : emoji.trim();
        if (value.length() > MAX_LENGTH) value = value.substring(0, MAX_LENGTH);
        if (value.isEmpty()) prefs(context).edit().remove(key).apply();
        else prefs(context).edit().putString(key, value).apply();
    }

}
