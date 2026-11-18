package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;

/** On/off switches for the tab bar features, toggled from the tab bar's settings key. */
public final class TabBarFeatures {

    public static final String CLAUDE_USAGE = "claude_usage";
    public static final String VPN = "vpn";
    public static final String FORWARDS = "forwards";
    public static final String SNIPPETS = "snippets";
    public static final String SSH = "ssh";
    public static final String SSH_REACHABILITY = "ssh_reachability";
    public static final String HOST_COLORS = "host_colors";
    public static final String SESSION_RESTORE = "session_restore";

    private static final String PREFS_NAME = "tabbar_features";

    private TabBarFeatures() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context, String key) {
        return prefs(context).getBoolean(key, true);
    }

    /** Show the features panel. {@code onChanged} runs after every change. */
    public static void showDialog(Context context, Runnable onChanged, Runnable editDefaultEmoji) {
        TabBarPopup panel = new TabBarPopup(context, context.getString(R.string.title_tabbar_features));

        panel.addHeader(context.getString(R.string.feature_group_keys));
        add(context, panel, CLAUDE_USAGE, R.string.feature_claude_usage, R.string.feature_claude_usage_desc, onChanged, null);
        add(context, panel, VPN, R.string.feature_vpn, R.string.feature_vpn_desc, onChanged, null);
        add(context, panel, FORWARDS, R.string.feature_forwards, R.string.feature_forwards_desc, onChanged, null);
        add(context, panel, SNIPPETS, R.string.feature_snippets, R.string.feature_snippets_desc, onChanged, null);
        TabBarPopup.Row[] sshOptions = new TabBarPopup.Row[2];
        add(context, panel, SSH, R.string.feature_ssh, R.string.feature_ssh_desc, onChanged, checked -> {
            for (TabBarPopup.Row row : sshOptions) row.setEnabled(checked);
        });

        panel.addHeader(context.getString(R.string.feature_group_ssh));
        sshOptions[0] = add(context, panel, SSH_REACHABILITY, R.string.feature_ssh_reachability, R.string.feature_ssh_reachability_desc, onChanged, null);
        sshOptions[1] = add(context, panel, HOST_COLORS, R.string.feature_host_colors, R.string.feature_host_colors_desc, onChanged, null);
        for (TabBarPopup.Row row : sshOptions) row.setEnabled(isEnabled(context, SSH));

        panel.addHeader(context.getString(R.string.feature_group_sessions));
        add(context, panel, SESSION_RESTORE, R.string.feature_session_restore, R.string.feature_session_restore_desc, onChanged, null);
        String emoji = TabEmojis.getDefault(context);
        panel.addLinkRow(context.getString(R.string.title_default_tab_emoji),
            emoji == null ? context.getString(R.string.feature_default_emoji_none)
                : context.getString(R.string.feature_default_emoji_set, emoji), () -> {
                panel.dismiss();
                if (editDefaultEmoji != null) editDefaultEmoji.run();
            });

        panel.addButtons(new TabBarPopup.Button(context.getString(R.string.action_done), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.showCentered(true, null);
    }

    private static TabBarPopup.Row add(Context context, TabBarPopup panel, String key, int label, int description,
                                       Runnable onChanged, TabBarPopup.OnToggle also) {
        return panel.addSwitchRow(context.getString(label), context.getString(description), false, isEnabled(context, key), checked -> {
            prefs(context).edit().putBoolean(key, checked).apply();
            if (also != null) also.onToggle(checked);
            if (onChanged != null) onChanged.run();
        });
    }

}
