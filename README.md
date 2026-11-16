# WorldData Core Facade (example mod)

Shows how to use the WorldData Core (WDC) API instead of reading the world through Minecraft directly.
Read the comments in `src/main/java/com/example/worlddatafacade/WorldDataFacade.java`: each method names the Minecraft call it replaces, when the swap is worth it, and what to watch for.

## Layout

| Path | What it is |
|------|------------|
| `src/main/java/.../WorldDataFacade.java` | Loader-neutral wrappers: block, material, fluid, height, light, biome, scans, columns, grids, spatial, world time, and the `withFallback` pattern |
| `src/main/java/.../ChangeFeedExample.java` | The push pipe: end-of-tick coalesced block changes |
| `fabric/`, `neoforge/` | Minimal entrypoints and mod metadata |

The loader-neutral sources compile against the WorldData Core jar only. No Minecraft types are needed to call the API.

## Depending on WDC

Add the WorldData Core 1.0.0 jar for your loader as a compile-only dependency and declare it as a required dependency in your mod metadata, as the two metadata files here do. Do not shade it into your jar.

## Requires: 
https://github.com/RohitVoxel/WorldData-Core

## When to use WDC and when to use Minecraft

| Use WDC for | Keep Minecraft for |
|-------------|--------------------|
| Repeated or bulk reads: scans, counts, region summaries, height maps, grids | One-off exact reads in the middle of tick logic |
| Data several mods ask for at once (built once, shared) | Writes of any kind (WDC is read-only) |
| Reacting to changes without polling | Entities, block entities, anything WDC does not store |
| Modded blocks and fluids by id or tag | Loading chunks (WDC never does) |

Cover any gap with the fallback pattern: ask WDC first, call Minecraft only when WDC has no answer (`WorldDataFacade.withFallback`).

## Rules

- Dimension ids are strings: `level.dimension().location().toString()`.
- Coordinates are absolute block coordinates.
- Hold a result and ask it many questions instead of fetching a chunk per block.
- Check `isUsable()` before trusting a chunk result.
- Read on the thread that owns the world. After bulk edits that bypass normal block updates, call `markChunkDirty`.
- Heights are the Y of the top block. Minecraft's `Level.getHeight` returns that Y plus one, so add one for the vanilla number. The kinds follow the vanilla predicates: `MOTION_BLOCKING` skips plants and counts fluids, `OCEAN_FLOOR` skips fluids and plants, `MOTION_BLOCKING_NO_LEAVES` also skips leaves.
- Light is exact on the thread that owns the level. Other threads read a RAM-only server snapshot that is built as chunks load and refreshed around block changes; sky darkening is computed from the current time and weather at read time.
