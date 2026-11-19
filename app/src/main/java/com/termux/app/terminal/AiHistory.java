package com.termux.app.terminal;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claude's answers per tab, so they can be read again after their panel closes. Kept in app
 * storage (not in HOME) under a stable id per tab that session restore carries over. Only the
 * answers and the user's own requests are stored, never what was sent from the terminal.
 * Main thread only; files are written in the background.
 */
final class AiHistory {

    static final String KIND_EXPLAIN = "explain";
    static final String KIND_COMMAND = "command";
    static final String KIND_DIAGNOSE = "diagnose";

    private static final int MAX_ENTRIES = 20;
    private static final long PRUNE_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    static final class Entry {
        String kind;
        String title;
        long time;
        ClaudeAssistant.Answer answer = new ClaudeAssistant.Answer();
    }

    private static final Map<TerminalSession, String> IDS = new WeakHashMap<>();
    private static final Map<String, List<Entry>> CACHE = new HashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    private AiHistory() {}

    /** The tab's history id, created on first use. */
    static String idOf(TerminalSession session) {
        String id = IDS.get(session);
        if (id == null) {
            id = UUID.randomUUID().toString();
            IDS.put(session, id);
        }
        return id;
    }

    /** Give a restored tab back its history id. */
    static void assign(TerminalSession session, @Nullable String id) {
        if (session != null && id != null && id.matches("[0-9a-f-]{36}")) IDS.put(session, id);
    }

    /** The id if the tab already has one, without creating it. */
    @Nullable
    static String existingIdOf(TerminalSession session) {
        return IDS.get(session);
    }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), "ai_history");
    }

    @NonNull
    static List<Entry> list(Context context, TerminalSession session) {
        String id = IDS.get(session);
        if (id == null) return new ArrayList<>();
        return new ArrayList<>(load(context, id));
    }

    static void add(Context context, TerminalSession session, String kind, String title, ClaudeAssistant.Answer answer) {
        if (session == null || answer == null || answer.error != null) return;
        String id = idOf(session);
        List<Entry> entries = load(context, id);
        Entry entry = new Entry();
        entry.kind = kind;
        entry.title = title;
        entry.time = System.currentTimeMillis();
        entry.answer = answer;
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        persist(context, id, entries);
    }

    static void clear(Context context, TerminalSession session) {
        String id = IDS.get(session);
        if (id == null) return;
        CACHE.put(id, new ArrayList<>());
        File file = new File(dir(context), id + ".json");
        WRITER.execute(file::delete);
    }

    /** Delete history of tabs that are neither open nor saved for restore, once it's a week old. */
    static void prune(Context context, Collection<String> keepIds) {
        Set<String> keep = new HashSet<>(keepIds);
        keep.addAll(IDS.values());
        File dir = dir(context);
        WRITER.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - PRUNE_AGE_MS;
            for (File file : files) {
                String name = file.getName();
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                if (!keep.contains(id) && file.lastModified() < cutoff) file.delete();
            }
        });
    }

    private static List<Entry> load(Context context, String id) {
        List<Entry> cached = CACHE.get(id);
        if (cached != null) return cached;
        List<Entry> entries = new ArrayList<>();
        File file = new File(dir(context), id + ".json");
        if (file.isFile()) {
            try {
                JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) entries.add(fromJson(array.getJSONObject(i)));
            } catch (Exception ignored) {}
        }
        CACHE.put(id, entries);
        return entries;
    }

    private static void persist(Context context, String id, List<Entry> entries) {
        String json;
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) array.put(toJson(entry));
            json = array.toString();
        } catch (Exception e) {
            return;
        }
        File dir = dir(context);
        WRITER.execute(() -> {
            try {
                dir.mkdirs();
                File temp = new File(dir, id + ".json.tmp");
                Files.write(temp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                if (!temp.renameTo(new File(dir, id + ".json"))) temp.delete();
            } catch (Exception ignored) {}
        });
    }

    private static JSONObject toJson(Entry entry) throws Exception {
        JSONObject object = new JSONObject();
        object.put("kind", entry.kind);
        object.put("title", entry.title);
        object.put("time", entry.time);
        ClaudeAssistant.Answer answer = entry.answer;
        if (answer.summary != null) object.put("summary", answer.summary);
        if (answer.cause != null) object.put("cause", answer.cause);
        JSONArray commands = new JSONArray();
        for (ClaudeAssistant.Suggestion suggestion : answer.commands) {
            JSONObject command = new JSONObject();
            command.put("command", suggestion.command);
            command.put("why", suggestion.why);
            command.put("risk", suggestion.risk);
            commands.put(command);
        }
        object.put("commands", commands);
        return object;
    }

    private static Entry fromJson(JSONObject object) {
        Entry entry = new Entry();
        entry.kind = object.optString("kind", KIND_EXPLAIN);
        entry.title = object.optString("title", "");
        entry.time = object.optLong("time", 0);
        entry.answer.summary = object.has("summary") ? object.optString("summary") : null;
        entry.answer.cause = object.has("cause") ? object.optString("cause") : null;
        JSONArray commands = object.optJSONArray("commands");
        for (int i = 0; commands != null && i < commands.length(); i++) {
            JSONObject command = commands.optJSONObject(i);
            if (command == null) continue;
            ClaudeAssistant.Suggestion suggestion = new ClaudeAssistant.Suggestion();
            suggestion.command = command.optString("command", "");
            suggestion.why = command.optString("why", "");
            suggestion.risk = command.optString("risk", "careful");
            if (!suggestion.command.isEmpty()) entry.answer.commands.add(suggestion);
        }
        return entry;
    }

}
