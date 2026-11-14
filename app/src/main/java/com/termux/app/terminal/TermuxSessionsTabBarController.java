package com.termux.app.terminal;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Taskbar-like row of session tabs shown above the extra keys toolbar, so that sessions can be
 * switched without swiping in the left drawer from the screen edge (which conflicts with gesture
 * navigation). Also hosts the vpn, port forwards, snippets, ssh, new session and settings keys.
 */
public class TermuxSessionsTabBarController {

    /** How long to wait for a killed session to actually exit before removing it. */
    private static final int CLOSE_POLL_INTERVAL_MS = 50;
    private static final int CLOSE_POLL_MAX_TRIES = 60;
    private static final int SAVE_DEBOUNCE_MS = 1000;

    /** Colors for SSH tabs, picked by host name so a host always gets the same one. */
    private static final int[] HOST_COLORS = {
        0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF26C6DA, 0xFF42A5F5, 0xFFAB47BC, 0xFFEC407A};

    private final TermuxActivity mActivity;
    private final List<TermuxSession> mSessions;
    private final View mBarRoot;
    private final LinearLayout mTabsContainer;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mProbeExecutor = Executors.newFixedThreadPool(4);

    private final VpnToggleController mVpnToggleController;
    private final ForwardsController mForwardsController;
    private final ClaudeUsageController mClaudeUsageController;
    private final TextView mClaudeUsageButton;
    private final TextView mVpnButton;
    private final TextView mForwardsButton;
    private final View mSnippetsButton;
    private final View mSshButton;

    private final Runnable mSaveRunnable = this::saveSessions;

