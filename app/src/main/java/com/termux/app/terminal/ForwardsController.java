package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port forwards from {@code ~/.termux/forwards} ({@code name host local:remoteHost:remotePort}),
 * each run as a background {@code ssh -N -L} that can be turned on and off from the tab bar.
 */
public class ForwardsController {

    enum State { OFF, STARTING, ON }

    static final String PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/forwards";
    static final String DISPLAY_PATH = "~/.termux/forwards";
    private static final String CACHE_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.cache/forwards";

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern SPEC = Pattern.compile("^(\\d{1,5}):([A-Za-z0-9._-]+):(\\d{1,5})$");

    /** Arguments are passed positionally ($1..$4) so nothing from the file is parsed by the shell. */
    private static final String START_SCRIPT =
        "nohup ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 " +
        "-o ServerAliveCountMax=3 -L \"$1\" -- \"$2\" >\"$3\" 2>&1 </dev/null & echo $! >\"$4\"";

    private static final String EXAMPLE =
        "# One port forward per line: name host localPort:remoteHost:remotePort\n" +
        "# Runs \"ssh -N -L localPort:remoteHost:remotePort host\" in the background.\n" +
        "# Only key-based login works, as there is no terminal to type a password in.\n" +
        "# Example, remove the \"#\" to use it:\n" +
        "# code ws1 8080:localhost:8080\n";

    static final class Forward {
        final String name;
        final String host;
        final String spec;
        final int localPort;
        State state = State.OFF;

        Forward(String name, String host, String spec, int localPort) {
            this.name = name;
            this.host = host;
            this.spec = spec;
            this.localPort = localPort;
        }

        String pidFile() {
            return CACHE_DIR + "/" + name + ".pid";
        }

        String logFile() {
            return CACHE_DIR + "/" + name + ".log";
        }
    }

    private final TermuxActivity mActivity;
    private final TextView mButton;
    private final Runnable mOpenEditor;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private TabBarPopup mPopup;
    /** Where the popup opens, the tab bar's tools key. Falls back to the (detached) button. */
    private android.view.View mAnchor;
    /** Runs when the active count or failures change, so the tools key and sheet can update. */
    private Runnable mOnStateChanged;
    private int mActiveCount;
    private boolean mHadPendingFailure;
    private final Map<String, TabBarPopup.Row> mRows = new HashMap<>();
    /** Forwards whose last start failed, so the next refresh keeps showing the error. */
    private final java.util.Set<String> mFailed = new java.util.HashSet<>();
    /** Failures that happened while the popup was closed, shown on the row when it opens again. */
    private final java.util.Map<String, String> mPendingFailures = new java.util.HashMap<>();

    public ForwardsController(TermuxActivity activity, TextView button, Runnable openEditor) {
        mActivity = activity;
        mButton = button;
        mOpenEditor = openEditor;
        mButton.setOnClickListener(v -> showPopup());
        applyCount(0);
    }

