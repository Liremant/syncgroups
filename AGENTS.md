# AGENTS.md — Sync Groups (Fabric client mod, Minecraft 26.3)

## Project overview

Client-only Fabric mod that lets a player group several multiplayer servers
into a "sync group" so per-server client-mod data (waypoints, maps, chunk/LOD
caches) is shared across the group as if it were one server (full spec: the
"Sync Groups" ТЗ, Sept 2026).

- Package root: `com.craftsync.syncgroups`.
- `core/` — pure-Java disk logic (no MC classes): `SyncEngine` (pull/push/
  backup/recovery), `GroupsStore` + `Group` (groups.json), `ServerKey`,
  `SessionManager`, `IntegrityChecker`, `StorageCloseWaiter`, `DirOps`.
- `integration/` — supported-mod table (`ModSpecs` defaults, `ModSyncSpec.Root`
  dir/prefix modes) and the user-editable `PathsConfig` (paths.json).
- `ui/` — screens (create/manage group, server picker, Mod Menu settings) on the
  26.3 `extractRenderState` pipeline.
- `mixin/` — `ConnectScreenMixin` (pre-connect pull), `JoinMultiplayerScreenMixin`
  (button), `ServerEntryTagMixin` (group tag on server entries).
- Optional Mod Menu integration: `integration/ModMenuIntegration` + a `modmenu`
  entrypoint. Mod Menu is `compileOnly` + `suggests` — never make it a hard
  dependency, the mod must run without it.
- Resources: `fabric.mod.json`, `syncgroups.mixins.json`, `assets/syncgroups/lang/`.
- On-disk data (game dir): `config/syncgroups/groups.json` (metadata, hex colors),
  `config/syncgroups/<gid>/<modid>/<rootSyncName>/` (shared data), `_backups/`,
  `_dissolved/`, `paths.json` (path overrides + settings), `diagnostics.log`.
  **The folder lives under `config/`**, not the game dir root; `SyncGroupsMod`
  migrates a legacy `syncgroups/` folder on startup.
- Two sync modes (`syncMode` in paths.json, editable in Mod Menu).
  `copy`: pull on join, push on leave. `symlink`: the local per-server folder is
  replaced by a relative symlink into the group folder, so every grouped server
  works on literally the same files and there is nothing to push. Symlinks apply
  to dir-mode roots only; Litematica's per-file roots always stay on copies.
- **A mode switch is a conversion, not a flag.** `SyncGroupsMod.reconcileSyncMode()`
  (startup, and after a settings change) and `SyncEngine.convertMode(...)` do the
  on-disk work; the conversion runs on a background thread because materialising a
  group back into copies can mean hundreds of MB (Bobby regions, DH databases) and
  must not stall the loading screen. It is serialised with the join pull via
  `SyncEngine.fsLock`: one monitor. The conversion takes it per (server, root)
  and releases it between units, so a join waits for at most one server's copy
  rather than for the whole conversion; the join pull itself holds it for its own
  duration, and a dissolve holds it for the whole copy-out (so a connect made
  during a dissolve waits). Dirty-marker recovery and each push's copy take the
  same monitor. While a session is open the mode is only saved —
  the conversion waits for the next launch, because the mods have their storage in
  use. Before converting, pending dirty markers are published
  (`recoverDirtyMarkers`) so a crashed copy-mode session's newest data is not
  hidden behind the group copy. Conversion never deletes a source: real+link→link
  moves the local folder into `_backups/` when both sides have data (there is no
  "which side wins" picker, so nothing is discarded) unless
  `DirOps.sameContent` shows it is byte-identical to the group folder, in which
  case it is left to be replaced by the link; link→copy deletes only the link,
  then copies sync out.
- **Only copy mode writes dirty markers.** A marker means "this session may have
  left local data unpushed", so in symlink mode there is nothing to push and a
  marker would only mislead a later conversion into publishing a server's copy
  over the group. A mod with any non-symlinked root (Litematica's per-server
  json) still gets one. `handleDirtyMarker` decides on the *physical* state
  (is this path a link?) rather than on the configured mode, so a marker from a
  crashed copy-mode session is still published after a switch.
- **`DirOps.overlaps` is the last line of defence against a self-copy.** If a
  local path is a symlink into the group folder, source and target are the same
  directory; `copyTree` would `deleteTree(to)` and destroy the source before the
  walk even starts. `copyTree` now throws on overlapping paths, and `push` skips
  symlinked entries in copy mode with a warning.
- `Group.names` remembers the server-list name per key (persisted in groups.json,
  normalized to a non-null map on load). Needed by name-keyed roots (`{name}`,
  Distant Horizons) and by crash recovery for servers that are not the current
  session; a name-keyed root with no remembered name is skipped with a warning
  instead of guessing a path.
- `DirOps` is symlink-aware and this matters: `deleteTree` must never follow a
  link into its target, `replaceWithRelativeSymlink` stashes the existing folder
  and restores it when link creation fails (Windows needs Developer Mode or admin
  rights), and links are stored as plain relative paths with no `./` prefix so
  they work on both Linux and Windows and survive moving the game directory.

