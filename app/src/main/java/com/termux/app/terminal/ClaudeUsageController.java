package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tab bar key showing the Claude plan session usage of every account (one line each), with a
 * popup of all limits per account like {@code /usage}. Accounts are the Claude Code config folders
 * {@code ~/.claude} ("personal") and {@code ~/.claude-<name>}.
 */
public class ClaudeUsageController {

    private static final long AUTO_REFRESH_MS = 5 * 60 * 1000;
    private static final int AMBER = 0xFFFFC107;
    private static final int RED = 0xFFEF5350;
    private static final int GREEN = 0xFF66BB6A;

    private static final String PREFS_NAME = "claude_usage";
    private static final String KEY_CACHE_PREFIX = "cache_";
    /** Cache key used before multiple accounts, migrated to "cache_personal". */
    private static final String KEY_LEGACY_CACHE = "cache";

    private static final class AccountState {
        final ClaudeUsage.Account account;
        /** Last successful result, possibly loaded from the cache. */
        ClaudeUsage.Result lastGood;
        /** The last attempt, if it failed. */
        ClaudeUsage.Result lastFailure;

        AccountState(ClaudeUsage.Account account) {
            this.account = account;
        }
    }

    private final TermuxActivity mActivity;
    private final TextView mButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /** Accounts in display order. Main thread only. */
    private final Map<String, AccountState> mAccounts = new LinkedHashMap<>();
    private boolean mFetching;
    private long mLastAttemptAt;
    private TabBarPopup mPopup;
    private TextView mRefreshAction;

    public ClaudeUsageController(TermuxActivity activity, TextView button) {
        mActivity = activity;
        mButton = button;
        migrateLegacyCache(activity);
        syncAccounts(ClaudeUsage.discoverAccounts(TermuxConstants.TERMUX_HOME_DIR_PATH));
        mButton.setOnClickListener(v -> showPopup());
        mButton.setOnLongClickListener(v -> {
            fetch();
            return true;
        });
        applyButton();
    }

    /** Called on every tab bar tick: refresh now and then while the activity is visible. */
    public void onTick() {
        if (!mFetching && System.currentTimeMillis() - mLastAttemptAt > AUTO_REFRESH_MS) fetch();
    }

    /** Keep state for the current accounts, loading the cache of new ones and dropping removed ones. */
    private void syncAccounts(List<ClaudeUsage.Account> accounts) {
        Map<String, AccountState> previous = new LinkedHashMap<>(mAccounts);
        mAccounts.clear();
        for (ClaudeUsage.Account account : accounts) {
            AccountState state = previous.get(account.name);
            if (state == null || !state.account.configDir.equals(account.configDir)) {
                state = new AccountState(account);
                state.lastGood = loadCache(mActivity, account.name);
            }
            mAccounts.put(account.name, state);
        }
    }

    private void fetch() {
        if (mFetching) return;
        mFetching = true;
        mLastAttemptAt = System.currentTimeMillis();
        renderPopup();
        mExecutor.execute(() -> {
            List<ClaudeUsage.Account> accounts = ClaudeUsage.discoverAccounts(TermuxConstants.TERMUX_HOME_DIR_PATH);
            List<ClaudeUsage.Result> results = new ArrayList<>();
            for (ClaudeUsage.Account account : accounts) results.add(ClaudeUsage.fetch(account.configDir));
            mHandler.post(() -> {
                mFetching = false;
                syncAccounts(accounts);
                for (int i = 0; i < accounts.size(); i++) {
                    AccountState state = mAccounts.get(accounts.get(i).name);
                    ClaudeUsage.Result result = results.get(i);
                    if (state == null) continue;
                    if (result.status == ClaudeUsage.Status.OK) {
                        state.lastGood = result;
                        state.lastFailure = null;
                        saveCache(mActivity, state.account.name, result);
                    } else {
                        state.lastFailure = result;
                        // An account that logged out should not keep showing old numbers on the key
                        if (result.status == ClaudeUsage.Status.NOT_LOGGED_IN) {
                            state.lastGood = null;
                            clearCache(mActivity, state.account.name);
                        }
                    }
                }
                applyButton();
                renderPopup();
            });
        });
    }

