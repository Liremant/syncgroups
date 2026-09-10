package com.craftsync.syncgroups.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata of one sync group, as persisted in groups.json.
 * The id is stable across renames; servers are normalized {@code host:port} keys.
 * Invariant enforced by GroupsStore: a server belongs to at most one group.
 */
public class Group {

    public String id;
    public String tag;
    /** Hex string like "#55FFAA" (hand-editable in groups.json). */
    public String color = "#55FFAA";
    public List<String> servers = new ArrayList<>();
    public long createdAt;

    public boolean contains(String serverKey) {
        return servers.contains(serverKey);
    }

    public int colorInt() {
        try {
            int rgb = Integer.parseInt(color.replace("#", ""), 16) & 0xFFFFFF;
            return 0xFF000000 | rgb;
        } catch (Exception e) {
            return 0xFF55FFAA;
        }
    }
}