## Repo rules

- Single local branch, no remote configured. Do not add remotes, push, or
  create tags without an explicit user request.
- Version lives in `gradle.properties` (`mod_version`); bump it when behavior
  changes in a user-visible way.
- The spec (ТЗ) is the source of truth for behavior; deviations discovered by
  research (e.g. Distant Horizons keyed by server name, not address) are
  recorded below and in code comments — update both together.

## Workflow rules

- **Maintain git history.** Commit after every meaningful unit of work (feature,
  fix, refactor, research-driven change). Do not leave the tree dirty at the end
  of a session. Commit messages: short imperative English, e.g. `core: add
  storage close waiter`. Never amend or rewrite pushed history.
- Do not commit `build/`, `.gradle/`, `.tools/` (see .gitignore).
- Multiple agent sessions may work in this repo concurrently. Before editing a
  file, Read it fresh; if it already contains adaptations you did not write,
  keep them and build on top instead of overwriting.

## Build & toolchain

- Minecraft **26.3**, Fabric Loader **0.20.1**, Fabric API **0.165.0+26.3**,
  Loom **1.17-SNAPSHOT**, Java **25** (system default is 21 — always set
  `JAVA_HOME=/usr/lib/jvm/java-25-temurin` for gradle).
- Build/compile: `JAVA_HOME=/usr/lib/jvm/java-25-temurin ./gradlew build`
- Gradle wrapper is 9.5.1 (system gradle 9.4 is too old for Loom 1.17 — do not
  use bare `gradle`).
- Loom stays on **1.17-SNAPSHOT** even for 26.3: Loom 1.19.x requires Gradle
  >= 9.8 and the wrapper here is 9.5.1. Loom 1.17.21 builds 26.3 fine.
- **26.3 API moves found so far**: `Util.getPlatform().openPath(Path)` is gone;
  opening a folder is `com.mojang.blaze3d.Blaze3D.openPath(Path)`. Everything
  else the mod touches (`Screen.extractRenderState`, `GuiGraphicsExtractor`,
  `MouseButtonEvent`, `ServerSelectionList$OnlineServerEntry.extractContent`,
  `ConnectScreen.startConnecting`) kept its 26.2 shape. Verify again on the next
  MC bump — mixin targets are strings, a green compile proves nothing.

## Mapping conventions (critical)

- **There is no Yarn for 26.x.** Code uses Mojang official names directly.
  Loom runs in non-remapping mode: no `mappings` dependency, no mixin refmaps.
- Mixin configs: `compatibilityLevel: JAVA_25`, `overwrites.requireAnnotations`.
- **The 26.3 GUI stack is rewritten**: there is no `GuiGraphics`. Screens
  override `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)`;
  selection-list entries implement
  `extractContent(GuiGraphicsExtractor, mouseX, mouseY, hovered, partialTick)`
  and get geometry via `getContentX()/getContentY()/getContentWidth()`.
  Text drawing: `gui.text(...)`, `gui.centeredText(...)`; screens get the menu
  background via `extractBackground(...)`.
- Saved servers render through `ServerSelectionList$OnlineServerEntry`
  (`NetworkServerEntry` is LAN-only in 26.x). `ServerData` fields: `ip`, `name`.
- **Do not @Shadow members inherited from a superclass** (e.g. `Screen
  .addRenderableWidget` when targeting a Screen subclass): descriptor matching
  is exact, and shadow resolution of inherited *methods* fails at runtime under
  Knot even when the descriptor matches. Instead, make the mixin class extend
  the declaring superclass (e.g. `abstract class XMixin extends Screen`) and
  call the method normally. Inherited shadows of *fields* and members declared
  directly in the target class are fine.
- **Before using any MC API, verify it against the real mapped jar**:
  `~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar` via `javap`.
  Compile success proves nothing for mixin targets (string-based).

## Domain facts (verified Sept 2026 against mod sources/bytecode)

- Supported-mod paths live in `ModSpecs` defaults and are user-overridable in
  `config/syncgroups/paths.json` inside the game dir (written on first
  run). Do not hardcode paths elsewhere.
- Xaero (≥ Minimap 25.3 / World Map 1.39): `xaero/minimap`,
  `xaero/world-map`, key `Multiplayer_<host-without-port>`. Legacy roots
  `XaeroWaypoints/`/`XaeroWorldMap/` are pre-2025 only.
  **Dir mode syncs the CONTENT of the per-server container** (`config.txt`,
  `dim%0/`, `null/`, `DIM1/`...), never the container itself — nesting one
  server's container inside another's produces a node the mod ignores.
  Verified in production (artifact cleaned into `xaero/.syncgroups-artifact-backup-*`).
- Litematica: per-server data = `config/litematica/litematica_<address>*.json`
  (prefix mode) + `config/litematica/area_selections_per_world/<address>/`.
- Bobby: `.bobby/<ip with ':'→'_'>/` — Anvil region files, NOT SQLite. Closes
  synchronously on world leave; no public event → probe-lock fallback.
