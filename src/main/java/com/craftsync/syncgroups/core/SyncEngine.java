package com.craftsync.syncgroups.core;

import com.craftsync.syncgroups.integration.ModSyncSpec;
import com.craftsync.syncgroups.integration.PathsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The disk-side half of Sync Groups: pull/push/backup/recovery (spec §4, §6, §7).
 * Everything here works on normalized server keys; the caller (client logic)
 * supplies "is mod installed" so this class stays free of loader APIs.
 *
 * Per-root layout inside a group:
 * {@code syncgroups/<gid>/<modid>/<rootSyncName>/} mirrors the local entries of
 * that root, so a mod can have several storage roots (dir- or prefix-mode).
 */
public final class SyncEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups/engine");
    private static final String MARKER_PREFIX = ".active_";

    public final Path gameDir;
    public final Path rootDir;
    private final PathsConfig config;
    private final Predicate<String> modInstalled;

    public SyncEngine(Path gameDir, PathsConfig config, Predicate<String> modInstalled) {
        this.gameDir = gameDir;
        this.rootDir = gameDir.resolve("syncgroups");
        this.config = config;
        this.modInstalled = modInstalled;
    }

    // ------------------------------------------------------------------ create

    /** Spec §4.1: create dirs for currently-installed mods, then pull each server's base data in. */
    public Group createGroup(String tag, int color, List<String> serverKeys, Map<String, String> serverNames,
                             GroupsStore store) throws IOException {
        Group g = new Group();
        g.id = randomId();
        g.tag = tag;
        g.color = String.format("#%06X", color & 0xFFFFFF);
        g.createdAt = Instant.now().getEpochSecond();
        g.servers.addAll(serverKeys);
        store.add(g); // throws if a server is already grouped
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                Files.createDirectories(syncDir(g.id, spec.modId, root));
            }
        }
        for (String server : serverKeys) {
            addServerToGroup(g, server, serverNames.get(server));
        }
        return g;
    }

    // ------------------------------------------------------------- add server

    /** Spec §4.2. */
    public void addServerToGroup(Group group, String serverKey, String serverName) throws IOException {
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                Path sync = syncDir(group.id, spec.modId, root);
                List<Path> local = root.resolveExisting(gameDir, vars(serverKey, serverName));
                if (DirOps.existsAndNonEmpty(sync) && !local.isEmpty()) {
                    // What's about to be overwritten goes to _backups, not /dev/null.
                    Path backup = backupsDir(group.id)
                            .resolve(ServerKey.sanitize(serverKey) + "_" + Instant.now().getEpochSecond())
                            .resolve(spec.modId)
                            .resolve(root.syncName());
                    DirOps.moveTree(sync, backup);
                    LOGGER.info("Backed up existing group data of {} to {}", spec.modId, backup);
                }
                for (Path entry : local) {
                    copyLocalIntoSync(entry, sync, root);
                }
                // Local copies are deliberately kept: they stay the working copy on join.
            }
        }
    }

    // ------------------------------------------------------------------ join

    /** Spec §4.3 + §7 recovery-before-pull. */
    public void pullForJoin(Group group, String serverKey, String serverName) throws IOException {
        recoverDirtyMarkers(group);
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                Path sync = syncDir(group.id, spec.modId, root);
                if (!DirOps.existsAndNonEmpty(sync)) {
                    continue; // first join for this mod — nothing to give the server yet
                }
                Map<String, String> v = vars(serverKey, serverName);
                if ("prefix".equals(root.mode)) {
                    // Overwrite the local per-server entries we manage, then restore the synced set.
                    for (Path entry : root.resolveExisting(gameDir, v)) {
                        DirOps.deleteTree(entry);
                    }
                    try (Stream<Path> s = Files.list(sync)) {
                        for (Path entry : (Iterable<Path>) s::iterator) {
                            DirOps.copyTree(entry, gameDir.resolve(root.path).resolve(entry.getFileName().toString()));
                        }
                    }
                } else {
                    Path local = root.resolvePrimary(gameDir, v);
                    List<Path> existing = root.resolveExisting(gameDir, v);
                    for (Path e : existing) {
                        DirOps.deleteTree(e);
                    }
                    if (!existing.isEmpty()) {
                        local = existing.get(0); // keep the name variant the mod already uses
                    } else {
                        Files.createDirectories(local.getParent());
                    }
                    // Dir mode syncs the CONTENT of the per-server container, not the
                    // container itself: Xaero keys the container by server address, so
                    // dumping another server's whole container inside would create an
                    // unreadable nested node (verified in production).
                    try (Stream<Path> s = Files.list(sync)) {
                        for (Path child : (Iterable<Path>) s::iterator) {
                            DirOps.copyTree(child, local.resolve(child.getFileName().toString()));
                        }
                    }
                }
            }
        }
        for (ModSyncSpec spec : activeSpecs()) {
            writeMarker(group, spec.modId, serverKey, serverName);
        }
    }

    // ------------------------------------------------------------------ leave

    /** Spec §4.4 / §7 push. Called on disconnect and by the periodic timer. */
    public boolean push(Group group, String serverKey, String serverName, boolean removeMarker) throws IOException {
        boolean allOk = true;
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                List<Path> local = root.resolveExisting(gameDir, vars(serverKey, serverName));
                if (local.isEmpty()) {
                    continue;
                }
                long timeout = config.storageCloseTimeoutSeconds * 1000L;
                for (Path entry : local) {
                    if (!StorageCloseWaiter.awaitClosed(entry, spec.closeWait.equals("none") ? 0 : timeout)) {
                        allOk = false;
                        continue;
                    }
                    Optional<String> suspect = IntegrityChecker.forName(spec.integrity).check(entry);
                    if (suspect.isPresent()) {
                        LOGGER.warn("Integrity check failed for {} ({}): {} — keeping sync copy as is",
                                spec.modId, serverKey, suspect.get());
                        allOk = false;
                        continue;
                    }
                    copyLocalIntoSync(entry, syncDir(group.id, spec.modId, root), root);
                }
            }
        }
        if (allOk && removeMarker) {
            for (ModSyncSpec spec : activeSpecs()) {
                Files.deleteIfExists(markerPath(group.id, spec.modId, serverKey));
            }
        }
        return allOk;
    }

    // -------------------------------------------------------------- recovery

    /** Spec §7.2–7.4: push leftovers of a crashed session before any join pull. */
    public void recoverDirtyMarkers(Group group) {
        try (Stream<Path> mods = Files.list(rootDir.resolve(group.id))) {
            for (Path modDir : (Iterable<Path>) mods.filter(Files::isDirectory)::iterator) {
                String modId = modDir.getFileName().toString();
                if (modId.startsWith("_") || modId.startsWith(".") || !modInstalled.test(modId)) {
                    continue;
                }
                try (Stream<Path> markers = Files.list(modDir)) {
                    for (Path marker : (Iterable<Path>) markers
                            .filter(p -> p.getFileName().toString().startsWith(MARKER_PREFIX))::iterator) {
                        String serverKey = marker.getFileName().toString().substring(MARKER_PREFIX.length());
                        handleDirtyMarker(group, modId, serverKey, marker);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Recovery scan failed for group {}", group.id, e);
        }
    }

    private void handleDirtyMarker(Group group, String modId, String serverKey, Path marker) {
        LOGGER.warn("Found stale session marker: group {}, mod {}, server {} — recovering", group.id, modId, serverKey);
        ModSyncSpec spec = config.mods.get(modId);
        if (spec == null) {
            return;
        }
        String serverName = readMarkerName(marker);
        boolean anyData = false;
        for (ModSyncSpec.Root root : spec.roots) {
            List<Path> local = root.resolveExisting(gameDir, vars(serverKey, serverName));
            for (Path entry : local) {
                anyData = true;
                Optional<String> suspect = IntegrityChecker.forName(spec.integrity).check(entry);
                if (suspect.isPresent()) {
                    // Spec §7.4: never push suspect data over the group copy.
                    LOGGER.error("Crashed session left possibly-corrupt data for {} ({}): {}. NOT pushing it over the " +
                            "group copy. Inspect {} manually.", spec.modId, serverKey, suspect.get(), entry);
                    continue;
                }
                try {
                    copyLocalIntoSync(entry, syncDir(group.id, modId, root), root);
                    LOGGER.info("Recovery push of {} for {} completed", modId, serverKey);
                } catch (IOException e) {
                    LOGGER.error("Recovery push failed for {} ({}), marker kept", modId, serverKey, e);
                    return;
                }
            }
        }
        if (anyData) {
            quietDelete(marker);
        }
    }

    // -------------------------------------------------------------- dissolve
    /** Spec §6: never delete group data silently — move to _dissolved. */
    public void dissolve(Group group, GroupsStore store) throws IOException {
        Path stamp = rootDir.resolve("_dissolved").resolve(group.id + "_" + Instant.now().getEpochSecond());
        Path dir = rootDir.resolve(group.id);
        if (Files.isDirectory(dir)) {
            DirOps.moveTree(dir, stamp);
        }
        store.remove(group);
    }

    /** Spec §6: removing a server that leaves ≤1 participant dissolves the group. */
    public boolean removeServer(Group group, String serverKey, GroupsStore store) throws IOException {
        group.servers.remove(serverKey);
        boolean dissolved = group.servers.size() <= 1;
        if (dissolved) {
            dissolve(group, store);
        } else {
            store.update(group);
        }
        return dissolved;
    }

    // -------------------------------------------------------------- helpers

    /**
     * Copies one resolved local entry into its group sync folder.
     * Dir mode stores the CONTENT of the per-server container (the container
     * itself is address-keyed — nesting it elsewhere is unreadable to the mod);
     * prefix mode stores the entry itself under its own name.
     */
    private void copyLocalIntoSync(Path entry, Path sync, ModSyncSpec.Root root) throws IOException {
        if ("prefix".equals(root.mode)) {
            DirOps.copyTree(entry, sync.resolve(entry.getFileName().toString()));
            return;
        }
        try (Stream<Path> s = Files.list(entry)) {
            for (Path child : (Iterable<Path>) s::iterator) {
                DirOps.copyTree(child, sync.resolve(child.getFileName().toString()));
            }
        }
    }

    public List<ModSyncSpec> activeSpecs() {
        List<ModSyncSpec> out = new ArrayList<>();
        for (ModSyncSpec spec : config.mods.values()) {
            if (modInstalled.test(spec.modId) && !spec.roots.isEmpty()) {
                out.add(spec);
            }
        }
        return out;
    }

    private Path syncDir(String groupId, String modId, ModSyncSpec.Root root) throws IOException {
        Path p = rootDir.resolve(groupId).resolve(modId).resolve(root.syncName());
        Files.createDirectories(p);
        return p;
    }

    private Path backupsDir(String groupId) throws IOException {
        Path p = rootDir.resolve(groupId).resolve("_backups");
        Files.createDirectories(p);
        return p;
    }

    private Path markerPath(String groupId, String modId, String serverKey) {
        return rootDir.resolve(groupId).resolve(modId).resolve(MARKER_PREFIX + ServerKey.sanitize(serverKey));
    }

    /** Marker file: first line = server-list name (needed for recovery of name-keyed mods), then timestamp. */
    private void writeMarker(Group group, String modId, String serverKey, String serverName) throws IOException {
        Files.writeString(markerPath(group.id, modId, serverKey),
                (serverName == null ? "" : serverName) + "\n" + Instant.now());
    }

    private String readMarkerName(Path marker) {
        try {
            String content = Files.readString(marker);
            int nl = content.indexOf('\n');
            return nl < 0 ? null : content.substring(0, nl);
        } catch (IOException e) {
            return null;
        }
    }

    private void quietDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static Map<String, String> vars(String serverKey, String serverName) {        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("server", serverKey);
        int colon = serverKey.lastIndexOf(':');
        if (colon > 0) {
            vars.put("host", serverKey.substring(0, colon));
            vars.put("port", serverKey.substring(colon + 1));
        } else {
            vars.put("host", serverKey);
            vars.put("port", "");
        }
        if (serverName != null) {
            vars.put("name", serverName);
        }
        return vars;
    }

    private static String randomId() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
