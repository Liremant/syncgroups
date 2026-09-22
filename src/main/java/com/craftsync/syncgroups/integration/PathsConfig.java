package com.craftsync.syncgroups.integration;

import com.craftsync.syncgroups.core.Diag;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-editable defaults + overrides for the supported-mod table (spec §2).
 * Written to {@code .minecraft/syncgroups/paths.json} on first run so the user
 * or a pack maintainer can fix paths for a new mod version without a code change.
 *
 * Bumping {@link #CONFIG_VERSION} regenerates the file (previous one is kept as
 * a .bak) so that corrected built-in paths — e.g. Xaero's move to probe-lock —
 * actually reach existing installs instead of being shadowed by stale values.
 */
public final class PathsConfig {

    /** Bump when built-in defaults change in a way existing files must pick up. */
    public static final int CONFIG_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int configVersion = CONFIG_VERSION;
    public int periodicPushMinutes = 5;
    public int storageCloseTimeoutSeconds = 30;
    /** How long a mod's files must stay unchanged before we treat a write as finished. */
    public int pushQuiescenceMs = 1500;
    public Map<String, ModSyncSpec> mods = new LinkedHashMap<>();

    public static PathsConfig loadOrCreate(Path file) {
        PathsConfig config = null;
        boolean stale = false;

        if (Files.exists(file)) {
            try {
                PathsConfig parsed = GSON.fromJson(Files.readString(file), PathsConfig.class);
                if (parsed != null) {
                    config = parsed;
                    stale = parsed.configVersion != CONFIG_VERSION;
                }
            } catch (Exception e) {
                Diag.error("CONFIG: failed to parse " + file + " — regenerating from built-in defaults", e);
            }
        }

        if (config == null || stale) {
            if (config != null) {
                Path backup = file.resolveSibling("paths.v" + config.configVersion + ".bak");
                try {
                    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    Diag.warn("CONFIG: paths.json was v%d, current is v%d — regenerated "
                                    + "(previous file kept at %s). Re-apply any manual path edits.",
                            config.configVersion, CONFIG_VERSION, backup.getFileName());
                } catch (IOException e) {
                    Diag.error("CONFIG: could not back up old paths.json", e);
                }
            }
            config = new PathsConfig();
        }

        // Fill in any spec missing from the file (new mod added to the mod).
        PathsConfig effective = config;
        ModSpecs.defaults().forEach(spec -> effective.mods.putIfAbsent(spec.modId, spec));

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config));
        } catch (IOException e) {
            Diag.error("CONFIG: failed to write " + file, e);
        }
        return config;
    }
}
