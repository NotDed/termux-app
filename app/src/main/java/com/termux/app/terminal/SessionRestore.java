package com.termux.app.terminal;

import android.app.AlertDialog;
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
        try {
            for (TermuxSession termuxSession : sessions) {
                TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
                if (session == null || !session.isRunning()) continue;
                JSONObject object = new JSONObject();
                if (!TextUtils.isEmpty(session.mSessionName)) object.put("name", session.mSessionName);
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

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String label = e.host != null ? "ssh " + e.host + (e.tmux ? " (tmux)" : "")
                : (!TextUtils.isEmpty(e.name) ? e.name : "shell") + (e.cwd != null ? "  " + shortPath(e.cwd) : "");
            names.append(i + 1).append(". ").append(label).append('\n');
        }

        new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_restore_tabs, entries.size()))
            .setMessage(names.toString().trim())
            .setCancelable(false)
            .setPositiveButton(R.string.action_restore, (d, w) -> {
                restore(client, entries);
                markReady();
                save(activity, activity.getTermuxService() == null ? null : activity.getTermuxService().getTermuxSessions());
            })
            .setNegativeButton(R.string.action_start_fresh, (d, w) -> {
                startFresh.run();
                markReady();
            })
            .show();
    }

    private static void restore(TermuxTerminalSessionClient client, List<Entry> entries) {
        TerminalSession first = null;
        for (Entry e : entries) {
            TerminalSession session;
            if (e.host != null) {
                session = client.addNewSshSession(e.host, e.tmux);
            } else {
                String cwd = e.cwd != null && new File(e.cwd).isDirectory() ? e.cwd : TermuxConstants.TERMUX_HOME_DIR_PATH;
                session = client.addNewSession(false, e.name, null, null, cwd);
            }
            if (first == null) first = session;
        }
        if (first != null) client.setCurrentSession(first);
    }

    private static String shortPath(String path) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

}
