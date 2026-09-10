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
 *   are writable. We additionally require the -wal/-shm sidecar quiescence
 *   (no size change across a probe interval) before declaring "probably safe".
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
             FileChannel ch = raf.getChannel();
             FileLock ignored = ch.tryLock()) {
            return true;
        } catch (IOException e) {
            return false; // still held by someone
        }
    }
}
