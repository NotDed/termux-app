package com.termux.app.terminal;

import android.widget.EditText;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

/**
 * The panels for the Claude helpers. Everything here starts from a tap, shows what will be sent,
 * and only ever types a suggested command into a tab, without pressing enter.
 */
final class ClaudeAiPanels {

    private ClaudeAiPanels() {}

    /** Whether the helpers are turned on in Settings. */
    static boolean isEnabled(TermuxActivity activity) {
        return TabBarFeatures.isEnabled(activity, TabBarFeatures.AI);
    }

    /** Run {@code then} if a key is set, otherwise ask for one first and run it after saving. */
    private static void withKey(TermuxActivity activity, Runnable then) {
        if (ClaudeApiKeyStore.hasKey(activity)) then.run();
        else showKeyEditor(activity, then);
    }

    // ---- What happened? ----

    static void explain(TermuxActivity activity, TerminalSession session) {
        withKey(activity, () -> {
            TabBarPopup panel = loading(activity, activity.getString(R.string.title_ai_explain),
                activity.getString(R.string.msg_ai_sending_lines, ClaudeAssistant.TRANSCRIPT_LINES));
            ClaudeAssistant.explain(activity, describeWhere(session), transcriptOf(session),
                answer -> showAnswer(activity, panel, answer, session));
        });
    }

    // ---- Describe it, get the command ----

    static void command(TermuxActivity activity, @Nullable TerminalSession session) {
        withKey(activity, () -> {
            TabBarPopup panel = new TabBarPopup(activity, activity.getString(R.string.title_ai_command));
            String where = describeWhere(session);
            panel.addFooter(activity.getString(R.string.msg_ai_command_for, where));
            EditText[] input = new EditText[1];
            Runnable ask = () -> {
                String request = input[0].getText().toString().trim();
                if (request.isEmpty()) return;
                panel.clearRows();
                panel.addMessage(TabBarPopup.Kind.INFO, activity.getString(R.string.msg_ai_asking), null, null);
                panel.relayout();
                ClaudeAssistant.command(activity, where, ClaudeAssistant.mask(request),
                    answer -> showAnswer(activity, panel, answer, session));
            };
            input[0] = panel.addInput(activity.getString(R.string.msg_ai_command_hint), null, false, ask);
            panel.addButtons(
                new TabBarPopup.Button(activity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss),
                new TabBarPopup.Button(activity.getString(R.string.action_ai_ask), TabBarPopup.Button.PRIMARY, ask));
            panel.showCentered(true, input[0]);
        });
    }

    // ---- Ask Claude about a failure ----

    /**
     * Explain why something run in the background failed.
     *
     * @param what What was run, in plain words, e.g. "ssh -N -L 8080:localhost:8080 ws1".
     * @param log The end of its log.
     */
    static void diagnose(TermuxActivity activity, String what, String log) {
        withKey(activity, () -> {
            TabBarPopup panel = loading(activity, activity.getString(R.string.title_ai_diagnose),
                activity.getString(R.string.msg_ai_sending_log));
            ClaudeAssistant.diagnose(activity, what, ClaudeAssistant.mask(ClaudeAssistant.tail(log, 40)),
                answer -> showAnswer(activity, panel, answer, activity.getCurrentSession()));
        });
    }

    // ---- Name with AI ----

    static void name(TermuxActivity activity, TerminalSession session, Runnable onNamed) {
        withKey(activity, () -> {
            TabBarPopup panel = loading(activity, activity.getString(R.string.title_ai_name),
                activity.getString(R.string.msg_ai_sending_lines, ClaudeAssistant.TRANSCRIPT_LINES));
            ClaudeAssistant.name(activity, describeWhere(session), transcriptOf(session), answer -> {
                if (answer.error != null || answer.name == null) {
                    showError(activity, panel, answer.error != null ? answer.error : activity.getString(R.string.msg_ai_no_answer));
                    return;
                }
                String name = answer.name.length() > 16 ? answer.name.substring(0, 16).trim() : answer.name;
                session.mSessionName = name;
                SessionMeta.markRenamed(session, true);
                panel.dismiss();
                onNamed.run();
            });
        });
    }

    // ---- API key ----

    /** Set, check, or remove the API key. {@code onSaved} runs after a working key is saved. */
    static void showKeyEditor(TermuxActivity activity, @Nullable Runnable onSaved) {
        TabBarPopup panel = new TabBarPopup(activity, activity.getString(R.string.title_ai_key));
        String current = ClaudeApiKeyStore.describe(activity);
        panel.addFooter(current != null ? activity.getString(R.string.msg_ai_key_current, current)
            : activity.getString(R.string.msg_ai_key_info));
        EditText[] input = new EditText[1];
        Runnable save = () -> {
            String key = input[0].getText().toString().trim();
            if (key.isEmpty()) return;
            panel.clearRows();
            panel.addMessage(TabBarPopup.Kind.INFO, activity.getString(R.string.msg_ai_key_checking), null, null);
            panel.relayout();
            ClaudeAssistant.testKey(key, answer -> {
                if (answer.error != null) {
                    panel.dismiss();
                    TabBarPopup failed = new TabBarPopup(activity, activity.getString(R.string.title_ai_key));
                    failed.addMessage(TabBarPopup.Kind.ERROR, answer.error, null, null);
                    failed.addButtons(new TabBarPopup.Button(activity.getString(R.string.action_ok), TabBarPopup.Button.PRIMARY, failed::dismiss));
                    failed.showCentered(true, null);
                    return;
                }
                boolean saved = ClaudeApiKeyStore.save(activity, key);
                panel.dismiss();
                if (saved && onSaved != null) onSaved.run();
                else if (!saved) showMessage(activity, TabBarPopup.Kind.ERROR, activity.getString(R.string.msg_ai_key_not_saved));
                else showMessage(activity, TabBarPopup.Kind.INFO, activity.getString(R.string.msg_ai_key_works));
            });
        };
        input[0] = panel.addInput(activity.getString(R.string.msg_ai_key_hint), null, true, save);
        if (current != null) {
            panel.addButtons(
                new TabBarPopup.Button(activity.getString(R.string.action_ai_key_remove), TabBarPopup.Button.DANGER, () -> {
                    ClaudeApiKeyStore.clear(activity);
                    panel.dismiss();
                }),
                new TabBarPopup.Button(activity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss),
                new TabBarPopup.Button(activity.getString(R.string.action_save), TabBarPopup.Button.PRIMARY, save));
        } else {
            panel.addButtons(
                new TabBarPopup.Button(activity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss),
                new TabBarPopup.Button(activity.getString(R.string.action_save), TabBarPopup.Button.PRIMARY, save));
        }
        panel.showCentered(true, input[0]);
    }

