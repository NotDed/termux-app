package com.termux.app.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the end of a terminal screen into readable notification text: drops box borders, empty
 * prompts and keyboard hints, and finds the question being asked, with its numbered options when
 * there are any (like a Claude Code permission prompt). Plain Java, no Android types.
 */
final class AttentionText {

    static final int SHORT_MAX = 100;
    static final int FULL_MAX = 300;
    private static final int OPTION_MAX = 40;

    /** Box drawing and block characters used for frames and fills. */
    private static final String FRAME = "│┃║╭╮╰╯─━═┌┐└┘├┤┬┴┼▏▕▌▐╔╗╚╝╠╣╦╩╬┊┆┋┇";
    private static final Pattern ONLY_DECORATION = Pattern.compile("^[\\s" + FRAME + "\\-_=~.·•…*+▔▁]*$");
    private static final Pattern BARE_PROMPT = Pattern.compile("^[>❯$%#➜λ]\\s*[█▌▊_▏|]?\\s*$");
    private static final Pattern HINT = Pattern.compile(
        "(?i)(\\?\\s*for shortcuts|esc to (interrupt|cancel|exit|go back)|shift\\+tab to|ctrl\\+\\w+ to |" +
        "press \\S+( \\S+)? to |enter to (confirm|select|submit)|tab to (amend|toggle)|^⏵|^\\s*⎿?\\s*tip:)");
    /** Status bullets at the start of a line, like Claude Code's "●" and "⎿". */
    private static final String BULLETS = "●⎿✻✶✳✢◦▪";
    /** A shell prompt left right after the last output, when it didn't end with a newline. */
    private static final Pattern TRAILING_PROMPT = Pattern.compile("(\\S)\\s*[$%#❯]\\s*$");
    private static final Pattern OPTION = Pattern.compile("^[❯›>▶➤*\\s]*(\\d{1,2})[.)]\\s+(.+)$");

    final String shortText;
    final String fullText;

    private AttentionText(String shortText, String fullText) {
        this.shortText = shortText;
        this.fullText = fullText;
    }

    static AttentionText from(String screen) {
        List<String> lines = meaningfulLines(screen);
        if (lines.isEmpty()) return new AttentionText("", "");

        // 1. Numbered options near the end, like "❯ 1. Yes / 2. Yes, and don't ask again / 3. No"
        int lastOption = -1;
        for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 6; i--) {
            if (OPTION.matcher(lines.get(i)).matches()) {
                lastOption = i;
                break;
            }
        }
        if (lastOption >= 0) {
            int firstOption = lastOption;
            while (firstOption > 0 && OPTION.matcher(lines.get(firstOption - 1)).matches()) firstOption--;
            if (lastOption > firstOption) {
                List<String> options = new ArrayList<>();
                for (int i = firstOption; i <= lastOption; i++) {
                    Matcher matcher = OPTION.matcher(lines.get(i));
                    if (matcher.matches()) options.add(matcher.group(1) + ". " + cut(cleanOption(matcher.group(2)), OPTION_MAX));
                }
                String question = null;
                for (int i = firstOption - 1; i >= 0 && i >= firstOption - 4; i--) {
                    if (lines.get(i).endsWith("?")) {
                        question = lines.get(i);
                        break;
                    }
                }
                int questionIndex = -1;
                for (int i = firstOption - 1; i >= 0 && i >= firstOption - 4; i--) {
                    if (lines.get(i) == question) {
                        questionIndex = i;
                        break;
                    }
                }
                if (question == null && firstOption > 0) {
                    question = lines.get(firstOption - 1);
                    questionIndex = firstOption - 1;
                }
                String choices = String.join(" · ", options);
                if (question == null) return build(choices, choices);
                // What the question is about, like the command Claude wants to run
                StringBuilder context = new StringBuilder();
                for (int i = Math.max(0, questionIndex - 2); i < questionIndex; i++) {
                    String line = lines.get(i);
                    // Not the command line that was typed at a shell prompt
                    if (line.length() <= 80 && !line.matches("^[$%#❯>]\\s.*")) context.append(line).append("\n");
                }
                return build(question, context + question + "\n" + choices);
            }
        }

        // 2. The last question, with the line above it as context when short
        for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 4; i--) {
            String line = lines.get(i);
            if (isQuestion(line)) {
                String context = i > 0 && lines.get(i - 1).length() <= 80 ? lines.get(i - 1) : null;
                return build(line, context == null ? line : context + "\n" + line);
            }
        }

        // 3. The last few meaningful lines
        int from = Math.max(0, lines.size() - 3);
        List<String> tail = lines.subList(from, lines.size());
        return build(tail.get(tail.size() - 1), String.join("\n", tail));
    }

    /** Lines without frames, empty prompts or keyboard hints, in order. */
    static List<String> meaningfulLines(String screen) {
        List<String> result = new ArrayList<>();
        if (screen == null) return result;
        for (String raw : screen.split("\n")) {
            String line = stripFrame(raw).replaceAll("\\s{2,}", " ").trim();
            while (!line.isEmpty() && BULLETS.indexOf(line.charAt(0)) >= 0) line = line.substring(1).trim();
            if (line.isEmpty() || ONLY_DECORATION.matcher(line).matches()) continue;
            if (BARE_PROMPT.matcher(line).matches()) continue;
            if (HINT.matcher(line).find()) continue;
            result.add(line);
        }
        if (!result.isEmpty()) {
            int last = result.size() - 1;
            String cleaned = TRAILING_PROMPT.matcher(result.get(last)).replaceFirst("$1");
            if (cleaned.isEmpty()) result.remove(last);
            else result.set(last, cleaned);
        }
        return result;
    }

    private static boolean isQuestion(String line) {
        String lower = line.toLowerCase();
        return line.endsWith("?") || line.endsWith("?:") || lower.endsWith("password:") || lower.endsWith("passphrase:")
            || lower.matches(".*[\\[(](y/n|yes/no|y/n/a)[\\])]\\s*[:?]?\\s*$")
            || lower.matches(".*\\?\\s*[\\[(][yn]/[yn][\\])]\\s*$");
    }

    private static String stripFrame(String line) {
        int start = 0, end = line.length();
        while (start < end && (FRAME.indexOf(line.charAt(start)) >= 0 || Character.isWhitespace(line.charAt(start)))) start++;
        while (end > start && (FRAME.indexOf(line.charAt(end - 1)) >= 0 || Character.isWhitespace(line.charAt(end - 1)))) end--;
        return line.substring(start, end);
    }

    /** "Yes, and don't ask again for this command (shift+tab)" -> "Yes, and don't ask again for this command". */
    private static String cleanOption(String option) {
        return option.replaceAll("\\s*\\((shift|ctrl|esc|alt)[^)]*\\)\\s*$", "").trim();
    }

    private static AttentionText build(String first, String full) {
        return new AttentionText(cut(first, SHORT_MAX), cut(full, FULL_MAX));
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1).trim() + "…" : text;
    }

}