    /** One line per account with a session limit, each colored by its level. */
    private void applyButton() {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int lines = 0;
        for (AccountState state : mAccounts.values()) {
            ClaudeUsage.Limit primary = state.lastGood == null ? null : state.lastGood.primary();
            if (primary == null) continue;
            if (lines > 0) text.append('\n');
            int start = text.length();
            text.append(String.valueOf(primary.percent)).append('%');
            text.setSpan(new ForegroundColorSpan(primary.color(ExtraKeysStyle.KEY_TEXT_COLOR, AMBER, RED)),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            lines++;
        }

        if (lines == 0) {
            mButton.setSingleLine(true);
            mButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            mButton.setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
            mButton.setText("✳");
        } else {
            mButton.setSingleLine(lines == 1);
            if (lines > 1) mButton.setMaxLines(3);
            mButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, lines > 1 ? 10 : 12);
            mButton.setText(text);
        }
        mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
    }

    private void showPopup() {
        mPopup = new TabBarPopup(mActivity, mActivity.getString(R.string.title_claude_usage));
        mRefreshAction = mPopup.addHeaderAction("↻", this::fetch);
        mPopup.setOnDismiss(() -> { mPopup = null; mRefreshAction = null; });
        renderPopup();
        mPopup.show(mButton);
        fetch();
    }

    /** Rebuild the popup content from the current state, if it is showing or about to be shown. */
    private void renderPopup() {
        if (mPopup == null) return;
        mPopup.clearRows();
        if (mRefreshAction != null) mRefreshAction.setAlpha(mFetching ? 0.35f : 1f);

        if (mAccounts.isEmpty()) {
            mPopup.addMessage(TabBarPopup.Kind.INFO,
                mActivity.getString(R.string.msg_claude_usage_not_logged_in, "claude"), null, null);
        }

        long oldestFetch = Long.MAX_VALUE;
        for (AccountState state : mAccounts.values()) {
            String plan = state.lastGood != null && state.lastGood.plan != null && !state.lastGood.plan.isEmpty()
                ? " · " + state.lastGood.plan : "";
            mPopup.addHeader(state.account.label() + plan);

            if (state.lastFailure != null) addFailure(state.account, state.lastFailure);

            if (state.lastGood != null) {
                oldestFetch = Math.min(oldestFetch, state.lastGood.fetchedAt);
                for (ClaudeUsage.Limit limit : state.lastGood.limits) {
                    mPopup.addMeterRow(limit.label, limit.percent, formatReset(limit.resetsAt),
                        limit.color(GREEN, AMBER, RED));
                }
            } else if (state.lastFailure == null && mFetching) {
                mPopup.addFooter(mActivity.getString(R.string.msg_claude_usage_loading));
            }
        }

        String footer = mFetching ? mActivity.getString(R.string.msg_claude_usage_refreshing)
            : oldestFetch == Long.MAX_VALUE ? mActivity.getString(R.string.action_claude_usage_retry)
            : mActivity.getString(R.string.msg_claude_usage_updated, formatAge(oldestFetch));
        mPopup.addFooter(footer);
        mPopup.relayout();
    }

