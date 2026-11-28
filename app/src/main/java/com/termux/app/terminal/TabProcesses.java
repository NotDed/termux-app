package com.termux.app.terminal;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Finds out what a tab is running, for session restore: a tmux client (and its session), or else
 * Claude Code (and its conversation). Reads /proc of the tab's processes and Claude Code's
 * {@code <config dir>/sessions/<pid>.json}. Never throws; anything unreadable means nothing found.
 */
final class TabProcesses {

    static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final Pattern TMUX_NAME = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    /** What was found in a tab. Either tmux, or claude, or nothing. */
    static final class Result {
        String tmuxSession;
        final List<String> tmuxSocketArgs = new ArrayList<>();
        String claudeSessionId;
        /** Null for the default ~/.claude. */
        String claudeConfigDir;
        String claudeCwd;
        String claudeName;
    }

    /** Runs a command and returns its output, or null. Lets tests run tmux their own way. */
    interface Runner {
        String run(List<String> command);
    }

    private TabProcesses() {}

    static Result detect(int shellPid, String homeDir, Runner runner) {
        Result result = new Result();
        if (shellPid <= 0) return result;
        try {
            List<Integer> descendants = descendantsOf(shellPid);

            // 1. tmux client
            for (int pid : descendants) {
                List<String> args = cmdline(pid);
                if (args.isEmpty() || !basename(args.get(0)).equals("tmux")) continue;
                List<String> socket = socketArgs(args);
                List<String> command = new ArrayList<>();
                command.add("tmux");
                command.addAll(socket);
                command.addAll(Arrays.asList("list-clients", "-F", "#{client_pid}\t#{session_name}"));
                String output = runner.run(command);
                if (output == null) continue;
                for (String line : output.split("\n")) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length == 2 && parts[0].trim().equals(String.valueOf(pid)) && TMUX_NAME.matcher(parts[1].trim()).matches()) {
                        result.tmuxSession = parts[1].trim();
                        result.tmuxSocketArgs.addAll(socket);
                        return result;
                    }
                }
            }

