package com.craftsync.syncgroups.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Extended diagnostics. Everything goes to the normal log AND to a single
 * append-only file the player can send back wholesale:
 * {@code .minecraft/syncgroups/diagnostics.log}.
 *
 * Remote debugging is the whole point here — when sync silently does nothing,
 * the log must show every decision: which server we resolved, which group it
 * matched, which paths were probed, what was copied. Never throws; a broken
 * diagnostics file must not break syncing.
 */
public final class Diag {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_TREE_ENTRIES = 400;

    private static Path file;
    private static boolean broken;

    private Diag() {
    }

    public static void init(Path diagnosticsFile) {
        file = diagnosticsFile;
        try {
            Files.createDirectories(file.getParent());
            // Start each game launch with a fresh header, keep one previous run.
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling("diagnostics.prev.log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            broken = true;
        }
    }

    public static Path file() {
        return file;
    }

    // ------------------------------------------------------------------ levels

    public static void info(String fmt, Object... args) {
        write("INFO ", fmt, args, null);
    }

    public static void warn(String fmt, Object... args) {
        write("WARN ", fmt, args, null);
    }

    public static void error(String fmt, Object... args) {
        write("ERROR", fmt, args, null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, new Object[0], t);
    }

    private static void write(String level, String fmt, Object[] args, Throwable t) {
        String msg;
        try {
            msg = args.length == 0 ? fmt : String.format(fmt, args);
        } catch (RuntimeException e) {
            msg = fmt + " [format failed: " + e + "]";
        }
        switch (level.trim()) {
            case "WARN" -> LOGGER.warn(msg);
            case "ERROR" -> {
                if (t != null) {
                    LOGGER.error(msg, t);
                } else {
                    LOGGER.error(msg);
                }
            }
            default -> LOGGER.info(msg);
        }
        StringBuilder sb = new StringBuilder(msg.length() + 48)
                .append('[').append(LocalTime.now().format(TIME)).append("] ")
                .append(level).append(' ').append(msg).append('\n');
        if (t != null) {
            sb.append("      ").append(t).append('\n');
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("        at ").append(el).append('\n');
            }
        }
        append(sb.toString());
    }

    private static synchronized void append(String text) {
        if (file == null || broken) {
            return;
        }
        // '"S"' is not thread-safe: two chat/render threads writing at once could
        // interleave inside a line on the one file remote testers send us.
        // synchronized covers this method; every caller funnels through it.
        // ponytail: one lock for all diagnostics, split per level if contention
        // ever shows up in a profile.
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            broken = true; // log once, then stay quiet
        }
    }

    /** Banner that makes the start of a session obvious in the log. */
    public static void session(String title) {
        info("==================== %s ====================", title);
    }

    // ------------------------------------------------------------------ paths

    /** One-line description of a path: missing / file / dir with N files, X KB. */
    public static String describe(Path p) {
        if (p == null) {
            return "<null>";
        }
        if (!Files.exists(p)) {
            return p + "  [missing]";
        }
        if (Files.isRegularFile(p)) {
            try {
                return p + "  [file, " + Files.size(p) + " B]";
            } catch (IOException e) {
                return p + "  [file, size unknown]";
            }
        }
        long[] stats = countFiles(p);
        return p + "  [dir, " + stats[0] + " files, " + humanBytes(stats[1]) + "]";
    }

    private static long[] countFiles(Path dir) {
        long count = 0;
        long bytes = 0;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                count++;
                try {
                    bytes += Files.size(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return new long[]{count, bytes};
    }

    public static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }

    /** Lists a directory tree (bounded) so the log shows the real on-disk shape. */
    public static void tree(String label, Path dir, int maxDepth) {
        if (dir == null || !Files.isDirectory(dir)) {
            info("TREE %s: %s", label, dir == null ? "<null>" : dir + "  [missing]");
            return;
        }
        List<String> lines = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir, maxDepth)) {
            List<Path> all = s.sorted(Comparator.comparing(Path::toString)).toList();
            boolean truncated = all.size() > MAX_TREE_ENTRIES;
            for (Path p : all.subList(0, Math.min(all.size(), MAX_TREE_ENTRIES))) {
                String rel = dir.relativize(p).toString();
                if (rel.isEmpty()) {
                    continue;
                }
                String size = Files.isRegularFile(p) ? " (" + fileSize(p) + ")" : "";
                lines.add("    " + rel + size);
            }
            if (truncated) {
                lines.add("    ... (" + (all.size() - MAX_TREE_ENTRIES) + " more)");
            }
        } catch (IOException e) {
            info("TREE %s: %s  [walk failed: %s]", label, dir, e);
            return;
        }
        info("TREE %s: %s  (%d entries shown)", label, dir, lines.size());
        for (String l : lines) {
            append(l + "\n");
            LOGGER.info(l);
        }
    }

    private static String fileSize(Path p) {
        try {
            return humanBytes(Files.size(p));
        } catch (IOException e) {
            return "?";
        }
    }
}
