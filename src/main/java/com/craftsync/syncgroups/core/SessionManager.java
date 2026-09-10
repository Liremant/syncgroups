package com.craftsync.syncgroups.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Tracks the active sync session: which group/server we are on, drives the
 * periodic push (spec §7) and the disconnect push (§4.4). Pure state + engine
 * calls; MC event wiring lives in the client entrypoint.
 */
public final class SessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups/session");

    private final SyncEngine engine;
    private final GroupsStore store;
    private final long periodicPushMs;

    private Group currentGroup;
    private String currentServerKey;
    private String currentServerName;
    private long lastPushAt;

    public SessionManager(SyncEngine engine, GroupsStore store, long periodicPushMs) {
        this.engine = engine;
        this.store = store;
        this.periodicPushMs = periodicPushMs;
    }

    public Optional<Group> currentGroup() {
        return Optional.ofNullable(currentGroup);
    }

    /** Called on login to a server. Returns true when a pull was performed. */
    public boolean onLogin(String address, String serverName) {
        String key = ServerKey.normalize(address);
        Optional<Group> group = store.byServer(key);
        if (group.isEmpty()) {
            return false;
        }
        long start = System.currentTimeMillis();
        try {
            engine.pullForJoin(group.get(), key, serverName);
        } catch (IOException e) {
            LOGGER.error("Join pull failed for {} (group {})", key, group.get().id, e);
            return false;
        }
        currentGroup = group.get();
        currentServerKey = key;
        currentServerName = serverName;
        lastPushAt = System.currentTimeMillis();
        LOGGER.info("Sync Group '{}' pulled in {} ms", group.get().tag, System.currentTimeMillis() - start);
        return true;
    }

    /** Called on END_CLIENT_TICK; fires the periodic push on a worker thread. */
    public void tick() {
        if (currentGroup == null || System.currentTimeMillis() - lastPushAt < periodicPushMs) {
            return;
        }
        Group group = currentGroup;
        String key = currentServerKey;
        String name = currentServerName;
        lastPushAt = System.currentTimeMillis();
        Thread worker = new Thread(() -> {
            try {
                engine.push(group, key, name, false);
            } catch (IOException e) {
                LOGGER.error("Periodic push failed for {} (group {})", key, group.id, e);
            }
        }, "syncgroups-periodic-push");
        worker.setDaemon(true);
        worker.start();
    }

    /** Called on disconnect — must run the push before the game tears down world dirs. */
    public void onDisconnect() {
        if (currentGroup == null) {
            return;
        }
        Group group = currentGroup;
        String key = currentServerKey;
        String name = currentServerName;
        currentGroup = null;
        currentServerKey = null;
        currentServerName = null;
        try {
            boolean ok = engine.push(group, key, name, true);
            LOGGER.info("Disconnect push {} for group '{}'", ok ? "ok" : "partial", group.tag);
        } catch (IOException e) {
            LOGGER.error("Disconnect push failed for {} (group {})", key, group.id, e);
        }
    }
}
