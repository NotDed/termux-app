package com.termux.app.terminal;

import com.termux.terminal.TerminalSession;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sessions that rang the terminal bell while not being looked at, like a Claude Code prompt
 * waiting for input. Their tab shows a red dot until they are shown. Main thread only.
 */
public final class SessionAttention {

    private static final Map<TerminalSession, Boolean> ATTENTION = new WeakHashMap<>();

    private SessionAttention() {}

    public static void mark(TerminalSession session) {
        if (session != null) ATTENTION.put(session, Boolean.TRUE);
    }

    public static void clear(TerminalSession session) {
        if (session != null) ATTENTION.remove(session);
    }

    public static boolean has(TerminalSession session) {
        return session != null && ATTENTION.containsKey(session);
    }

}
