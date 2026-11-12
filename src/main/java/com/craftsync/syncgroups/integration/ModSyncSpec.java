package com.craftsync.syncgroups.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * How to locate the per-server data of one supported mod, and how to treat it.
 * Persisted in paths.json so users/maintainers can fix paths for a new mod
 * version without touching code (spec §2).
 *
 * Placeholders inside patterns, substituted from the server identity:
 * {@code {server}} (address as typed, lowercased), {@code {host}}, {@code {port}},
 * {@code {name}} (server-list entry name).
 */
public class ModSyncSpec {

    public String modId;
    /** "files" (readability/size check) or "sqlite" (header + PRAGMA when driver present). */
    public String integrity = "files";
    /** "none" or "probe-lock" — how to wait for the mod to release its files on disconnect. */
    public String closeWait = "none";
    public List<Root> roots = new ArrayList<>();

    public ModSyncSpec() {
    }

    public ModSyncSpec(String modId, String integrity, String closeWait, Root... roots) {
        this.modId = modId;
        this.integrity = integrity;
        this.closeWait = closeWait;
        this.roots.addAll(List.of(roots));
    }

    /**
     * One storage root of a mod, relative to the game dir.
     * mode "dir": each server's data is the subdirectory {@code pattern}.
     * mode "prefix": each server's data is every entry (file or dir) under the
     * root whose name starts with the substituted {@code pattern} — e.g.
     * Litematica's {@code litematica_<address>*.json} files.
     */
    public static class Root {
        public String path;
        public String mode = "dir";
        public String pattern;

        public Root() {
        }

        public Root(String path, String mode, String pattern) {
            this.path = path;
            this.mode = mode;
            this.pattern = pattern;
        }

        /** Stable folder name for this root inside the group's sync dir. */
        public String syncName() {
            return path.replaceAll("[^a-zA-Z0-9]", "_");
        }

        /** All existing per-server data entries for the given variables. */
        public List<Path> resolveExisting(Path gameDir, Map<String, String> vars) {
            Path root = gameDir.resolve(path);
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            String name = substitute(pattern, vars);
            // A pattern of only {port} (empty by design) would resolve to the root
            // itself and make one server's data the sync source for all of them.
            if (name == null || name.isBlank()) {
                return List.of();
            }
            if ("prefix".equals(mode)) {
                String prefix = safe(name);
                try (Stream<Path> s = Files.list(root)) {
                    return s.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
                } catch (Exception e) {
                    return List.of();
                }
            }
            Path p = root.resolve(safe(name));
            return Files.isDirectory(p) ? List.of(p) : List.of();
        }

        /**
         * Where to place pulled data when nothing local exists yet, or null when
         * the root cannot be resolved (no value for one of its placeholders).
         * Never falls back to the root directory itself: {@code root.resolve("")}
         * is the whole root, so a missing {@code {name}} would otherwise pull one
         * server's Distant Horizons folder over every other server's.
         */
        public Path resolvePrimary(Path gameDir, Map<String, String> vars) {
            String name = substitute(pattern, vars);
            if (name == null || name.isBlank()) {
                return null;
            }
            return gameDir.resolve(path).resolve(safe(name));
        }

        /**
         * Null when a placeholder this pattern uses has no value. {@code {port}}
         * may legitimately be empty (an address without a port); the others may
         * not, because an empty value resolves to the shared root directory.
         */
        private static String substitute(String pattern, Map<String, String> vars) {
            String s = pattern;
            for (String ph : new String[]{"name", "server", "host"}) {
                if (!s.contains("{" + ph + "}")) {
                    continue;
                }
                String value = vars.get(ph);
                // All-dot values are path noise, not names: "." and ".." would
                // resolve back to the root itself or its parent.
                if (value == null || value.isBlank() || value.matches("\\.+")) {
                    return null;
                }
                s = s.replace("{" + ph + "}", value);
            }
            return s.replace("{port}", vars.getOrDefault("port", ""));
        }

        private static String safe(String name) {
            // '%' is allowed so mod-specific escapes (e.g. Xaero's %us%) survive intact.
            // Dots are kept (Xaero's Multiplayer_mc.example.com) but a ".." is not a
            // name: a server-list entry called ".." would otherwise escape the root.
            // Known limit: ".." -> "__" can collide ("a..b" vs "a__b") in one sync
            // folder; a real server name with ".." is rare enough to accept.
            return name.replace("..", "__").replaceAll("[^a-zA-Z0-9._% -]", "_");
        }
    }
}
