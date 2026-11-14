package com.termux.app.terminal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Claude plan usage, as shown by {@code /usage} in Claude Code. Reads Claude Code's login token
 * from {@code ~/.claude/.credentials.json} and asks the same endpoint Claude Code uses. The token
 * is only sent to that endpoint and never stored or logged. The refresh token is never used.
 */
final class ClaudeUsage {

    static final String USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
    private static final int TIMEOUT_MS = 10000;

    enum Status { OK, NOT_LOGGED_IN, TOKEN_EXPIRED, ERROR }

    /** One limit, like the 5 hour session or the weekly limit. */
    static final class Limit {
        String label;
        int percent;
        /** Epoch millis, or 0 if unknown. */
        long resetsAt;
        /** "normal", "warning" or something more severe, as sent by the server. */
        String severity = "normal";

        /** Color for this limit: server severity first, then the percent. */
        int color(int normal, int amber, int red) {
            if ("warning".equals(severity)) return amber;
            if (!"normal".equals(severity)) return red;
            if (percent >= 80) return red;
            if (percent >= 50) return amber;
            return normal;
        }
    }

    static final class Result {
        Status status = Status.ERROR;
        String error;
        String plan;
        final List<Limit> limits = new ArrayList<>();
        long fetchedAt;

        /** The session (5 hour) limit, or the first limit, or null. */
        Limit primary() {
            for (Limit limit : limits) if (limit.label.startsWith("Session")) return limit;
            return limits.isEmpty() ? null : limits.get(0);
        }
    }

    private ClaudeUsage() {}

    /** Fetch the usage. Blocks on the network, so call it off the main thread. Never throws. */
    static Result fetch(String homeDir) {
        Result result = new Result();
        String token;
        try {
            JSONObject oauth = new JSONObject(readFile(new File(homeDir, ".claude/.credentials.json")))
                .optJSONObject("claudeAiOauth");
            if (oauth == null || oauth.optString("accessToken", "").isEmpty()) {
                result.status = Status.NOT_LOGGED_IN;
                return result;
            }
            result.plan = describePlan(oauth.optString("subscriptionType", ""), oauth.optString("rateLimitTier", ""));
            long expiresAt = oauth.optLong("expiresAt", 0);
            if (expiresAt > 0 && expiresAt < System.currentTimeMillis()) {
                result.status = Status.TOKEN_EXPIRED;
                return result;
            }
            token = oauth.getString("accessToken");
        } catch (Exception e) {
            result.status = Status.NOT_LOGGED_IN;
            return result;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(USAGE_URL).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("anthropic-beta", "oauth-2025-04-20");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "termux-tabbar");

            int code = connection.getResponseCode();
            if (code == 401 || code == 403) {
                result.status = Status.TOKEN_EXPIRED;
                return result;
            }
            if (code != 200) {
                result.status = Status.ERROR;
                result.error = "HTTP " + code;
                return result;
            }
            try (InputStream in = connection.getInputStream()) {
                parse(readStream(in), result);
            }
            result.status = result.limits.isEmpty() ? Status.ERROR : Status.OK;
            if (result.limits.isEmpty()) result.error = "No limits in the response";
            result.fetchedAt = System.currentTimeMillis();
        } catch (Exception e) {
            // Never include the request in the message, only the kind of failure
            result.status = Status.ERROR;
            result.error = e.getClass().getSimpleName();
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    /** Fill {@code result.limits} from the endpoint's JSON. Tolerates missing or unknown fields. */
    static void parse(String json, Result result) throws Exception {
        JSONObject root = new JSONObject(json);

        JSONArray limits = root.optJSONArray("limits");
        if (limits != null) {
            for (int i = 0; i < limits.length(); i++) {
                JSONObject row = limits.optJSONObject(i);
                if (row == null || row.isNull("percent")) continue;
                Limit limit = new Limit();
                limit.label = labelForKind(row.optString("kind", ""), row.optJSONObject("scope"));
                limit.percent = (int) Math.round(row.optDouble("percent", 0));
                limit.resetsAt = parseTime(row.optString("resets_at", ""));
                String severity = row.optString("severity", "normal");
                limit.severity = severity.isEmpty() || "null".equals(severity) ? "normal" : severity;
                result.limits.add(limit);
            }
        }

        if (result.limits.isEmpty()) {
            addLegacy(root, "five_hour", "Session (5h)", result);
            addLegacy(root, "seven_day", "Weekly", result);
            addLegacy(root, "seven_day_opus", "Weekly · Opus", result);
            addLegacy(root, "seven_day_sonnet", "Weekly · Sonnet", result);
        }

        JSONObject extra = root.optJSONObject("extra_usage");
        if (extra != null && extra.optBoolean("is_enabled", false) && !extra.isNull("used_credits")) {
            Limit limit = new Limit();
            double used = extra.optDouble("used_credits", 0) / 100.0;
            String label = String.format(Locale.US, "Extra usage · $%.2f", used);
            if (!extra.isNull("monthly_limit"))
                label += String.format(Locale.US, " / $%.2f", extra.optDouble("monthly_limit", 0) / 100.0);
            limit.label = label;
            limit.percent = extra.isNull("utilization") ? 0 : (int) Math.round(extra.optDouble("utilization", 0));
            result.limits.add(limit);
        }
    }

    private static void addLegacy(JSONObject root, String key, String label, Result result) {
        JSONObject object = root.optJSONObject(key);
        if (object == null || object.isNull("utilization")) return;
        Limit limit = new Limit();
        limit.label = label;
        limit.percent = (int) Math.round(object.optDouble("utilization", 0));
        limit.resetsAt = parseTime(object.optString("resets_at", ""));
        result.limits.add(limit);
    }

    private static String labelForKind(String kind, JSONObject scope) {
        switch (kind) {
            case "session": return "Session (5h)";
            case "weekly_all": return "Weekly";
            case "weekly_scoped": {
                String model = null;
                JSONObject modelObject = scope == null ? null : scope.optJSONObject("model");
                if (modelObject != null && !modelObject.isNull("display_name"))
                    model = modelObject.optString("display_name", null);
                return model == null ? "Weekly (scoped)" : "Weekly · " + model;
            }
            default: {
                String text = kind.replace('_', ' ').trim();
                return text.isEmpty() ? "Limit" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
        }
    }

    /** "max" + "default_claude_max_5x" gives "max 5x". */
    static String describePlan(String subscription, String tier) {
        String plan = subscription == null ? "" : subscription;
        if (tier != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+x)$").matcher(tier);
            if (matcher.find()) plan = (plan + " " + matcher.group(1)).trim();
        }
        return plan;
    }

    static long parseTime(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return 0;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readFile(File file) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            return builder.toString();
        }
    }

    private static String readStream(InputStream in) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            return builder.toString();
        }
    }

}
