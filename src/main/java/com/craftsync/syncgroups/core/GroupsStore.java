package com.craftsync.syncgroups.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads/writes {@code .minecraft/syncgroups/groups.json}.
 * Enforces the "one server = one group" invariant from the spec (§5.4).
 */
public final class GroupsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups/groups");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Root {
        public List<Group> groups = new ArrayList<>();
    }

    private final Path file;
    private final Root root = new Root();

    public GroupsStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Root parsed = GSON.fromJson(Files.readString(file), Root.class);
            if (parsed != null && parsed.groups != null) {
                root.groups.clear();
                root.groups.addAll(parsed.groups);
            }
            // Fields added after 0.3.0 may be absent (or explicitly null) in an
            // existing file. Normalising here keeps every reader free of null checks.
            for (Group g : root.groups) {
                if (g.servers == null) {
                    g.servers = new ArrayList<>();
                }
                if (g.names == null) {
                    g.names = new java.util.LinkedHashMap<>();
                }
                if (g.color == null || !g.color.matches("#[0-9a-fA-F]{6}")) {
                    g.color = "#55FFAA";
                }
            }
        } catch (Exception e) {
            // A corrupt groups.json must not silently wipe group metadata.
            LOGGER.error("Failed to parse {}, starting with empty group list (file kept on disk)", file, e);
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        // Temp + move: a truncated groups.json loads as "no groups", which would
        // orphan every config/syncgroups/<gid> tree on disk.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized List<Group> all() {
        return List.copyOf(root.groups);
    }

    public synchronized Optional<Group> byServer(String serverKey) {
        return root.groups.stream().filter(g -> g.contains(serverKey)).findFirst();
    }

    public synchronized Optional<Group> byId(String id) {
        return root.groups.stream().filter(g -> id.equals(g.id)).findFirst();
    }

    public synchronized boolean contains(String id) {
        return root.groups.stream().anyMatch(g -> id.equals(g.id));
    }

    public synchronized void add(Group group) throws IOException {
        // Invariant: no server may appear in two groups.
        for (String server : group.servers) {
            byServer(server).ifPresent(existing -> {
                throw new IllegalStateException("Server " + server + " already in group " + existing.id);
            });
        }
        root.groups.add(group);
        try {
            save();
        } catch (IOException e) {
            // Keep memory and disk in agreement: a group that only exists in memory
            // makes every retry fail with "server already in group".
            root.groups.remove(group);
            throw e;
        }
    }

    public synchronized void remove(Group group) throws IOException {
        if (!root.groups.removeIf(g -> g.id.equals(group.id))) {
            return;
        }
        try {
            save();
        } catch (IOException e) {
            root.groups.add(group);
            throw e;
        }
    }

    public synchronized void update(Group group) throws IOException {
        // Accept a detached copy as well as the live object: callers that add a
        // group mid-way (createGroup) then work on a snapshot so a concurrent
        // settings change cannot make them write into an object no longer in the
        // list, silently dropping the change from groups.json.
        int idx = -1;
        for (int i = 0; i < root.groups.size(); i++) {
            if (root.groups.get(i).id.equals(group.id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IOException("group " + group.id + " is not in the store");
        }
        Group previous = root.groups.set(idx, group);
        try {
            save();
        } catch (IOException e) {
            root.groups.set(idx, previous);
            throw e;
        }
    }
}
