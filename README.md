# Sync Groups

A client-side Fabric mod for Minecraft 26.2. It lets you put several servers
into a group so that per-server client data — minimap waypoints, world-map
regions, Bobby's chunk cache, Distant Horizons LODs — is shared between them
as if they were one server.

Nothing is uploaded. Everything happens between folders on your own disk.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API
- Java 25
- Mod Menu is optional; without it the settings screen is simply not reachable
  from the mod list (the files below still work).

## Install

Drop the jar into `.minecraft/mods`. That's it — the mod creates its own
folder on first launch and does nothing until you make a group.

## Making a group

1. Multiplayer → **Sync Groups** (bottom-left corner of the server list).
2. Pick at least two saved servers and press **Next**.
3. Choose a tag (16 characters max) and a color.

The first selected server that has data for a given mod seeds that mod's folder.
Any other server's differing data is moved to `_backups/` rather than merged, so
nothing is lost, but the group starts from one server's map. A server can belong
to only one group.

## The two modes

**Copies** (default). On join, the group's data is copied into that server's
folder. On leave — and every few minutes while playing — the local folder is
copied back into the group. Server folders stay ordinary directories, so you can
inspect or back them up yourself.

**Symlinks.** The per-server folder is replaced by a relative symlink into the
shared group folder. Every grouped server works on literally the same files, and
there is nothing to push. On Windows this needs Developer Mode or an
administrator account; without it the mod falls back with a warning in the log.
Litematica's per-server files (`litematica_<address>*.json`) stay on copies in
both modes — they are individual files, not folders.

Switching modes converts the folders on disk rather than just flipping a flag.
The conversion runs in the background, and if you switch while a world is open
it waits until the next launch, because the mods have their storage in use.
Nothing is deleted during a conversion: if both the local folder and the group
folder hold data and they differ, the local one is moved to `_backups/`.

## Supported mods

| Mod | Where it keeps per-server data |
| --- | --- |
| Xaero's Minimap (25.3+) | `xaero/minimap/Multiplayer_<host>/` |
| Xaero's World Map (1.39+) | `xaero/world-map/Multiplayer_<host>/` |
| Litematica | `config/litematica/litematica_<address>*.json`, `area_selections_per_world/<address>/` |
| Bobby | `.bobby/<address>/` |
| Distant Horizons | `Distant_Horizons_server_data/<server list name>/` |

**Voxy is not supported.** It keys its storage by a hash of the world seed and
dimension, which cannot be derived from the server address, so there is no
folder to point at.

Every path above can be overridden in `paths.json` (see below) if a mod update
moves something.

## Where the files are

```
config/syncgroups/
  groups.json          groups, colors, server names
  paths.json           paths and settings
  diagnostics.log      what the mod did, in order
  <group>/<mod>/<root>/  the shared data
  _backups/            local folders displaced by a conversion
  _dissolved/          groups you deleted
```

Note the `config/` prefix. A folder in the game directory root from an older
build is moved here automatically on first launch.

## Settings

Mod Menu → Sync Groups, or edit `paths.json` by hand:

| Key | Default | Meaning |
| --- | --- | --- |
| `syncMode` | `copy` | `copy` or `symlink` |
| `periodicPushMinutes` | `5` | How often the local folder is pushed while playing |
| `storageCloseTimeoutSeconds` | `30` | How long to wait for a mod to close its files on leave |
| `pushQuiescenceMs` | `1500` | How long files must stop changing before a push counts as safe |
| `showGroupTags` | `true` | Draw the group tag next to a server in the list |

The three timing values exist because Xaero and Bobby have no "I closed my
storage" event. The mod watches the files instead. On a slow disk, or with very
large world-map data, raising them is the fix for pushes that get skipped.

## Troubleshooting

The mod logs every decision — which server it resolved, which group matched,
which paths it touched, how many files and bytes moved. When sync appears to do
nothing, read `config/syncgroups/diagnostics.log` from the top. It is the only
file worth attaching to a bug report.

## Building

```
JAVA_HOME=/usr/lib/jvm/java-25-temurin ./gradlew build
```

The jar lands in `build/libs/`. There is no Yarn mapping for 26.x, so the code
uses Mojang's official names directly and Loom runs without remapping.

## License

GNU General Public License, version 3 or later. See [LICENSE](LICENSE).

In practice: you may use, modify and redistribute this mod, but a distributed
modified version must stay under the same license and ship its source. You
cannot take this code into a closed-source mod.
