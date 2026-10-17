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

    /**
     * Guards folder moves: the mode conversion (background thread) and the join
     * pull (render/netty thread) can touch the same local path, and a copy that
     * starts while another thread is moving that folder is how data gets lost.
     * Held per (server, root) unit of work, not for a whole group, so a join is
     * never stuck behind a long conversion of an unrelated server.
     */
    private final Object fsLock = new Object();

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

    /**
     * True when this local path is already the group folder, in this session's
     * mode. Used to skip a copy whose source and target are the same directory —
     * `DirOps.copyTree` refuses that anyway, but skipping keeps the log readable
     * and avoids the self-copy exception path on every push.
     */
    private static boolean alreadyGroupFolder(Path entry, Path sync) {
        return DirOps.sameLocation(entry, sync)
                || (DirOps.isSymlink(entry) && DirOps.linksTo(entry, sync));
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
        if (serverNames != null) {
            serverNames.forEach(g::rememberName);
        }
        store.add(g); // throws if a server is already grouped
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                Files.createDirectories(syncDir(g.id, spec.modId, root));
            }
        }
        // Seed from a snapshot: the live object is already in the store, and the
        // seeding loop moves whole folders around. Editing the stored instance here
        // meant a concurrent settings change could be lost (or the store could be
        // rewritten mid-move) — the copy is merged back at the end and is the only
        // thing written to groups.json.
        Group working = g.copyOf();
        for (String server : serverKeys) {
            addServerToGroup(working, server, serverNames == null ? null : serverNames.get(server));
        }
        // A settings change may have re-added or replaced the live object while we
        // worked; in that case the store is authoritative and must not be undone.
        if (!store.contains(working.id)) {
            Diag.warn("CREATE: group '%s' disappeared from the store while seeding — not restoring it",
                    working.tag);
            return working;
        }
        store.update(working);
        return working;
    }

    /** Fresh groups get a random hue so they are told apart at a glance. */
    public static int randomColor() {
        return Colors.randomGroupColor();
    }

    // ------------------------------------------------------------- add server

    /** Spec §4.2. In symlink mode this links instead of copying. */
    public void addServerToGroup(Group group, String serverKey, String serverName) throws IOException {
        group.rememberName(serverKey, serverName);
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
                    } else if (localHasData && DirOps.sameContent(local, sync)) {
                        // The local folder already IS this server's data for the group
                        // (this is the second time we adopt it, e.g. create() pulls the
                        // first server and then loops over every server again). Nothing
                        // is lost, so skip the backup that would otherwise copy the whole
                        // map into _backups.
                        Diag.info("ADD %s: local %s already matches the group folder — no backup", tag, local);
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
                    // The group already has data from an earlier server in this
                    // creation: that copy is the reference. Preserve this server's
                    // own folder under its own key instead of overwriting the group
                    // and then filing its data in a backup named after someone else.
                    if (DirOps.sameContent(local, sync)) {
                        Diag.info("ADD %s: %s already matches the group folder — nothing to do", tag, local);
                    } else {
                        Path backup = backupDir(group.id, serverKey, spec.modId, root);
                        Diag.warn("ADD %s: group already seeded — this server's own data %s moved to %s",
                                tag, local, backup);
                        for (Path entry : existing) {
                            DirOps.moveTree(entry, backup.resolve(entry.getFileName().toString()));
                        }
                    }
                    continue;
                }
                for (Path entry : existing) {
                    copyLocalIntoSync(entry, sync, root);
                }
                // Local copies are deliberately kept: they stay the working copy on join.
            }
        }
    }

    // ------------------------------------------------------------------ join

    /** Spec §4.3 + §7 recovery-before-pull. Serialised with the mode conversion. */
    public void pullForJoin(Group group, String serverKey, String serverName) throws IOException {
        synchronized (fsLock) {
            pullForJoinLocked(group, serverKey, serverName);
        }
    }

    private void pullForJoinLocked(Group group, String serverKey, String serverName) throws IOException {
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

                // A leftover link while the mode is "copy" (the user flipped the switch
                // mid-session, so the startup conversion could not run): detach it here,
                // otherwise the pull silently keeps the link and the switch never takes
                // effect for this server. Detaching is safe when the link is ours — its
                // target is the group folder, so even an empty one loses nothing.
                Path local = root.resolvePrimary(gameDir, v);
                if (DirOps.isSymlink(local)
                        && (DirOps.linksTo(local, sync) || DirOps.existsAndNonEmpty(sync))) {
                    Diag.warn("PULL %s: %s is a leftover symlink while mode=copy — detaching", tag, local);
                    DirOps.deleteTree(local); // link only, never the target
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
            // A dirty marker means "this session may have left local data unpushed".
            // In symlink mode the local path IS the group folder, so there is nothing
            // to push and the marker would only mislead a later mode conversion into
            // publishing a server's copy over the group. A mod with any copied root
            // (Litematica's per-server json) still needs one.
            if (spec.roots.stream().anyMatch(r -> !useSymlinks(r))) {
                writeMarker(group, spec.modId, serverKey, serverName);
            } else {
                Diag.info("PULL %s: fully symlinked — no dirty marker needed", spec.modId);
            }
            // The marker's timestamp is our own bookkeeping and must not be mistaken
            // for the mod's data still being written: backdate it, or a conversion
            // running right after this pull would treat the folders as "unfinished".
            touchMarkerOlder(markerPath(group.id, spec.modId, serverKey));
        }
        Diag.info("PULL: done for key='%s'", serverKey);
    }

    // ------------------------------------------------------------------ leave

    /** Spec §4.4 / §7 push. Called on disconnect and by the periodic timer. */
    public boolean push(Group group, String serverKey, String serverName, boolean removeMarker) throws IOException {
        // Symlink mode has nothing to copy, but a *leftover* link from a mode
        // switch made mid-session still has to be reported as "not pushed", so the
        // marker stays and the recovery path can deal with it.
        boolean wroteSomething = false;
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
                    Path sync = syncDir(group.id, spec.modId, root);
                    if (DirOps.isSymlink(entry) && alreadyGroupFolder(entry, sync)) {
                        // Leftover link from a mode switch made mid-session: the entry IS
                        // the group folder, so "copying" it would target itself. The
                        // switch is applied per server on the next join — never here.
                        Diag.warn("PUSH %s: %s is a symlink into the group folder — skipping", tag, entry);
                        continue;
                    }
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
                    copyLocalIntoSync(entry, sync, root);
                    wroteSomething = true;
                    Diag.info("PUSH %s: %s -> %s", tag, entry, Diag.describe(sync));
                    Diag.tree("PUSH " + tag + " sync", sync, 2);
                }
            }
        }
        if (allOk && removeMarker && wroteSomething) {
            for (ModSyncSpec spec : specs) {
                Files.deleteIfExists(markerPath(group.id, spec.modId, serverKey));
            }
            Diag.info("PUSH: session markers cleared");
        } else if (removeMarker && !wroteSomething) {
            Diag.warn("PUSH: nothing was written (all roots skipped) — keeping the sync marker");
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
        // Marker first (written at pull time), then the name remembered in
        // groups.json — the marker is the fresher of the two when both exist.
        String serverName = readMarkerName(marker);
        if (serverName == null || serverName.isBlank()) {
            serverName = group.nameOf(serverKey);
        }
        boolean anyData = false;
        boolean needsRecovery = false;
        for (ModSyncSpec.Root root : spec.roots) {
            // Decide on the *physical* state, not on the configured mode: after a
            // mode switch the flag and the layout disagree, and a marker left by a
            // crashed copy-mode session must still be published even though the
            // current mode is symlink.
            if ("dir".equals(root.mode)
                    && DirOps.isSymlink(root.resolvePrimary(gameDir, vars(serverKey, serverName)))) {
                // The local path IS the group folder, so a crash cannot have left
                // anything unpublished — just drop the marker.
                Diag.info("RECOVERY %s/%s: local path is a link into the group, nothing to recover for key='%s'",
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

    // -------------------------------------------------------------- mode switch

    /**
     * Converts the local per-server data of every grouped server between the
     * copy and symlink layouts (spec §4.5), to match {@code syncMode}.
     *
     * Switching {@code syncMode} only changes what future joins/pushes do, so
     * without this an install ends up with a mix of links and real folders, and
     * the switch is not reversible by flipping the flag back: a local path that
     * is a link looks like "no local data" to copy-mode logic, and the data the
     * user had before the switch is never materialised. This makes the switch
     * explicit, idempotent, and never destructive.
     *
     * Must not run while a session is open (mods hold their storage): the caller
     * checks that and defers the conversion to the next launch.
     *
     * @return human-readable summary for the log/UI
     */
    public String convertMode(Group group) {
        boolean toSymlink = config.symlinkMode();
        int linked = 0;
        int detached = 0;
        int already = 0;
        int skipped = 0;
        Diag.info("MODE: converting group '%s' (%s) to %s", group.tag, group.id,
                toSymlink ? "symlink" : "copy");
        for (ModSyncSpec spec : activeSpecs()) {
            for (ModSyncSpec.Root root : spec.roots) {
                String tag = spec.modId + "/" + root.syncName();
                if (!"dir".equals(root.mode)) {
                    // Per-file (prefix) roots are never linked, in either mode.
                    continue;
                }
                Path sync;
                try {
                    sync = syncDir(group.id, spec.modId, root);
                } catch (IOException e) {
                    Diag.error("MODE: cannot resolve sync dir for " + tag, e);
                    skipped++;
                    continue;
                }
                for (String serverKey : group.servers) {
                    if (config.symlinkMode() != toSymlink) {
                        // Someone flipped the switch again while we were working. Stop
                        // instead of finishing in a direction nobody asked for; the
                        // next reconcile (startup or settings) finishes the job.
                        Diag.warn("MODE: syncMode changed mid-conversion — stopping at %s", tag);
                        return summaryFor(toSymlink, linked, detached, already, skipped);
                    }
                    String serverName = group.nameOf(serverKey);
                    if (serverName == null && root.pattern != null && root.pattern.contains("{name}")) {
                        // Distant Horizons and friends: without the name there is no
                        // path to convert. It will be adopted on that server's next join.
                        Diag.warn("MODE %s: no remembered name for key='%s' — skipped (will convert on its next join)",
                                tag, serverKey);
                        skipped++;
                        continue;
                    }
                    String status;
                    // Serialised against the join pull: both may move the same folders,
                    // and the unit of work here is one (server, root) pair so a join to
                    // another server never waits for a long conversion.
                    synchronized (fsLock) {
                        status = convertOne(group, spec, root, serverKey, serverName, sync, toSymlink);
                    }
                    switch (status) {
                        case "linked" -> linked++;
                        case "detached" -> detached++;
                        case "already" -> already++;
                        case "none" -> { /* nothing on disk yet: not a skip, a no-op */ }
                        default -> skipped++;
                    }
                }
            }
        }
        String summary = summaryFor(toSymlink, linked, detached, already, skipped);
        Diag.info("MODE: done for group '%s': %s", group.tag, summary);
        return summary;
    }

    private static String summaryFor(boolean toSymlink, int linked, int detached, int already, int skipped) {
        int done = toSymlink ? linked : detached;
        String what = toSymlink ? (linked == 1 ? "folder linked" : "folders linked")
                : (detached == 1 ? "folder detached" : "folders detached");
        return done + " " + what + ", " + already + " already correct, " + skipped + " skipped";
    }

    /**
     * Converts one (server, root) pair. Returns "linked", "detached", "already"
     * or "skipped". Caller holds {@link #fsLock}.
     */
    private String convertOne(Group group, ModSyncSpec spec, ModSyncSpec.Root root,
                              String serverKey, String serverName, Path sync, boolean toSymlink) {
        String tag = spec.modId + "/" + root.syncName();
        String key = "key='" + serverKey + "'";
        Map<String, String> v = vars(serverKey, serverName);
        List<Path> existing = root.resolveExisting(gameDir, v);
        if (existing.isEmpty()) {
            return "none"; // nothing local yet — the next join creates the right shape
        }
        Path local = existing.get(0);
        try {
            if (toSymlink) {
                if (DirOps.linksTo(local, sync)) {
                    return "already";
                }
                if (DirOps.isSymlink(local)) {
                    Diag.warn("MODE %s: %s is a link to somewhere else — left untouched", tag, local);
                    return "skip";
                }
                if (Files.exists(markerPath(group.id, spec.modId, serverKey))) {
                    // A crashed session left unpublished local data: it is the newest
                    // copy, so publish it before shadowing the folder with a link.
                    // Backing it up instead would silently show stale group data on
                    // the next join.
                    Diag.warn("MODE %s: unfinished session for %s — publishing local data into "
                            + "the group before linking", tag, key);
                    copyLocalIntoSync(local, sync, root);
                } else if (!DirOps.existsAndNonEmpty(sync)) {
                    // Group has no data yet: the local folder IS the reference set, so
                    // move its content in rather than shadowing it.
                    Diag.info("MODE %s: seeding group from %s", tag, local);
                    moveContentIn(local, sync);
                } else if (DirOps.existsAndNonEmpty(local)) {
                    if (DirOps.sameContent(local, sync)) {                        // The local folder is exactly the group's data (typically the copy
                        // a previous switch to copy mode materialised). Nothing is lost by
                        // discarding it, so skip the backup — otherwise every flip leaves
                        // another copy in _backups. replaceWithRelativeSymlink stashes and
                        // restores it if the link cannot be created.
                        Diag.info("MODE %s: %s is an identical copy of the group folder — "
                                + "not backing it up", tag, local);
                    } else {
                        // Both sides have data and they differ. Never pick a winner
                        // silently: keep the local copy in _backups.
                        Path backup = backupDir(group.id, serverKey, spec.modId, root);
                        Diag.warn("MODE %s: group already has data — local %s moved to %s", tag, local, backup);
                        DirOps.moveTree(local, backup);
                    }
                }
                DirOps.replaceWithRelativeSymlink(local, sync);
                // Nothing left to recover once the local path IS the group folder.
                quietDelete(markerPath(group.id, spec.modId, serverKey));
                Diag.info("MODE %s: linked %s -> %s", tag, local, sync);
                return "linked";
            }

            if (!DirOps.isSymlink(local)) {
                return "already";
            }
            boolean groupHasData = DirOps.existsAndNonEmpty(sync);
            if (!groupHasData && !DirOps.linksTo(local, sync)) {
                // A link to somewhere else with an empty group folder: removing it
                // would lose the only pointer to that data.
                Diag.warn("MODE %s: %s is a link to another location and the group folder is "
                        + "empty — not detaching", tag, local);
                return "skip";
            }
            DirOps.deleteTree(local); // link only, never the target
            if (groupHasData) {
                DirOps.copyTree(sync, local);
                Diag.info("MODE %s: detached %s from the group link", tag, local);
            } else {
                // Our own link into an empty group folder: there is nothing to
                // materialise, but the link still has to go, otherwise copy mode
                // keeps behaving like symlink mode for this root.
                Files.createDirectories(local);
                Diag.info("MODE %s: detached %s (group folder was empty)", tag, local);
            }
            return "detached";
        } catch (IOException e) {
            Diag.error("MODE %s: conversion failed for " + key, e);
            return "skip";
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
        // Snapshot before mutating: the server being removed also has a link into
        // the group folder, and that folder is about to move to _dissolved — leaving
        // its link dangling in the mod's data directory.
        List<String> all = new ArrayList<>(group.servers);
        group.servers.remove(serverKey);
        boolean dissolved = group.servers.size() <= 1;
        if (dissolved) {
            // Everyone still pointing into the group folder (the removed server
            // included) needs a real directory before that folder moves away.
            materializeLinks(group, all);
            dissolve(group, store);
        } else {
            store.update(group);
        }
        return dissolved;
    }

    /**
     * Replaces group symlinks with real directories holding the shared data.
     * Runs regardless of the current {@code syncMode}: what matters is whether
     * the local path is *actually* a link into the group folder, because that
     * folder is about to be moved away. Relying on the mode flag here used to
     * leave dangling links (and with them, the only copy of the data) behind
     * after a mode switch.
     */
    private void materializeLinks(Group group, List<String> keep) {
        for (String serverKey : keep) {
            for (ModSyncSpec spec : activeSpecs()) {
                for (ModSyncSpec.Root root : spec.roots) {
                    if (!"dir".equals(root.mode)) {
                        continue;
                    }
                    try {
                        List<Path> existing = root.resolveExisting(gameDir,
                                vars(serverKey, group.nameOf(serverKey)));
                        if (existing.isEmpty() || !DirOps.isSymlink(existing.get(0))) {
                            continue;
                        }
                        Path local = existing.get(0);
                        Path sync = syncDir(group.id, spec.modId, root);
                        if (!DirOps.existsAndNonEmpty(sync)) {
                            Diag.warn("DISSOLVE %s: %s is a link into an empty group folder — "
                                    + "leaving the link so nothing is lost", spec.modId, local);
                            continue;
                        }
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
        // A stale link from a mode switch that has not been converted yet, pointing
        // into the group folder: its children ARE the sync folder's children, so a
        // copy would target the source of every file. Nothing to do, and pushing
        // would otherwise walk (and possibly race) its own output.
        if (alreadyGroupFolder(entry, sync)) {
            Diag.warn("COPY: %s is the group folder itself (%s) — skipping", entry, sync);
            return;
        }
        if (!Files.isDirectory(entry)) {
            // A file where a dir-mode root expects a container: do not silently drop
            // it, but do not pretend it was synced either.
            Diag.warn("COPY %s: %s is not a directory — skipped", root.syncName(), entry);
            return;
        }
        try (Stream<Path> s = Files.list(entry)) {
            for (Path child : (Iterable<Path>) s::iterator) {
                if (alreadyGroupFolder(child, sync.resolve(child.getFileName().toString()))) {
                    Diag.warn("COPY: %s is the group's own entry — skipping", child);
                    continue;
                }
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

    /**
     * Marks a marker file as "written before us" so a mode conversion running
     * seconds later does not read it as a session that crashed mid-write.
     */
    private void touchMarkerOlder(Path marker) {
        try {
            if (Files.exists(marker)) {
                Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 60_000L));
            }
        } catch (IOException e) {
            Diag.warn("PULL: could not backdate marker %s: %s", marker, e);
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
