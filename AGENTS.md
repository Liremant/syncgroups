# AGENTS.md — Sync Groups (Fabric client mod, Minecraft 26.2)

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
  26.2 `extractRenderState` pipeline.
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
- Two sync modes (`syncMode` in paths.json, editable in Mod Menu):
  `copy` (pull on join, push on leave) and `symlink` (the local per-server folder
  is replaced by a relative symlink into the group folder, so every grouped server
  works on literally the same files and there is nothing to push). Symlinks apply
  to dir-mode roots only; Litematica's per-file roots always stay on copies.
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
  research (e.g. Voxy unsupported by default, DH keyed by server name) are
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

- Minecraft **26.2**, Fabric Loader **0.19.5**, Fabric API **0.160.0+26.2**,
  Loom **1.17-SNAPSHOT**, Java **25** (system default is 21 — always set
  `JAVA_HOME=/usr/lib/jvm/java-25-temurin` for gradle).
- Build/compile: `JAVA_HOME=/usr/lib/jvm/java-25-temurin ./gradlew build`
- Gradle wrapper is 9.5.1 (system gradle 9.4 is too old for Loom 1.17 — do not
  use bare `gradle`).

## Mapping conventions (critical)

- **There is no Yarn for 26.x.** Code uses Mojang official names directly.
  Loom runs in non-remapping mode: no `mappings` dependency, no mixin refmaps.
- Mixin configs: `compatibilityLevel: JAVA_25`, `overwrites.requireAnnotations`.
- **The 26.2 GUI stack is rewritten**: there is no `GuiGraphics`. Screens
  override `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)`;
  selection-list entries implement
  `extractContent(GuiGraphicsExtractor, mouseX, mouseY, hovered, partialTick)`
  and get geometry via `getContentX()/getContentY()/getContentWidth()`.
  Text drawing: `gui.text(...)`, `gui.centeredText(...)`; screens get the menu
  background via `extractBackground(...)`.
- Saved servers render through `ServerSelectionList$OnlineServerEntry`
  (`NetworkServerEntry` is LAN-only in 26.2). `ServerData` fields: `ip`, `name`.
- **Do not @Shadow members inherited from a superclass** (e.g. `Screen
  .addRenderableWidget` when targeting a Screen subclass): descriptor matching
  is exact, and shadow resolution of inherited *methods* fails at runtime under
  Knot even when the descriptor matches. Instead, make the mixin class extend
  the declaring superclass (e.g. `abstract class XMixin extends Screen`) and
  call the method normally. Inherited shadows of *fields* and members declared
  directly in the target class are fine.
- **Before using any MC API, verify it against the real mapped jar**:
  `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` via `javap`.
  Compile success proves nothing for mixin targets (string-based).

## Domain facts (verified Sept 2026 against mod sources/bytecode)

- Supported-mod paths live in `ModSpecs` defaults and are user-overridable in
  `.minecraft/syncgroups/paths.json` (regenerated on first run). Do not
  hardcode paths elsewhere.
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
- Voxy: storage keyed by SHA-256(seed+dimension) — cannot be derived from the
  server identity; unsupported by default, mappable manually in paths.json.
- No public "storage closed" events exist for Bobby/Xaero/Voxy; DH is the only
  one. `StorageCloseWaiter` probe-lock is best-effort, especially on Linux
  (SQLite holds no persistent POSIX locks while idle) — keep it honest in
  comments and logs.
- **The join pull has three hooks, in order: `ConnectScreen.startConnecting`
  (primary, via ConnectScreenMixin), `ClientPlayConnectionEvents.INIT` (fallback),
  then a client-tick retry if INIT could not resolve the server.**
  Do NOT rely on `Minecraft.getCurrentServer()` alone: it reads from the
  connection handler and can be null at INIT, which silently turned the whole
  join pull into a no-op. `handler.getServerData()` is the better source at INIT.
  JOIN is too late — Xaero has already initialized its sessions there and keeps
  serving stale in-memory data over freshly pulled files (verified in production).
  SessionManager methods are synchronized because these hooks fire on different
  threads (render thread for ConnectScreen, netty for INIT).
- **Push waits for quiescence, not just locks.** Xaero flushes world-map regions
  *after* our disconnect handler runs and holds no file locks, so a probe-lock
  check alone passes instantly and copies a half-written snapshot.
  `StorageCloseWaiter.awaitQuiescent(dir, timeout, stableMs)` polls a
  path+size+mtime fingerprint until it stops changing. Tune via
  `pushQuiescenceMs` in paths.json.
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
  whenever built-in defaults change, otherwise existing installs keep stale
  values (the old file is preserved as `paths.v<N>.bak`).