            // 2. Claude Code, the most recently started one
            int claudePid = -1;
            long newest = -1;
            for (int pid : descendants) {
                List<String> args = cmdline(pid);
                if (args.isEmpty()) continue;
                String exe = args.get(0);
                if (!exe.contains("/claude/versions/") && !basename(exe).equals("claude")) continue;
                long start = startTime(pid);
                if (start > newest) {
                    newest = start;
                    claudePid = pid;
                }
            }
            if (claudePid > 0) {
                String configDir = environ(claudePid).get("CLAUDE_CONFIG_DIR");
                File dir = configDir != null && !configDir.isEmpty() ? new File(configDir) : new File(homeDir, ".claude");
                File sessionFile = new File(dir, "sessions/" + claudePid + ".json");
                if (sessionFile.isFile()) {
                    JSONObject session = new JSONObject(new String(Files.readAllBytes(sessionFile.toPath()), StandardCharsets.UTF_8));
                    String sessionId = session.optString("sessionId", "");
                    if (UUID.matcher(sessionId).matches()) {
                        result.claudeSessionId = sessionId;
                        String canonical = dir.getCanonicalPath();
                        result.claudeConfigDir = canonical.equals(new File(homeDir, ".claude").getCanonicalPath()) ? null : canonical;
                        result.claudeCwd = session.optString("cwd", null);
                        String name = session.optString("name", "");
                        result.claudeName = name.isEmpty() ? null : name;
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * The ssh client running in the tab, as the arguments that reach the same host (options, then
     * the destination last), or null when the tab isn't connected over SSH. The newest one wins.
     */
    static List<String> sshTarget(int shellPid) {
        if (shellPid <= 0) return null;
        try {
            List<String> best = null;
            long newest = -1;
            for (int pid : descendantsOf(shellPid)) {
                List<String> args = cmdline(pid);
                if (args.isEmpty() || !basename(args.get(0)).equals("ssh")) continue;
                List<String> target = parseSshArgs(args);
                if (target == null) continue;
                long start = startTime(pid);
                if (start > newest) {
                    newest = start;
                    best = target;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** Options of ssh that take a value and pick the host, and so are kept. */
    private static final String KEEP_WITH_VALUE = "pliJFo";
    /** Other ssh options that take a value, which are skipped with it. */
    private static final String SKIP_WITH_VALUE = "BbcDEeILmOQRSWw";

    /** From an ssh command line, the host-picking options and the destination (last). Null if unclear. */
    static List<String> parseSshArgs(List<String> argv) {
        List<String> kept = new ArrayList<>();
        for (int i = 1; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (arg.equals("--")) {
                if (i + 1 >= argv.size()) return null;
                return withDestination(kept, argv.get(i + 1));
            }
            if (!arg.startsWith("-") || arg.length() < 2) return withDestination(kept, arg);
            // A group of flags, like -tt or -4p 22, where the last may take a value
            for (int j = 1; j < arg.length(); j++) {
                char flag = arg.charAt(j);
                boolean keep = KEEP_WITH_VALUE.indexOf(flag) >= 0;
                if (keep || SKIP_WITH_VALUE.indexOf(flag) >= 0) {
                    String value;
                    if (j + 1 < arg.length()) value = arg.substring(j + 1);
                    else if (i + 1 < argv.size()) value = argv.get(++i);
                    else return null;
                    if (keep) {
                        kept.add("-" + flag);
                        kept.add(value);
                    }
                    break;
                }
                if (flag == '4' || flag == '6') kept.add("-" + flag);
            }
        }
        return null;
    }

    private static List<String> withDestination(List<String> kept, String destination) {
        if (destination.startsWith("-")) return null;
        String host = destination.substring(destination.lastIndexOf('@') + 1);
        if (host.isEmpty() || !SshConfigHosts.isSafeHost(destination)) return null;
        kept.add(destination);
        return kept;
    }

    /** All processes under {@code root}, nearest first. */
    static List<Integer> descendantsOf(int root) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                String name = entry.getName();
                if (!name.matches("\\d+")) continue;
                int ppid = parentOf(Integer.parseInt(name));
                if (ppid > 0) children.computeIfAbsent(ppid, k -> new ArrayList<>()).add(Integer.parseInt(name));
            }
        }
        List<Integer> result = new ArrayList<>();
        List<Integer> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            int pid = queue.remove(0);
            List<Integer> kids = children.get(pid);
            if (kids == null) continue;
            for (int kid : kids) {
                if (result.contains(kid)) continue;
                result.add(kid);
                queue.add(kid);
            }
        }
        return result;
    }

    /** Fields after the ")" that ends the command name in /proc/pid/stat. */
    private static String[] statFields(int pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            int close = stat.lastIndexOf(')');
            return close < 0 ? null : stat.substring(close + 2).trim().split("\\s+");
        } catch (Exception e) {
            return null;
        }
    }

    private static int parentOf(int pid) {
        String[] fields = statFields(pid);
        try {
            return fields == null || fields.length < 2 ? -1 : Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long startTime(int pid) {
        String[] fields = statFields(pid);
        try {
            // Field 22 of /proc/pid/stat, the 20th after the command name
            return fields == null || fields.length < 20 ? -1 : Long.parseLong(fields[19]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static List<String> cmdline(int pid) {
        try {
            byte[] bytes = Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath());
            List<String> args = new ArrayList<>();
            for (String arg : new String(bytes, StandardCharsets.UTF_8).split("\0")) if (!arg.isEmpty()) args.add(arg);
            return args;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static Map<String, String> environ(int pid) {
        Map<String, String> env = new HashMap<>();
        try {
            byte[] bytes = Files.readAllBytes(new File("/proc/" + pid + "/environ").toPath());
            for (String entry : new String(bytes, StandardCharsets.UTF_8).split("\0")) {
                int eq = entry.indexOf('=');
                if (eq > 0) env.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        } catch (Exception ignored) {}
        return env;
    }

    /** The -L name / -S path options a tmux client was started with. */
    private static List<String> socketArgs(List<String> args) {
        List<String> socket = new ArrayList<>();
        for (int i = 1; i < args.size() - 1; i++) {
            String arg = args.get(i);
            if (arg.equals("-L") || arg.equals("-S")) {
                socket.add(arg);
                socket.add(args.get(i + 1));
                i++;
            } else if (!arg.startsWith("-")) {
                break; // The tmux command starts here
            }
        }
        return socket;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Run a command with a 2 second limit, for tmux. */
    static String runSimple(ProcessBuilder builder) {
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                return null;
            }
            return process.exitValue() == 0 ? output.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

}
