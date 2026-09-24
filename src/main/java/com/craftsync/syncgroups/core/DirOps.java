package com.craftsync.syncgroups.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** Small recursive file ops used by the sync engine. */
public final class DirOps {

    private DirOps() {
    }

    public static boolean existsAndNonEmpty(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    public static void copyTree(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            // Single-file copy (e.g. Litematica's litematica_<server>.json) —
            // 'to' IS the file, not a directory to put it into.
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        if (Files.exists(to)) {
            // Overwrite semantics: the target may exist as a file while we
            // copy a directory (or vice versa) — remove it either way.
            deleteTree(to);
        }
        Files.createDirectories(to);
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : (Iterable<Path>) s::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Removes a file, a directory tree, or a symlink.
     * A symlink is deleted as a link only — never followed into its target,
     * which is critical in symlink mode where a local path points at the
     * group's shared folder.
     */
    public static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(dir)) {
            Files.delete(dir);
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    public static void moveTree(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to);
        } catch (IOException e) {
            // Cross-filesystem or locked files: fall back to copy+delete.
            copyTree(from, to);
            deleteTree(from);
        }
    }

    public static long sizeOf(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    // ------------------------------------------------------------- symlinks

    public static boolean isSymlink(Path p) {
        return p != null && Files.isSymbolicLink(p);
    }

    /**
     * Replaces {@code link} with a symlink pointing at {@code target}, using a
     * path relative to the link's own directory (plain names, no leading "./").
     * Relative links survive moving the game directory, and work on both Linux
     * and Windows — on Windows, though, creating one needs Developer Mode or
     * administrator rights, hence the explicit message on failure.
     *
     * The existing content is moved aside first and restored if the link cannot
     * be created, so a failed attempt never leaves the mod without its folder.
     */
    public static void replaceWithRelativeSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        Path stash = null;
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            stash = link.resolveSibling(link.getFileName() + ".syncgroups-old");
            deleteTree(stash);
            moveTree(link, stash);
        }
        Path relative = link.getParent().relativize(target);
        try {
            Files.createSymbolicLink(link, relative);
        } catch (UnsupportedOperationException | IOException e) {
            if (stash != null) {
                moveTree(stash, link); // put the data back exactly as it was
            }
            throw new IOException("Could not create symlink " + link + " -> " + relative
                    + " (on Windows this needs Developer Mode or administrator rights)", e);
        }
        if (stash != null) {
            deleteTree(stash);
        }
    }

    /**
     * Checks that this system can actually create symlinks, by making one in a
     * throwaway directory. Windows refuses without Developer Mode or admin
     * rights, and finding that out at startup beats discovering it mid-join.
     */
    public static String probeSymlinkSupport(Path scratchDir) {
        try {
            Files.createDirectories(scratchDir);
            Path target = scratchDir.resolve("probe-target");
            Path link = scratchDir.resolve("probe-link");
            Files.writeString(target, "x");
            deleteTree(link);
            Files.createSymbolicLink(link, link.getParent().relativize(target));
            boolean ok = Files.isSymbolicLink(link) && Files.exists(link);
            deleteTree(link);
            Files.deleteIfExists(target);
            return ok ? null : "symlink created but not reported as a link";
        } catch (UnsupportedOperationException | IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** True when {@code link} is already a symlink pointing at {@code target}. */
    public static boolean linksTo(Path link, Path target) {        if (!Files.isSymbolicLink(link)) {
            return false;
        }
        try {
            Path stored = Files.readSymbolicLink(link);
            Path resolved = link.getParent().resolve(stored).normalize();
            return resolved.equals(target.normalize());
        } catch (IOException e) {
            return false;
        }
    }

    public static final class UncheckedIOException extends RuntimeException {
        public UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
