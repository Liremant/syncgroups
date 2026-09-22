package com.craftsync.syncgroups.core;

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
 */
public final class SessionManager {

    private final SyncEngine engine;
    private final GroupsStore store;
    private final long periodicPushMs;

    private Group currentGroup;
    private String currentServerKey;
    private String currentServerName;
    private long lastPushAt;
    private boolean pullDone;
    private volatile Thread pushThread;

    public SessionManager(SyncEngine engine, GroupsStore store, long periodicPushMs) {
        this.engine = engine;
        this.store = store;
        this.periodicPushMs = periodicPushMs;
    }

    public Optional<Group> currentGroup() {
        return Optional.ofNullable(currentGroup);
    }

    public boolean pullDone() {
        return pullDone;
    }

    /** Called before connecting (ConnectScreen) or at INIT as a fallback. */
    public synchronized boolean onLogin(String address, String serverName) {
        if (address == null || address.isBlank()) {
            Diag.warn("JOIN: empty address (name='%s') — nothing to do", serverName);
            return false;
        }
        String key = ServerKey.normalize(address);
        List<Group> all = store.all();
        Diag.info("JOIN: address='%s' name='%s' -> key='%s'; known groups=%d, servers=%s",
                address, serverName, key, all.size(), allServers(all));

        if (pullDone && key.equals(currentServerKey)) {
            Diag.info("JOIN: already pulled for key='%s' in this session — skipping duplicate pull", key);
            return true;
        }

        Optional<Group> group = store.byServer(key);
        if (group.isEmpty()) {
            Diag.info("JOIN: no group contains key='%s' — sync not applicable for this server", key);
            return false;
        }
        Diag.info("JOIN: matched group '%s' (id=%s, color=%s) for key='%s'",
                group.get().tag, group.get().id, group.get().color, key);

        long start = System.currentTimeMillis();
        try {
            engine.pullForJoin(group.get(), key, serverName);
        } catch (Exception e) {
            Diag.error("JOIN: pull FAILED for key='" + key + "' group=" + group.get().id, e);
            return false;
        }
        currentGroup = group.get();
        currentServerKey = key;
        currentServerName = serverName;
        lastPushAt = System.currentTimeMillis();
        pullDone = true;
        Diag.info("JOIN: pull finished in %d ms for group '%s'",
                System.currentTimeMillis() - start, group.get().tag);
        return true;
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
        if (currentGroup == null || System.currentTimeMillis() - lastPushAt < periodicPushMs) {
            return;
        }
        Group group = currentGroup;
        String key = currentServerKey;
        String name = currentServerName;
        lastPushAt = System.currentTimeMillis();
        Diag.info("PUSH(periodic): starting for key='%s' group='%s'", key, group.tag);
        runAsync(() -> {
            try {
                engine.push(group, key, name, false);
            } catch (Exception e) {
                Diag.error("PUSH(periodic): failed for key=" + key, e);
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
        currentGroup = null;
        currentServerKey = null;
        currentServerName = null;
        pullDone = false;
        Diag.info("DISCONNECT: pushing for key='%s' group='%s'", key, group.tag);
        runAsync(() -> {
            try {
                boolean ok = engine.push(group, key, name, true);
                Diag.info("DISCONNECT: push %s for group '%s'", ok ? "ok" : "partial", group.tag);
            } catch (Exception e) {
                Diag.error("DISCONNECT: push failed for key=" + key, e);
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
