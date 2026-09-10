package com.craftsync.syncgroups;

import com.craftsync.syncgroups.core.GroupsStore;
import com.craftsync.syncgroups.core.SessionManager;
import com.craftsync.syncgroups.core.SyncEngine;
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
        config = PathsConfig.loadOrCreate(gameDir.resolve("syncgroups").resolve("paths.json"));
        store = new GroupsStore(gameDir.resolve("syncgroups").resolve("groups.json"));
        engine = new SyncEngine(gameDir, config, FabricLoader.getInstance()::isModLoaded);
        session = new SessionManager(engine, store, config.periodicPushMinutes * 60_000L);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client != null && client.getCurrentServer() != null) {
                ServerData server = client.getCurrentServer();
                session.onLogin(server.ip, server.name);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> session.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client -> session.tick());

        LOGGER.info("Sync Groups initialized: {} group(s)", store.all().size());
    }
}
