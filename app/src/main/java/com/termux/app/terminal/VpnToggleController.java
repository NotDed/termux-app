package com.termux.app.terminal;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.TermuxShellUtils;
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
import java.util.Map;
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

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshState();
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
            new AlertDialog.Builder(mActivity)
                .setMessage(R.string.msg_vpn_off_confirm)
                .setPositiveButton(R.string.action_vpn_disconnect, (d, w) -> runScript("off", null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }
    }

    private void askPasswordAndConnect() {
        EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSingleLine(true);

        FrameLayout container = new FrameLayout(mActivity);
        int padding = Math.round(ExtraKeysStyle.dp(mActivity, 20));
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_vpn_password)
            .setView(container)
            .setPositiveButton(R.string.action_vpn_connect, (d, w) -> {
                String password = input.getText().toString();
                input.setText("");
                if (password.isEmpty()) return;
                runScript("on", password);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> input.setText(""))
            .create();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });

        dialog.show();
        input.requestFocus();
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    /** Run the VPN script in the background. The password, if any, is only written to stdin. */
    private void runScript(String action, String password) {
        mCommandRunning = true;
        if ("on".equals(action)) applyState(State.CONNECTING);

        mCommandExecutor.execute(() -> {
            int exitCode;
            Deque<String> lastLines = new ArrayDeque<>();
            try {
                ProcessBuilder builder = new ProcessBuilder(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", SCRIPT_PATH, action);
                builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
                builder.redirectErrorStream(true);
                Map<String, String> env = builder.environment();
                env.clear();
                for (String entry : TermuxShellUtils.buildEnvironment(mActivity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                    int eq = entry.indexOf('=');
                    if (eq > 0) env.put(entry.substring(0, eq), entry.substring(eq + 1));
                }

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
                if (code == 0) {
                    mActivity.showToast(output.isEmpty() ? getStatusText() : lastLine(output), false);
                } else {
                    mActivity.showToast(mActivity.getString(R.string.msg_vpn_failed) +
                        (output.isEmpty() ? "" : "\n" + output), true);
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
                mButton.setText("vpn…");
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
