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
    public static final String SESSION_RESTORE = "session_restore";
    /** The Claude API helpers: what happened, ask for a command, ask about failures, name with AI. */
    public static final String AI = "ai";

    private static final String PREFS_NAME = "tabbar_features";

    private TabBarFeatures() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context, String key) {
        return prefs(context).getBoolean(key, true);
    }

    /** Show the features panel. {@code onChanged} runs after every change. */
    public static void showDialog(Context context, Runnable onChanged) {
        TabBarPopup panel = new TabBarPopup(context, context.getString(R.string.title_tabbar_features));

        panel.addHeader(context.getString(R.string.feature_group_on_bar));
        add(context, panel, CLAUDE_USAGE, R.string.feature_claude_usage, R.string.feature_claude_usage_desc, onChanged, null);
        TabBarPopup.Row[] sshOptions = new TabBarPopup.Row[1];
        add(context, panel, SSH, R.string.feature_ssh, R.string.feature_ssh_desc, onChanged, checked -> {
            for (TabBarPopup.Row row : sshOptions) row.setEnabled(checked);
        });

        panel.addHeader(context.getString(R.string.feature_group_in_tools));
        add(context, panel, VPN, R.string.feature_vpn, R.string.feature_vpn_desc, onChanged, null);
        add(context, panel, FORWARDS, R.string.feature_forwards, R.string.feature_forwards_desc, onChanged, null);
        add(context, panel, SNIPPETS, R.string.feature_snippets, R.string.feature_snippets_desc, onChanged, null);

        panel.addHeader(context.getString(R.string.feature_group_ssh));
        sshOptions[0] = add(context, panel, SSH_REACHABILITY, R.string.feature_ssh_reachability, R.string.feature_ssh_reachability_desc, onChanged, null);
        for (TabBarPopup.Row row : sshOptions) row.setEnabled(isEnabled(context, SSH));

        panel.addHeader(context.getString(R.string.feature_group_sessions));
        add(context, panel, SESSION_RESTORE, R.string.feature_session_restore, R.string.feature_session_restore_desc, onChanged, null);

        panel.addHeader(context.getString(R.string.feature_group_ai));
        add(context, panel, AI, R.string.feature_ai, R.string.feature_ai_desc, onChanged, null);
        if (context instanceof com.termux.app.TermuxActivity) {
            com.termux.app.TermuxActivity activity = (com.termux.app.TermuxActivity) context;
            String key = ClaudeApiKeyStore.describe(context);
            panel.addLinkRow(context.getString(R.string.title_ai_key),
                key != null ? key : context.getString(R.string.msg_ai_key_not_set), () -> {
                    panel.dismiss();
                    ClaudeAiPanels.showKeyEditor(activity, null);
                });
            addModelRow(context, panel, ClaudeAssistant.PREF_MAIN_MODEL, R.string.feature_ai_main_model);
            addModelRow(context, panel, ClaudeAssistant.PREF_LIGHT_MODEL, R.string.feature_ai_light_model);
        }

        panel.addButtons(new TabBarPopup.Button(context.getString(R.string.action_done), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.showCentered(true, null);
    }

    /** A row that cycles the model used for one kind of request: Opus, Sonnet, Haiku. */
    private static void addModelRow(Context context, TabBarPopup panel, String which, int label) {
        TabBarPopup.Row[] row = new TabBarPopup.Row[1];
        row[0] = panel.addLinkRow(context.getString(label),
            ClaudeAssistant.modelLabel(ClaudeAssistant.getModel(context, which)), () -> {
                java.util.List<String> models = ClaudeAssistant.MODELS;
                String current = ClaudeAssistant.getModel(context, which);
                String next = models.get((models.indexOf(current) + 1) % models.size());
                ClaudeAssistant.setModel(context, which, next);
                row[0].setSubtitle(ClaudeAssistant.modelLabel(next));
            });
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
