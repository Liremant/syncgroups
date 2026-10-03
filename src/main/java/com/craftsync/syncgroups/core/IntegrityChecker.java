package com.craftsync.syncgroups.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pre-push sanity check (spec §7.4): never push data that looks corrupted by a
 * crash mid-write over the group's good copy. SQLite databases get a header
 * check, plus PRAGMA quick_check when a JDBC driver is available; plain files
 * get readability + non-trivial size checks.
 */
public interface IntegrityChecker {

    /** Empty = ok, otherwise a human-readable reason the data looks suspect. */
    Optional<String> check(Path dataDir);

    static IntegrityChecker forName(String name) {
        return "sqlite".equalsIgnoreCase(name) ? new Sqlite() : new FilesChecker();
    }

    class FilesChecker implements IntegrityChecker {
        private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups/integrity");

        @Override
        public Optional<String> check(Path dir) {
            if (!Files.isDirectory(dir)) {
                return Optional.empty(); // nothing stored yet — nothing to corrupt
            }
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    if (!Files.isReadable(p)) {
                        return Optional.of("unreadable file: " + p.getFileName());
                    }
                }
            } catch (IOException e) {
                return Optional.of("walk failed: " + e.getMessage());
            }
            return Optional.empty();
        }
    }

    class Sqlite implements IntegrityChecker {
        private static final byte[] HEADER = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        @Override
        public Optional<String> check(Path dir) {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".db") && Files.isRegularFile(f))::iterator) {
                    Optional<String> problem = checkDb(p);
                    if (problem.isPresent()) {
                        return problem;
                    }
                }
            } catch (IOException e) {
                return Optional.of("walk failed: " + e.getMessage());
            }
            return new FilesChecker().check(dir);
        }

        /**
         * Header-only check. The PRAGMA quick_check path this used to call needed a
         * JDBC driver that is not on the classpath, so it always silently returned
         * "fine" — dead code pretending to be a safety net. If a driver is ever
         * bundled, restore the query here (it must stay read-only!).
         */
        private Optional<String> checkDb(Path db) {
            try {
                byte[] head = new byte[HEADER.length];
                try (var in = Files.newInputStream(db)) {
                    if (in.readNBytes(head, 0, head.length) != head.length
                            || !java.util.Arrays.equals(head, HEADER)) {
                        return Optional.of("bad SQLite header: " + db.getFileName());
                    }
                }
                return Optional.empty();
            } catch (IOException e) {
                return Optional.of("unreadable: " + db.getFileName());
            }
        }
    }
}
