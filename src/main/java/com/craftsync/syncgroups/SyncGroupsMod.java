package com.craftsync.syncgroups;

import com.craftsync.syncgroups.core.Diag;
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
        Path root = gameDir.resolve("syncgroups");
        Diag.init(root.resolve("diagnostics.log"));

        Diag.session("Sync Groups starting (v" + version() + ")");
        Diag.info("ENV: gameDir=%s", gameDir);
        Diag.info("ENV: java=%s os=%s",
                System.getProperty("java.version"), System.getProperty("os.name"));

        config = PathsConfig.loadOrCreate(root.resolve("paths.json"));
        store = new GroupsStore(root.resolve("groups.json"));
        engine = new SyncEngine(gameDir, config, FabricLoader.getInstance()::isModLoaded);
        session = new SessionManager(engine, store, config.periodicPushMinutes * 60_000L);

        Diag.info("CONFIG: periodicPushMinutes=%d storageCloseTimeoutSeconds=%d pushQuiescenceMs=%d",
                config.periodicPushMinutes, config.storageCloseTimeoutSeconds, config.pushQuiescenceMs);
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

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> session.awaitPendingPush(5000), "syncgroups-shutdown"));

        Diag.info("READY: Sync Groups initialized with %d group(s)", store.all().size());
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
