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

    /**
     * Bump only when an *existing* built-in default changes semantics in a way
     * stale files must pick up. Purely additive fields need no bump: Gson keeps
     * field initializers for keys missing from the file.
     */
    public static final int CONFIG_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int configVersion = CONFIG_VERSION;
    public int periodicPushMinutes = 5;
    public int storageCloseTimeoutSeconds = 30;
    /** How long a mod's files must stay unchanged before we treat a write as finished. */
    public int pushQuiescenceMs = 1500;
    /** Draw the group tag on server entries in the multiplayer list. */
    public boolean showGroupTags = true;
    /**
     * "copy" — the group folder holds copies that are pulled on join and pushed
     * on leave; local data stays real and self-contained.
     * "symlink" — the local per-server folder becomes a link into the group
     * folder, so every grouped server reads and writes the same files.
     * Symlink mode applies to directory-shaped roots only (Xaero, Bobby, DH);
     * Litematica-style per-file roots always stay on copies.
     */
    public String syncMode = "copy";
    public Map<String, ModSyncSpec> mods = new LinkedHashMap<>();

    public boolean symlinkMode() {
        return "symlink".equalsIgnoreCase(syncMode);
    }

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

        // Save the user's own values, not the v2 defaults, when their file is
        // stale: they may have tuned periodicPushMinutes/quiescence by hand and
        // losing that silently would be worse than the stale defaults we fix.
        Integer keepPush = config == null ? null : config.periodicPushMinutes;
        Integer keepTimeout = config == null ? null : config.storageCloseTimeoutSeconds;
        Integer keepQuiescence = config == null ? null : config.pushQuiescenceMs;
        Boolean keepTags = config == null ? null : config.showGroupTags;
        String keepMode = config == null ? null : config.syncMode;
        Map<String, ModSyncSpec> keepMods = config == null ? null : config.mods;

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
            if (keepPush != null) config.periodicPushMinutes = keepPush;
            if (keepTimeout != null) config.storageCloseTimeoutSeconds = keepTimeout;
            if (keepQuiescence != null) config.pushQuiescenceMs = keepQuiescence;
            if (keepTags != null) config.showGroupTags = keepTags;
            if (keepMode != null) config.syncMode = keepMode;
            if (keepMods != null) config.mods = keepMods;
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

    /** Writes the given config back to disk (settings screen edits). */
    public static void save(Path file, PathsConfig config) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(config));
    }
}
