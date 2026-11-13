package com.termux.app.terminal;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.util.Map;
import java.util.WeakHashMap;

/** Extra info about sessions opened from the tab bar, like the SSH host. Main thread only. */
public final class SessionMeta {

    public final String host;
    public final boolean tmux;

    private static final Map<TerminalSession, SessionMeta> META = new WeakHashMap<>();

    private SessionMeta(String host, boolean tmux) {
        this.host = host;
        this.tmux = tmux;
    }

    static void putSsh(TerminalSession session, String host, boolean tmux) {
        if (session != null) META.put(session, new SessionMeta(host, tmux));
    }

    @Nullable
    public static SessionMeta get(TerminalSession session) {
        return session == null ? null : META.get(session);
    }

}
