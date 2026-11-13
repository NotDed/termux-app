package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads the host aliases defined in {@code ~/.ssh/config} for the SSH host picker. */
public final class SshConfigHosts {

    /** Host names are passed to a shell command, so only allow safe characters. */
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9._@:-]+$");
    private static final int MAX_INCLUDE_DEPTH = 3;

    private SshConfigHosts() {}

    public static String getSshDirPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh";
    }

    /** Returns the host aliases in order of first appearance. Never throws. */
    @NonNull
    public static List<String> read() {
        Set<String> hosts = new LinkedHashSet<>();
        readFile(new File(getSshDirPath(), "config"), hosts, 0);
        return new ArrayList<>(hosts);
    }

    public static boolean isSafeHost(String host) {
        return host != null && SAFE_HOST.matcher(host).matches();
    }

    private static void readFile(File file, Set<String> hosts, int depth) {
        if (depth > MAX_INCLUDE_DEPTH || !file.isFile() || !file.canRead()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // "Keyword value" or "Keyword=value"
                String[] parts = line.split("[\\s=]+", 2);
                if (parts.length < 2) continue;
                String keyword = parts[0];
                String[] values = parts[1].trim().split("\\s+");

                if (keyword.equalsIgnoreCase("Host")) {
                    for (String value : values) {
                        if (value.contains("*") || value.contains("?") || value.startsWith("!")) continue;
                        if (isSafeHost(value)) hosts.add(value);
                    }
                } else if (keyword.equalsIgnoreCase("Include")) {
                    for (String value : values) {
                        if (value.contains("*") || value.contains("?")) continue;
                        File included;
                        if (value.startsWith("~/"))
                            included = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, value.substring(2));
                        else if (value.startsWith("/"))
                            included = new File(value);
                        else
                            included = new File(getSshDirPath(), value);
                        readFile(included, hosts, depth + 1);
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable config: just show what was collected
        }
    }

}
