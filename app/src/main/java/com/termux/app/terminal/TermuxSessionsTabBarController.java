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
import android.widget.HorizontalScrollView;
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
 * navigation). Laid out as a dock: fixed-size tabs that scroll on the left with the new session key
 * after them, and on the right only the pinned Claude usage and ssh keys plus a tools key that
 * opens the VPN, port forwards, snippets and settings.
 */
public class TermuxSessionsTabBarController {

    /** How long to wait for a killed session to actually exit before removing it. */
    private static final int CLOSE_POLL_INTERVAL_MS = 50;
    private static final int CLOSE_POLL_MAX_TRIES = 60;
    private static final int SAVE_DEBOUNCE_MS = 1000;
    /** Every tab has this width from the start, instead of stretching to fill the bar. */
    private static final int TAB_WIDTH_DP = 60;

    /** Quick picks in the emoji picker, 3 rows of 8. */
    private static final List<String> QUICK_EMOJIS = java.util.Arrays.asList(
        "🐚", "🐺", "🦊", "🐙", "🐧", "🦉", "🐝", "🐢",
        "🌿", "🌊", "🔥", "⚡", "🌙", "⭐", "💎", "🍀",
        "🖥", "💻", "🛰", "🧪", "🔧", "📦", "🚀", "🎯");

    private final TermuxActivity mActivity;
    private final List<TermuxSession> mSessions;
    private final View mBarRoot;
    private final LinearLayout mTabsContainer;
    private final HorizontalScrollView mTabsScroll;
    private final TextView mToolsButton;
    private TabBarPopup mToolsSheet;
    /** Commands of Claude accounts that don't ring the terminal bell yet, so their prompts can't set red dots. */
    private List<String> mClaudeWithoutBell = new java.util.ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mProbeExecutor = Executors.newFixedThreadPool(4);

    private final VpnToggleController mVpnToggleController;
    private final ForwardsController mForwardsController;
    private final ClaudeUsageController mClaudeUsageController;
    private final TextView mClaudeUsageButton;
    private final View mSshButton;

    private final Runnable mSaveRunnable = this::saveSessions;

