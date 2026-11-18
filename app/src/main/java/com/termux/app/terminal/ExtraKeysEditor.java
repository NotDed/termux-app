package com.termux.app.terminal;

import android.app.Dialog;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.settings.properties.TermuxPropertyConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysStyle;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * A screen to edit the extra keys, by hand or by asking Claude. It fills the space above the real
 * extra keys row, which shows the draft live. Nothing is written until Save; Cancel puts the saved
 * layout back.
 */
final class ExtraKeysEditor {

    private static final List<String> SPECIAL_KEYS = Arrays.asList(
        "ESC", "TAB", "CTRL", "ALT", "SHIFT", "FN", "ENTER", "BKSP", "DEL", "INS",
        "HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT", "SPACE", "PASTE",
        "KEYBOARD", "DRAWER", "SCROLL", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12");
    private static final int MAX_UNDO = 20;

    private final TermuxActivity mActivity;
    private final File mFile;
    private final Dialog mDialog;
    private final LinearLayout mRowsView;
    private final TextView mStatus;
    private final TextView mUndoButton;
    private final Deque<List<List<ExtraKeysFile.Key>>> mUndo = new ArrayDeque<>();
    private List<List<ExtraKeysFile.Key>> mLayout;
    private boolean mSaved;

    static void show(TermuxActivity activity) {
        new ExtraKeysEditor(activity).open();
    }

    private ExtraKeysEditor(TermuxActivity activity) {
        mActivity = activity;
        mFile = !TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE.isFile() && TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE.isFile()
            ? TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE : TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;

        String current = ExtraKeysFile.read(mFile);
        try {
            mLayout = ExtraKeysFile.parse(current != null ? current : TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
        } catch (Exception e) {
            mLayout = new ArrayList<>();
            try {
                mLayout = ExtraKeysFile.parse(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
            } catch (Exception ignored) {}
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ExtraKeysStyle.PANEL_COLOR);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView title = text(activity.getString(R.string.title_extra_keys_editor), 18, ExtraKeysStyle.KEY_TEXT_COLOR);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text(activity.getString(R.string.msg_extra_keys_editor, shortPath(mFile)), 12, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        subtitle.setPadding(0, dp(2), 0, dp(8));
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mRowsView = new LinearLayout(activity);
        mRowsView.setOrientation(LinearLayout.VERTICAL);
        content.addView(mRowsView);

        TextView addRow = button(activity.getString(R.string.action_extra_keys_add_row), false);
        addRow.setOnClickListener(v -> change(layout -> layout.add(new ArrayList<>(Arrays.asList(newKey("ESC"))))));
        LinearLayout.LayoutParams addRowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addRowParams.topMargin = dp(6);
        content.addView(addRow, addRowParams);

        if (ClaudeAiPanels.isEnabled(activity)) content.addView(buildClaudeBox());

        mStatus = text("", 12, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        mStatus.setPadding(0, dp(6), 0, dp(6));
        root.addView(mStatus);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mUndoButton = button(activity.getString(R.string.action_undo), false);
        mUndoButton.setOnClickListener(v -> undo());
        bar.addView(mUndoButton);
        View spacer = new View(activity);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView cancel = button(activity.getString(R.string.action_cancel), false);
        cancel.setOnClickListener(v -> mDialog.dismiss());
        bar.addView(cancel);
        TextView save = button(activity.getString(R.string.action_save), true);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.leftMargin = dp(8);
        bar.addView(save, saveParams);
        root.addView(bar);

        mDialog = new Dialog(activity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setContentView(root);
        mDialog.setCanceledOnTouchOutside(false);
        // Leaving without saving puts the saved layout back on the real row
        mDialog.setOnDismissListener(d -> {
            if (!mSaved) mActivity.restoreExtraKeys();
        });
    }

    private void open() {
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(ExtraKeysStyle.PANEL_COLOR));
            window.setGravity(Gravity.TOP);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        try {
            mDialog.show();
        } catch (Exception e) {
            return;
        }
        renderRows();
        preview();
    }

    /** Size the screen to end right above the extra keys row, so the live preview stays visible. */
    private void fitAboveExtraKeys() {
        Window window = mDialog.getWindow();
        View toolbar = mActivity.findViewById(R.id.terminal_toolbar_view_pager);
        if (window == null || toolbar == null) return;
        toolbar.post(() -> {
            int[] location = new int[2];
            toolbar.getLocationOnScreen(location);
            Rect frame = new Rect();
            mActivity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
            int height = location[1] - frame.top;
            if (toolbar.getVisibility() != View.VISIBLE || height <= dp(200))
                height = Math.round(frame.height() * 0.7f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
        });
    }

    // ---- Changes, preview, undo, save ----

    private interface Change {
        void apply(List<List<ExtraKeysFile.Key>> layout);
    }

    /** Apply a change to a copy of the draft; keep it only if Termux can read the result. */
    private void change(Change change) {
        List<List<ExtraKeysFile.Key>> next = ExtraKeysFile.copy(mLayout);
        change.apply(next);
        next.removeIf(List::isEmpty);
        setLayout(next, true);
    }

    private boolean setLayout(List<List<ExtraKeysFile.Key>> next, boolean remember) {
        String json = toJson(next);
        if (json == null || next.isEmpty() || !mActivity.isValidExtraKeys(json)) {
            mStatus.setText(mActivity.getString(R.string.msg_extra_keys_invalid));
            mStatus.setTextColor(TabBarPopup.DOT_RED);
            return false;
        }
        if (remember) {
            mUndo.push(ExtraKeysFile.copy(mLayout));
            while (mUndo.size() > MAX_UNDO) mUndo.removeLast();
        }
        mLayout = next;
        mStatus.setText("");
        renderRows();
        preview();
        return true;
    }

    private void undo() {
        if (mUndo.isEmpty()) return;
        mLayout = mUndo.pop();
        renderRows();
        preview();
    }

    private void preview() {
        String json = toJson(mLayout);
        if (json != null) mActivity.previewExtraKeys(json);
        mUndoButton.setAlpha(mUndo.isEmpty() ? 0.4f : 1f);
        fitAboveExtraKeys();
    }

    private void save() {
        String json = toJson(mLayout);
        if (json == null || !mActivity.isValidExtraKeys(json)) {
            mStatus.setText(mActivity.getString(R.string.msg_extra_keys_invalid));
            mStatus.setTextColor(TabBarPopup.DOT_RED);
            return;
        }
        try {
            ExtraKeysFile.write(mFile, new File(mFile.getPath() + ".before-editor"), mLayout);
        } catch (Exception e) {
            mStatus.setText(mActivity.getString(R.string.msg_extra_keys_save_failed, e.getClass().getSimpleName()));
            mStatus.setTextColor(TabBarPopup.DOT_RED);
            return;
        }
        mSaved = true;
        mDialog.dismiss();
        mActivity.reloadStyling();
    }

    private static String toJson(List<List<ExtraKeysFile.Key>> layout) {
        try {
            return ExtraKeysFile.toJson(layout);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- The grid ----

    private void renderRows() {
        mRowsView.removeAllViews();
        for (int r = 0; r < mLayout.size(); r++) {
            final int row = r;
            LinearLayout header = new LinearLayout(mActivity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, dp(r == 0 ? 0 : 10), 0, dp(4));
            TextView label = text(mActivity.getString(R.string.label_extra_keys_row, r + 1), 12, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
            label.setTypeface(null, Typeface.BOLD);
            header.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (r > 0) header.addView(small("↑", () -> change(l -> l.add(row - 1, l.remove(row)))));
            if (r < mLayout.size() - 1) header.addView(small("↓", () -> change(l -> l.add(row + 1, l.remove(row)))));
            if (mLayout.size() > 1) header.addView(small("✕", () -> change(l -> l.remove(row))));
            mRowsView.addView(header);

            HorizontalScrollView strip = new HorizontalScrollView(mActivity);
            strip.setHorizontalScrollBarEnabled(false);
            strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout keys = new LinearLayout(mActivity);
            keys.setOrientation(LinearLayout.HORIZONTAL);
            strip.addView(keys);
            List<ExtraKeysFile.Key> rowKeys = mLayout.get(r);
            for (int k = 0; k < rowKeys.size(); k++) {
                final int index = k;
                ExtraKeysFile.Key key = rowKeys.get(k);
                TextView chip = keyChip(key.label(), key.popup != null);
                chip.setOnClickListener(v -> editKey(row, index));
                keys.addView(chip, new LinearLayout.LayoutParams(dp(52), dp(42)));
            }
            TextView add = keyChip("＋", false);
            add.setTextColor(ExtraKeysStyle.ACCENT_COLOR);
            add.setOnClickListener(v -> editKey(row, -1));
            keys.addView(add, new LinearLayout.LayoutParams(dp(44), dp(42)));
            mRowsView.addView(strip);
        }
    }

    // ---- Editing one key ----

    /** Edit key {@code index} of {@code row}, or add a new one at the end when index is -1. */
    private void editKey(int row, int index) {
        ExtraKeysFile.Key key = index < 0 ? newKey("") : mLayout.get(row).get(index).copy();
        showKeyPanel(key, index < 0, false, edited -> change(layout -> {
            if (index < 0) layout.get(row).add(edited);
            else layout.get(row).set(index, edited);
        }), index < 0 ? null : new KeyActions() {
            public void move(int delta) {
                int target = index + delta;
                if (target < 0 || target >= mLayout.get(row).size()) return;
                change(layout -> layout.get(row).add(target, layout.get(row).remove(index)));
            }

            public void delete() {
                change(layout -> layout.get(row).remove(index));
            }
        });
    }

    private interface KeyActions {
        void move(int delta);
        void delete();
    }

    private interface OnKey {
        void done(ExtraKeysFile.Key key);
    }

    /** Edit a key: key name, macro or text to type, its label, and its swipe-up key. */
    private void showKeyPanel(ExtraKeysFile.Key key, boolean isNew, boolean isPopup, OnKey onDone, KeyActions actions) {
        TabBarPopup panel = new TabBarPopup(mActivity, mActivity.getString(isPopup ? R.string.title_extra_keys_popup
            : isNew ? R.string.title_extra_keys_new : R.string.title_extra_keys_edit));
        int[] mode = {key.isMacro() ? (macroToText(key.macro) != null ? 2 : 1) : 0};
        boolean[] enter = {key.isMacro() && key.macro.endsWith(" ENTER")};
        buildKeyPanel(panel, key, mode, enter, isPopup, onDone, actions);
        panel.showCentered(true, null);
    }

    private void buildKeyPanel(TabBarPopup panel, ExtraKeysFile.Key key, int[] mode, boolean[] enter, boolean isPopup,
                               OnKey onDone, KeyActions actions) {
        panel.clearRows();
        String[] modes = {mActivity.getString(R.string.label_extra_keys_mode_key),
            mActivity.getString(R.string.label_extra_keys_mode_macro), mActivity.getString(R.string.label_extra_keys_mode_text)};

        EditText[] value = new EditText[1];
        panel.addSegments(modes, mode[0], selected -> {
            readInto(key, mode[0], value[0], enter[0]);
            mode[0] = selected;
            buildKeyPanel(panel, key, mode, enter, isPopup, onDone, actions);
            panel.relayout();
        });

        String initial;
        int hint;
        if (mode[0] == 0) {
            initial = key.isMacro() ? "" : key.key;
            hint = R.string.msg_extra_keys_hint_key;
        } else if (mode[0] == 1) {
            initial = key.macro;
            hint = R.string.msg_extra_keys_hint_macro;
        } else {
            String text = macroToText(key.macro);
            initial = text != null ? text : "";
            hint = R.string.msg_extra_keys_hint_text;
        }
        value[0] = panel.addInput(mActivity.getString(hint), initial, false, null);

        if (mode[0] == 0) {
            panel.addGrid(SPECIAL_KEYS, 5, name -> value[0].setText(name));
        } else if (mode[0] == 2) {
            panel.addCheckRow(mActivity.getString(R.string.label_extra_keys_press_enter), null, enter[0], checked -> enter[0] = checked);
        } else {
            panel.addFooter(mActivity.getString(R.string.msg_extra_keys_macro_help));
        }

        EditText label = panel.addInput(mActivity.getString(R.string.msg_extra_keys_hint_label), key.display, false, null);

        if (!isPopup) {
            String popupText = key.popup == null ? mActivity.getString(R.string.msg_extra_keys_no_popup) : key.popup.label();
            panel.addLinkRow(mActivity.getString(R.string.label_extra_keys_popup), popupText, () -> {
                readInto(key, mode[0], value[0], enter[0]);
                key.display = label.getText().toString().trim();
                ExtraKeysFile.Key popup = key.popup != null ? key.popup.copy() : newKey("");
                showKeyPanel(popup, key.popup == null, true, edited -> {
                    key.popup = edited.isMacro() || !edited.key.isEmpty() ? edited : null;
                    buildKeyPanel(panel, key, mode, enter, false, onDone, actions);
                    panel.relayout();
                }, key.popup == null ? null : new KeyActions() {
                    public void move(int delta) {}
                    public void delete() {
                        key.popup = null;
                        buildKeyPanel(panel, key, mode, enter, false, onDone, actions);
                        panel.relayout();
                    }
                });
            });
        }

        List<TabBarPopup.Button> buttons = new ArrayList<>();
        if (actions != null && !isPopup) {
            buttons.add(new TabBarPopup.Button("◀", TabBarPopup.Button.TEXT, () -> { panel.dismiss(); actions.move(-1); }));
            buttons.add(new TabBarPopup.Button("▶", TabBarPopup.Button.TEXT, () -> { panel.dismiss(); actions.move(1); }));
        }
        if (actions != null)
            buttons.add(new TabBarPopup.Button(mActivity.getString(R.string.action_delete), TabBarPopup.Button.DANGER, () -> { panel.dismiss(); actions.delete(); }));
        buttons.add(new TabBarPopup.Button(mActivity.getString(R.string.action_cancel), TabBarPopup.Button.TEXT, panel::dismiss));
        buttons.add(new TabBarPopup.Button(mActivity.getString(R.string.action_done), TabBarPopup.Button.PRIMARY, () -> {
            readInto(key, mode[0], value[0], enter[0]);
            key.display = label.getText().toString().trim();
            if (!key.isMacro() && key.key.isEmpty()) {
                value[0].setError(mActivity.getString(R.string.msg_extra_keys_empty));
                return;
            }
            panel.dismiss();
            onDone.done(key);
        }));
        panel.addButtons(buttons.toArray(new TabBarPopup.Button[0]));
    }

    /** Store what the value field says into the key, for the given mode. */
    private static void readInto(ExtraKeysFile.Key key, int mode, EditText value, boolean enter) {
        if (value == null) return;
        String text = value.getText().toString();
        if (mode == 0) {
            key.key = text.trim();
            key.macro = "";
        } else if (mode == 1) {
            key.macro = text.trim().replaceAll("\\s+", " ");
            key.key = "";
        } else {
            key.macro = textToMacro(text, enter);
            key.key = "";
        }
    }

    /** "git st" + enter becomes "g i t SPACE s t ENTER". */
    static String textToMacro(String text, boolean enter) {
        StringBuilder macro = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (macro.length() > 0) macro.append(' ');
            macro.append(codePoint == ' ' ? "SPACE" : new String(Character.toChars(codePoint)));
        }
        if (enter) macro.append(macro.length() > 0 ? " ENTER" : "ENTER");
        return macro.toString();
    }

    /** The text a macro types, if it only types characters (and maybe enter at the end), else null. */
    static String macroToText(String macro) {
        if (TextUtils.isEmpty(macro)) return null;
        String[] tokens = macro.trim().split("\\s+");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.equals("ENTER") && i == tokens.length - 1) break;
            if (token.equals("SPACE")) text.append(' ');
            else if (token.codePointCount(0, token.length()) == 1) text.append(token);
            else return null;
        }
        return text.length() == 0 ? null : text.toString();
    }

    // ---- Claude ----

    private View buildClaudeBox() {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(16), 0, 0);
        TextView header = text(mActivity.getString(R.string.title_extra_keys_claude), 12, ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
        header.setTypeface(null, Typeface.BOLD);
        box.addView(header);

        LinearLayout line = new LinearLayout(mActivity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, 0);
        EditText input = new EditText(mActivity);
        input.setHint(mActivity.getString(R.string.msg_extra_keys_claude_hint));
        input.setTextColor(ExtraKeysStyle.KEY_TEXT_COLOR);
        input.setHintTextColor(0xFF6E6E78);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setBackground(round(TabBarPopup.GROUP_COLOR, 10, 0));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        line.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ask = button(mActivity.getString(R.string.action_ai_ask), true);
        LinearLayout.LayoutParams askParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        askParams.leftMargin = dp(8);
        line.addView(ask, askParams);
        box.addView(line);

        ask.setOnClickListener(v -> {
            String request = input.getText().toString().trim();
            if (request.isEmpty()) return;
            if (!ClaudeApiKeyStore.hasKey(mActivity)) {
                ClaudeAiPanels.showKeyEditor(mActivity, null);
                return;
            }
            String json = toJson(mLayout);
            if (json == null) return;
            ask.setEnabled(false);
            mStatus.setTextColor(ExtraKeysStyle.KEY_HINT_TEXT_COLOR);
            mStatus.setText(mActivity.getString(R.string.msg_ai_asking));
            ClaudeAssistant.editExtraKeys(mActivity, json, ClaudeAssistant.mask(request), answer -> {
                ask.setEnabled(true);
                if (!mDialog.isShowing()) return;
                if (answer.error != null || answer.layoutJson == null) {
                    mStatus.setTextColor(TabBarPopup.DOT_RED);
                    mStatus.setText(answer.error != null ? answer.error : mActivity.getString(R.string.msg_ai_no_answer));
                    return;
                }
                try {
                    if (setLayout(ExtraKeysFile.parse(answer.layoutJson), true)) {
                        input.setText("");
                        mStatus.setTextColor(ExtraKeysStyle.ACCENT_COLOR);
                        mStatus.setText((answer.summary != null ? answer.summary + " · " : "") +
                            mActivity.getString(R.string.msg_extra_keys_claude_review));
                    }
                } catch (Exception e) {
                    mStatus.setTextColor(TabBarPopup.DOT_RED);
                    mStatus.setText(mActivity.getString(R.string.msg_extra_keys_invalid));
                }
            });
        });
        return box;
    }

    // ---- Views ----

    private static ExtraKeysFile.Key newKey(String name) {
        ExtraKeysFile.Key key = new ExtraKeysFile.Key();
        key.key = name;
        return key;
    }

    private int dp(float value) {
        return Math.round(ExtraKeysStyle.dp(mActivity, value));
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private Drawable round(int fill, float radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(ExtraKeysStyle.dp(mActivity, radius));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private TextView button(String label, boolean primary) {
        TextView view = text(label, 14, primary ? ExtraKeysStyle.ON_ACCENT_TEXT_COLOR : ExtraKeysStyle.KEY_TEXT_COLOR);
        view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setBackground(round(primary ? ExtraKeysStyle.ACCENT_COLOR : TabBarPopup.GROUP_COLOR, 10, 0));
        return view;
    }

    private TextView small(String glyph, Runnable action) {
        TextView view = text(glyph, 14, ExtraKeysStyle.KEY_TEXT_COLOR);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** A key as a keycap, with a corner dot when it has a swipe-up key. */
    private TextView keyChip(String label, boolean hasPopup) {
        TextView chip = text(label, 13, ExtraKeysStyle.KEY_TEXT_COLOR);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(4), 0, dp(4), 0);
        Drawable key = ExtraKeysStyle.keyBackground(mActivity, ExtraKeysStyle.KEY_COLOR, 0);
        if (hasPopup && Build.VERSION.SDK_INT >= 23) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(ExtraKeysStyle.ACCENT_COLOR);
            LayerDrawable layers = new LayerDrawable(new Drawable[]{key, dot});
            int size = dp(5);
            layers.setLayerGravity(1, Gravity.TOP | Gravity.END);
            layers.setLayerSize(1, size, size);
            layers.setLayerInset(1, 0, dp(6), dp(6), 0);
            key = layers;
        }
        chip.setBackground(key);
        return chip;
    }

    private static String shortPath(File file) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String path = file.getPath();
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

}
