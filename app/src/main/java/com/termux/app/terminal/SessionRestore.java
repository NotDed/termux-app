package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Remembers the open tabs and offers to reopen them after Termux starts fresh. Main thread only. */
public final class SessionRestore {

    private static final String PREFS_NAME = "session_restore";
    private static final String KEY_TABS = "tabs";
    private static final int MAX_TABS = 8;

    /** Do not save until the launch prompt is resolved, so the fresh default tab can't overwrite the saved ones. */
    private static boolean sReadyToSave;

    static final class Entry {
        String name;
        /** The user named this tab themselves, so it shows the name rather than an emoji. */
        boolean renamed;
        /** The tab's AI history id, so its saved answers come back with it. */
        String ai;
        String cwd;
        String host;
        boolean tmux;
    }

    private SessionRestore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void markReady() {
        sReadyToSave = true;
    }

    public static void save(Context context, List<TermuxSession> sessions) {
        if (!sReadyToSave || sessions == null) return;
        JSONArray array = new JSONArray();
        List<String> keepAi = new ArrayList<>();
        try {
            for (TermuxSession termuxSession : sessions) {
                TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
                if (session == null || !session.isRunning()) continue;
                JSONObject object = new JSONObject();
                if (!TextUtils.isEmpty(session.mSessionName)) object.put("name", session.mSessionName);
                if (SessionMeta.isRenamed(session)) object.put("renamed", true);
                String aiId = AiHistory.existingIdOf(session);
                if (aiId != null) {
                    object.put("ai", aiId);
                    keepAi.add(aiId);
                }
                SessionMeta meta = SessionMeta.get(session);
                if (meta != null) {
                    object.put("host", meta.host);
                    object.put("tmux", meta.tmux);
                } else {
                    String cwd = session.getCwd();
                    if (cwd != null) object.put("cwd", cwd);
                }
                array.put(object);
            }
        } catch (Exception ignored) {}
        prefs(context).edit().putString(KEY_TABS, array.toString()).apply();
        AiHistory.prune(context, keepAi);
    }

    @NonNull
    static List<Entry> load(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_TABS, "[]"));
            for (int i = 0; i < array.length() && entries.size() < MAX_TABS; i++) {
                JSONObject object = array.getJSONObject(i);
                Entry entry = new Entry();
                entry.name = object.optString("name", null);
                entry.renamed = object.optBoolean("renamed", false);
                entry.ai = object.optString("ai", null);
                entry.cwd = object.optString("cwd", null);
                entry.host = object.optString("host", null);
                entry.tmux = object.optBoolean("tmux", false);
                if (entry.host != null && !SshConfigHosts.isSafeHost(entry.host)) entry.host = null;
                entries.add(entry);
            }
        } catch (Exception ignored) {}
        return entries;
    }

    /**
     * Called when Termux starts with no sessions. Asks to restore the saved tabs if there are
     * any worth restoring, otherwise runs {@code startFresh}.
     */
    public static void offerOrStart(TermuxActivity activity, TermuxTerminalSessionClient client, Runnable startFresh) {
        List<Entry> entries = TabBarFeatures.isEnabled(activity, TabBarFeatures.SESSION_RESTORE)
            ? load(activity) : new ArrayList<>();

        boolean anySsh = false;
        for (Entry e : entries) if (e.host != null) anySsh = true;

        if (entries.size() < 2 && !anySsh) {
            startFresh.run();
            markReady();
            return;
        }

        TabBarPopup panel = new TabBarPopup(activity, activity.getString(R.string.title_restore_tabs, entries.size()));
        panel.addFooter(activity.getString(R.string.msg_restore_tabs_desc));
        boolean[] selected = new boolean[entries.size()];
        java.util.Arrays.fill(selected, true);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String label = e.host != null ? "ssh " + e.host + (e.tmux ? " · tmux" : "")
                : (!TextUtils.isEmpty(e.name) ? e.name : "shell");
            String subtitle = e.host != null ? null : (e.cwd != null ? shortPath(e.cwd) : null);
            final int index = i;
            panel.addCheckRow((i + 1) + "  " + label, subtitle, true, checked -> selected[index] = checked);
        }
        panel.addButtons(
            new TabBarPopup.Button(activity.getString(R.string.action_start_fresh), TabBarPopup.Button.TEXT, () -> {
                panel.dismiss();
                startFresh.run();
                markReady();
            }),
            new TabBarPopup.Button(activity.getString(R.string.action_restore), TabBarPopup.Button.PRIMARY, () -> {
                List<Entry> chosen = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) if (selected[i]) chosen.add(entries.get(i));
                panel.dismiss();
                if (chosen.isEmpty()) {
                    startFresh.run();
                    markReady();
                    return;
                }
                restore(client, chosen);
                markReady();
                save(activity, activity.getTermuxService() == null ? null : activity.getTermuxService().getTermuxSessions());
            }));
        panel.showCentered(false, null);
    }

    private static void restore(TermuxTerminalSessionClient client, List<Entry> entries) {
        TerminalSession first = null;
        for (Entry e : entries) {
            TerminalSession session;
            if (e.host != null) {
                session = client.addNewSshSession(e.host, e.tmux);
                if (session != null && e.renamed && !TextUtils.isEmpty(e.name)) {
                    session.mSessionName = e.name;
                    SessionMeta.markRenamed(session, true);
                }
            } else {
                String cwd = e.cwd != null && new File(e.cwd).isDirectory() ? e.cwd : TermuxConstants.TERMUX_HOME_DIR_PATH;
                session = client.addNewSession(false, e.name, null, null, cwd);
                SessionMeta.markRenamed(session, e.renamed && !TextUtils.isEmpty(e.name));
            }
            AiHistory.assign(session, e.ai);
            if (first == null) first = session;
        }
        if (first != null) client.setCurrentSession(first);
    }

    private static String shortPath(String path) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

}
