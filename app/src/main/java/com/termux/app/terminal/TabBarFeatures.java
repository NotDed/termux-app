package com.termux.app.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;

/** On/off switches for the tab bar features, toggled from the tab bar's settings key. */
public final class TabBarFeatures {

    public static final String VPN = "vpn";
    public static final String FORWARDS = "forwards";
    public static final String SNIPPETS = "snippets";
    public static final String SSH = "ssh";
    public static final String SSH_REACHABILITY = "ssh_reachability";
    public static final String HOST_COLORS = "host_colors";
    public static final String SESSION_RESTORE = "session_restore";

    private static final String[] KEYS = {VPN, FORWARDS, SNIPPETS, SSH, SSH_REACHABILITY, HOST_COLORS, SESSION_RESTORE};
    private static final int[] LABELS = {R.string.feature_vpn, R.string.feature_forwards, R.string.feature_snippets,
        R.string.feature_ssh, R.string.feature_ssh_reachability, R.string.feature_host_colors, R.string.feature_session_restore};

    private static final String PREFS_NAME = "tabbar_features";

    private TabBarFeatures() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context, String key) {
        return prefs(context).getBoolean(key, true);
    }

    /** Show the features checklist. {@code onChanged} runs after every change. */
    public static void showDialog(Context context, Runnable onChanged) {
        String[] labels = new String[KEYS.length];
        boolean[] checked = new boolean[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            labels[i] = context.getString(LABELS[i]);
            checked[i] = isEnabled(context, KEYS[i]);
        }

        new AlertDialog.Builder(context)
            .setTitle(R.string.title_tabbar_features)
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                prefs(context).edit().putBoolean(KEYS[which], isChecked).apply();
                if (onChanged != null) onChanged.run();
            })
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

}
