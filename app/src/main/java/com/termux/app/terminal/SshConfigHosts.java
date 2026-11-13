package com.termux.app.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the hosts defined in {@code ~/.ssh/config} for the SSH host picker. */
public final class SshConfigHosts {

    /** Host names are passed to a shell command, so only allow safe characters. */
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9._@:-]+$");
    /** A ProxyCommand going through a local SOCKS proxy, like {@code nc -X 5 -x 127.0.0.1:1080 %h %p}. */
    private static final Pattern LOCAL_SOCKS = Pattern.compile("-x\\s*(?:127\\.0\\.0\\.1|localhost):(\\d+)");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final int MAX_INCLUDE_DEPTH = 3;
    private static final int PROBE_TIMEOUT_MS = 3000;

    public enum Reachability { CHECKING, REACHABLE, UNREACHABLE, NEEDS_VPN, UNKNOWN }

    /** One {@code Host} alias with the settings needed to probe it. */
    public static final class HostEntry {
        public final String alias;
        @Nullable public String hostName;
        public int port = -1;
        @Nullable public String proxyCommand;
        @Nullable public String proxyJump;

        HostEntry(String alias) {
            this.alias = alias;
        }

        public String getTarget() {
            return hostName != null && !hostName.contains("%") ? hostName : alias;
        }

        public int getPort() {
            return port > 0 ? port : 22;
        }

        /** The local SOCKS proxy port this host goes through, or -1. */
        public int getLocalSocksPort() {
            if (proxyCommand == null) return -1;
            Matcher matcher = LOCAL_SOCKS.matcher(proxyCommand);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        /** A short description of how the host is reached. */
        public String describe() {
            if (getLocalSocksPort() > 0) return "via VPN · " + getTarget();
            if (proxyCommand != null && proxyCommand.contains("cloudflared")) return "via cloudflared · " + getTarget();
            if (proxyJump != null) return "via " + proxyJump;
            return getTarget() + (getPort() != 22 ? ":" + getPort() : "");
        }
    }

    private SshConfigHosts() {}

    public static String getSshDirPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh";
    }

    public static boolean isSafeHost(String host) {
        return host != null && SAFE_HOST.matcher(host).matches();
    }

    /** Returns the hosts in order of first appearance. Never throws. */
    @NonNull
    public static List<HostEntry> read() {
        Map<String, HostEntry> hosts = new LinkedHashMap<>();
        readFile(new File(getSshDirPath(), "config"), hosts, 0);
        return new ArrayList<>(hosts.values());
    }

    private static void readFile(File file, Map<String, HostEntry> hosts, int depth) {
        if (depth > MAX_INCLUDE_DEPTH || !file.isFile() || !file.canRead()) return;

        // Entries of the current Host block. Like ssh, the first value of a setting wins.
        List<HostEntry> current = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // "Keyword value" or "Keyword=value"
                String[] parts = line.split("[\\s=]+", 2);
                if (parts.length < 2) continue;
                String keyword = parts[0].toLowerCase();
                String value = parts[1].trim();

                switch (keyword) {
                    case "host":
                        current = new ArrayList<>();
                        for (String alias : value.split("\\s+")) {
                            if (alias.contains("*") || alias.contains("?") || alias.startsWith("!")) continue;
                            if (!isSafeHost(alias)) continue;
                            HostEntry entry = hosts.get(alias);
                            if (entry == null) {
                                entry = new HostEntry(alias);
                                hosts.put(alias, entry);
                            }
                            current.add(entry);
                        }
                        break;
                    case "match":
                        current = new ArrayList<>();
                        break;
                    case "hostname":
                        for (HostEntry e : current) if (e.hostName == null) e.hostName = value;
                        break;
                    case "port":
                        try {
                            int port = Integer.parseInt(value);
                            for (HostEntry e : current) if (e.port < 0) e.port = port;
                        } catch (NumberFormatException ignored) {}
                        break;
                    case "proxycommand":
                        for (HostEntry e : current) if (e.proxyCommand == null) e.proxyCommand = value;
                        break;
                    case "proxyjump":
                        for (HostEntry e : current) if (e.proxyJump == null) e.proxyJump = value;
                        break;
                    case "include":
                        for (String path : value.split("\\s+")) {
                            if (path.contains("*") || path.contains("?")) continue;
                            File included;
                            if (path.startsWith("~/"))
                                included = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, path.substring(2));
                            else if (path.startsWith("/"))
                                included = new File(path);
                            else
                                included = new File(getSshDirPath(), path);
                            readFile(included, hosts, depth + 1);
                        }
                        break;
                }
            }
        } catch (Exception ignored) {
            // Unreadable config: just show what was collected
        }
    }

    /**
     * Best effort check of whether a host can be reached, following how ssh would get there.
     * Blocks for up to a few seconds, so call it off the main thread.
     */
    public static Reachability probe(HostEntry entry) {
        String target = entry.getTarget();

        int socksPort = entry.getLocalSocksPort();
        if (socksPort > 0) {
            if (!tcp("127.0.0.1", socksPort, 500)) return Reachability.NEEDS_VPN;
            InetSocketAddress address = IPV4.matcher(target).matches()
                ? new InetSocketAddress(target, entry.getPort())
                : InetSocketAddress.createUnresolved(target, entry.getPort());
            try (Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort)))) {
                socket.connect(address, PROBE_TIMEOUT_MS);
                return Reachability.REACHABLE;
            } catch (Exception e) {
                return Reachability.UNREACHABLE;
            }
        }

        if (entry.proxyCommand != null) {
            if (entry.proxyCommand.contains("cloudflared"))
                return tcp(target, 443, PROBE_TIMEOUT_MS) ? Reachability.REACHABLE : Reachability.UNREACHABLE;
            return Reachability.UNKNOWN;
        }

        if (entry.proxyJump != null) {
            // First hop of "user@host:port,other"
            String jump = entry.proxyJump.split(",")[0];
            if (jump.equalsIgnoreCase("none")) return tcp(target, entry.getPort(), PROBE_TIMEOUT_MS) ? Reachability.REACHABLE : Reachability.UNREACHABLE;
            int at = jump.lastIndexOf('@');
            if (at >= 0) jump = jump.substring(at + 1);
            int port = 22;
            int colon = jump.lastIndexOf(':');
            if (colon > 0 && jump.indexOf(':') == colon) {
                try {
                    port = Integer.parseInt(jump.substring(colon + 1));
                    jump = jump.substring(0, colon);
                } catch (NumberFormatException ignored) {}
            }
            return tcp(jump, port, PROBE_TIMEOUT_MS) ? Reachability.REACHABLE : Reachability.UNREACHABLE;
        }

        return tcp(target, entry.getPort(), PROBE_TIMEOUT_MS) ? Reachability.REACHABLE : Reachability.UNREACHABLE;
    }

    private static boolean tcp(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
