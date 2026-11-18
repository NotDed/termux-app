package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Calls the Claude API for the tab bar helpers: explain a tab's output, turn a request into a
 * command, diagnose a VPN or forward failure, and name a tab. Every call is started by a tap,
 * runs off the main thread, and only returns text: commands are never run here.
 */
final class ClaudeAssistant {

    static final String MODEL_OPUS = "claude-opus-5-5";
    static final String MODEL_SONNET = "claude-sonnet-5-5";
    static final String MODEL_HAIKU = "claude-haiku-5-5";
    static final List<String> MODELS = Arrays.asList(MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU);

    private static final String PREFS_NAME = "claude_api_models";
    /** Model for explanations, commands and diagnoses. */
    static final String PREF_MAIN_MODEL = "main";
    /** Model for tab names. */
    static final String PREF_LIGHT_MODEL = "light";

    /** How much of a tab's scrollback is sent. */
    static final int TRANSCRIPT_LINES = 200;

    /** Obvious secrets are replaced before anything is sent. */
    private static final Pattern[] SECRETS = {
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Pattern.compile("sk-ant-[\\w-]+"),
        Pattern.compile("\\bgh[pousr]_\\w+"),
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
        Pattern.compile("(?i)bearer\\s+\\S+"),
        Pattern.compile("(?i)\\b(password|passwd|pass|token|secret|api[_-]?key)(\\s*[=:]\\s*)\\S+"),
    };

    static final class Suggestion {
        String command;
        String why;
        /** "safe", "careful" or "destructive". */
        String risk;
    }

    static final class Answer {
        @Nullable String error;
        @Nullable String summary;
        @Nullable String cause;
        @Nullable String name;
        final List<Suggestion> commands = new ArrayList<>();
    }

    interface Callback {
        void onAnswer(Answer answer);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ClaudeAssistant() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String getModel(Context context, String which) {
        String fallback = PREF_LIGHT_MODEL.equals(which) ? MODEL_HAIKU : MODEL_OPUS;
        String model = prefs(context).getString(which, fallback);
        return MODELS.contains(model) ? model : fallback;
    }

    static void setModel(Context context, String which, String model) {
        prefs(context).edit().putString(which, model).apply();
    }

    static String modelLabel(String model) {
        switch (model) {
            case MODEL_SONNET: return "Sonnet 5.5";
            case MODEL_HAIKU: return "Haiku 5.5";
            default: return "Opus 5.5";
        }
    }

    /** Replace obvious secrets (API keys, tokens, passwords, private keys) with [redacted]. */
    static String mask(String text) {
        if (text == null) return "";
        String masked = text;
        for (Pattern secret : SECRETS) {
            masked = secret.pattern().contains("(password")
                ? secret.matcher(masked).replaceAll("$1$2[redacted]")
                : secret.matcher(masked).replaceAll("[redacted]");
        }
        return masked;
    }

    /** The last {@code lines} lines of {@code text}. */
    static String tail(String text, int lines) {
        if (text == null) return "";
        String[] all = text.split("\n", -1);
        int from = Math.max(0, all.length - lines);
        return String.join("\n", Arrays.asList(all).subList(from, all.length)).trim();
    }

    // ---- Features ----

    private static final String COMMON =
        "You help a developer using Termux, a Linux terminal on Android (no root; packages come from " +
        "`pkg`; $PREFIX is /data/data/com.termux/files/usr and HOME is /data/data/com.termux/files/home). " +
        "Remote tabs are SSH sessions on other machines, so suggest commands for that machine. " +
        "Commands you suggest are typed into the terminal for the user to review, never run for them. " +
        "Mark each command's risk: safe (reads only), careful (changes something) or destructive " +
        "(deletes data, overwrites files, force-pushes, kills processes). Keep every text short and plain.";

    static void explain(Context context, String where, String transcript, Callback callback) {
        String system = COMMON + " The user shares the end of a terminal session and asks what happened. " +
            "summary: one or two sentences on what happened, focusing on the last command. " +
            "cause: the likely cause if something failed, else an empty string. " +
            "commands: up to 3 commands that fix or follow up on it, or none if nothing is needed.";
        String user = "Where: " + where + "\n\nEnd of the terminal session (secrets masked):\n```\n" + transcript + "\n```";
        request(context, PREF_MAIN_MODEL, system, user, schemaWithCommands(true), callback);
    }

    static void command(Context context, String where, String request, Callback callback) {
        String system = COMMON + " The user describes what they want to do; answer with 1 to 3 shell " +
            "commands for it, best first, using the current folder or host when it matters. " +
            "summary: one sentence about the approach.";
        String user = "Where: " + where + "\n\nWhat I want to do: " + request;
        request(context, PREF_MAIN_MODEL, system, user, schemaWithCommands(false), callback);
    }

    static void diagnose(Context context, String what, String log, Callback callback) {
        String system = COMMON + " Something the user started in the background failed. You get what " +
            "was run and the end of its log. summary: one sentence on what failed. cause: the most likely " +
            "reason, in plain words. commands: up to 3 commands to check or fix it, or none.";
        String user = "What was run: " + what + "\n\nEnd of the log (secrets masked):\n```\n" + log + "\n```";
        request(context, PREF_MAIN_MODEL, system, user, schemaWithCommands(true), callback);
    }

