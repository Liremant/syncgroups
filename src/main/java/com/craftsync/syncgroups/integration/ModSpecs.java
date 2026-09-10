package com.craftsync.syncgroups.integration;

import java.util.List;

/**
 * Built-in table of supported mods (spec §2), verified against current mod
 * sources/bytecode (Sept 2026). The user-editable paths.json overrides all of it.
 */
public final class ModSpecs {

    private ModSpecs() {
    }

    public static List<ModSyncSpec> defaults() {
        return List.of(
                // Minimap ≥ 25.3: .minecraft/xaero/minimap/Multiplayer_<address-without-port>/
                // Legacy (≤ 25.2): XaeroWaypoints/ — set root in paths.json for old installs.
                new ModSyncSpec("xaerominimap", "files", "none",
                        new ModSyncSpec.Root("xaero/minimap", "dir", "Multiplayer_{host}")),
                // World Map ≥ 1.39: .minecraft/xaero/world-map/Multiplayer_<address-without-port>/
                new ModSyncSpec("xaeroworldmap", "files", "none",
                        new ModSyncSpec.Root("xaero/world-map", "dir", "Multiplayer_{host}")),
                // Per-server files litematica_<address>*.json + area_selections_per_world/<address>/
                new ModSyncSpec("litematica", "files", "none",
                        new ModSyncSpec.Root("config/litematica", "prefix", "litematica_{server}"),
                        new ModSyncSpec.Root("config/litematica/area_selections_per_world", "dir", "{server}")),
                // Bobby: .minecraft/.bobby/<ip with ':'→'_'>/<seedHash>/... — Anvil region files, closes
                // synchronously in Minecraft#clearDownloadedResourcePacks on world leave; no public event.
                new ModSyncSpec("bobby", "files", "probe-lock",
                        new ModSyncSpec.Root(".bobby", "dir", "{server}")),
                // Distant Horizons: per-(server,dim) SQLite. serverFolderNameMode defaults to NAME_ONLY
                // (server-list name), so the folder is keyed by {name}, not the address. DH closes storage
                // before DhApiWorldUnloadEvent; we can't depend on DH, hence probe-lock fallback.
                new ModSyncSpec("distanthorizons", "sqlite", "probe-lock",
                        new ModSyncSpec.Root("Distant_Horizons_server_data", "dir", "{name}")),
                // Voxy keys storage by SHA-256(worldSeed + dimension), NOT by server address — a folder
                // cannot be derived from the server identity. Left unsupported by default; a maintainer
                // can map a specific world manually via paths.json (dir pattern with a literal name).
                new ModSyncSpec("voxy", "files", "probe-lock"));
    }
}