    @NonNull
    static List<Forward> read() {
        List<Forward> forwards = new ArrayList<>();
        File file = new File(PATH);
        if (!file.isFile()) return forwards;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length != 3) continue;
                Matcher spec = SPEC.matcher(parts[2]);
                if (!NAME.matcher(parts[0]).matches() || !SshConfigHosts.isSafeHost(parts[1]) || !spec.matches()) continue;
                forwards.add(new Forward(parts[0], parts[1], parts[2], Integer.parseInt(spec.group(1))));
            }
        } catch (Exception ignored) {}
        return forwards;
    }

    static boolean createExample() {
        File file = new File(PATH);
        if (file.exists()) return true;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(EXAMPLE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static State detect(Forward forward) {
        if (BackgroundShell.readAlivePid(forward.pidFile()) < 0) return State.OFF;
        return BackgroundShell.isLocalPortOpen(forward.localPort, 200) ? State.ON : State.STARTING;
    }

    /** Re-check all forwards in the background and update the key and an open popup. */
    public void refresh() {
        mExecutor.execute(() -> {
            List<Forward> forwards = read();
            for (Forward f : forwards) f.state = detect(f);
            mHandler.post(() -> {
                int active = 0;
                for (Forward f : forwards) {
                    if (f.state != State.OFF) active++;
                    TabBarPopup.Row row = mRows.get(f.name);
                    if (row != null && !mFailed.contains(f.name)) applyRow(row, f);
                }
                applyCount(active);
            });
        });
    }

    public void setAnchor(android.view.View anchor) {
        mAnchor = anchor;
    }

    public void setOnStateChanged(Runnable onStateChanged) {
        mOnStateChanged = onStateChanged;
    }

    int getActiveCount() {
        return mActiveCount;
    }

    boolean hasPendingFailure() {
        return !mPendingFailures.isEmpty();
    }

    /** Offer to ask Claude why a forward failed, under the list. */
    private void addAskClaude(Forward forward, String message) {
        if (mPopup == null || !ClaudeAiPanels.isEnabled(mActivity)) return;
        TabBarPopup popup = mPopup;
        popup.addMessage(TabBarPopup.Kind.ERROR, message, mActivity.getString(R.string.action_ai_diagnose), () -> {
            popup.dismiss();
            String log = BackgroundShell.tail(forward.logFile(), 40);
            ClaudeAiPanels.diagnose(mActivity, "A background port forward named \"" + forward.name + "\": ssh -N -o BatchMode=yes " +
                "-o ExitOnForwardFailure=yes -L " + forward.spec + " -- " + forward.host +
                " (key-based login only, no terminal for a password)", (log.isEmpty() ? "" : log + "\n") + message);
        });
    }

    /** Open the list of forwards. */
    public void open() {
        showPopup();
    }

    private void applyCount(int active) {
        boolean changed = active != mActiveCount || hasPendingFailure() != mHadPendingFailure;
        mActiveCount = active;
        mHadPendingFailure = hasPendingFailure();
        if (changed && mOnStateChanged != null) mHandler.post(mOnStateChanged);
        if (active > 0) {
            mButton.setText("⇄" + active);
            mButton.setTextColor(ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);
            mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.ACCENT_COLOR, 0));
        } else {
            mButton.setText("⇄");
            mButton.setTextColor(mPendingFailures.isEmpty() ? ExtraKeysStyle.KEY_TEXT_COLOR : TabBarPopup.DOT_RED);
            mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
        }
    }

    private void applyRow(TabBarPopup.Row row, Forward forward) {
        row.setChecked(forward.state != State.OFF);
        row.setDotColor(dotColor(forward.state));
        row.setSubtitle(describe(forward) + (forward.state == State.STARTING ? " · starting…" : ""));
    }

    private static int dotColor(State state) {
        switch (state) {
            case ON: return TabBarPopup.DOT_GREEN;
            case STARTING: return TabBarPopup.DOT_AMBER;
            default: return TabBarPopup.DOT_GREY;
        }
    }

    private static String describe(Forward f) {
        Matcher spec = SPEC.matcher(f.spec);
        return f.host + " · :" + f.localPort + (spec.matches() ? " → " + spec.group(2) + ":" + spec.group(3) : "");
    }

    private void showPopup() {
        List<Forward> forwards = read();
        mRows.clear();
        mFailed.clear();
        mPopup = new TabBarPopup(mActivity, mActivity.getString(R.string.title_forwards));
        TabBarPopup popup = mPopup;
        Runnable edit = () -> {
            popup.dismiss();
            createExample();
            mOpenEditor.run();
        };
        mPopup.addHeaderAction("✎", edit);
        mPopup.setOnDismiss(() -> { if (mPopup == popup) mPopup = null; });

        if (forwards.isEmpty()) {
            boolean exists = new File(PATH).isFile();
            mPopup.addMessage(TabBarPopup.Kind.INFO, mActivity.getString(R.string.msg_forwards_empty),
                mActivity.getString(exists ? R.string.action_edit_file : R.string.action_create_file, DISPLAY_PATH), edit);
        } else {
            for (Forward f : forwards) {
                TabBarPopup.Row row = mPopup.addSwitchRow(f.name, describe(f), true, false, checked -> toggle(f));
                mRows.put(f.name, row);
                String failure = mPendingFailures.get(f.name);
                if (failure != null) {
                    mFailed.add(f.name);
                    row.setDotColor(TabBarPopup.DOT_RED);
                    row.setSubtitle(failure, TabBarPopup.DOT_RED);
                    addAskClaude(f, failure);
                }
            }
            mPendingFailures.clear();
            mPopup.addFooter(mActivity.getString(R.string.msg_forwards_hint));
        }

        mPopup.show(mAnchor != null ? mAnchor : mButton);
        refresh();
    }

    private void toggle(Forward forward) {
        TabBarPopup.Row row = mRows.get(forward.name);
        mExecutor.execute(() -> {
            if (detect(forward) != State.OFF) {
                stop(forward);
                mHandler.post(this::refresh);
                return;
            }

            mHandler.post(() -> {
                mFailed.remove(forward.name);
                mPendingFailures.remove(forward.name);
                if (row != null) {
                    row.setDotColor(TabBarPopup.DOT_AMBER);
                    row.setSubtitle(describe(forward) + " · starting…");
                }
            });
            String error = start(forward);
            if (error != null) {
                mHandler.post(() -> {
                    String message = mActivity.getString(R.string.msg_forward_failed, forward.name) + ": " + error;
                    if (mPopup != null && mPopup.isShowing() && row != null) {
                        mFailed.add(forward.name);
                        row.setChecked(false);
                        row.setDotColor(TabBarPopup.DOT_RED);
                        row.setSubtitle(message, TabBarPopup.DOT_RED);
                        addAskClaude(forward, message);
                        mPopup.relayout();
                    } else {
                        // No toasts: keep it for the next time the popup opens and mark the key
                        mPendingFailures.put(forward.name, message);
                    }
                });
            }
            mHandler.post(this::refresh);
        });
    }

    /** Start the forward. Returns an error message, or {@code null} if it is running. Off the main thread. */
    private String start(Forward forward) {
        new File(CACHE_DIR).mkdirs();
        try {
            Process process = BackgroundShell.newProcessBuilder(mActivity,
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "-c", START_SCRIPT, "forward",
                forward.spec, forward.host, forward.logFile(), forward.pidFile()).start();
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy();
        } catch (Exception e) {
            return e.getMessage();
        }

        // Wait for the port to open or ssh to give up
        for (int i = 0; i < 40; i++) {
            if (BackgroundShell.readAlivePid(forward.pidFile()) < 0) {
                new File(forward.pidFile()).delete();
                String log = BackgroundShell.tail(forward.logFile(), 3);
                return log.isEmpty() ? "ssh exited" : log;
            }
            if (BackgroundShell.isLocalPortOpen(forward.localPort, 200)) return null;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null; // Still connecting, the dot stays amber
    }

    private static void stop(Forward forward) {
        int pid = BackgroundShell.readAlivePid(forward.pidFile());
        if (pid > 0) {
            try {
                Os.kill(pid, OsConstants.SIGTERM);
            } catch (Exception ignored) {}
        }
        new File(forward.pidFile()).delete();
    }

}