    public TermuxSessionsTabBarController(TermuxActivity activity, List<TermuxSession> sessions, View barRoot) {
        mActivity = activity;
        mSessions = sessions;
        mBarRoot = barRoot;
        mTabsContainer = barRoot.findViewById(R.id.terminal_sessions_tabs);

        if (barRoot instanceof TermuxSessionsTabBarView) {
            ((TermuxSessionsTabBarView) barRoot).setSwipeListener(forward -> {
                TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
                if (client != null) client.switchToSession(forward);
            });
        }

        barRoot.setBackgroundColor(ExtraKeysStyle.PANEL_COLOR);

        mVpnButton = barRoot.findViewById(R.id.vpn_tab_button);
        mVpnToggleController = new VpnToggleController(mActivity, mVpnButton);

        mClaudeUsageButton = barRoot.findViewById(R.id.claude_usage_tab_button);
        mClaudeUsageController = new ClaudeUsageController(mActivity, mClaudeUsageButton);
        mVpnToggleController.addTickListener(() -> {
            if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.CLAUDE_USAGE)) mClaudeUsageController.onTick();
        });

        mForwardsButton = barRoot.findViewById(R.id.forwards_tab_button);
        mForwardsController = new ForwardsController(mActivity, mForwardsButton,
            () -> openEditor("forwards", ForwardsController.PATH));
        mVpnToggleController.addTickListener(() -> {
            if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.FORWARDS)) mForwardsController.refresh();
        });

        mSnippetsButton = barRoot.findViewById(R.id.snippets_tab_button);
        styleKey(mSnippetsButton);
        mSnippetsButton.setOnClickListener(this::showSnippetsPopup);
        mSnippetsButton.setOnLongClickListener(v -> {
            mActivity.showToast(mActivity.getString(R.string.msg_snippets_source), false);
            return true;
        });

        mSshButton = barRoot.findViewById(R.id.ssh_hosts_tab_button);
        styleKey(mSshButton);
        mSshButton.setOnClickListener(this::showSshHostsPopup);
        mSshButton.setOnLongClickListener(v -> {
            mActivity.showToast(mActivity.getString(R.string.msg_ssh_hosts_source), false);
            return true;
        });

        View newSessionButton = barRoot.findViewById(R.id.new_session_tab_button);
        styleKey(newSessionButton);
        newSessionButton.setOnClickListener(v -> {
            TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
            if (client != null) client.addNewSession(false, null);
        });
        newSessionButton.setOnLongClickListener(v -> {
            TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
            if (client != null) client.addNewSession(true, "Failsafe");
            return true;
        });

        View settingsButton = barRoot.findViewById(R.id.settings_tab_button);
        styleKey(settingsButton);
        settingsButton.setOnClickListener(v -> TabBarFeatures.showDialog(mActivity, this::applyFeatures));

        applyFeatures();
    }

    private void styleKey(View key) {
        key.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
    }

    /** Show or hide keys according to the enabled features and redraw the tabs. */
    public void applyFeatures() {
        mClaudeUsageButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.CLAUDE_USAGE) ? View.VISIBLE : View.GONE);
        mVpnButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.VPN) ? View.VISIBLE : View.GONE);
        mForwardsButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.FORWARDS) ? View.VISIBLE : View.GONE);
        mSnippetsButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.SNIPPETS) ? View.VISIBLE : View.GONE);
        mSshButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.SSH) ? View.VISIBLE : View.GONE);
        update();
    }

    /** Start refreshing the VPN and forwards state while the activity is visible. */
    public void onStart() {
        mVpnToggleController.startPolling();
    }

    public void onStop() {
        mVpnToggleController.stopPolling();
        mHandler.removeCallbacks(mSaveRunnable);
        saveSessions();
    }

    private void saveSessions() {
        SessionRestore.save(mActivity, mSessions);
    }

    /** Rebuild the tabs from the current sessions list. Must be called on the main thread. */
    public void update() {
        int count = mSessions.size();
        mBarRoot.setVisibility(View.VISIBLE);
        mTabsContainer.removeAllViews();

        boolean hostColors = TabBarFeatures.isEnabled(mActivity, TabBarFeatures.HOST_COLORS);
        TerminalSession current = mActivity.getCurrentSession();
        float density = mActivity.getResources().getDisplayMetrics().density;
        int padding = Math.round(4 * density);

        for (int i = 0; i < count; i++) {
            TermuxSession termuxSession = mSessions.get(i);
            TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
            if (session == null) continue;

            TextView tab = new TextView(mActivity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            tab.setLayoutParams(params);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(padding, 0, padding, 0);
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setText(getTabTitle(i, session));

            boolean isCurrent = session == current;
            SessionMeta meta = SessionMeta.get(session);
            int hostColor = hostColors && meta != null ? getHostColor(meta.host) : 0;
            tab.setBackground(getTabBackground(isCurrent, hostColor));
            tab.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);

            boolean running = session.isRunning();
            if (running)
                tab.setPaintFlags(tab.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            else
                tab.setPaintFlags(tab.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

            int textColor = isCurrent ? Color.WHITE : ExtraKeysStyle.KEY_HINT_TEXT_COLOR;
            if (!running && session.getExitStatus() != 0) textColor = Color.RED;
            tab.setTextColor(textColor);

            tab.setOnClickListener(v -> {
                TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
                if (client != null) client.setCurrentSession(session);
            });
            tab.setOnLongClickListener(v -> {
                showTabMenu(v, session);
                return true;
            });

            mTabsContainer.addView(tab);
        }

        mHandler.removeCallbacks(mSaveRunnable);
        mHandler.postDelayed(mSaveRunnable, SAVE_DEBOUNCE_MS);
    }

    private static int getHostColor(String host) {
        return HOST_COLORS[Math.abs(host.hashCode() % HOST_COLORS.length)];
    }

    /** A keycap, plus a colored bar along the bottom for SSH tabs when host colors are on. */
    private Drawable getTabBackground(boolean isCurrent, int hostColor) {
        int fill = isCurrent ? ExtraKeysStyle.KEY_PRESSED_COLOR : ExtraKeysStyle.KEY_COLOR;
        int stroke = isCurrent ? (hostColor != 0 ? hostColor : ExtraKeysStyle.ACCENT_COLOR) : 0;
        Drawable key = ExtraKeysStyle.keyBackground(mActivity, fill, stroke);
        if (hostColor == 0 || Build.VERSION.SDK_INT < 23) return key;

        GradientDrawable bar = new GradientDrawable();
        bar.setColor(hostColor);
        bar.setCornerRadius(ExtraKeysStyle.dp(mActivity, 1.5f));

        LayerDrawable layers = new LayerDrawable(new Drawable[]{key, bar});
        int side = ExtraKeysStyle.insetHorizontalPx(mActivity) + Math.round(ExtraKeysStyle.dp(mActivity, 6));
        int bottom = ExtraKeysStyle.insetVerticalPx(mActivity) + Math.round(ExtraKeysStyle.dp(mActivity, 2));
        layers.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        layers.setLayerHeight(1, Math.round(ExtraKeysStyle.dp(mActivity, 3)));
        layers.setLayerInset(1, side, 0, side, bottom);
        return layers;
    }

    private static String getTabTitle(int index, TerminalSession session) {
        String name = session.mSessionName;
        String title = session.getTitle();
        String label = !TextUtils.isEmpty(name) ? name : (!TextUtils.isEmpty(title) ? title : "");
        return (index + 1) + (label.isEmpty() ? "" : " " + label);
    }

    private void openEditor(String name, String path) {
        TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
        if (client != null) client.addNewEditorSession(name, path);
    }

    /** Show the hosts from ~/.ssh/config with reachability dots and a tmux shortcut. */
    private void showSshHostsPopup(View anchor) {
        List<SshConfigHosts.HostEntry> hosts = SshConfigHosts.read();
        TabBarPopup popup = new TabBarPopup(mActivity, mActivity.getString(R.string.title_ssh_hosts));
        popup.addHeaderAction("✎", () -> {
            popup.dismiss();
            openEditor("ssh config", TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh/config");
        });

        if (hosts.isEmpty()) {
            popup.addMessage(TabBarPopup.Kind.INFO, mActivity.getString(R.string.msg_no_ssh_hosts), null, null);
            popup.show(anchor);
            return;
        }

        boolean probe = TabBarFeatures.isEnabled(mActivity, TabBarFeatures.SSH_REACHABILITY);
        for (SshConfigHosts.HostEntry host : hosts) {
            TabBarPopup.Row row = popup.addRow(host.alias, host.describe(), probe, "tmux",
                () -> openSsh(popup, host, false), null,
                () -> openSsh(popup, host, true));
            if (probe) {
                mProbeExecutor.execute(() -> {
                    SshConfigHosts.Reachability reachability = SshConfigHosts.probe(host);
                    mHandler.post(() -> {
                        row.setDotColor(getReachabilityColor(reachability));
                        if (reachability == SshConfigHosts.Reachability.NEEDS_VPN)
                            row.setSubtitle(host.describe() + " · " + mActivity.getString(R.string.msg_ssh_needs_vpn_short), TabBarPopup.DOT_AMBER);
                    });
                });
            }
        }
        popup.addFooter(mActivity.getString(R.string.msg_ssh_hint));
        popup.show(anchor);
    }

    private static int getReachabilityColor(SshConfigHosts.Reachability reachability) {
        switch (reachability) {
            case REACHABLE: return TabBarPopup.DOT_GREEN;
            case UNREACHABLE: return TabBarPopup.DOT_RED;
            case NEEDS_VPN: return TabBarPopup.DOT_AMBER;
            default: return TabBarPopup.DOT_GREY;
        }
    }

    /**
     * Open an SSH host. If it goes through the VPN and that is off, the popup asks first whether
     * to connect it.
     */
    private void openSsh(TabBarPopup popup, SshConfigHosts.HostEntry host, boolean tmux) {
        TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
        if (client == null) return;

        Runnable open = () -> client.addNewSshSession(host.alias, tmux);
        if (host.getLocalSocksPort() <= 0 || mVpnToggleController.isOn()) {
            popup.dismiss();
            open.run();
            return;
        }

        popup.clearRows();
        popup.addMessage(TabBarPopup.Kind.WARN, mActivity.getString(R.string.msg_ssh_needs_vpn, host.alias), null, null);
        popup.addButtons(
            new TabBarPopup.Button(mActivity.getString(R.string.action_open_anyway), TabBarPopup.Button.TEXT, () -> {
                popup.dismiss();
                open.run();
            }),
            new TabBarPopup.Button(mActivity.getString(R.string.action_connect_vpn), TabBarPopup.Button.PRIMARY, () -> {
                popup.dismiss();
                mVpnToggleController.connectThen(open);
            }));
        popup.relayout();
    }

    /** Show the saved commands from ~/.termux/snippets. Tapping types a command, ▶ runs it. */
    private void showSnippetsPopup(View anchor) {
        List<Snippets.Snippet> snippets = Snippets.read();
        TabBarPopup popup = new TabBarPopup(mActivity, mActivity.getString(R.string.title_snippets));
        Runnable edit = () -> {
            popup.dismiss();
            Snippets.createExample();
            openEditor("snippets", Snippets.PATH);
        };
        popup.addHeaderAction("✎", edit);

        if (snippets.isEmpty()) {
            popup.addMessage(TabBarPopup.Kind.INFO, mActivity.getString(R.string.msg_snippets_empty),
                mActivity.getString(Snippets.exists() ? R.string.action_edit_file : R.string.action_create_file, Snippets.DISPLAY_PATH), edit);
        } else {
            for (Snippets.Snippet snippet : snippets) {
                popup.addRow(snippet.label, snippet.label.equals(snippet.command) ? null : snippet.command, false,
                    mActivity.getString(R.string.action_run),
                    () -> { popup.dismiss(); typeIntoCurrentSession(snippet.command, false); }, null,
                    () -> { popup.dismiss(); typeIntoCurrentSession(snippet.command, true); });
            }
            popup.addFooter(mActivity.getString(R.string.msg_snippets_hint));
        }
        popup.show(anchor);
    }

    private void typeIntoCurrentSession(String command, boolean run) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning()) return;
        session.write(run ? command + "\r" : command);
    }

    /** The long-press menu of a tab: rename, reconnect (SSH tabs) and close. */
    private void showTabMenu(View anchor, TerminalSession session) {
        int index = indexOf(session);
        TabBarPopup popup = new TabBarPopup(mActivity, getTabTitle(index, session));
        popup.addLinkRow(mActivity.getString(R.string.action_rename_session), null, () -> {
            popup.dismiss();
            showRename(session);
        });
        SessionMeta meta = SessionMeta.get(session);
        if (meta != null) {
            popup.addLinkRow(mActivity.getString(R.string.action_reconnect), mActivity.getString(R.string.msg_reconnect_desc), () -> {
                popup.dismiss();
                TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
                if (client == null) return;
                client.addNewSshSession(meta.host, meta.tmux);
                closeSession(session);
            });
        }
        popup.addLinkRow(mActivity.getString(R.string.action_close_session), null, () -> {
            if (!session.isRunning()) {
                popup.dismiss();
                closeSession(session);
                return;
            }
            popup.clearRows();
            popup.addMessage(TabBarPopup.Kind.WARN, mActivity.getString(R.string.msg_close_session_confirm), null, null);
            popup.addButtons(
                new TabBarPopup.Button(mActivity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, popup::dismiss),
                new TabBarPopup.Button(mActivity.getString(R.string.action_close_session), TabBarPopup.Button.DANGER, () -> {
                    popup.dismiss();
                    closeSession(session);
                }));
            popup.relayout();
        });
        popup.show(anchor);
    }

    private int indexOf(TerminalSession session) {
        for (int i = 0; i < mSessions.size(); i++) {
            TermuxSession termuxSession = mSessions.get(i);
            if (termuxSession != null && termuxSession.getTerminalSession() == session) return i;
        }
        return 0;
    }

    private void showRename(TerminalSession session) {
        TabBarPopup panel = new TabBarPopup(mActivity, mActivity.getString(R.string.title_rename_session));
        EditText[] holder = new EditText[1];
        Runnable apply = () -> {
            session.mSessionName = holder[0].getText().toString().trim();
            panel.dismiss();
            TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
            if (client != null) client.termuxSessionListNotifyUpdated();
        };
        holder[0] = panel.addInput(mActivity.getString(R.string.msg_rename_session_hint), session.mSessionName, false, apply);
        panel.addButtons(
            new TabBarPopup.Button(mActivity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss),
            new TabBarPopup.Button(mActivity.getString(R.string.action_rename_session_confirm), TabBarPopup.Button.PRIMARY, apply));
        panel.showCentered(true, holder[0]);
    }

    private void closeSession(TerminalSession session) {
        session.finishIfRunning();
        removeWhenFinished(session, 0);
    }

    /** The session is only marked finished once its process exit is processed, so poll briefly. */
    private void removeWhenFinished(TerminalSession session, int tries) {
        TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
        if (client == null) return;
        if (!session.isRunning()) {
            client.removeFinishedSession(session);
        } else if (tries < CLOSE_POLL_MAX_TRIES) {
            mHandler.postDelayed(() -> removeWhenFinished(session, tries + 1), CLOSE_POLL_INTERVAL_MS);
        }
    }

}
