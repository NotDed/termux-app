package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tab bar key showing the Claude plan session usage, with a popup of all limits like {@code /usage}. */
public class ClaudeUsageController {

    private static final long AUTO_REFRESH_MS = 5 * 60 * 1000;
    private static final int AMBER = 0xFFFFC107;
    private static final int RED = 0xFFEF5350;
    private static final int GREEN = 0xFF66BB6A;

    private static final String PREFS_NAME = "claude_usage";
    private static final String KEY_CACHE = "cache";

    private final TermuxActivity mActivity;
    private final TextView mButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /** Last successful result, possibly loaded from the cache. */
    private ClaudeUsage.Result mLastGood;
    /** Status of the last attempt, if it failed. */
    private ClaudeUsage.Result mLastFailure;
    private boolean mFetching;
    private long mLastAttemptAt;
    private TabBarPopup mPopup;

    public ClaudeUsageController(TermuxActivity activity, TextView button) {
        mActivity = activity;
        mButton = button;
        mLastGood = loadCache(activity);
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

    private void fetch() {
        if (mFetching) return;
        mFetching = true;
        mLastAttemptAt = System.currentTimeMillis();
        renderPopup();
        mExecutor.execute(() -> {
            ClaudeUsage.Result result = ClaudeUsage.fetch(TermuxConstants.TERMUX_HOME_DIR_PATH);
            mHandler.post(() -> {
                mFetching = false;
                if (result.status == ClaudeUsage.Status.OK) {
                    mLastGood = result;
                    mLastFailure = null;
                    saveCache(mActivity, result);
                } else {
                    mLastFailure = result;
                }
                applyButton();
                renderPopup();
            });
        });
    }

    private void applyButton() {
        ClaudeUsage.Limit primary = mLastGood == null ? null : mLastGood.primary();
        if (primary == null) {
            mButton.setText("✳");
            mButton.setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
        } else {
            mButton.setText(primary.percent + "%");
            mButton.setTextColor(primary.color(ExtraKeysStyle.KEY_TEXT_COLOR, AMBER, RED));
        }
        mButton.setBackground(ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0));
    }

    private void showPopup() {
        String plan = mLastGood != null && mLastGood.plan != null && !mLastGood.plan.isEmpty() ? " · " + mLastGood.plan : "";
        mPopup = new TabBarPopup(mActivity, mActivity.getString(R.string.title_claude_usage) + plan);
        renderPopup();
        mPopup.show(mButton);
        fetch();
    }

    /** Rebuild the popup content from the current state, if it is showing or about to be shown. */
    private void renderPopup() {
        if (mPopup == null) return;
        mPopup.clearRows();

        if (mLastFailure != null) {
            mPopup.addRow(getFailureMessage(mLastFailure), null, false, null, null, null, null);
        }

        if (mLastGood != null) {
            for (ClaudeUsage.Limit limit : mLastGood.limits) {
                mPopup.addMeterRow(limit.label, limit.percent, formatReset(limit.resetsAt),
                    limit.color(GREEN, AMBER, RED));
            }
        } else if (mFetching) {
            mPopup.addRow(mActivity.getString(R.string.msg_claude_usage_loading), null, false, null, null, null, null);
        }

        String footer = mFetching ? mActivity.getString(R.string.msg_claude_usage_refreshing)
            : mLastGood == null ? mActivity.getString(R.string.action_claude_usage_retry)
            : mActivity.getString(R.string.msg_claude_usage_updated, formatAge(mLastGood.fetchedAt));
        mPopup.addRow(footer, null, false, null, this::fetch, null, null);
    }

    private String getFailureMessage(ClaudeUsage.Result result) {
        switch (result.status) {
            case NOT_LOGGED_IN: return mActivity.getString(R.string.msg_claude_usage_not_logged_in);
            case TOKEN_EXPIRED: return mActivity.getString(R.string.msg_claude_usage_token_expired);
            default: return mActivity.getString(R.string.msg_claude_usage_error,
                result.error == null ? "?" : result.error);
        }
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

    /** Only numbers and labels are cached, never the token. */
    private static void saveCache(Context context, ClaudeUsage.Result result) {
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
            prefs(context).edit().putString(KEY_CACHE, object.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static ClaudeUsage.Result loadCache(Context context) {
        try {
            String json = prefs(context).getString(KEY_CACHE, null);
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
