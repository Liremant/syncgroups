package com.craftsync.syncgroups;

import com.craftsync.syncgroups.core.Diag;
import com.craftsync.syncgroups.core.DirOps;
import com.craftsync.syncgroups.core.GroupsStore;
import com.craftsync.syncgroups.core.SessionManager;
import com.craftsync.syncgroups.core.SyncEngine;
import com.craftsync.syncgroups.integration.ModSyncSpec;
import com.craftsync.syncgroups.integration.PathsConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SyncGroupsMod implements ClientModInitializer {

    public static final String MOD_ID = "syncgroups";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PathsConfig config;
    private static GroupsStore store;
    private static SyncEngine engine;
    private static SessionManager session;
    /** Set when INIT could not resolve the target server; retried on the next tick. */
    private static volatile boolean pendingPull;
    /** Background sync-mode conversion, joined on shutdown so a switch is not cut in half. */
    private static volatile Thread modeThread;

    public static PathsConfig config() {
        return config;
    }

    public static GroupsStore store() {
        return store;
    }

    public static SyncEngine engine() {
        return engine;
    }

    public static SessionManager session() {
        return session;
    }

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path root = gameDir.resolve("config").resolve("syncgroups");
        Diag.init(root.resolve("diagnostics.log"));

        Diag.session("Sync Groups starting (v" + version() + ")");
        Diag.info("ENV: gameDir=%s", gameDir);
        Diag.info("ENV: java=%s os=%s",
                System.getProperty("java.version"), System.getProperty("os.name"));

        migrateLegacyRoot(gameDir, root);

        config = PathsConfig.loadOrCreate(root.resolve("paths.json"));
        store = new GroupsStore(root.resolve("groups.json"));
        engine = new SyncEngine(gameDir, root, config, FabricLoader.getInstance()::isModLoaded);
        session = new SessionManager(engine, store, config.periodicPushMinutes * 60_000L);

        Diag.info("CONFIG: periodicPushMinutes=%d storageCloseTimeoutSeconds=%d pushQuiescenceMs=%d "
                        + "syncMode=%s showGroupTags=%s",
                config.periodicPushMinutes, config.storageCloseTimeoutSeconds, config.pushQuiescenceMs,
                config.syncMode, config.showGroupTags);
        if (config.symlinkMode()) {
            String problem = DirOps.probeSymlinkSupport(root.resolve(".probe"));
            if (problem == null) {
                Diag.info("CONFIG: symlink support OK — symlink mode will be used for directory-shaped roots");
            } else {
                Diag.warn("CONFIG: symlink mode is ON but this system cannot create symlinks (%s). "
                        + "On Windows enable Developer Mode or run as administrator, or switch syncMode "
                        + "back to \"copy\" in paths.json.", problem);
            }
        }
        for (ModSyncSpec spec : config.mods.values()) {
            boolean installed = FabricLoader.getInstance().isModLoaded(spec.modId);
            Diag.info("CONFIG: mod '%s' installed=%s closeWait=%s integrity=%s roots=%d",
                    spec.modId, installed, spec.closeWait, spec.integrity, spec.roots.size());
            for (ModSyncSpec.Root r : spec.roots) {
                Diag.info("        root path='%s' mode='%s' pattern='%s'", r.path, r.mode, r.pattern);
            }
        }
        Diag.info("GROUPS: %d group(s) loaded from %s", store.all().size(), root.resolve("groups.json"));
        store.all().forEach(g -> Diag.info("        group id=%s tag='%s' servers=%s",
                g.id, g.tag, g.servers));
        Diag.info("ACTIVE: %d mod spec(s) will sync: %s", engine.activeSpecs().size(),
                engine.activeSpecs().stream().map(s -> s.modId).toList());

        // Primary hook is the ConnectScreen mixin (before any packet). This INIT
        // handler is the fallback for paths that skip startConnecting.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            ServerData fromHandler = null;
            try {
                fromHandler = handler.getServerData();
            } catch (Throwable t) {
                Diag.warn("INIT: handler.getServerData() threw: %s", t);
            }
            ServerData fromClient = client == null ? null : client.getCurrentServer();
            Diag.info("INIT: handler.serverData=%s client.currentServer=%s pullDone=%s",
                    describe(fromHandler), describe(fromClient), session.pullDone());
            ServerData server = fromHandler != null ? fromHandler : fromClient;
            if (server == null) {
                Diag.warn("INIT: no ServerData available yet — pull deferred to client tick");
                pendingPull = true;
                return;
            }
            session.onLogin(server.ip, server.name);
            pendingPull = false;
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!session.pullDone()) {
                Diag.warn("JOIN: reached the world without a successful pull — sync did NOT apply "
                        + "for this session (mods may already hold stale in-memory data)");
            }
            // The mode was switched mid-session and could not be converted then
            // (mods held their storage). If this server is not synced at all, that
            // conversion is still due — run it now, off the render thread.
            if (!session.sessionActive() && session.modePending()) {
                Diag.info("JOIN: on an ungrouped server with a pending mode change — converting");
                reconcileSyncModeAsync();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            session.onDisconnect();
            pendingPull = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingPull && !session.pullDone()) {
                ServerData data = client == null ? null : client.getCurrentServer();
                if (data != null) {
                    Diag.warn("TICK: running deferred pull for '%s'/'%s' (late — a mod may have "
                            + "opened its storage already)", data.name, data.ip);
                    session.onLogin(data.ip, data.name);
                    pendingPull = false;
                }
            }
            session.tick();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.awaitPendingPush(5000);
            awaitModeConversion(5000);
        }, "syncgroups-shutdown"));

        // Bring the on-disk layout in line with the configured mode (spec §4.5).
        // Idempotent, and it repairs leftovers from a switch made mid-session.
        // Off the render thread: materialising a group back into copies can mean
        // hundreds of MB (Bobby regions, Distant Horizons databases) and must not
        // stall the loading screen. A join that races it waits on the engine's
        // per-folder lock, which is bounded by one server's data.
        reconcileSyncModeAsync();

        Diag.info("READY: Sync Groups initialized with %d group(s)", store.all().size());
    }

    private static void reconcileSyncModeAsync() {
        Thread t = new Thread(SyncGroupsMod::reconcileSyncMode, "syncgroups-mode-convert");
        t.setDaemon(true);
        modeThread = t;
        t.start();
    }

    /**
     * Makes every group's folders match {@code syncMode}. Runs off the render
     * thread (startup and settings changes); the join pull is serialised with it.
     */
    private static void reconcileSyncMode() {
        boolean symlink = config.symlinkMode();
        for (com.craftsync.syncgroups.core.Group g : store.all()) {
            // Publish whatever a crashed session left behind *before* converting:
            // in symlink mode the local folder is about to be shadowed by a link,
            // and in copy mode the group copy may be the stale one.
            engine.recoverDirtyMarkers(g);
            String summary = engine.convertMode(g);
            Diag.info("MODE: group '%s' -> %s (%s)", g.tag, symlink ? "symlink" : "copy", summary);
        }
        // Only clear the pending flag if the mode is still the one we converted to
        // (the user may have flipped it again while this ran).
        if (config.symlinkMode() == symlink) {
            session.setModePending(false);
        } else {
            Diag.warn("MODE: mode changed again during conversion — a further conversion is due");
        }
        Diag.info("MODE: conversion to %s finished", symlink ? "symlink" : "copy");
    }

    /** Best-effort: let an in-flight mode conversion finish before the JVM exits. */
    private static void awaitModeConversion(long timeoutMs) {
        Thread t = modeThread;
        if (t == null || !t.isAlive()) {
            return;
        }
        Diag.info("SHUTDOWN: waiting up to %d ms for the mode conversion", timeoutMs);
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Applies a sync-mode change from the settings screen: persist it, then
     * convert the existing folders. While a session is open the mods have their
     * storage in use, so the change is only recorded and the conversion happens
     * on the next launch instead of moving folders under a running mod.
     *
     * @return which message to show: "applied.symlink", "applied.copy" or "deferred"
     */
    public static String applySyncMode(boolean symlink) {
        config.syncMode = symlink ? "symlink" : "copy";
        saveConfig();
        if (session.sessionActive()) {
            Diag.warn("MODE: switched to %s while a session is open — existing folders will be "
                    + "converted on the next game launch", config.syncMode);
            session.setModePending(true);
            return "deferred";
        }
        reconcileSyncModeAsync();
        return symlink ? "applied.symlink" : "applied.copy";
    }

    /**
     * The data folder used to live directly in the game dir; it now lives under
     * {@code config/} like every other mod. Move it once, rather than leaving two
     * half-populated trees and silently starting with no groups.
     */
    private static void migrateLegacyRoot(Path gameDir, Path newRoot) {
        Path oldRoot = gameDir.resolve("syncgroups");
        if (!Files.isDirectory(oldRoot) || Files.exists(newRoot)) {
            return;
        }
        try {
            Files.createDirectories(newRoot.getParent());
            Files.move(oldRoot, newRoot);
            Diag.info("MIGRATE: moved %s -> %s", oldRoot, newRoot);
        } catch (IOException e) {
            Diag.error("MIGRATE: could not move " + oldRoot + " to " + newRoot
                    + " — continuing with the new location, old data left untouched", e);
        }
    }

    /** Persists config edits made in the Mod Menu screen. */
    public static void saveConfig() {
        try {
            PathsConfig.save(FabricLoader.getInstance().getGameDir()
                    .resolve("config").resolve("syncgroups").resolve("paths.json"), config);
        } catch (Exception e) {
            Diag.error("CONFIG: failed to save from settings screen", e);
        }
    }

    private static String describe(ServerData data) {
        return data == null ? "null" : "'" + data.name + "'/" + data.ip;
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
