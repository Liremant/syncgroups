package com.craftsync.syncgroups.core;

import com.craftsync.syncgroups.integration.ModSyncSpec;
import com.craftsync.syncgroups.integration.PathsConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * {@code <rootDir>/<gid>/<modid>/<rootSyncName>/} mirrors the local entries of
 * that root, so a mod can have several storage roots (dir- or prefix-mode).
 *
 * Two copy strategies, selected by {@code syncMode}:
 * - copy (default): the group folder holds snapshots, pulled on join and pushed
 *   on leave. Local data stays real and readable outside the mod.
 * - symlink: the local per-server folder is replaced by a relative symlink into
 *   the group folder, so all grouped servers work on literally the same files
 *   and there is nothing to push. Applies to dir-mode roots only — Litematica's
 *   per-file roots would need one link per file and stay on copies.
 */
public final class SyncEngine {

    private static final String MARKER_PREFIX = ".active_";

    public final Path gameDir;
    public final Path rootDir;
    private final PathsConfig config;
    private final Predicate<String> modInstalled;

    public SyncEngine(Path gameDir, Path rootDir, PathsConfig config, Predicate<String> modInstalled) {
        this.gameDir = gameDir;
        this.rootDir = rootDir;
        this.config = config;
        this.modInstalled = modInstalled;
    }

