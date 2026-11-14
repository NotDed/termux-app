package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tab bar button that turns the user's VPN script ({@code ~/connect-vpn-termux.sh on|off}) on or
 * off in the background and shows its state. The password is asked for every time and is only
 * written to the script's stdin, never stored or logged.
 */
public class VpnToggleController {

    private enum State { OFF, CONNECTING, ON }

    private static final String SCRIPT_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/connect-vpn-termux.sh";
    private static final String PID_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.cache/vpn/openconnect.pid";
    private static final int PROXY_PORT = 1080;
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int COMMAND_TIMEOUT_S = 90;
    private static final int CONNECTING_COLOR = 0xFFFFC107;

    private final TermuxActivity mActivity;
    private final TextView mButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mStateExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mCommandExecutor = Executors.newSingleThreadExecutor();

    private State mState = State.OFF;
    private boolean mCommandRunning;
    private boolean mPolling;
    /** Runs once after the next successful "on" command, like opening an SSH host that needs the VPN. */
    private Runnable mOnConnected;
    /** Extra work to run on every poll tick, like refreshing the port forwards. */
    private final java.util.List<Runnable> mTickListeners = new java.util.ArrayList<>();

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshState();
            for (Runnable listener : mTickListeners) listener.run();
            if (mPolling) mHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public VpnToggleController(TermuxActivity activity, TextView button) {
        mActivity = activity;
        mButton = button;
        mButton.setOnClickListener(v -> onClick());
        mButton.setOnLongClickListener(v -> {
            mActivity.showToast(getStatusText(), false);
            return true;
        });
        applyState(State.OFF);
    }

    public void startPolling() {
        if (mPolling) return;
        mPolling = true;
        mHandler.post(mPollRunnable);
    }

    public void stopPolling() {
        mPolling = false;
        mHandler.removeCallbacks(mPollRunnable);
    }

    private void onClick() {
        if (mCommandRunning) {
            mActivity.showToast(mActivity.getString(R.string.msg_vpn_busy), false);
            return;
        }
        if (!new File(SCRIPT_PATH).isFile()) {
            mActivity.showToast(mActivity.getString(R.string.msg_vpn_script_missing), true);
            return;
        }
        if (mState == State.OFF) {
            askPasswordAndConnect();
        } else {
            TabBarPopup popup = new TabBarPopup(mActivity, mActivity.getString(R.string.msg_vpn_off_confirm));
            popup.addMessage(TabBarPopup.Kind.INFO, getStatusText(), null, null);
            popup.addButtons(
                new TabBarPopup.Button(mActivity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, popup::dismiss),
                new TabBarPopup.Button(mActivity.getString(R.string.action_vpn_disconnect), TabBarPopup.Button.DANGER, () -> {
                    popup.dismiss();
                    runScript("off", null);
                }));
            popup.show(mButton);
        }
    }

    public void addTickListener(Runnable listener) {
        mTickListeners.add(listener);
    }

    public boolean isOn() {
        return mState == State.ON;
    }

    /** Connect the VPN (asking for the password) and run {@code then} once it is on. */
    public void connectThen(Runnable then) {
        if (mState == State.ON) {
            then.run();
            return;
        }
        if (mCommandRunning) {
            mActivity.showToast(mActivity.getString(R.string.msg_vpn_busy), false);
            return;
        }
        if (!new File(SCRIPT_PATH).isFile()) {
            mActivity.showToast(mActivity.getString(R.string.msg_vpn_script_missing), true);
            return;
        }
        mOnConnected = then;
        askPasswordAndConnect();
    }

    private void askPasswordAndConnect() {
        TabBarPopup panel = new TabBarPopup(mActivity, mActivity.getString(R.string.title_vpn_password));
        boolean[] connecting = {false};
        EditText[] holder = new EditText[1];
        Runnable connect = () -> {
            String password = holder[0].getText().toString();
            if (password.isEmpty()) return;
            holder[0].setText("");
            connecting[0] = true;
            panel.dismiss();
            runScript("on", password);
        };
        panel.addFooter(mActivity.getString(R.string.msg_vpn_password_desc));
        holder[0] = panel.addInput(mActivity.getString(R.string.msg_vpn_password_hint), null, true, connect);
        panel.addButtons(
            new TabBarPopup.Button(mActivity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss),
            new TabBarPopup.Button(mActivity.getString(R.string.action_vpn_connect), TabBarPopup.Button.PRIMARY, connect));
        panel.setOnDismiss(() -> {
            holder[0].setText("");
            if (!connecting[0]) mOnConnected = null;
        });
        panel.showCentered(true, holder[0]);
    }