    // ---- Shared ----

    private static TabBarPopup loading(TermuxActivity activity, String title, String sending) {
        TabBarPopup panel = new TabBarPopup(activity, title);
        panel.addMessage(TabBarPopup.Kind.INFO, activity.getString(R.string.msg_ai_asking), null, null);
        panel.addFooter(sending);
        panel.addButtons(new TabBarPopup.Button(activity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss));
        panel.showCentered(true, null);
        return panel;
    }

    private static void showMessage(TermuxActivity activity, TabBarPopup.Kind kind, String message) {
        TabBarPopup panel = new TabBarPopup(activity, activity.getString(R.string.title_ai_key));
        panel.addMessage(kind, message, null, null);
        panel.addButtons(new TabBarPopup.Button(activity.getString(R.string.action_ok), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.showCentered(true, null);
    }

    private static void showError(TermuxActivity activity, TabBarPopup panel, String error) {
        if (!panel.isShowing()) return;
        panel.clearRows();
        panel.addMessage(TabBarPopup.Kind.ERROR, error, null, null);
        panel.addButtons(new TabBarPopup.Button(activity.getString(R.string.action_ok), TabBarPopup.Button.PRIMARY, panel::dismiss));
        panel.relayout();
    }

    /** Fill a panel with Claude's answer: summary, cause, and each command with a "Type it" button. */
    private static void showAnswer(TermuxActivity activity, TabBarPopup panel, ClaudeAssistant.Answer answer,
                                   @Nullable TerminalSession target) {
        if (!panel.isShowing()) return; // Closed while waiting
        if (answer.error != null) {
            showError(activity, panel, answer.error);
            return;
        }
        panel.clearRows();
        if (answer.summary != null) panel.addMessage(TabBarPopup.Kind.INFO, answer.summary, null, null);
        if (answer.cause != null) panel.addMessage(TabBarPopup.Kind.WARN, answer.cause, null, null);
        if (answer.commands.isEmpty() && answer.summary == null)
            panel.addMessage(TabBarPopup.Kind.INFO, activity.getString(R.string.msg_ai_no_answer), null, null);

        for (ClaudeAssistant.Suggestion suggestion : answer.commands) {
            panel.addOutput(suggestion.command);
            boolean destructive = "destructive".equals(suggestion.risk);
            String risk = destructive ? activity.getString(R.string.msg_ai_risk_destructive)
                : "safe".equals(suggestion.risk) ? activity.getString(R.string.msg_ai_risk_safe)
                : activity.getString(R.string.msg_ai_risk_careful);
            panel.addFooter(risk + (suggestion.why.isEmpty() ? "" : " · " + suggestion.why));
            panel.addButtons(new TabBarPopup.Button(
                activity.getString(destructive ? R.string.action_ai_type_anyway : R.string.action_ai_type),
                destructive ? TabBarPopup.Button.DANGER : TabBarPopup.Button.PRIMARY, () -> {
                    TerminalSession session = target != null && target.isRunning() ? target : activity.getCurrentSession();
                    // Typed, never run: no enter at the end
                    if (session != null && session.isRunning()) session.write(suggestion.command);
                    panel.dismiss();
                }));
        }
        panel.addButtons(new TabBarPopup.Button(activity.getString(R.string.action_close), TabBarPopup.Button.TEXT, panel::dismiss));
        panel.relayout();
    }

    /** Where a tab is: its SSH host or its local folder, for the prompt and the panel subtitle. */
    static String describeWhere(@Nullable TerminalSession session) {
        if (session == null) return "a local Termux shell";
        SessionMeta meta = SessionMeta.get(session);
        if (meta != null) return "an SSH session on host \"" + meta.host + "\"" + (meta.tmux ? " inside tmux" : "");
        String cwd = session.getCwd();
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (cwd == null) return "a local Termux shell";
        return "a local Termux shell in " + (cwd.startsWith(home) ? "~" + cwd.substring(home.length()) : cwd);
    }

    /** The end of a tab's scrollback, with obvious secrets masked. */
    private static String transcriptOf(TerminalSession session) {
        String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        return ClaudeAssistant.mask(ClaudeAssistant.tail(transcript, ClaudeAssistant.TRANSCRIPT_LINES));
    }

}
