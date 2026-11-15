# Changelog — since 0.4.4

All notable changes from the first published release (**0.4.4**, Minecraft 26.2) to **0.5.7** (Minecraft 26.3).

## 0.5.x — Minecraft 26.3

### Port & new features

- **Minecraft 26.3.** Full port to the rewritten 26.3 GUI stack (`extractRenderState` pipeline, no GuiGraphics), Loader 0.20.1, Java 25. Mod Menu integration kept optional — the mod runs without it.
- **Voxy support (0.2.x).** `.voxy/saves/<address>/` syncs as a per-server container, like Xaero. Copy mode is last-write-wins (RocksDB cannot be merged) — symlink mode is the honest choice for Voxy.
- **Sync mode conversion.** Switching between copy and symlink modes is a real on-disk conversion, not a flag flip: links are materialised back into copies (into `_backups` when both sides hold data), and vice versa. Runs on a background thread — never blocks the loading screen. Never deletes a source.
- **Background dissolve.** Removing a server from a two-server group dissolves it: a confirmation first, then the shared folders are copied out on a worker thread with a status line — the screen no longer freezes for minutes on large Bobby/DH caches.
- **Crash recovery.** A crashed session's newest local data is published into the group before anything can overwrite it (markers carry the real server key now).

### Data-safety fixes

- `{name}`-keyed roots (Distant Horizons) with a missing server-list name no longer resolve to the whole mod root — one join could move every server's DH data into the group.
- Recovery no longer fails silently on a sanitized marker name, and never deletes suspect data: it goes to `_backups` instead.
- The symlink pull backs up a differing local folder instead of deleting it.
- Removing a server from a 3+ group detaches its symlinks — it no longer keeps writing into the group's shared folder.
- `groups.json` / `paths.json` writes are atomic and roll back in memory on failure; a dissolve restores the group folder if the write fails.
- probe-lock honours `tryLock() == null` and read-only files; integrity check accepts empty databases and Distant Horizons' `.sqlite`.
- `paths.json` migration works for files that predate the `configVersion` key; a cleared root stays a real opt-out.

### Lifecycle fixes

- The join pull runs outside the session monitor — the render thread no longer stalls on it.
- A connect that never opens a world no longer leaves a phantom session pushing to the wrong group.
- A stale pull finishing after a newer one can no longer overwrite the session.
- Mode conversions cannot overlap; the autosave interval applies immediately.

### UI

- The creation wizard keeps its selection across scrolling and resizing; failures are shown, not swallowed.
- The Create button activates as you type the tag; both wizard buttons explain themselves instead of dead-ending on grey.
- The Sync Groups button on the multiplayer screen is anchored to the vanilla button rows (FancyMenu-friendly) instead of a hardcoded corner.
- Any saved server can be added to a group, offline ones included — that is the user's call.
- ~15 new localization strings, complete en/ru parity.

### For 0.4.4 users upgrading

The data folder moved from the game dir root to `config/syncgroups/` — moved automatically on first launch. `paths.json` and `groups.json` migrate themselves.