    /** Dir-shaped roots can be linked; per-file (prefix) roots cannot. */
    private boolean useSymlinks(ModSyncSpec.Root root) {
        return config.symlinkMode() && "dir".equals(root.mode);
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

    /** Fresh groups get a random hue so they are told apart at a glance. */
    public static int randomColor() {
        return Colors.randomGroupColor();
    }

    // ------------------------------------------------------------- add server

    /** Spec §4.2. In symlink mode this links instead of copying. */
    public void addServerToGroup(Group group, String serverKey, String serverName) throws IOException {
        Map<String, String> v = vars(serverKey, serverName);
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                String tag = spec.modId + "/" + root.syncName();
                Path sync = syncDir(group.id, spec.modId, root);
                List<Path> existing = root.resolveExisting(gameDir, v);
                Path local = existing.isEmpty() ? root.resolvePrimary(gameDir, v) : existing.get(0);
                boolean localHasData = !existing.isEmpty() && DirOps.existsAndNonEmpty(local);

                if (useSymlinks(root)) {
                    if (localHasData && !DirOps.existsAndNonEmpty(sync)) {
                        // First server in the group defines the reference set. Move (not
                        // copy) so the data ends up in exactly one place — the link target.
                        Diag.info("ADD %s: seeding group from local (symlink mode) %s -> %s", tag, local, sync);
                        moveContentIn(local, sync);
                    } else if (localHasData) {
                        Path backup = backupDir(group.id, serverKey, spec.modId, root);
                        Diag.info("ADD %s: local data replaced by group link, backing up to %s", tag, backup);
                        DirOps.moveTree(local, backup);
                    }
                    DirOps.replaceWithRelativeSymlink(local, sync);
                    Diag.info("ADD %s: linked %s -> %s", tag, local, Diag.describe(sync));
                    continue;
                }

                if (DirOps.existsAndNonEmpty(sync) && localHasData) {
                    // What's about to be overwritten goes to _backups, not /dev/null.
                    Path backup = backupDir(group.id, serverKey, spec.modId, root);
                    DirOps.moveTree(sync, backup);
                    Diag.info("ADD %s: backed up existing group data to %s", tag, backup);
                }
                for (Path entry : existing) {
                    copyLocalIntoSync(entry, sync, root);
                }
                // Local copies are deliberately kept: they stay the working copy on join.
            }
        }
    }

    // ------------------------------------------------------------------ join

    /** Spec §4.3 + §7 recovery-before-pull. */
    public void pullForJoin(Group group, String serverKey, String serverName) throws IOException {
        Diag.info("PULL: group='%s' key='%s' name='%s' mode=%s",
                group.tag, serverKey, serverName, config.symlinkMode() ? "symlink" : "copy");
        recoverDirtyMarkers(group);
        List<ModSyncSpec> specs = activeSpecs();
        if (specs.isEmpty()) {
            Diag.warn("PULL: no supported mods detected as installed — nothing to sync. "
                    + "Check that the mod ids in paths.json match the installed jars.");
        }
        for (ModSyncSpec spec : specs) {
            for (ModSyncSpec.Root root : spec.roots) {
                String tag = spec.modId + "/" + root.syncName();
                Path sync = syncDir(group.id, spec.modId, root);
                Map<String, String> v = vars(serverKey, serverName);
                Diag.info("PULL %s: sync=%s", tag, Diag.describe(sync));

                if (useSymlinks(root)) {
                    List<Path> existing = root.resolveExisting(gameDir, v);
                    Path local = existing.isEmpty() ? root.resolvePrimary(gameDir, v) : existing.get(0);
                    if (DirOps.linksTo(local, sync)) {
                        Diag.info("PULL %s: already linked, nothing to do", tag);
                        continue;
                    }
                    boolean localHasData = !existing.isEmpty()
                            && !DirOps.isSymlink(local) && DirOps.existsAndNonEmpty(local);
                    if (!DirOps.existsAndNonEmpty(sync) && localHasData) {
                        // First join ever for this mod: adopt the local data instead of
                        // replacing it, otherwise the server's own map would be wiped.
                        Diag.info("PULL %s: group empty — adopting local data %s -> %s", tag, local, sync);
                        moveContentIn(local, sync);
                    } else if (Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
                        DirOps.deleteTree(local);
                    }
                    DirOps.replaceWithRelativeSymlink(local, sync);
                    Diag.info("PULL %s: linked %s -> %s", tag, local, Diag.describe(sync));
                    continue;
                }

                if (!DirOps.existsAndNonEmpty(sync)) {
                    Diag.info("PULL %s: sync copy is empty — first join for this mod, skipping", tag);
                    continue;
                }
                if ("prefix".equals(root.mode)) {
                    // Overwrite the local per-server entries we manage, then restore the synced set.
                    List<Path> existing = root.resolveExisting(gameDir, v);
                    Diag.info("PULL %s: prefix mode, deleting %d local entr(ies): %s", tag, existing.size(), existing);
                    for (Path entry : existing) {
                        DirOps.deleteTree(entry);
                    }
                    Path dest = gameDir.resolve(root.path);
                    try (Stream<Path> s = Files.list(sync)) {
                        for (Path entry : (Iterable<Path>) s::iterator) {
                            Path target = dest.resolve(entry.getFileName().toString());
                            DirOps.copyTree(entry, target);
                            Diag.info("PULL %s: %s -> %s", tag, entry.getFileName(), Diag.describe(target));
                        }
                    }
                } else {
                    Path local = root.resolvePrimary(gameDir, v);
                    List<Path> existing = root.resolveExisting(gameDir, v);
                    Diag.info("PULL %s: local=%s existing=%d", tag, local, existing.size());
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
                            Path target = local.resolve(child.getFileName().toString());
                            DirOps.copyTree(child, target);
                            Diag.info("PULL %s: %s -> %s", tag, child.getFileName(), Diag.describe(target));
                        }
                    }
                    Diag.info("PULL %s: result %s", tag, Diag.describe(local));
                    Diag.tree("PULL " + tag + " local", local, 2);
                    Diag.tree("PULL " + tag + " sync", sync, 2);
                }
            }
        }
        for (ModSyncSpec spec : specs) {
            writeMarker(group, spec.modId, serverKey, serverName);
        }
        Diag.info("PULL: done for key='%s'", serverKey);
    }

    // ------------------------------------------------------------------ leave

    /** Spec §4.4 / §7 push. Called on disconnect and by the periodic timer. */
    public boolean push(Group group, String serverKey, String serverName, boolean removeMarker) throws IOException {
        Diag.info("PUSH: group='%s' key='%s' name='%s' removeMarker=%s mode=%s",
                group.tag, serverKey, serverName, removeMarker,
                config.symlinkMode() ? "symlink" : "copy");
        boolean allOk = true;
        List<ModSyncSpec> specs = activeSpecs();
        if (specs.isEmpty()) {
            Diag.warn("PUSH: no supported mods detected as installed — nothing to push.");
        }
        long timeout = config.storageCloseTimeoutSeconds * 1000L;
        for (ModSyncSpec spec : specs) {
            for (ModSyncSpec.Root root : spec.roots) {
                String tag = spec.modId + "/" + root.syncName();

                if (useSymlinks(root)) {
                    // Nothing to copy: the mod wrote straight into the group folder.
                    List<Path> local = root.resolveExisting(gameDir, vars(serverKey, serverName));
                    boolean linked = !local.isEmpty()
                            && DirOps.linksTo(local.get(0), syncDir(group.id, spec.modId, root));
                    Diag.info("PUSH %s: symlink mode — no copy needed (link present=%s)", tag, linked);
                    if (!local.isEmpty()) {
                        Diag.tree("PUSH " + tag + " group data", syncDir(group.id, spec.modId, root), 2);
                    }
                    continue;
                }

                List<Path> local = root.resolveExisting(gameDir, vars(serverKey, serverName));
                if (local.isEmpty()) {
                    Diag.info("PUSH %s: no local data for key='%s' — skipping", tag, serverKey);
                    continue;
                }
                for (Path entry : local) {
                    Diag.info("PUSH %s: source %s", tag, Diag.describe(entry));
                    boolean waiting = !spec.closeWait.equals("none");
                    if (waiting) {
                        // Mods that write on teardown (Xaero flushes world-map regions
                        // *after* our disconnect handler) need a quiescence wait first,
                        // otherwise we copy a half-written snapshot.
                        boolean quiet = StorageCloseWaiter.awaitQuiescent(entry, timeout, config.pushQuiescenceMs);
                        boolean closed = StorageCloseWaiter.awaitClosed(entry, timeout);
                        Diag.info("PUSH %s: quiescent=%s closed=%s (mode=%s)", tag, quiet, closed, spec.closeWait);
                        if (!closed) {
                            Diag.warn("PUSH %s: storage did not confirm closure — skipping to avoid corruption", tag);
                            allOk = false;
                            continue;
                        }
                    }
                    Optional<String> suspect = IntegrityChecker.forName(spec.integrity).check(entry);
                    if (suspect.isPresent()) {
                        Diag.warn("PUSH %s: integrity check failed (%s) — keeping sync copy as is",
                                tag, suspect.get());
                        allOk = false;
                        continue;
                    }
                    Path sync = syncDir(group.id, spec.modId, root);
                    copyLocalIntoSync(entry, sync, root);
                    Diag.info("PUSH %s: %s -> %s", tag, entry, Diag.describe(sync));
                    Diag.tree("PUSH " + tag + " sync", sync, 2);
                }
            }
        }
        if (allOk && removeMarker) {
            for (ModSyncSpec spec : specs) {
                Files.deleteIfExists(markerPath(group.id, spec.modId, serverKey));
            }
            Diag.info("PUSH: session markers cleared");
        }
        Diag.info("PUSH: done for key='%s' allOk=%s", serverKey, allOk);
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
            Diag.error("Recovery scan failed for group " + group.id, e);
        }
    }

    private void handleDirtyMarker(Group group, String modId, String serverKey, Path marker) {
        ModSyncSpec spec = config.mods.get(modId);
        if (spec == null) {
            return;
        }
        String serverName = readMarkerName(marker);
        boolean anyData = false;
        boolean needsRecovery = false;
        for (ModSyncSpec.Root root : spec.roots) {
            if (useSymlinks(root)) {
                // The local path IS the group folder, so a crash cannot have left
                // anything unpublished — just drop the marker.
                Diag.info("RECOVERY %s/%s: symlink mode, nothing to recover for key='%s'",
                        modId, root.syncName(), serverKey);
                continue;
            }
            needsRecovery = true;
            List<Path> local = root.resolveExisting(gameDir, vars(serverKey, serverName));
            for (Path entry : local) {
                anyData = true;
                Optional<String> suspect = IntegrityChecker.forName(spec.integrity).check(entry);
                if (suspect.isPresent()) {
                    // Spec §7.4: never push suspect data over the group copy.
                    Diag.error(String.format("Crashed session left possibly-corrupt data for %s (%s): %s. "
                                    + "NOT pushing it over the group copy. Inspect %s manually.",
                            modId, serverKey, suspect.get(), entry));
                    continue;
                }
                try {
                    copyLocalIntoSync(entry, syncDir(group.id, modId, root), root);
                    Diag.info("RECOVERY: pushed %s for key='%s'", modId, serverKey);
                } catch (IOException e) {
                    Diag.error("RECOVERY: push failed for " + modId + " (" + serverKey + "), marker kept", e);
                    return;
                }
            }
        }
        if (anyData || !needsRecovery) {
            quietDelete(marker);
        }
    }

    // -------------------------------------------------------------- dissolve

    /** Spec §6: never delete group data silently — move to _dissolved. */
    public void dissolve(Group group, GroupsStore store) throws IOException {
        Path stamp = rootDir.resolve("_dissolved").resolve(group.id + "_" + Instant.now().getEpochSecond());
        Path dir = rootDir.resolve(group.id);
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            DirOps.moveTree(dir, stamp);
        }
        store.remove(group);
        Diag.info("DISSOLVE: group '%s' (%s) moved to %s", group.tag, group.id, stamp);
    }

    /** Spec §6: removing a server that leaves ≤1 participant dissolves the group. */
    public boolean removeServer(Group group, String serverKey, GroupsStore store) throws IOException {
        group.servers.remove(serverKey);
        boolean dissolved = group.servers.size() <= 1;
        if (dissolved) {
            // Whoever is left keeps their linked folder pointing into a group that
            // is about to move — turn the link back into a real directory first.
            if (config.symlinkMode()) {
                detachLinks(group, group.servers);
            }
            dissolve(group, store);
        } else {
            store.update(group);
        }
        return dissolved;
    }

    /** Replaces group symlinks with real directories holding the shared data. */
    private void detachLinks(Group group, List<String> keep) {
        for (String serverKey : keep) {
            for (ModSyncSpec spec : activeSpecs()) {
                for (ModSyncSpec.Root root : spec.roots) {
                    if (!useSymlinks(root)) {
                        continue;
                    }
                    try {
                        List<Path> existing = root.resolveExisting(gameDir, vars(serverKey, null));
                        if (existing.isEmpty() || !DirOps.isSymlink(existing.get(0))) {
                            continue;
                        }
                        Path local = existing.get(0);
                        Path sync = syncDir(group.id, spec.modId, root);
                        DirOps.deleteTree(local);
                        DirOps.copyTree(sync, local);
                        Diag.info("DISSOLVE: %s detached from group link for key='%s'",
                                spec.modId, serverKey);
                    } catch (IOException e) {
                        Diag.error("DISSOLVE: failed to detach link for " + spec.modId
                                + " key=" + serverKey, e);
                    }
                }
            }
        }
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

    /** Moves the children of {@code from} into {@code to}, leaving {@code from} empty. */
    private void moveContentIn(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path child : (Iterable<Path>) s::iterator) {
                Path target = to.resolve(child.getFileName().toString());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    DirOps.deleteTree(target);
                }
                DirOps.moveTree(child, target);
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

    private Path backupDir(String groupId, String serverKey, String modId, ModSyncSpec.Root root)
            throws IOException {
        Path p = rootDir.resolve(groupId).resolve("_backups")
                .resolve(ServerKey.sanitize(serverKey) + "_" + Instant.now().getEpochSecond())
                .resolve(modId)
                .resolve(root.syncName());
        Files.createDirectories(p.getParent());
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

    private static Map<String, String> vars(String serverKey, String serverName) {
        Map<String, String> vars = new LinkedHashMap<>();
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
