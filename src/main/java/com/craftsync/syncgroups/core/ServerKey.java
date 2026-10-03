package com.craftsync.syncgroups.core;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Normalizes a server address into the stable key used everywhere:
 * groups.json membership, folder names under syncgroups/, session markers.
 *
 * Key = the address as typed, lowercased (scheme stripped). Deliberately NOT
 * port-padded: Xaero/Bobby/Litematica all key their folders on the raw typed
 * address, so an invented ":25565" would break pattern matching. Two servers
 * differing only by an omitted default port are treated as different keys —
 * same MVP trade-off as any IP change (spec §0).
 */
public final class ServerKey {

    private ServerKey() {
    }

    /** Returns e.g. {@code mc.example.com:25565} or {@code mc.example.com}; never null. */
    public static String normalize(String address) {
        String s = address == null ? "" : address.trim().toLowerCase();
        if (s.contains("://")) {
            try {
                URI uri = new URI(s);
                s = uri.getHost() != null ? uri.getHost() : s.substring(s.indexOf("://") + 3);
                if (uri.getPort() > 0) {
                    s += ":" + uri.getPort();
                }
            } catch (URISyntaxException ignored) {
            }
        }
        while (s.endsWith(":")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Filesystem-safe variant (hosts are mostly safe, but be defensive). */
    public static String sanitize(String serverKey) {
        String s = serverKey.replaceAll("[^a-z0-9._%-]", "_");
        // A legal SMB share name (and a safe file name everywhere else): no
        // leading/trailing dot, bounded length, never empty. Keys are already
        // lowercased by normalize(), so lowercasing here only helps callers that
        // pass a raw address.
        s = s.toLowerCase(java.util.Locale.ROOT).replaceAll("^[._-]+|[.]+$", "");
        if (s.isEmpty()) {
            return "server";
        }
        return s.length() <= 64 ? s : s.substring(0, 64);
    }
}
