package com.craftsync.syncgroups.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-editable defaults + overrides for the supported-mod table (spec §2).
 * Written to {@code .minecraft/syncgroups/paths.json} on first run so the user
 * or a pack maintainer can fix paths for a new mod version without a code change.
 */
public final class PathsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int periodicPushMinutes = 5;
    public int storageCloseTimeoutSeconds = 30;
    public Map<String, ModSyncSpec> mods = new LinkedHashMap<>();

    public static PathsConfig loadOrCreate(Path file) {
        PathsConfig config = new PathsConfig();
        if (Files.exists(file)) {
            try {
                PathsConfig parsed = GSON.fromJson(Files.readString(file), PathsConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to parse {}, using built-in defaults", file, e);
            }
        }
        // Fill in any spec missing from the file (new mod added to the mod).
        final PathsConfig effective = config;
        ModSpecs.defaults().forEach(spec -> effective.mods.putIfAbsent(spec.modId, spec));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config));
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", file, e);
        }
        return config;
    }
}
