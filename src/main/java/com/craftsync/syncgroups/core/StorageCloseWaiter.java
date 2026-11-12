package com.craftsync.syncgroups.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Best-effort wait for a mod to release its storage before we copy it
 * (spec §4.4 / §8 blocker). Honest about its limits:
 *
 * - "probe-lock": repeatedly tries to open every regular file READ/WRITE and
 *   take an OS lock. On Linux SQLite does not hold POSIX locks while idle, so
 *   a success here does NOT prove the handle is closed — it only proves files
 *   are unlocked and writable. A lock held by our own JVM's native library
 *   (RocksDB) is invisible to it too, because POSIX scopes fcntl locks per
 *   process. Treat the result as a hint; the quiescence wait is the real guard.
 * - Mods that expose a real shutdown hook should register a listener via
 *   {@link #registerListener} instead — probe mode is the fallback, not the goal.
 */
public final class StorageCloseWaiter {

    private static final Logger LOGGER = LoggerFactory.getLogger("syncgroups/closewait");

    public interface Listener {
        /** Return true once the mod has fully released storage for its data dir. */
        boolean isClosed(Path dataDir);
    }

    private static Listener extraListener = dir -> true;

    public static void registerListener(Listener l) {
        extraListener = l;
    }

    /**
     * Blocks until the storage looks released or the timeout elapses.
     * Returns true when "released", false on timeout (caller must log and skip).
     */
    public static boolean awaitClosed(Path dataDir, long timeoutMs) {
        if (!Files.isDirectory(dataDir)) {
            return true;
        }
        if (!extraListener.isClosed(dataDir)) {
            LOGGER.warn("Mod did not confirm storage closure for {} within timeout, skipping", dataDir);
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (filesQuiet(dataDir)) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        LOGGER.warn("Timed out waiting for storage release: {} — files may be in an unsafe state", dataDir);
        return false;
    }

    /**
     * Waits until no file under {@code dir} changes for {@code stableMs}.
     * This is what actually catches mods that write during teardown but hold no
     * locks (Xaero flushes world-map regions after the disconnect event): the
     * probe-lock check alone would pass immediately and we would copy a
     * half-written snapshot.
     */
    public static boolean awaitQuiescent(Path dir, long timeoutMs, long stableMs) {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        long stableSince = -1;
        long last = fingerprint(dir);
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long cur = fingerprint(dir);
            if (cur != last) {
                last = cur;
                stableSince = -1;
            } else if (stableSince == -1) {
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince >= stableMs) {
                return true;
            }
        }
        LOGGER.warn("Timed out waiting for {} to stop changing — pushing anyway", dir);
        return false;
    }

    /** Cheap order-sensitive digest of relative path + size + mtime for every file. */
    private static long fingerprint(Path dir) {
        long h = 1125899906842597L;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                h = 31 * h + p.getFileName().toString().hashCode();
                try {
                    h = 31 * h + Files.size(p);
                    h = 31 * h + Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            return h;
        }
        return h;
    }

    private static boolean filesQuiet(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                if (!writableAndLockable(p)) {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private static boolean writableAndLockable(Path p) {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            // tryLock returns null (it does not throw) when somebody else holds the
            // lock, so binding it to try-with-resources used to report exactly the
            // "still held" case as safe.
            FileLock lock = ch.tryLock();
            if (lock == null) {
                return false;
            }
            lock.release();
            return true;
        } catch (java.io.FileNotFoundException e) {
            // A read-only file cannot be a mod's live write handle, and RandomAccessFile
            // reports it as FileNotFoundException (not AccessDeniedException). We copy
            // such files all the time (COPY_ATTRIBUTES keeps 0444 bits), so treating
            // them as "still in use" blocked every later push for that mod.
            return !Files.exists(p) || !Files.isWritable(p);
        } catch (IOException e) {
            return false; // locked by another process, or otherwise unavailable
        }
    }
}
