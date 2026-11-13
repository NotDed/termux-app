package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Saved commands from {@code ~/.termux/snippets}: one per line, {@code label: command} or just {@code command}. */
final class Snippets {

    static final String PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/snippets";
    static final String DISPLAY_PATH = "~/.termux/snippets";

    private static final String EXAMPLE =
        "# One snippet per line: \"label: command\" or just \"command\".\n" +
        "# Tap a snippet to run it in the current tab, long-press to type it without running.\n" +
        "git status\n" +
        "build: ./gradlew assembleDebug\n" +
        "claude\n";

    static final class Snippet {
        final String label;
        final String command;

        Snippet(String label, String command) {
            this.label = label;
            this.command = command;
        }
    }

    private Snippets() {}

    static boolean exists() {
        return new File(PATH).isFile();
    }

    @NonNull
    static List<Snippet> read() {
        List<Snippet> snippets = new ArrayList<>();
        File file = new File(PATH);
        if (!file.isFile()) return snippets;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // "label: command", where the label has no spaces before the colon
                int colon = trimmed.indexOf(": ");
                if (colon > 0 && !trimmed.substring(0, colon).contains(" ")) {
                    String command = trimmed.substring(colon + 2).trim();
                    if (!command.isEmpty()) snippets.add(new Snippet(trimmed.substring(0, colon), command));
                } else {
                    snippets.add(new Snippet(trimmed, trimmed));
                }
            }
        } catch (Exception ignored) {}
        return snippets;
    }

    /** Write the example file if there is none. */
    static boolean createExample() {
        File file = new File(PATH);
        if (file.exists()) return true;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(EXAMPLE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
