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
- `ui/` — screens (create/manage group, server picker) on the 26.2
  `extractRenderState` pipeline.
- `mixin/` — `JoinMultiplayerScreenMixin` (button) and `ServerEntryTagMixin`
  (group tag on server entries).
- Resources: `fabric.mod.json`, `syncgroups.mixins.json`, `assets/syncgroups/lang/`.
- On-disk data (game dir): `syncgroups/groups.json` (metadata, hex colors),
  `syncgroups/<gid>/<modid>/<rootSyncName>/` (shared copy), `syncgroups/<gid>/_backups/`,
  `syncgroups/_dissolved/`, plus `syncgroups/paths.json` (path overrides,
  regenerated on first run — do not hardcode paths in code).

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
- **The join pull runs on `ClientPlayConnectionEvents.INIT`, never JOIN.**
  Verified in production: on JOIN, Xaero has already initialized its sessions
  (its log line precedes our pull by ~1s) and keeps serving stale in-memory
  data over freshly pulled files. INIT fires before the login packet, on the
  netty thread — SessionManager methods are synchronized because of that.