    /** Show why the VPN command failed, with the last lines of its output. */
    private void showFailure(String output) {
        TabBarPopup panel = new TabBarPopup(mActivity, mActivity.getString(R.string.msg_vpn_failed));
        panel.addMessage(TabBarPopup.Kind.ERROR, output.isEmpty() ? mActivity.getString(R.string.msg_vpn_failed)
            : lastLine(output), null, null);
        if (output.contains("\n")) panel.addOutput(output);
        panel.addButtons(new TabBarPopup.Button(mActivity.getString(R.string.action_ok), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.showCentered(true, null);
    }

    /** Run the VPN script in the background. The password, if any, is only written to stdin. */
    private void runScript(String action, String password) {
        mCommandRunning = true;
        if ("on".equals(action)) applyState(State.CONNECTING);

        mCommandExecutor.execute(() -> {
            int exitCode;
            Deque<String> lastLines = new ArrayDeque<>();
            try {
                ProcessBuilder builder = BackgroundShell.newProcessBuilder(mActivity,
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", SCRIPT_PATH, action);

                Process process = builder.start();
                try (OutputStream stdin = process.getOutputStream()) {
                    if (password != null)
                        stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
                }

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Strip the "Conectando...." progress dots
                        line = line.replaceAll("^Conectando\\.*", "").trim();
                        if (line.isEmpty()) continue;
                        lastLines.addLast(line);
                        if (lastLines.size() > 3) lastLines.removeFirst();
                    }
                }

                if (process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
                    exitCode = process.exitValue();
                } else {
                    process.destroy();
                    exitCode = -1;
                }
            } catch (Exception e) {
                exitCode = -1;
                lastLines.addLast(e.getMessage() == null ? e.toString() : e.getMessage());
            }

            final int code = exitCode;
            final String output = String.join("\n", lastLines);
            mHandler.post(() -> {
                mCommandRunning = false;
                Runnable onConnected = mOnConnected;
                mOnConnected = null;
                if (code == 0 && "on".equals(action) && onConnected != null) {
                    applyState(State.ON);
                    onConnected.run();
                }
                if (code == 0) {
                    mActivity.showToast(output.isEmpty() ? getStatusText() : lastLine(output), false);
                } else {
                    showFailure(output);
                }
                refreshState();
            });
        });
    }

    private static String lastLine(String text) {
        int i = text.lastIndexOf('\n');
        return i >= 0 ? text.substring(i + 1) : text;
    }

    /** Check the VPN state off the main thread and update the button. */
    private void refreshState() {
        mStateExecutor.execute(() -> {
            State state = detectState();
            mHandler.post(() -> {
                // Keep showing "connecting" while the on command is still running
                if (mCommandRunning && mState == State.CONNECTING && state == State.OFF) return;
                applyState(state);
            });
        });
    }

    private static State detectState() {
        boolean processAlive = false;
        try {
            File pidFile = new File(PID_FILE_PATH);
            if (pidFile.isFile()) {
                String pid = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
                processAlive = pid.matches("\\d+") && new File("/proc/" + pid).exists();
            }
        } catch (Exception ignored) {}

        if (!processAlive) return State.OFF;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PROXY_PORT), 300);
            return State.ON;
        } catch (Exception e) {
            return State.CONNECTING;
        }
    }

    private void applyState(State state) {
        mState = state;
        switch (state) {
            case ON:
                mButton.setText("vpn");
                mButton.setTextColor(ExtraKeysStyle.ON_ACCENT_TEXT_COLOR);
                mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.ACCENT_COLOR, 0));
                break;
            case CONNECTING:
                mButton.setText("vpn");
                mButton.setTextColor(CONNECTING_COLOR);
                mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, CONNECTING_COLOR));
                break;
            default:
                mButton.setText("vpn");
                mButton.setTextColor(ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
                mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
                break;
        }
    }

    private String getStatusText() {
        switch (mState) {
            case ON: return mActivity.getString(R.string.msg_vpn_status_on, PROXY_PORT);
            case CONNECTING: return mActivity.getString(R.string.msg_vpn_status_connecting);
            default: return mActivity.getString(R.string.msg_vpn_status_off);
        }
    }

}