    static void name(Context context, String where, String transcript, Callback callback) {
        String system = "You name terminal tabs. From the end of a terminal session, give a short tab name: " +
            "1 to 3 lowercase words, at most 16 characters, describing what the session is doing " +
            "(like \"gradle build\", \"ws1 logs\", \"claude\", \"htop\"). No quotes, no punctuation at the end.";
        String user = "Where: " + where + "\n\nEnd of the terminal session:\n```\n" + transcript + "\n```";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", stringType());
        request(context, PREF_LIGHT_MODEL, system, user, objectSchema(properties), callback);
    }

    /** Check that a key works with a tiny token count, before saving it. */
    static void testKey(String apiKey, Callback callback) {
        EXECUTOR.execute(() -> {
            Answer answer = new Answer();
            try {
                AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                client.messages().countTokens(MessageCountTokensParams.builder()
                    .model(MODEL_HAIKU)
                    .addUserMessage("ping")
                    .build());
            } catch (Exception e) {
                answer.error = describeError(e);
            }
            MAIN.post(() -> callback.onAnswer(answer));
        });
    }

    // ---- Requests ----

    private static void request(Context context, String which, String system, String user,
                                Map<String, Object> schema, Callback callback) {
        String apiKey = ClaudeApiKeyStore.load(context);
        String model = getModel(context, which);
        boolean light = PREF_LIGHT_MODEL.equals(which);
        EXECUTOR.execute(() -> {
            Answer answer = new Answer();
            try {
                if (apiKey == null) throw new IllegalStateException("no key");
                answer = call(apiKey, model, light, system, user, schema);
            } catch (Exception e) {
                answer.error = describeError(e);
            }
            final Answer result = answer;
            MAIN.post(() -> callback.onAnswer(result));
        });
    }

    private static Answer call(String apiKey, String model, boolean light, String system, String user,
                               Map<String, Object> schema) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();

        JsonOutputFormat.Schema.Builder schemaBuilder = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet())
            schemaBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));

        MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(light ? 2000L : 16000L)
            .system(system)
            .addUserMessage(user)
            .outputConfig(OutputConfig.builder()
                .effort(light ? OutputConfig.Effort.LOW : OutputConfig.Effort.MEDIUM)
                .format(JsonOutputFormat.builder().schema(schemaBuilder.build()).build())
                .build());
        if (!MODEL_HAIKU.equals(model)) {
            // If a safety classifier declines, let the server retry on a suitable model
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Message message = client.messages().create(params.build());

        Answer answer = new Answer();
        StopReason stopReason = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            String category = message.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse(null);
            answer.error = "Claude declined this request" + (category != null ? " (" + category + ")" : "");
            return answer;
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            answer.error = "The answer was cut off, try again";
            return answer;
        }

        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content())
            block.text().ifPresent(t -> text.append(t.text()));
        parse(text.toString(), answer);
        return answer;
    }

    private static void parse(String json, Answer answer) {
        try {
            JSONObject object = new JSONObject(json);
            answer.summary = emptyToNull(object.optString("summary", ""));
            answer.cause = emptyToNull(object.optString("cause", ""));
            answer.name = emptyToNull(object.optString("name", "").trim());
            JSONArray commands = object.optJSONArray("commands");
            for (int i = 0; commands != null && i < commands.length() && i < 3; i++) {
                JSONObject item = commands.optJSONObject(i);
                if (item == null || item.optString("command", "").trim().isEmpty()) continue;
                Suggestion suggestion = new Suggestion();
                suggestion.command = item.optString("command", "").trim();
                suggestion.why = item.optString("why", "");
                suggestion.risk = item.optString("risk", "careful");
                answer.commands.add(suggestion);
            }
        } catch (Exception e) {
            answer.error = "Could not read Claude's answer";
        }
    }

    private static String describeError(Exception e) {
        if (e instanceof IllegalStateException) return "No Claude API key set";
        if (e instanceof UnauthorizedException) return "The API key was rejected, check it in Settings";
        if (e instanceof PermissionDeniedException) return "This API key can't use that model";
        if (e instanceof RateLimitException) return "Rate limited, try again in a moment";
        if (e instanceof AnthropicServiceException)
            return "Claude API error " + ((AnthropicServiceException) e).statusCode();
        if (e instanceof AnthropicIoException) return "No connection to the Claude API";
        // Never include the exception message: it could contain request details
        return "Request failed (" + e.getClass().getSimpleName() + ")";
    }

    @Nullable
    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    // ---- Schemas ----

    private static Map<String, Object> stringType() {
        Map<String, Object> type = new HashMap<>();
        type.put("type", "string");
        return type;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> schemaWithCommands(boolean withCause) {
        Map<String, Object> risk = new HashMap<>();
        risk.put("type", "string");
        risk.put("enum", Arrays.asList("safe", "careful", "destructive"));

        Map<String, Object> commandProperties = new LinkedHashMap<>();
        commandProperties.put("command", stringType());
        commandProperties.put("why", stringType());
        commandProperties.put("risk", risk);

        Map<String, Object> commands = new HashMap<>();
        commands.put("type", "array");
        commands.put("items", objectSchema(commandProperties));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", stringType());
        if (withCause) properties.put("cause", stringType());
        properties.put("commands", commands);
        return objectSchema(properties);
    }

}