- Distant Horizons: `Distant_Horizons_server_data/<server-list-NAME>/`
  (serverFolderNameMode default NAME_ONLY) — keyed by `{name}`, not address.
  SQLite per (server, dimension). Has `DhApiWorldUnloadEvent`, but we do not
  depend on DH.
- Voxy 0.2.x: `.voxy/saves/<address with ':'→'_'>/<sha256(seed+dimension)>/storage/`
  (`VoxyClientInstance.getBasePath`, verified in 0.2.5 bytecode and against a
  live instance). The **container** is address-keyed exactly like Bobby — an
  earlier note here claimed Voxy was undeliverable by looking only at the inner
  hash layer, which is the Xaero mistake again. Sync the container content
  (`dir` root `.voxy/saves` + `{server}`), let the hash subfolders co-exist:
  the hash is SHA-256 over a `biomeSeed` plus the dimension key, which we never
  derive, so the folder simply carries whatever Voxy wrote — different worlds
  don't collide, and the same world re-visited under another address may or may
  not land on the same hash (we do not rely on either). `storage/` is **RocksDB**
  (ZSTD), which is not a mergeable-per-server format — copy mode is
  last-write-wins, so symlink mode is the honest choice for Voxy. RocksDB does
  hold `storage/LOCK` while open, but probe-lock cannot see it: fcntl locks are
  per-process (so a lock our own JVM's native lib holds is invisible) and the
  probe itself discards `tryLock`'s null. So the only guard is the quiescence
  wait, and it is best-effort: `SyncEngine.push` logs the result and copies
  anyway when it times out.
- VoxyExtra-style mods alias several addresses onto one folder by mixing into
  `Path.resolve`; we get the same effect from symlink mode without touching
  Voxy internals, so no mixin for it.
- No public "storage closed" events exist for Bobby/Xaero/Voxy; DH is the only
  one. `StorageCloseWaiter` probe-lock is best-effort, especially on Linux
  (SQLite holds no persistent POSIX locks while idle) — keep it honest in
  comments and logs.
- **The join pull has three hooks, in order: `ConnectScreen.startConnecting`
  (primary, via ConnectScreenMixin), `ClientPlayConnectionEvents.INIT` (fallback),
  then a bounded client-tick retry (3 attempts) if INIT could not resolve the
  server.**
  Do NOT rely on `Minecraft.getCurrentServer()` alone: it reads from the
  connection handler and can be null at INIT, which silently turned the whole
  join pull into a no-op. `handler.getServerData()` is the better source at INIT.
  JOIN is too late — Xaero has already initialized its sessions there and keeps
  serving stale in-memory data over freshly pulled files (verified in production).
  The hooks fire on different threads (render thread for ConnectScreen, netty
  for INIT), so SessionManager guards its state with the monitor — but the pull
  itself runs outside it, or a multi-second pull would block the render thread
  that reads that state every tick. A second hook for the same key is refused
  via `pullingFor`, and a connect that never opens a world clears the session.
- **Push waits for quiescence, but it is not a gate.** Xaero flushes world-map
  regions *after* our disconnect handler runs and holds no file locks, so a
  probe-lock check alone passes instantly and copies a half-written snapshot.
  `StorageCloseWaiter.awaitQuiescent(dir, timeout, stableMs)` polls a
  path+size+mtime fingerprint until it stops changing. The probe result, not
  the quiescence result, decides whether the copy happens — a quiescence timeout
  is logged and the push proceeds. Tune via `pushQuiescenceMs` in paths.json.
- Disconnect/periodic pushes run on a background thread (never freeze the UI)
  and a shutdown hook joins the in-flight push for up to 5 s. Caveat: FastQuit
  can terminate the JVM abruptly — a hard quit right after disconnecting may
  truncate the final push; the periodic push is the backstop.

## Diagnostics (remote debugging)

- **`syncgroups/diagnostics.log` is the single file to request from a tester.**
  `Diag` mirrors everything to the normal log and appends to this file (rotated
  to `diagnostics.prev.log` past 4 MB). It never throws — a broken diagnostics
  file must not break syncing.
- Logged at every decision point: game dir and env, resolved config values, each
  mod's installed state and roots, loaded groups with their server keys, the raw
  address and normalized key on connect, which group matched, per-root paths with
  file counts/bytes before and after each copy, quiescence/lock results, and
  bounded directory trees (`Diag.tree`) of the pulled and pushed data.
- When sync silently does nothing, read the log top-down: `JOIN:` tells whether
  the server matched a group, `PULL`/`PUSH` lines tell whether paths resolved.
- `paths.json` carries a `configVersion`; bump `PathsConfig.CONFIG_VERSION`
  when built-in defaults change (the old file is preserved as
  `paths.v<its version>.bak`, or `paths.v1.bak` for a file that predates the
  key). A bump only reaches specs the file does not have yet and ids listed in
  `PREVIOUSLY_ROOTLESS` — a spec already present keeps its paths, because
  overwriting a hand-edited path or a cleared root is worse than a stale
  default. Add an id there when a mod that shipped rootless gains roots.
