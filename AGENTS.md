# AGENTS.md — Sync Groups (Fabric client mod, Minecraft 26.2)

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
