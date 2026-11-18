package com.termux.app.terminal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads and writes the {@code extra-keys} entry of {@code ~/.termux/termux.properties}, keeping
 * every other line and comment of the file as it is.
 */
final class ExtraKeysFile {

    static final String PROPERTY = "extra-keys";
    private static final Pattern ENTRY = Pattern.compile("^\\s*extra-keys\\s*([=:\\s].*)?$");

    /** One extra key: a key name or a macro, with an optional label and swipe-up popup. */
    static final class Key {
        String key = "";
        String macro = "";
        String display = "";
        Key popup;

        Key copy() {
            Key copy = new Key();
            copy.key = key;
            copy.macro = macro;
            copy.display = display;
            copy.popup = popup == null ? null : popup.copy();
            return copy;
        }

        boolean isMacro() {
            return !macro.isEmpty();
        }

        /** What the key shows: its label, else its key name or macro. */
        String label() {
            if (!display.isEmpty()) return display;
            return isMacro() ? macro : key;
        }

        Object toJson() throws Exception {
            if (!isMacro() && display.isEmpty() && popup == null) return key;
            JSONObject object = new JSONObject();
            if (isMacro()) object.put("macro", macro);
            else object.put("key", key);
            if (!display.isEmpty()) object.put("display", display);
            if (popup != null) object.put("popup", popup.toJson());
            return object;
        }

        static Key fromJson(Object value) {
            Key key = new Key();
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                key.key = object.optString("key", "");
                key.macro = object.optString("macro", "");
                key.display = object.optString("display", "");
                if (object.has("popup") && !object.isNull("popup")) key.popup = fromJson(object.opt("popup"));
            } else if (value != null) {
                key.key = String.valueOf(value);
            }
            return key;
        }
    }

    private ExtraKeysFile() {}

    // ---- Layout <-> JSON ----

    static List<List<Key>> parse(String json) throws Exception {
        JSONArray rows = new JSONArray(json);
        List<List<Key>> layout = new ArrayList<>();
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r);
            List<Key> keys = new ArrayList<>();
            for (int k = 0; k < row.length(); k++) keys.add(Key.fromJson(row.get(k)));
            layout.add(keys);
        }
        return layout;
    }

    static String toJson(List<List<Key>> layout) throws Exception {
        StringBuilder builder = new StringBuilder("[");
        for (int r = 0; r < layout.size(); r++) {
            if (r > 0) builder.append(", ");
            builder.append(rowToJson(layout.get(r)));
        }
        return builder.append("]").toString();
    }

    private static String rowToJson(List<Key> row) throws Exception {
        JSONArray array = new JSONArray();
        for (Key key : row) array.put(key.toJson());
        return array.toString();
    }

    static List<List<Key>> copy(List<List<Key>> layout) {
        List<List<Key>> copy = new ArrayList<>();
        for (List<Key> row : layout) {
            List<Key> keys = new ArrayList<>();
            for (Key key : row) keys.add(key.copy());
            copy.add(keys);
        }
        return copy;
    }

    // ---- File ----

    /** The current extra-keys value from the file, or null if it has none. */
    static String read(File file) {
        if (!file.isFile()) return null;
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception e) {
            return null;
        }
        return properties.getProperty(PROPERTY);
    }

    /**
     * Replace the extra-keys entry in {@code file} with {@code layout}, or append it. Everything else
     * in the file stays as it is. Copies the file to {@code backup} first if that doesn't exist yet.
     */
    static void write(File file, File backup, List<List<Key>> layout) throws Exception {
        List<String> lines = file.isFile()
            ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
            : new ArrayList<>();
        if (file.isFile() && !backup.exists())
            Files.copy(file.toPath(), backup.toPath());

        List<String> entry = formatEntry(layout);

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (ENTRY.matcher(lines.get(i)).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            lines.addAll(entry);
        } else {
            int end = start;
            while (end < lines.size() - 1 && continues(lines.get(end))) end++;
            for (int i = end; i >= start; i--) lines.remove(i);
            lines.addAll(start, entry);
        }

        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File temp = new File(file.getPath() + ".tmp");
        Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Backslash continuations, like a hand-written entry: a row of plain keys on one line, and a
     * row with macros or labels one key per line.
     */
    static List<String> formatEntry(List<List<Key>> layout) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(PROPERTY + " = [ \\");
        for (int r = 0; r < layout.size(); r++) {
            List<Key> row = layout.get(r);
            String rowEnd = r < layout.size() - 1 ? "], \\" : "] \\";
            boolean simple = true;
            for (Key key : row) if (!(key.toJson() instanceof String)) simple = false;
            if (simple) {
                String json = escape(rowToJson(row));
                lines.add("  " + json.substring(0, json.length() - 1) + rowEnd);
                continue;
            }
            lines.add("  [ \\");
            for (int k = 0; k < row.size(); k++) {
                Object json = row.get(k).toJson();
                String text = json instanceof String ? JSONObject.quote((String) json) : json.toString();
                lines.add("    " + escape(text) + (k < row.size() - 1 ? ", \\" : " \\"));
            }
            lines.add("  " + rowEnd);
        }
        lines.add("]");
        return lines;
    }

    /** A properties line continues on the next one when it ends with an odd number of backslashes. */
    private static boolean continues(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) count++;
        return count % 2 == 1;
    }

    /** Escape backslashes for the properties format. Non-ASCII stays as is, the file is UTF-8. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\");
    }

}
