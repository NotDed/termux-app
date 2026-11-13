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
    private final Map<String, TabBarPopup.Row> mRows = new HashMap<>();

    public ForwardsController(TermuxActivity activity, TextView button, Runnable openEditor) {
        mActivity = activity;
        mButton = button;
        mOpenEditor = openEditor;
        mButton.setOnClickListener(v -> showPopup());
        mButton.setOnLongClickListener(v -> {
            mActivity.showToast(mActivity.getString(R.string.msg_forwards_source), false);
            return true;
        });
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
                    if (row != null) row.setDotColor(dotColor(f.state));
                }
                applyCount(active);
            });
        });
    }

    private void applyCount(int active) {
        if (active > 0) {
            mButton.setText("⇄" + active);
            mButton.setTextColor(ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);
            mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.ACCENT_COLOR, 0));
        } else {
            mButton.setText("⇄");
            mButton.setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
            mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
        }
    }

    private static int dotColor(State state) {
        switch (state) {
            case ON: return TabBarPopup.DOT_GREEN;
            case STARTING: return TabBarPopup.DOT_AMBER;
            default: return TabBarPopup.DOT_GREY;
        }
    }

    private void showPopup() {
        List<Forward> forwards = read();
        mRows.clear();
        mPopup = new TabBarPopup(mActivity, mActivity.getString(R.string.title_forwards));

        if (forwards.isEmpty()) {
            boolean exists = new File(PATH).isFile();
            mPopup.addRow(mActivity.getString(exists ? R.string.action_edit_file : R.string.action_create_file, DISPLAY_PATH),
                mActivity.getString(R.string.msg_forwards_empty), false, null, () -> {
                    mPopup.dismiss();
                    createExample();
                    mOpenEditor.run();
                }, null, null);
        } else {
            for (Forward f : forwards) {
                Matcher spec = SPEC.matcher(f.spec);
                String subtitle = f.host + " · localhost:" + f.localPort +
                    (spec.matches() ? " → " + spec.group(2) + ":" + spec.group(3) : "");
                TabBarPopup.Row row = mPopup.addRow(f.name, subtitle, true, null, () -> toggle(f), null, null);
                mRows.put(f.name, row);
            }
            mPopup.addRow(mActivity.getString(R.string.action_edit_file, DISPLAY_PATH), null, false, null, () -> {
                mPopup.dismiss();
                mOpenEditor.run();
            }, null, null);
        }

        mPopup.show(mButton);
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

            mHandler.post(() -> { if (row != null) row.setDotColor(TabBarPopup.DOT_AMBER); });
            String error = start(forward);
            if (error != null) {
                mHandler.post(() -> mActivity.showToast(mActivity.getString(R.string.msg_forward_failed, forward.name) + "\n" + error, true));
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
