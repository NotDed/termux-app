package com.termux.app.terminal;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.shell.ShellUtils;
import com.termux.terminal.TerminalSession;

/**
 * Notifications for tabs that rang the bell while Termux wasn't on screen, like a Claude Code
 * prompt waiting for input. Tapping one opens Termux on that tab. While Termux is on screen the
 * tab's red dot is enough, so nothing is posted then.
 */
public final class AttentionNotifier {

    /** Intent extra with the {@link TerminalSession#mHandle} of the tab to open. */
    public static final String EXTRA_ATTENTION_SESSION = "com.termux.app.extra.ATTENTION_SESSION";

    private static final String CHANNEL_ID = "termux_attention";
    private static final int MAX_TEXT = 100;
    private static boolean sChannelCreated;

    private AttentionNotifier() {}

    /** Post (or update) the tab's notification, if the feature is on. */
    public static void notify(Context context, TerminalSession session) {
        if (context == null || session == null) return;
        if (!TabBarFeatures.isEnabled(context, TabBarFeatures.ATTENTION_NOTIFY)) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (!sChannelCreated) {
            NotificationUtils.setupNotificationChannel(context, CHANNEL_ID,
                context.getString(R.string.notification_channel_attention), NotificationManager.IMPORTANCE_HIGH);
            sChannelCreated = true;
        }

        Intent intent = new Intent(context, TermuxActivity.class)
            .putExtra(EXTRA_ATTENTION_SESSION, session.mHandle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(context, idFor(session), intent, flags);

        String title = context.getString(R.string.notification_attention_title, describe(context, session));
        String text = lastLine(session);

        Notification.Builder builder = new Notification.Builder(context)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // On the lock screen, only this shows: never the tab's text
            .setPublicVersion(new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(context.getString(R.string.notification_attention_public))
                .build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(CHANNEL_ID);

        try {
            manager.notify(idFor(session), builder.build());
        } catch (Exception ignored) {
            // No permission to post notifications: the red dot still shows
        }
    }

    /** Remove the tab's notification, when the tab is shown or closed. */
    public static void cancel(Context context, TerminalSession session) {
        if (context == null || session == null) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(idFor(session));
    }

    private static int idFor(TerminalSession session) {
        // Away from the ids Termux uses for its own notifications
        return 0x5A000000 | (session.mHandle.hashCode() & 0x00FFFFFF);
    }

    /** How the tab shows in the tab bar: its name, or its host's emoji and name, or the default emoji. */
    private static String describe(Context context, TerminalSession session) {
        SessionMeta meta = SessionMeta.get(session);
        if (SessionMeta.isRenamed(session) && !TextUtils.isEmpty(session.mSessionName)) return session.mSessionName;
        if (meta != null) {
            String emoji = TabEmojis.getHost(context, meta.host);
            return emoji != null ? emoji + " " + meta.host : meta.host;
        }
        if (!TextUtils.isEmpty(session.mSessionName)) return session.mSessionName;
        String emoji = TabEmojis.getDefault(context);
        String title = session.getTitle();
        if (emoji != null) return emoji + (TextUtils.isEmpty(title) ? "" : " " + title);
        return TextUtils.isEmpty(title) ? context.getString(R.string.notification_attention_tab) : title;
    }

    /** The last non-empty line on the tab's screen, with obvious secrets masked. */
    private static String lastLine(TerminalSession session) {
        try {
            String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
            if (transcript != null) {
                String[] lines = transcript.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    line = ClaudeAssistant.mask(line);
                    return line.length() > MAX_TEXT ? line.substring(0, MAX_TEXT) + "…" : line;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

}
