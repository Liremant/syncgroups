package com.craftsync.syncgroups.core;

import com.craftsync.syncgroups.integration.PathsConfig;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Tracks the active sync session: which group/server we are on, drives the
 * periodic push (spec §7) and the disconnect push (§4.4). Pure state + engine
 * calls; MC event wiring lives in the client entrypoint.
 *
 * Every branch here is logged through {@link Diag} — when sync silently does
 * nothing on someone else's machine, this is the only evidence we get.
 *
 * The join hooks fire on different threads (render thread for ConnectScreen,
 * netty for INIT), and the push runs on its own thread. State transitions are
 * guarded by the monitor; the pull itself deliberately is not, because a pull
 * can take seconds and the render thread reads this state every tick.
 */
public final class SessionManager {

    private final SyncEngine engine;
    private final GroupsStore store;
    private final PathsConfig config;

    private Group currentGroup;
    private String currentServerKey;
    private String currentServerName;
    private long lastPushAt;
    /** Read from the INIT/JOIN/tick hooks without holding the monitor. */
    private volatile boolean pullDone;
    /** Key whose pull is running right now, so a second hook does not repeat it. */
    private String pullingFor;
    private volatile Thread pushThread;
    /** Set when a mode switch was deferred because a session was open. */
    private boolean modePending;
    /** Serialises pushes: the periodic one and the disconnect one must not overlap on the same files. */
    private final Object pushLock = new Object();

    public SessionManager(SyncEngine engine, GroupsStore store, PathsConfig config) {
        this.engine = engine;
        this.store = store;
        this.config = config;
    }

    public synchronized Optional<Group> currentGroup() {
        return Optional.ofNullable(currentGroup);
    }

    public boolean pullDone() {
        return pullDone;
    }

    /** True while the player is on a grouped server — conversion must not run then. */
    public synchronized boolean sessionActive() {
        return currentGroup != null;
    }

    /**
     * True when the sync mode was changed while a session was open, so the
     * on-disk conversion is still owed. Cleared once a conversion has run for the
     * mode currently in the config.
     */
    public synchronized boolean modePending() {
        return modePending;
    }

    public synchronized void setModePending(boolean pending) {
        modePending = pending;
    }

    /** Called before connecting (ConnectScreen) or at INIT as a fallback. */
    public boolean onLogin(String address, String serverName) {
        if (address == null || address.isBlank()) {
            Diag.warn("JOIN: empty address (name='%s') — nothing to do", serverName);
            return false;
        }
        String key = ServerKey.normalize(address);
        Group group;
        synchronized (this) {
            List<Group> all = store.all();
            Diag.info("JOIN: address='%s' name='%s' -> key='%s'; known groups=%d, servers=%s",
                    address, serverName, key, all.size(), allServers(all));

            if (pullDone && key.equals(currentServerKey)) {
                Diag.info("JOIN: already pulled for key='%s' in this session — skipping duplicate pull", key);
                return true;
            }
            if (key.equals(pullingFor)) {
                Diag.info("JOIN: pull for key='%s' is already running — not repeating it", key);
                return false;
            }
            Optional<Group> match = store.byServer(key);
            if (match.isEmpty()) {
                Diag.info("JOIN: no group contains key='%s' — sync not applicable for this server", key);
                // A connect that never reached a world fires no DISCONNECT: without
                // this, that stale session would keep pushing and would block the
                // deferred mode conversion for the server we are actually on.
                clearSessionLocked();
                return false;
            }
            Diag.info("JOIN: matched group '%s' (id=%s, color=%s) for key='%s'",
                    match.get().tag, match.get().id, match.get().color, key);
            group = match.get();
            pullingFor = key;
        }

        long start = System.currentTimeMillis();
        try {
            engine.pullForJoin(group, key, serverName);
        } catch (Exception e) {
            Diag.error("JOIN: pull FAILED for key='" + key + "' group=" + group.id, e);
            synchronized (this) {
                pullingFor = null;
            }
            return false;
        }
        synchronized (this) {
            pullingFor = null;
            currentGroup = group;
            currentServerKey = key;
            currentServerName = serverName;
            lastPushAt = System.currentTimeMillis();
            pullDone = true;
        }
        // Keep the name on record: name-keyed mods (Distant Horizons) and crash
        // recovery need it for servers that are not the current session.
        if (group.rememberName(key, serverName)) {
            try {
                store.update(group);
                Diag.info("JOIN: remembered server name '%s' for key='%s'", serverName, key);
            } catch (IOException e) {
                Diag.error("JOIN: could not persist server name for key=" + key, e);
            }
        }
        Diag.info("JOIN: pull finished in %d ms for group '%s'",
                System.currentTimeMillis() - start, group.tag);
        return true;
    }

    private void clearSessionLocked() {
        if (currentGroup != null) {
            Diag.warn("JOIN: dropping the session for key='%s' — no group matches it any more",
                    currentServerKey);
        }
        currentGroup = null;
        currentServerKey = null;
        currentServerName = null;
        pullDone = false;
    }

    private static String allServers(List<Group> groups) {
        StringBuilder sb = new StringBuilder("[");
        for (Group g : groups) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(g.id).append(':').append(g.servers);
        }
        return sb.append(']').toString();
    }

    /** Called on END_CLIENT_TICK; fires the periodic push on a worker thread. */
    public synchronized void tick() {
        // Read live: the settings screen changes this value and the tooltip promises
        // it takes effect, not "on the next launch".
        long periodicPushMs = Math.max(1, config.periodicPushMinutes) * 60_000L;
        if (currentGroup == null || System.currentTimeMillis() - lastPushAt < periodicPushMs) {
            return;
        }
        Group group = currentGroup;
        String key = currentServerKey;
        String name = currentServerName;
        lastPushAt = System.currentTimeMillis();
        Diag.info("PUSH(periodic): starting for key='%s' group='%s'", key, group.tag);
        runAsync(() -> {
            synchronized (pushLock) {
                try {
                    engine.push(group, key, name, false);
                } catch (Exception e) {
                    Diag.error("PUSH(periodic): failed for key=" + key, e);
                }
            }
        });
    }

    /** Called on disconnect. Runs off-thread so the UI never freezes. */
    public synchronized void onDisconnect() {
        if (currentGroup == null) {
            Diag.info("DISCONNECT: no active sync session — nothing to push");
            return;
        }
        Group group = currentGroup;
        String key = currentServerKey;
        String name = currentServerName;
        clearSessionLocked();
        Diag.info("DISCONNECT: pushing for key='%s' group='%s'", key, group.tag);
        runAsync(() -> {
            synchronized (pushLock) {
                try {
                    boolean ok = engine.push(group, key, name, true);
                    Diag.info("DISCONNECT: push %s for group '%s'", ok ? "ok" : "partial", group.tag);
                } catch (Exception e) {
                    Diag.error("DISCONNECT: push failed for key=" + key, e);
                }
            }
        });
    }

    private void runAsync(Runnable r) {
        Thread t = new Thread(r, "syncgroups-push");
        t.setDaemon(true);
        pushThread = t;
        t.start();
    }

    /** Best-effort: let an in-flight push finish before the JVM goes away. */
    public void awaitPendingPush(long timeoutMs) {
        Thread t = pushThread;
        if (t == null || !t.isAlive()) {
            return;
        }
        Diag.info("SHUTDOWN: waiting up to %d ms for in-flight push", timeoutMs);
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Diag.info("SHUTDOWN: push thread %s", t.isAlive() ? "still running (abandoned)" : "finished");
    }
}
