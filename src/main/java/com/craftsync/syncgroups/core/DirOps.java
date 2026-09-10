package com.craftsync.syncgroups.core;

import java.io.IOException;
import java.nio.file.Files;
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

    /** Copies only the per-server subdirectory tree of a mod root, not root-level global files. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
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

    public static final class UncheckedIOException extends RuntimeException {
        public UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