    public TermuxSessionsTabBarController(TermuxActivity activity, List<TermuxSession> sessions, View barRoot) {
        mActivity = activity;
        mSessions = sessions;
        mBarRoot = barRoot;
        mTabsContainer = barRoot.findViewById(R.id.terminal_sessions_tabs);
        mTabsScroll = barRoot.findViewById(R.id.terminal_sessions_tabs_scroll);
        mToolsButton = barRoot.findViewById(R.id.tools_tab_button);

        if (barRoot instanceof TermuxSessionsTabBarView) {
            ((TermuxSessionsTabBarView) barRoot).setSwipeListener(forward -> {
                TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
                if (client != null) client.switchToSession(forward);
            });
        }

        barRoot.setBackgroundColor(ExtraKeysStyle.PANEL_COLOR);

        // VPN and forwards live in the tools sheet: their controllers get a detached view to
        // style, and open their popups from the tools key
        mVpnToggleController = new VpnToggleController(mActivity, new TextView(mActivity));
        mVpnToggleController.setAnchor(mToolsButton);
        mVpnToggleController.setOnStateChanged(this::onToolsStateChanged);

        mClaudeUsageButton = barRoot.findViewById(R.id.claude_usage_tab_button);
        mClaudeUsageController = new ClaudeUsageController(mActivity, mClaudeUsageButton);
        mVpnToggleController.addTickListener(() -> {
            if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.CLAUDE_USAGE)) mClaudeUsageController.onTick();
        });

        mForwardsController = new ForwardsController(mActivity, new TextView(mActivity),
            () -> openEditor("forwards", ForwardsController.PATH));
        mForwardsController.setAnchor(mToolsButton);
        mForwardsController.setOnStateChanged(this::onToolsStateChanged);
        mVpnToggleController.addTickListener(() -> {
            if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.FORWARDS)) mForwardsController.refresh();
        });

        mSshButton = barRoot.findViewById(R.id.ssh_hosts_tab_button);
        styleKey(mSshButton);
        mSshButton.setOnClickListener(this::showSshHostsPopup);

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

        mToolsButton.setOnClickListener(v -> showToolsSheet());
        updateToolsKey();

        applyFeatures();
    }

    private void styleKey(View key) {
        key.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
    }

    /** Show or hide keys according to the enabled features and redraw the tabs. */
    public void applyFeatures() {
        mClaudeUsageButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.CLAUDE_USAGE) ? View.VISIBLE : View.GONE);
        mSshButton.setVisibility(TabBarFeatures.isEnabled(mActivity, TabBarFeatures.SSH) ? View.VISIBLE : View.GONE);
        update();
        updateToolsKey();
        refreshTools();
    }

    /** VPN or forwards state changed: update the tools key's dot and an open tools sheet. */
    private void onToolsStateChanged() {
        updateToolsKey();
        refreshTools();
    }

    /**
     * The tools key gets a small dot so the VPN and forwards stay visible without their own keys:
     * red if a forward failed, amber while the VPN connects, accent when the VPN or a forward is on.
     */
    private void updateToolsKey() {
        boolean vpn = TabBarFeatures.isEnabled(mActivity, TabBarFeatures.VPN);
        boolean forwards = TabBarFeatures.isEnabled(mActivity, TabBarFeatures.FORWARDS);
        VpnToggleController.State vpnState = mVpnToggleController.getState();

        int dotColor = 0;
        if (forwards && mForwardsController.hasPendingFailure()) dotColor = TabBarPopup.DOT_RED;
        else if (vpn && vpnState == VpnToggleController.State.CONNECTING) dotColor = TabBarPopup.DOT_AMBER;
        else if ((vpn && vpnState == VpnToggleController.State.ON) || (forwards && mForwardsController.getActiveCount() > 0))
            dotColor = ExtraKeysStyle.ACCENT_COLOR;

        Drawable key = ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0);
        if (dotColor == 0 || Build.VERSION.SDK_INT < 23) {
            mToolsButton.setBackground(key);
            return;
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColor);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{key, dot});
        int size = Math.round(ExtraKeysStyle.dp(mActivity, 6));
        int end = ExtraKeysStyle.insetHorizontalPx(mActivity) + Math.round(ExtraKeysStyle.dp(mActivity, 3));
        int top = ExtraKeysStyle.insetVerticalPx(mActivity) + Math.round(ExtraKeysStyle.dp(mActivity, 3));
        layers.setLayerGravity(1, Gravity.TOP | Gravity.END);
        layers.setLayerSize(1, size, size);
        layers.setLayerInset(1, 0, top, end, 0);
        mToolsButton.setBackground(layers);
    }

    /** The sheet behind the tools key: VPN, port forwards, snippets and settings. */
    private void showToolsSheet() {
        TabBarPopup sheet = new TabBarPopup(mActivity, mActivity.getString(R.string.title_tools));
        mToolsSheet = sheet;
        sheet.setOnDismiss(() -> { if (mToolsSheet == sheet) mToolsSheet = null; });
        fillToolsSheet(sheet);
        sheet.show(mToolsButton);
        checkClaudeBell();
    }

    /** Find Claude accounts not set to ring the terminal bell, off the main thread, then refresh the sheet. */
    private void checkClaudeBell() {
        mProbeExecutor.execute(() -> {
            List<String> missing = new java.util.ArrayList<>();
            for (ClaudeUsage.Account account : ClaudeUsage.discoverAccounts(TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                if (!ringsBell(account)) missing.add(account.command());
            }
            mHandler.post(() -> {
                mClaudeWithoutBell = missing;
                refreshTools();
            });
        });
    }

    /** Whether Claude Code for this account sends a terminal bell when it needs input. */
    private static boolean ringsBell(ClaudeUsage.Account account) {
        // The default account keeps its settings in ~/.claude.json, others inside their folder
        java.io.File config = "personal".equals(account.name) || account.configDir.getName().equals(".claude")
            ? new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".claude.json")
            : new java.io.File(account.configDir, ".claude.json");
        if (!config.isFile()) return true; // Never set up, nothing to tell
        try {
            String json = new String(java.nio.file.Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            String channel = new org.json.JSONObject(json).optString("preferredNotifChannel", "");
            return "terminal_bell".equals(channel) || "iterm2_with_bell".equals(channel);
        } catch (Exception e) {
            return true;
        }
    }

    /** How to make Claude ring the bell, so its prompts mark tabs with a red dot. */
    private void showClaudeBellHelp() {
        TabBarPopup panel = new TabBarPopup(mActivity, mActivity.getString(R.string.title_claude_bell));
        panel.addMessage(TabBarPopup.Kind.INFO, mActivity.getString(R.string.msg_claude_bell_why), null, null);
        panel.addOutput(mActivity.getString(R.string.msg_claude_bell_steps, TextUtils.join(", ", mClaudeWithoutBell)));
        panel.addButtons(new TabBarPopup.Button(mActivity.getString(R.string.action_ok), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.showCentered(true, null);
    }

    /** Rebuild an open tools sheet, so its VPN and forwards rows stay current. */
    private void refreshTools() {
        TabBarPopup sheet = mToolsSheet;
        if (sheet == null || !sheet.isShowing()) return;
        sheet.clearRows();
        fillToolsSheet(sheet);
        sheet.relayout();
    }

    private void fillToolsSheet(TabBarPopup sheet) {
        if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.VPN)) {
            VpnToggleController.State state = mVpnToggleController.getState();
            TabBarPopup.Row vpnRow = sheet.addSwitchRow(mActivity.getString(R.string.feature_vpn),
                mVpnToggleController.getStatusText(), true, state != VpnToggleController.State.OFF, checked -> {
                    sheet.dismiss();
                    mVpnToggleController.toggle();
                });
            vpnRow.setDotColor(state == VpnToggleController.State.ON ? TabBarPopup.DOT_GREEN
                : state == VpnToggleController.State.CONNECTING ? TabBarPopup.DOT_AMBER : TabBarPopup.DOT_GREY);
        }

        if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.FORWARDS)) {
            int active = mForwardsController.getActiveCount();
            String subtitle = mForwardsController.hasPendingFailure() ? mActivity.getString(R.string.msg_tools_forwards_failed)
                : active > 0 ? mActivity.getResources().getQuantityString(R.plurals.msg_tools_forwards_active, active, active)
                : mActivity.getString(R.string.msg_tools_forwards_none);
            sheet.addLinkRow(mActivity.getString(R.string.feature_forwards), subtitle, () -> {
                sheet.dismiss();
                mForwardsController.open();
            });
        }

        if (TabBarFeatures.isEnabled(mActivity, TabBarFeatures.SNIPPETS)) {
            sheet.addLinkRow(mActivity.getString(R.string.feature_snippets), mActivity.getString(R.string.msg_tools_snippets), () -> {
                sheet.dismiss();
                showSnippetsPopup(mToolsButton);
            });
        }

        if (ClaudeAiPanels.isEnabled(mActivity)) {
            sheet.addLinkRow(mActivity.getString(R.string.action_ai_command), mActivity.getString(R.string.msg_ai_command_desc), () -> {
                sheet.dismiss();
                ClaudeAiPanels.command(mActivity, mActivity.getCurrentSession());
            });
        }

        sheet.addLinkRow(mActivity.getString(R.string.title_tab_emojis), mActivity.getString(R.string.msg_tools_tab_emojis), () -> {
            sheet.dismiss();
            showTabEmojisList();
        });

        if (!mClaudeWithoutBell.isEmpty()) {
            TabBarPopup.Row bell = sheet.addLinkRow(mActivity.getString(R.string.title_claude_bell),
                mActivity.getString(R.string.msg_tools_claude_bell, TextUtils.join(", ", mClaudeWithoutBell)), () -> {
                    sheet.dismiss();
                    showClaudeBellHelp();
                });
        }

        sheet.addLinkRow(mActivity.getString(R.string.action_tabbar_settings), mActivity.getString(R.string.msg_tools_settings), () -> {
            sheet.dismiss();
            TabBarFeatures.showDialog(mActivity, this::applyFeatures);
        });
    }

    /** Start refreshing the VPN and forwards state while the activity is visible. */
    public void onStart() {
        mVpnToggleController.startPolling();
    }

    public void onStop() {
        TabBarPopup.dismissAll();
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

        TerminalSession current = mActivity.getCurrentSession();
        float density = mActivity.getResources().getDisplayMetrics().density;
        int padding = Math.round(4 * density);
        View currentTab = null;

        for (int i = 0; i < count; i++) {
            TermuxSession termuxSession = mSessions.get(i);
            TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
            if (session == null) continue;

            TextView tab = new TextView(mActivity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                Math.round(TAB_WIDTH_DP * density), LinearLayout.LayoutParams.MATCH_PARENT);
            tab.setLayoutParams(params);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(padding, 0, padding, 0);
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            String emoji = getTabEmoji(session);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, emoji != null ? 16 : 13);
            tab.setText(emoji != null ? emoji : getTabTitle(i, session));

            boolean isCurrent = session == current;
            tab.setBackground(getTabBackground(isCurrent, SessionAttention.has(session)));
            tab.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);

            boolean running = session.isRunning();
            if (running)
                tab.setPaintFlags(tab.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            else
                tab.setPaintFlags(tab.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

            // On black, the current tab is just an accent outline with accent text
            int textColor = isCurrent ? ExtraKeysStyle.ACCENT_COLOR : ExtraKeysStyle.KEY_HINT_TEXT_COLOR;
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
            if (isCurrent) currentTab = tab;
        }

        // Keep the current tab in view, and let swipes switch sessions only while nothing scrolls
        final View shownTab = currentTab;
        mTabsScroll.post(() -> {
            View content = mTabsScroll.getChildAt(0);
            boolean overflows = content != null && content.getWidth() > mTabsScroll.getWidth();
            if (mBarRoot instanceof TermuxSessionsTabBarView)
                ((TermuxSessionsTabBarView) mBarRoot).setSwipeEnabled(!overflows);
            if (overflows && shownTab != null) {
                int left = shownTab.getLeft();
                int right = shownTab.getRight();
                int scroll = mTabsScroll.getScrollX();
                int width = mTabsScroll.getWidth();
                if (left < scroll) mTabsScroll.smoothScrollTo(left, 0);
                else if (right > scroll + width) mTabsScroll.smoothScrollTo(right - width, 0);
            }
        });

        mHandler.removeCallbacks(mSaveRunnable);
        mHandler.postDelayed(mSaveRunnable, SAVE_DEBOUNCE_MS);
    }

    /**
     * A tab's background: the current tab is only an accent outline on black, the others a dim
     * keycap, plus a red dot in the top corner when the session needs attention.
     */
    private Drawable getTabBackground(boolean isCurrent, boolean attention) {
        Drawable key = isCurrent ? outlineKey(ExtraKeysStyle.ACCENT_COLOR)
            : ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0);
        if (!attention || Build.VERSION.SDK_INT < 23) return key;

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(TabBarPopup.DOT_RED);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{key, dot});
        int size = Math.round(ExtraKeysStyle.dp(mActivity, 7));
        int end = ExtraKeysStyle.insetHorizontalPx(mActivity) + Math.round(ExtraKeysStyle.dp(mActivity, 3));
        int top = ExtraKeysStyle.insetVerticalPx(mActivity) + Math.round(ExtraKeysStyle.dp(mActivity, 3));
        layers.setLayerGravity(1, Gravity.TOP | Gravity.END);
        layers.setLayerSize(1, size, size);
        layers.setLayerInset(1, 0, top, end, 0);
        return layers;
    }

    /** A keycap with no fill and a 1.5dp outline, so only the outline lights up on OLED. */
    private Drawable outlineKey(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0);
        shape.setCornerRadius(ExtraKeysStyle.dp(mActivity, ExtraKeysStyle.CORNER_RADIUS_DP));
        shape.setStroke(Math.max(1, Math.round(ExtraKeysStyle.dp(mActivity, 1.5f))), color);
        int h = ExtraKeysStyle.insetHorizontalPx(mActivity);
        int v = ExtraKeysStyle.insetVerticalPx(mActivity);
        return new android.graphics.drawable.InsetDrawable(shape, h, v, h, v);
    }

    /**
     * The emoji to show as the whole tab label, or {@code null} for the usual title. A name the
     * user gave the tab wins, then the SSH host's emoji, then the default emoji for local tabs.
     */
    private String getTabEmoji(TerminalSession session) {
        if (SessionMeta.isRenamed(session)) return null;
        SessionMeta meta = SessionMeta.get(session);
        if (meta != null) return TabEmojis.getHost(mActivity, meta.host);
        return TabEmojis.getDefault(mActivity);
    }

    /**
     * The emoji picker: a quick-pick grid (one tap saves), a field for any other emoji with the
     * keyboard open, and Clear. {@code save} gets the new emoji, or null to clear it.
     */
    private void showEmojiPicker(String title, String scope, String current, java.util.function.Consumer<String> save) {
        TabBarPopup panel = new TabBarPopup(mActivity, title);
        panel.addFooter(scope);
        Runnable[] done = new Runnable[1];
        java.util.function.Consumer<String> pick = emoji -> {
            save.accept(emoji);
            panel.dismiss();
            update();
        };
        panel.addGrid(QUICK_EMOJIS, 8, pick);
        EditText input = panel.addInput(mActivity.getString(R.string.msg_emoji_hint), current, false,
            () -> done[0].run());
        done[0] = () -> pick.accept(input.getText().toString());
        panel.addButtons(
            new TabBarPopup.Button(mActivity.getString(R.string.action_clear), TabBarPopup.Button.TEXT, () -> pick.accept(null)),
            new TabBarPopup.Button(mActivity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss),
            new TabBarPopup.Button(mActivity.getString(R.string.action_save), TabBarPopup.Button.PRIMARY, () -> done[0].run()));
        panel.showCentered(true, input);
    }

    /** Picker for the emoji of every tab of an SSH host. */
    private void showHostEmojiPicker(String host) {
        showEmojiPicker(mActivity.getString(R.string.title_host_emoji, host),
            mActivity.getString(R.string.msg_emoji_scope_host, host), TabEmojis.getHost(mActivity, host),
            emoji -> TabEmojis.setHost(mActivity, host, emoji));
    }

    /** Picker for the emoji of every local tab. */
    private void showDefaultEmojiPicker() {
        showEmojiPicker(mActivity.getString(R.string.title_default_tab_emoji),
            mActivity.getString(R.string.msg_emoji_scope_local), TabEmojis.getDefault(mActivity),
            emoji -> TabEmojis.setDefault(mActivity, emoji));
    }

    /** All tab emojis in one place: local tabs, then every SSH host. */
    private void showTabEmojisList() {
        TabBarPopup panel = new TabBarPopup(mActivity, mActivity.getString(R.string.title_tab_emojis));
        String none = mActivity.getString(R.string.msg_emoji_none);
        String local = TabEmojis.getDefault(mActivity);
        panel.addLinkRow((local != null ? local + "  " : "") + mActivity.getString(R.string.label_local_tabs),
            local != null ? null : none, () -> {
                panel.dismiss();
                showDefaultEmojiPicker();
            });
        List<SshConfigHosts.HostEntry> hosts = SshConfigHosts.read();
        if (!hosts.isEmpty()) panel.addHeader(mActivity.getString(R.string.label_ssh_hosts));
        for (SshConfigHosts.HostEntry host : hosts) {
            String emoji = TabEmojis.getHost(mActivity, host.alias);
            panel.addLinkRow((emoji != null ? emoji + "  " : "") + host.alias, emoji != null ? null : none, () -> {
                panel.dismiss();
                showHostEmojiPicker(host.alias);
            });
        }
        panel.show(mToolsButton);
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
            String emoji = TabEmojis.getHost(mActivity, host.alias);
            TabBarPopup.Row row = popup.addRow((emoji != null ? emoji + "  " : "") + host.alias, host.describe(), probe, "tmux",
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
        SessionMeta tabMeta = SessionMeta.get(session);
        String tabEmoji = tabMeta != null ? TabEmojis.getHost(mActivity, tabMeta.host) : TabEmojis.getDefault(mActivity);
        popup.addLinkRow((tabEmoji != null ? tabEmoji + "  " : "") + mActivity.getString(R.string.action_tab_emoji),
            tabMeta != null ? mActivity.getString(R.string.msg_emoji_menu_host, tabMeta.host)
                : mActivity.getString(R.string.msg_emoji_menu_local), () -> {
                popup.dismiss();
                if (tabMeta != null) showHostEmojiPicker(tabMeta.host);
                else showDefaultEmojiPicker();
            });
        if (ClaudeAiPanels.isEnabled(mActivity)) {
            popup.addLinkRow(mActivity.getString(R.string.action_ai_explain), mActivity.getString(R.string.msg_ai_explain_desc), () -> {
                popup.dismiss();
                ClaudeAiPanels.explain(mActivity, session);
            });
            if (!SessionMeta.isRenamed(session)) {
                popup.addLinkRow(mActivity.getString(R.string.action_ai_name), mActivity.getString(R.string.msg_ai_name_desc), () -> {
                    popup.dismiss();
                    ClaudeAiPanels.name(mActivity, session, this::update);
                });
            }
        }
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
            SessionMeta.markRenamed(session, !session.mSessionName.isEmpty());
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
