package com.example.worlddatafacade;

import net.wdc.core.api.WDC;
import net.wdc.core.api.request.BlockVisitor;
import net.wdc.core.api.request.WDCBlockFilter;
import net.wdc.core.api.request.WDCBlockInfo;
import net.wdc.core.api.request.WDCBlockMatch;
import net.wdc.core.api.request.WDCChunkResult;
import net.wdc.core.api.request.WDCLightResult;
import net.wdc.core.api.request.WDCMaterial;
import net.wdc.core.api.request.WDCWaterInfo;
import net.wdc.core.api.spatial.WDCGridSample;
import net.wdc.core.api.spatial.WDCRegionSummary;
import net.wdc.core.api.spatial.WDCVisitResult;
import net.wdc.core.api.world.WDCWorldSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Loader-neutral facade over the WorldData Core (WDC) public API.
 *
 * Copy the methods you need into your own mod. Every method says which Minecraft call it replaces, when the
 * replacement is worth it, and what to watch for.
 *
 * HOW TO SWITCH FROM THE MINECRAFT API TO WDC
 *
 * 1. Dimensions are plain strings: level.dimension().location().toString(), for example "minecraft:overworld"
 *    or "mymod:mining_world". Modded dimensions work the same way.
 *
 * 2. Coordinates are absolute block coordinates, like BlockPos x/y/z. WDC converts them to chunk and section
 *    coordinates itself (blockX >> 4, blockZ >> 4).
 *
 * 3. Modded blocks, fluids and states are addressed by registry id ("modid:block_name") or by tag
 *    ("c:ores"), never by a Java class. That is why no Minecraft type appears in this file.
 *
 * 4. Hold a result and ask it many questions. requests().chunk(...) once per chunk is far cheaper than once
 *    per block. Results are immutable snapshots of what the cache knew at that moment.
 *
 * 5. Read on the thread that owns the world (server thread or client thread). A pull in the middle of a
 *    tick is best effort. For guaranteed accuracy read at the end of the tick, use the previous tick's
 *    result, or use the push feed in ChangeFeedExample.
 *
 * WHAT WDC DOES NOT DO, AND HOW TO COVER THE GAP
 *
 * WDC never loads chunks, never writes to the world, and does not know entities, block entities or weather
 * simulation. When a read has no answer, use the same pattern everywhere: ask WDC first and fall back to
 * Minecraft only when WDC has nothing. withFallback(...) below is that pattern in one line:
 *
 *     String id = WorldDataFacade.withFallback(
 *             WorldDataFacade.blockIdAt(dim, x, y, z),
 *             () -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
 *
 * Writes always go through Minecraft. After a bulk edit that bypasses LevelChunk.setBlockState (direct
 * section writes, editor tools) call markChunkDirty(...) so WDC re-reads those chunks.
 */
public final class WorldDataFacade {

    private WorldDataFacade() {}

    /**
     * The fallback pattern. Returns the WDC answer when there is one, otherwise whatever the supplier
     * computes from the Minecraft API. Use it for exact same-instant reads, unloaded chunks and anything
     * WDC does not provide.
     */
    public static <T> T withFallback(Optional<T> wdc, Supplier<T> minecraft) {
        return wdc.orElseGet(minecraft);
    }

    /**
     * Replaces: level.getChunk(x >> 4, z >> 4) followed by chunk.getBlockState(...) calls.
     *
     * Use it once per chunk you are working in and keep the result. Check isUsable() before trusting it:
     * LIVE means WDC currently mirrors that chunk, STALE means WDC holds the last known data after the
     * chunk left Minecraft, and anything else means there is nothing to read.
     */
    public static WDCChunkResult chunkAt(String dimensionId, int blockX, int blockZ) {
        return WDC.requests().chunk(dimensionId, blockX >> 4, blockZ >> 4);
    }

    /**
     * Replaces: BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).
     *
     * Returns the registry id of the block, vanilla or modded. Empty when the chunk is unusable. Air is
     * reported as not present, so it also returns empty.
     */
    public static Optional<String> blockIdAt(String dimensionId, int x, int y, int z) {
        WDCChunkResult chunk = chunkAt(dimensionId, x, z);
        if (!chunk.isUsable()) return Optional.empty();
        WDCBlockInfo info = chunk.blockAt(x, y, z);
        return info.present() ? Optional.of(info.blockId()) : Optional.empty();
    }

    /**
     * Replaces: level.getBlockState(pos).isAir().
     *
     * Returns false when the chunk has no usable data, so "false" can mean "solid" or "unknown". Check
     * chunkAt(...).isUsable() when you need to tell them apart.
     */
    public static boolean isAir(String dimensionId, int x, int y, int z) {
        WDCChunkResult chunk = chunkAt(dimensionId, x, z);
        return chunk.isUsable() && !chunk.isPresent(x, y, z);
    }

    /**
     * Replaces: state.is(BlockTags.LOGS), state.getBlock() instanceof LeavesBlock, state.is(Blocks.SAND).
     *
     * WDCMaterial is a small fixed list (STONE, DIRT, GRASS, SAND, WATER, LEAVES, WOOD, ...). Modded blocks
     * are classified from Minecraft's tags and ids; anything WDC cannot place is UNKNOWN. Use it for broad
     * decisions (footstep type, ambience, "is this foliage"). Use block ids or tags when you need exactness.
     */
    public static WDCMaterial materialAt(String dimensionId, int x, int y, int z) {
        return chunkAt(dimensionId, x, z).materialAt(x, y, z);
    }

    /**
     * Replaces: state.getFluidState().is(FluidTags.WATER) and the level and source checks on FluidState.
     *
     * Covers vanilla and modded fluids. isFluid() is true for any fluid; fluidLevel() is the vanilla 0-8
     * style level, -1 when there is no fluid.
     */
    public static WDCWaterInfo fluidAt(String dimensionId, int x, int y, int z) {
        return chunkAt(dimensionId, x, z).waterAt(x, y, z);
    }

    /**
     * Replaces: checking the fluid state of the block at an entity's eye position by hand.
     * Pass the block coordinates of the point you care about, for example the camera block.
     */
    public static boolean isSubmerged(String dimensionId, int x, int y, int z) {
        return chunkAt(dimensionId, x, z).isSubmerged(x, y, z);
    }

    /**
     * Replaces: level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) and manual downward scans.
     *
     * The height is stored per chunk when the chunk is read and updated on every block change, so this
     * never scans.
     *
     * The value is the Y of the top block. Minecraft's level.getHeight returns that Y plus one, so add
     * one when you need the vanilla number. A column with no matching block answers UNKNOWN.
     *
     * Pass a WDCHeightKind to heightmap().topY(localX, localZ, kind) for the other kinds. They follow
     * the vanilla predicates, built from each block state's own collision, fluid and leaves data, so
     * modded blocks behave like vanilla ones:
     * WORLD_SURFACE is the highest block that is not air (plants count).
     * MOTION_BLOCKING is the highest block that blocks motion or holds a fluid (plants are skipped,
     * water, lava and waterlogged blocks count).
     * OCEAN_FLOOR is the highest block that blocks motion (fluids and plants are skipped).
     * MOTION_BLOCKING_NO_LEAVES is MOTION_BLOCKING without leaves.
     */
    public static int surfaceY(String dimensionId, int x, int z) {
        return chunkAt(dimensionId, x, z).heightmap().topY(x & 15, z & 15);
    }

    /**
     * Replaces: level.getBrightness(LightLayer.SKY, pos), getBrightness(LightLayer.BLOCK, pos) and
     * level.getRawBrightness(pos, darkening).
     *
     * On the thread that owns the level (the server thread on a server, the client thread on a client)
     * light is read live from Minecraft, so it is exact at the moment of the call.
     * From any other thread, and for client reads of chunks the client does not hold, the answer comes
     * from a server snapshot that is kept in RAM only. The server thread builds it inside the tick
     * budget when a chunk loads and refreshes the sections around a block change, so it is at most a
     * few ticks behind.
     *
     * effectiveSkyLight() already includes day, night and weather darkening, computed at read time
     * from the current time and weather.
     *
     * read() tells you why there is no value: UNLOADED means Minecraft does not hold the chunk (fall
     * back or skip), PENDING means that section was never built yet (normally only right after the
     * chunk loaded, the next read answers), NO_WORLD means that dimension is not loaded on this side.
     * Call it on the game thread when you can.
     */
    public static WDCLightResult lightAt(String dimensionId, int x, int y, int z) {
        return WDC.light().read(dimensionId, x, y, z);
    }

    /**
     * Replaces: level.getBiome(pos).value().getBaseTemperature() and precipitation checks.
     *
     * Raw temperature is the biome's own value, not a simulated air temperature. Simulation (seasons,
     * altitude, weather) is yours to add on top of this raw value.
     */
    public static float rawBiomeTemperature(String dimensionId, int blockX, int blockZ) {
        return chunkAt(dimensionId, blockX, blockZ).biome().rawTemperature();
    }

    /**
     * Replaces: three nested loops over a chunk calling getBlockState and comparing with a tag or block.
     *
     * Counting and testing use the palette counts WDC already keeps, so the cost does not grow with the
     * number of positions in a tall chunk. Tags such as "c:ores" or "minecraft:logs" include every mod that
     * added to them. A tag WDC cannot resolve matches nothing.
     */
    public static int countByTag(String dimensionId, int chunkX, int chunkZ, String tagId) {
        return WDC.requests().chunk(dimensionId, chunkX, chunkZ).countBlocksByTag(tagId);
    }

    /** Replaces: scanning a chunk to answer "is there any of this block". Stops at the first match. */
    public static boolean chunkHasBlock(String dimensionId, int chunkX, int chunkZ, String blockId) {
        return WDC.requests().chunk(dimensionId, chunkX, chunkZ).hasBlockByName(blockId);
    }

    /**
     * Replaces: BlockPos.betweenClosed(...) scans that collect positions.
     *
     * Each match carries x, y, z and the block id. For very large result sets use visitBlocks on the chunk
     * result, which streams matches without building a list. For a custom rule build a WDCBlockFilter
     * (ofId, ofTag, ofStateId or the builder with exclude forms) and pass it to findBlocks.
     */
    public static List<WDCBlockMatch> findBlocks(String dimensionId, int chunkX, int chunkZ, WDCBlockFilter filter) {
        return WDC.requests().chunk(dimensionId, chunkX, chunkZ).findBlocks(filter);
    }

    /**
     * Replaces: looping over a whole block tag in the registry to find every state of every block in it.
     *
     * Returns WDC internal state ids. They are only valid in this session, so convert with
     * WDC.blocks().blockIdOf(id) before saving anything.
     */
    public static int[] stateIdsOfTag(String tagId) {
        return WDC.blocks().stateIdsOfTag(tagId);
    }

    /**
     * Replaces: a loop of getBlockState(pos.above()) calls down or up a single column.
     *
     * The visitor is called for every non-air block between minY and maxY and can stop early by returning
     * false. Empty sections are skipped without looking at their blocks.
     */
    public static WDCVisitResult visitColumn(String dimensionId, int x, int z, int minY, int maxY, BlockVisitor visitor) {
        return WDC.spatial().visitColumn(dimensionId, x, z, minY, maxY, visitor);
    }

    /**
     * Replaces: sampling a sphere of block states around a point to learn how much water, leaves or stone
     * is around here.
     *
     * Spatial calls span several chunks, are limited (radius 192, 1024 chunks per call) and report coverage,
     * so you can see when some chunks were missing instead of silently getting a partial answer.
     */
    public static WDCRegionSummary summarizeAround(String dimensionId, int x, int y, int z, int radius) {
        return WDC.spatial().summarizeSphere(dimensionId, x, y, z, radius);
    }

    /**
     * Replaces: calling level.getHeight for a grid of columns to build a height or water map.
     *
     * One call returns one cell per cellSize x cellSize area, with coverage. Good for fog, clouds, minimaps.
     */
    public static WDCGridSample sampleGrid(String dimensionId, int minX, int minZ, int maxX, int maxZ, int cellSize) {
        return WDC.spatial().sampleGrid(dimensionId, minX, minZ, maxX, maxZ, cellSize);
    }

    /**
     * Replaces: level.getDayTime(), level.getGameTime(), level.isRaining(), level.isThundering(),
     * level.getRainLevel(1.0F), level.getMoonPhase().
     *
     * Raw vanilla values only. WDC does not simulate weather. Empty when the dimension is not loaded on
     * this side.
     */
    public static Optional<WDCWorldSnapshot> world(String dimensionId) {
        return WDC.world().snapshot(dimensionId);
    }

    /**
     * Tells you why a chunk has no data: never requested, being acquired, unavailable, or unloaded. Use it
     * to decide between waiting, skipping and falling back to Minecraft.
     */
    public static String availability(String dimensionId, int chunkX, int chunkZ) {
        return WDC.requests().availability(dimensionId, chunkX, chunkZ).name();
    }

    /**
     * Use after code that changes blocks without LevelChunk.setBlockState (bulk tools, direct section
     * writes). WDC cannot see those edits by itself. It re-reads only chunks Minecraft already holds,
     * spread over the next ticks. Returns false when the chunk is not tracked.
     */
    public static boolean markChunkDirty(String dimensionId, int chunkX, int chunkZ) {
        return WDC.changes().markChunkDirty(dimensionId, chunkX, chunkZ);
    }
}
