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
        return serverKey.replaceAll("[^a-z0-9._%-]", "_");
    }
}
