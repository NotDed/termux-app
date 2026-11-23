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
        /** A local tab that was attached to this tmux session, with its -L/-S socket options. */
        String localTmux;
        final List<String> localTmuxSocket = new ArrayList<>();
        /** A local tab that was running this Claude Code conversation. */
        String claudeSession;
        /** Null for the default ~/.claude. */
        String claudeConfigDir;
        String claudeCwd;
        String claudeName;
    }

    /** Detection reads /proc and may call tmux, so it runs off the main thread, in order. */
    private static final java.util.concurrent.ExecutorService SAVER = java.util.concurrent.Executors.newSingleThreadExecutor();

    private SessionRestore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void markReady() {
        sReadyToSave = true;
    }

    public static void save(Context context, List<TermuxSession> sessions) {
        if (!sReadyToSave || sessions == null) return;
        // Snapshot on the main thread; what runs in each local tab is found in the background
        List<JSONObject> objects = new ArrayList<>();
        List<Integer> shellPids = new ArrayList<>();
        List<String> keepAi = new ArrayList<>();
        try {
            for (TermuxSession termuxSession : new ArrayList<>(sessions)) {
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
                    shellPids.add(-1);
                } else {
                    String cwd = session.getCwd();
                    if (cwd != null) object.put("cwd", cwd);
                    shellPids.add(session.getPid());
                }
                objects.add(object);
            }
        } catch (Exception ignored) {}

        Context app = context.getApplicationContext();
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        TabProcesses.Runner runner = command -> {
            List<String> full = new ArrayList<>(command);
            full.set(0, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + full.get(0));
            return TabProcesses.runSimple(BackgroundShell.newProcessBuilder(app, full.toArray(new String[0])));
        };
        SAVER.execute(() -> {
            JSONArray array = new JSONArray();
            for (int i = 0; i < objects.size(); i++) {
                JSONObject object = objects.get(i);
                int pid = shellPids.get(i);
                if (pid > 0) {
                    try {
                        TabProcesses.Result found = TabProcesses.detect(pid, home, runner);
                        if (found.tmuxSession != null) {
                            JSONObject tmux = new JSONObject();
                            tmux.put("session", found.tmuxSession);
                            tmux.put("socket", new JSONArray(found.tmuxSocketArgs));
                            object.put("localTmux", tmux);
                        } else if (found.claudeSessionId != null) {
                            JSONObject claude = new JSONObject();
                            claude.put("session", found.claudeSessionId);
                            if (found.claudeConfigDir != null) claude.put("configDir", found.claudeConfigDir);
                            if (found.claudeCwd != null) claude.put("cwd", found.claudeCwd);
                            if (found.claudeName != null) claude.put("name", found.claudeName);
                            object.put("claude", claude);
                        }
                    } catch (Exception ignored) {}
                }
                array.put(object);
            }
            prefs(app).edit().putString(KEY_TABS, array.toString()).apply();
        });
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
                JSONObject tmux = object.optJSONObject("localTmux");
                if (tmux != null && TabProcesses.TMUX_NAME.matcher(tmux.optString("session", "")).matches()) {
                    entry.localTmux = tmux.optString("session");
                    JSONArray socket = tmux.optJSONArray("socket");
                    for (int k = 0; socket != null && k + 1 < socket.length(); k += 2) {
                        String option = socket.optString(k), value = socket.optString(k + 1);
                        if ((option.equals("-L") || option.equals("-S")) && !value.isEmpty()) {
                            entry.localTmuxSocket.add(option);
                            entry.localTmuxSocket.add(value);
                        }
                    }
                }
                JSONObject claude = object.optJSONObject("claude");
                if (claude != null && TabProcesses.UUID.matcher(claude.optString("session", "")).matches()) {
                    entry.claudeSession = claude.optString("session");
                    entry.claudeConfigDir = safeConfigDir(claude.optString("configDir", null));
                    entry.claudeCwd = claude.optString("cwd", null);
                    entry.claudeName = claude.optString("name", null);
                }
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
            String label;
            String subtitle = e.host != null ? null : (e.cwd != null ? shortPath(e.cwd) : null);
            if (e.host != null) {
                label = "ssh " + e.host + (e.tmux ? " · tmux" : "");
            } else if (e.localTmux != null) {
                label = "tmux · " + e.localTmux;
            } else if (e.claudeSession != null) {
                String account = e.claudeConfigDir == null ? "" : " (" + new File(e.claudeConfigDir).getName().replaceFirst("^\\.claude-?", "") + ")";
                label = "claude · " + (TextUtils.isEmpty(e.claudeName) ? e.claudeSession.substring(0, 8) : e.claudeName) + account;
                if (e.claudeCwd != null) subtitle = shortPath(e.claudeCwd);
            } else {
                label = !TextUtils.isEmpty(e.name) ? e.name : "shell";
            }
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
                String bin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                if (e.localTmux != null) {
                    // Reattach, or recreate under the same name if tmux was killed with Termux.
                    // Values are passed as arguments, never inside the script.
                    List<String> args = new ArrayList<>();
                    args.add("-c");
                    args.add("\"" + bin + "/tmux\" \"$@\" new-session -A -s \"$0\"; exec \"" + bin + "/login\"");
                    args.add(e.localTmux);
                    args.addAll(e.localTmuxSocket);
                    session = client.addNewSession(false, e.name, bin + "/bash", args.toArray(new String[0]), cwd);
                } else if (e.claudeSession != null) {
                    String claudeCwd = e.claudeCwd != null && new File(e.claudeCwd).isDirectory() ? e.claudeCwd : cwd;
                    session = client.addNewSession(false, e.name, bin + "/bash", new String[]{"-c",
                        "cd \"$0\" 2>/dev/null; if [ -n \"$1\" ]; then export CLAUDE_CONFIG_DIR=\"$1\"; fi; \"" + bin +
                            "/claude\" --resume \"$2\"; exec \"" + bin + "/login\"",
                        claudeCwd, e.claudeConfigDir == null ? "" : e.claudeConfigDir, e.claudeSession}, claudeCwd);
                } else {
                    session = client.addNewSession(false, e.name, null, null, cwd);
                }
                SessionMeta.markRenamed(session, e.renamed && !TextUtils.isEmpty(e.name));
            }
            AiHistory.assign(session, e.ai);
            if (first == null) first = session;
        }
        if (first != null) client.setCurrentSession(first);
    }

    /** A Claude config folder must be a .claude* folder in HOME, else the default is used. */
    private static String safeConfigDir(String dir) {
        if (TextUtils.isEmpty(dir)) return null;
        File file = new File(dir);
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (!file.isDirectory() || !home.equals(file.getParent()) || !file.getName().startsWith(".claude")) return null;
        return file.getPath();
    }

    private static String shortPath(String path) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

}
