package com.termux.app.terminal;

import android.content.Context;

import com.termux.shared.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Helpers to run commands in the background with the same environment as Termux sessions. */
final class BackgroundShell {

    private BackgroundShell() {}

    /** A {@link ProcessBuilder} for {@code command} with the Termux environment, in HOME, with stderr merged. */
    static ProcessBuilder newProcessBuilder(Context context, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        for (String entry : TermuxShellUtils.buildEnvironment(context, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            int eq = entry.indexOf('=');
            if (eq > 0) env.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return builder;
    }

    /** Read a pid file and return the pid if that process is still alive, otherwise -1. */
    static int readAlivePid(String pidFilePath) {
        try {
            File pidFile = new File(pidFilePath);
            if (!pidFile.isFile()) return -1;
            String pid = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
            if (!pid.matches("\\d+")) return -1;
            return new File("/proc/" + pid).exists() ? Integer.parseInt(pid) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Last {@code count} non-empty lines of a text file, or an empty string. */
    static String tail(String path, int count) {
        try {
            java.util.List<String> lines = Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8);
            StringBuilder builder = new StringBuilder();
            int added = 0;
            for (int i = lines.size() - 1; i >= 0 && added < count; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                builder.insert(0, line + (added == 0 ? "" : "\n"));
                added++;
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Whether something accepts TCP connections on 127.0.0.1:{@code port}. Call off the main thread. */
    static boolean isLocalPortOpen(int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
