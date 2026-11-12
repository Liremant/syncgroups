package com.craftsync.syncgroups.integration;

import com.craftsync.syncgroups.core.Diag;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * User-editable defaults + overrides for the supported-mod table (spec §2).
 * Written to {@code config/syncgroups/paths.json} on first run so the user
 * or a pack maintainer can fix paths for a new mod version without a code change.
 *
 * Bumping {@link #CONFIG_VERSION} makes the file regenerate (the previous one is
 * kept as a .bak) because Gson would otherwise keep whatever the old file had.
 * A spec already in the file keeps its roots, so a bump only brings the built-in
 * defaults to mods that are new — or, per {@link #PREVIOUSLY_ROOTLESS}, that a
 * previous table shipped rootless. A cleared root is a user's opt-out.
 */
public final class PathsConfig {

    /**
     * Bump only when an *existing* built-in default changes semantics in a way
     * stale files must pick up. Purely additive fields need no bump: Gson keeps
     * field initializers for keys missing from the file.
     */
    public static final int CONFIG_VERSION = 3;

    /**
     * Mods that shipped with empty {@code roots} in a previous built-in table —
     * Voxy used to be unsupported — so a stale file has to gain their roots.
     * Any other rootless spec is a user's deliberate opt-out and is left alone.
     * Only files older than the version that introduced the roots are migrated;
     * otherwise every future bump would resurrect an opt-out again.
     */
    private static final Set<String> PREVIOUSLY_ROOTLESS = Set.of("voxy");

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
        // Files without the key predate it: the key first shipped in v2.
        int fileVersion = 1;
        boolean stale = false;

        if (Files.exists(file)) {
            try {
                JsonObject raw = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                PathsConfig parsed = GSON.fromJson(raw, PathsConfig.class);
                if (parsed != null) {
                    config = parsed;
                    // Staleness must be read from the raw JSON: Gson leaves the
                    // field initializer in place for a missing key, so an old
                    // file would report the current version and never migrate.
                    if (raw.has("configVersion")) {
                        fileVersion = parsed.configVersion;
                    }
                    stale = fileVersion != CONFIG_VERSION;
                }
            } catch (Exception e) {
                Diag.error("CONFIG: failed to parse " + file + " — regenerating from built-in defaults", e);
            }
        }

        if (config == null || stale) {
            // Keep the parsed values (Gson leaves every field initialized) and
            // only stamp the current version; a fresh instance would lose a
            // field added without remembering to copy it here.
            if (config == null) {
                config = new PathsConfig();
            }
            config.configVersion = CONFIG_VERSION;
            if (Files.exists(file)) {
                Path backup = file.resolveSibling("paths.v" + fileVersion + ".bak");
                try {
                    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    Diag.warn("CONFIG: paths.json was v%d, current is v%d — regenerated "
                                    + "(previous file kept at %s). Paths already in the file were kept.",
                            fileVersion, CONFIG_VERSION, backup.getFileName());
                } catch (IOException e) {
                    // Never overwrite a file we could not preserve.
                    Diag.error("CONFIG: could not back up " + file + " — leaving it untouched", e);
                    return config;
                }
            }
        }

        // Fill in any spec missing from the file (new mod added to the mod). On a
        // file from an older default table, a spec this table shipped rootless
        // (Voxy) gets its roots back, otherwise the update silently does nothing
        // for existing installs. Everything else keeps its roots: clearing them
        // is how a user opts out, and the next join would pull the group's older
        // copy over the local data with no backup.
        if (config.mods == null) {
            config.mods = new LinkedHashMap<>();
        }
        // A hand-edited "roots": null would otherwise NPE on startup and on
        // every join, including for specs this table does not know.
        config.mods.values().forEach(spec -> {
            if (spec.roots == null) {
                spec.roots = new ArrayList<>();
            }
        });
        // Voxy's roots first shipped in v3; only older files migrate.
        boolean migrateRoots = stale && fileVersion < 3;
        PathsConfig effective = config;
        ModSpecs.defaults().forEach(spec -> effective.mods.compute(spec.modId, (id, existing) -> {
            if (existing == null) {
                return spec;
            }
            if (migrateRoots && PREVIOUSLY_ROOTLESS.contains(id) && existing.roots.isEmpty()) {
                return spec;
            }
            return existing;
        }));

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
