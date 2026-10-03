package com.craftsync.syncgroups.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /**
     * Last known server-list name per key. Name-keyed mods (Distant Horizons)
     * and crash recovery need it for servers that are not the current session,
     * where nothing else can supply the name. Additive field: older groups.json
     * files simply load with an empty map.
     */
    public Map<String, String> names = new LinkedHashMap<>();
    public long createdAt;

    public boolean contains(String serverKey) {
        return servers.contains(serverKey);
    }

    /** Remembered server-list name for a key, or null when never seen. */
    public String nameOf(String serverKey) {
        return names.get(serverKey);
    }

    /** Records a name, reporting whether the stored value actually changed. */
    public boolean rememberName(String serverKey, String serverName) {
        if (serverName == null || serverName.isBlank() || serverName.equals(names.get(serverKey))) {
            return false;
        }
        names.put(serverKey, serverName);
        return true;
    }

    public int colorInt() {
        try {
            int rgb = Integer.parseInt(color.replace("#", ""), 16) & 0xFFFFFF;
            return 0xFF000000 | rgb;
        } catch (Exception e) {
            return 0xFF55FFAA;
        }
    }

    /**
     * Snapshot for callers that keep their own copy while editing the live group
     * (creating one adds it to the store before the seeding loop runs).
     */
    public Group copyOf() {
        Group c = new Group();
        c.id = id;
        c.tag = tag;
        c.color = color;
        c.servers = new ArrayList<>(servers);
        c.names = new LinkedHashMap<>(names);
        c.createdAt = createdAt;
        return c;
    }
}
