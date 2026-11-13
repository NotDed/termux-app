package com.termux.app.terminal;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * Taskbar-like row of session tabs shown above the extra keys toolbar, so that sessions can be
 * switched without swiping in the left drawer from the screen edge (which conflicts with gesture
 * navigation). Hidden when there is only one session.
 */
public class TermuxSessionsTabBarController {

    private static final int COLOR_TAB_ACTIVE = 0xFF3A3A3A;
    private static final int COLOR_TAB_INACTIVE = 0xFF141414;
    private static final int COLOR_TEXT_INACTIVE = 0xFFB0B0B0;

    /** How long to wait for a killed session to actually exit before removing it. */
    private static final int CLOSE_POLL_INTERVAL_MS = 50;
    private static final int CLOSE_POLL_MAX_TRIES = 60;

    private final TermuxActivity mActivity;
    private final List<TermuxSession> mSessions;
    private final View mBarRoot;
    private final LinearLayout mTabsContainer;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

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

        View newSessionButton = barRoot.findViewById(R.id.new_session_tab_button);
        newSessionButton.setOnClickListener(v -> {
            TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
            if (client != null) client.addNewSession(false, null);
        });
        newSessionButton.setOnLongClickListener(v -> {
            TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
            if (client != null) client.addNewSession(true, "Failsafe");
            return true;
        });

        update();
    }

    /** Rebuild the tabs from the current sessions list. Must be called on the main thread. */
    public void update() {
        int count = mSessions.size();
        mBarRoot.setVisibility(count >= 2 ? View.VISIBLE : View.GONE);
        mTabsContainer.removeAllViews();
        if (count < 2) return;

        TerminalSession current = mActivity.getCurrentSession();
        float density = mActivity.getResources().getDisplayMetrics().density;
        int margin = Math.round(density);
        int padding = Math.round(4 * density);

        for (int i = 0; i < count; i++) {
            TermuxSession termuxSession = mSessions.get(i);
            TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
            if (session == null) continue;

            TextView tab = new TextView(mActivity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            params.setMargins(margin, 0, margin, 0);
            tab.setLayoutParams(params);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(padding, 0, padding, 0);
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setText(getTabTitle(i, session));

            boolean isCurrent = session == current;
            tab.setBackgroundColor(isCurrent ? COLOR_TAB_ACTIVE : COLOR_TAB_INACTIVE);
            tab.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);

            boolean running = session.isRunning();
            if (running)
                tab.setPaintFlags(tab.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            else
                tab.setPaintFlags(tab.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

            int textColor = isCurrent ? Color.WHITE : COLOR_TEXT_INACTIVE;
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
    }

    private static String getTabTitle(int index, TerminalSession session) {
        String name = session.mSessionName;
        String title = session.getTitle();
        String label = !TextUtils.isEmpty(name) ? name : (!TextUtils.isEmpty(title) ? title : "");
        return (index + 1) + (label.isEmpty() ? "" : " " + label);
    }

    private void showTabMenu(View anchor, TerminalSession session) {
        PopupMenu popup = new PopupMenu(mActivity, anchor);
        popup.getMenu().add(0, 1, 0, R.string.action_rename_session);
        popup.getMenu().add(0, 2, 1, R.string.action_close_session);
        popup.setOnMenuItemClickListener(item -> {
            TermuxTerminalSessionClient client = mActivity.getTermuxTerminalSessionClient();
            if (client == null) return true;
            if (item.getItemId() == 1) {
                client.renameSession(session);
            } else if (item.getItemId() == 2) {
                confirmCloseSession(session);
            }
            return true;
        });
        popup.show();
    }

    private void confirmCloseSession(TerminalSession session) {
        if (!session.isRunning()) {
            closeSession(session);
            return;
        }
        new AlertDialog.Builder(mActivity)
            .setMessage(R.string.msg_close_session_confirm)
            .setPositiveButton(R.string.action_close_session, (dialog, which) -> closeSession(session))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