    private void addFailure(ClaudeUsage.Account account, ClaudeUsage.Result result) {
        switch (result.status) {
            case NOT_LOGGED_IN:
                mPopup.addMessage(TabBarPopup.Kind.WARN,
                    mActivity.getString(R.string.msg_claude_usage_not_logged_in, account.command()),
                    mActivity.getString(R.string.msg_claude_usage_logged_out_action, account.command()),
                    () -> openAccount(account));
                break;
            case TOKEN_EXPIRED:
                mPopup.addMessage(TabBarPopup.Kind.WARN,
                    mActivity.getString(R.string.msg_claude_usage_token_expired, account.command()),
                    mActivity.getString(R.string.msg_claude_usage_logged_out_action, account.command()),
                    () -> openAccount(account));
                break;
            default:
                mPopup.addMessage(TabBarPopup.Kind.ERROR, mActivity.getString(R.string.msg_claude_usage_error,
                    result.error == null ? "?" : result.error), null, null);
                break;
        }
    }

    /** Open a new tab running the account's command, so the user can log in. */
    private void openAccount(ClaudeUsage.Account account) {
        if (mPopup != null) mPopup.dismiss();
        TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
        if (client == null) return;
        TerminalSession session = client.addNewSession(false, account.command(), null, null, TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (session != null) session.write(account.command() + "\r");
    }

    private static String formatReset(long resetsAt) {
        if (resetsAt <= 0) return null;
        long left = resetsAt - System.currentTimeMillis();
        if (left <= 0) return "resets now";
        long minutes = left / 60000;
        if (minutes < 60) return "resets in " + minutes + "m";
        if (minutes < 24 * 60) return "resets in " + (minutes / 60) + "h " + (minutes % 60) + "m";
        return "resets " + new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date(resetsAt));
    }

    private static String formatAge(long at) {
        long minutes = (System.currentTimeMillis() - at) / 60000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        if (minutes < 24 * 60) return (minutes / 60) + " h ago";
        return new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date(at));
    }

    private static void migrateLegacyCache(Context context) {
        SharedPreferences prefs = prefs(context);
        String legacy = prefs.getString(KEY_LEGACY_CACHE, null);
        if (legacy == null) return;
        SharedPreferences.Editor editor = prefs.edit().remove(KEY_LEGACY_CACHE);
        if (!prefs.contains(KEY_CACHE_PREFIX + "personal")) editor.putString(KEY_CACHE_PREFIX + "personal", legacy);
        editor.apply();
    }

    /** Only numbers and labels are cached, never the token. */
    private static void saveCache(Context context, String account, ClaudeUsage.Result result) {
        try {
            JSONObject object = new JSONObject();
            object.put("plan", result.plan == null ? "" : result.plan);
            object.put("fetchedAt", result.fetchedAt);
            JSONArray limits = new JSONArray();
            for (ClaudeUsage.Limit limit : result.limits) {
                JSONObject row = new JSONObject();
                row.put("label", limit.label);
                row.put("percent", limit.percent);
                row.put("resetsAt", limit.resetsAt);
                row.put("severity", limit.severity);
                limits.put(row);
            }
            object.put("limits", limits);
            prefs(context).edit().putString(KEY_CACHE_PREFIX + account, object.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void clearCache(Context context, String account) {
        prefs(context).edit().remove(KEY_CACHE_PREFIX + account).apply();
    }

    private static ClaudeUsage.Result loadCache(Context context, String account) {
        try {
            String json = prefs(context).getString(KEY_CACHE_PREFIX + account, null);
            if (json == null) return null;
            JSONObject object = new JSONObject(json);
            ClaudeUsage.Result result = new ClaudeUsage.Result();
            result.status = ClaudeUsage.Status.OK;
            result.plan = object.optString("plan", "");
            result.fetchedAt = object.optLong("fetchedAt", 0);
            JSONArray limits = object.optJSONArray("limits");
            for (int i = 0; limits != null && i < limits.length(); i++) {
                JSONObject row = limits.getJSONObject(i);
                ClaudeUsage.Limit limit = new ClaudeUsage.Limit();
                limit.label = row.optString("label", "Limit");
                limit.percent = row.optInt("percent", 0);
                limit.resetsAt = row.optLong("resetsAt", 0);
                limit.severity = row.optString("severity", "normal");
                result.limits.add(limit);
            }
            return result.limits.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

}
