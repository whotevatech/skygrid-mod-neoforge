package com.skygrid.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skygrid.SkyGridConfig;
import com.skygrid.SkyGridMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SkyGridChunkGenerator extends ChunkGenerator {

    // -------------------------------------------------------------------------
    // Codec — includes dimension so the generator is saved/loaded correctly
    // -------------------------------------------------------------------------
    public static final MapCodec<SkyGridChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(gen -> gen.biomeSource),
            Codec.INT.optionalFieldOf("grid_spacing", 4).forGetter(gen -> gen.gridSpacing),
            Codec.STRING.optionalFieldOf("dimension", "overworld").forGetter(gen -> gen.dimension)
        ).apply(instance, SkyGridChunkGenerator::new)
    );

    // -------------------------------------------------------------------------
    // Always-excluded technical blocks
    // -------------------------------------------------------------------------
    private static final Set<Block> EXCLUDED_BLOCKS = Set.of(
        Blocks.AIR, Blocks.VOID_AIR, Blocks.CAVE_AIR,
        Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID,
        Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW,
        Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_GATEWAY,
        Blocks.NETHER_PORTAL, Blocks.MOVING_PISTON,
        Blocks.SPAWNER, Blocks.CHEST
    );

    // -------------------------------------------------------------------------
    // Per-dimension block pools — built lazily on first generation
    // -------------------------------------------------------------------------
    private static final Map<String, BlockState[]> DIMENSION_POOLS = new ConcurrentHashMap<>();

    public static BlockState[] getPublicBlockPool(String dimension) {
        return DIMENSION_POOLS.get(dimension);
    }

    /** Legacy accessor for the debug command — returns overworld pool. */
    public static BlockState[] getPublicBlockPool() {
        return DIMENSION_POOLS.get("overworld");
    }

    /** Clears all cached pools so they rebuild on next generation — called by /skygrid reload. */
    public static void clearPools() {
        DIMENSION_POOLS.clear();
    }

    private static BlockState[] getBlockPool(String dimension) {
        return DIMENSION_POOLS.computeIfAbsent(dimension, SkyGridChunkGenerator::buildBlockPool);
    }

    private static BlockState[] buildBlockPool(String dimension) {
        List<BlockState> pool = new ArrayList<>();
        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);

        // Build a weight lookup: blockId -> weight
        Map<String, Integer> weightMap = new HashMap<>();
        for (SkyGridConfig.BlockEntry entry : config.getBlockEntries())
            weightMap.put(entry.id(), entry.weight());

        for (Block block : BuiltInRegistries.BLOCK) {
            if (EXCLUDED_BLOCKS.contains(block)) continue;
            BlockState state = block.defaultBlockState();
            if (state.isAir()) continue;
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (!config.isAllowed(blockId)) continue;
            // Add the block N times according to its weight (default 1)
            int weight = weightMap.getOrDefault(blockId, 1);
            for (int i = 0; i < weight; i++) pool.add(state);
        }

        long uniqueBlocks = pool.stream().distinct().count();
        SkyGridMod.LOGGER.info("SkyGrid [{}] block pool: {}/{} unique blocks, {} weighted slots (mode: {}).",
            dimension, uniqueBlocks, BuiltInRegistries.BLOCK.size(), pool.size(), config.getMode());
        return pool.toArray(new BlockState[0]);
    }

    // -------------------------------------------------------------------------
    // Dimension-specific spawner mobs
    // -------------------------------------------------------------------------
    private static final EntityType<?>[] OVERWORLD_MOBS = {
        EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CAVE_SPIDER,
        EntityType.CREEPER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.SLIME,
        EntityType.PHANTOM, EntityType.HUSK, EntityType.STRAY, EntityType.DROWNED,
        EntityType.SILVERFISH,
    };

    private static final EntityType<?>[] NETHER_MOBS = {
        EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.ZOMBIFIED_PIGLIN,
        EntityType.MAGMA_CUBE, EntityType.HOGLIN, EntityType.PIGLIN_BRUTE,
        EntityType.STRIDER, EntityType.GHAST,
    };

    private static final EntityType<?>[] END_MOBS = {
        EntityType.ENDERMAN, EntityType.ENDERMAN, EntityType.ENDERMAN,
        EntityType.ENDERMITE, EntityType.SHULKER,
    };

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private final int gridSpacing;
    private final String dimension;

    public SkyGridChunkGenerator(BiomeSource biomeSource, int gridSpacing, String dimension) {
        super(biomeSource);
        this.gridSpacing = gridSpacing;
        this.dimension   = dimension;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // Main generation — place blocks at each grid position
    // -------------------------------------------------------------------------
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        int minY   = chunk.getMinBuildHeight();
        int maxY   = chunk.getMaxBuildHeight();

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    if (x % gridSpacing != 0 || y % gridSpacing != 0 || z % gridSpacing != 0) continue;

                    mutablePos.set(x, y, z);
                    Random rand = new Random(hashPos(x, y, z));
                    double roll = rand.nextDouble();

                    BlockState state;
                    if (roll < 0.008) {
                        state = Blocks.SPAWNER.defaultBlockState();
                    } else if (roll < 0.022) {
                        state = Blocks.CHEST.defaultBlockState();
                    } else {
                        BlockState[] pool = getBlockPool(dimension);
                        state = pool[rand.nextInt(pool.length)];
                    }

                    // Leaves never decay
                    if (state.getBlock() instanceof LeavesBlock) {
                        state = state.setValue(LeavesBlock.PERSISTENT, true);
                    }

                    // Saplings → dirt below, sapling on top
                    if (isSapling(state) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.DIRT.defaultBlockState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    // Nether fungi → matching nylium below, fungus on top
                    } else if (state.is(Blocks.CRIMSON_FUNGUS) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.CRIMSON_NYLIUM.defaultBlockState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    } else if (state.is(Blocks.WARPED_FUNGUS) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.WARPED_NYLIUM.defaultBlockState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    // MA seeds → farmland below, seed on top
                    } else if (needsFarmland(state) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.FARMLAND.defaultBlockState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    // Cactus → sand below, cactus on top
                    } else if (state.is(Blocks.CACTUS) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.SAND.defaultBlockState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    // Sugar cane → sand below, sugar cane on top, water beside sand
                    } else if (state.is(Blocks.SUGAR_CANE) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.SAND.defaultBlockState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                        if (x + 1 < startX + 16) {
                            mutablePos.set(x + 1, y, z);
                            chunk.setBlockState(mutablePos, Blocks.WATER.defaultBlockState(), false);
                        }
                    // Ores → 2% chance of a 2x2x2 or 3x3x3 cluster
                    } else if (isOre(state) && new Random(hashPos(x, y, z) + 54321L).nextDouble() < 0.02) {
                        placeCluster(chunk, x, y, z, state, startX, startZ, minY, maxY);
                    } else {
                        chunk.setBlockState(mutablePos, state, false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // -------------------------------------------------------------------------
    // Entity population — configure spawners and chest loot
    // -------------------------------------------------------------------------
    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        ChunkPos chunkPos = region.getCenter();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = region.getMinBuildHeight(); y < region.getMaxBuildHeight(); y++) {
                    if (x % gridSpacing != 0 || y % gridSpacing != 0 || z % gridSpacing != 0) continue;

                    pos.set(x, y, z);
                    BlockState state = region.getBlockState(pos);

                    if (state.is(Blocks.SPAWNER)) {
                        if (region.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                            Random rand = new Random(hashPos(x, y, z) + 999L);
                            spawner.setEntityId(pickMob(rand), region.getRandom());
                        }
                    } else if (state.is(Blocks.CHEST)) {
                        if (region.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                            Random rand = new Random(hashPos(x, y, z) + 777L);
                            chest.setLootTable(pickLootTable(rand), hashPos(x, y, z));
                        }
                    }
                }
            }
        }
    }

    private EntityType<?> pickMob(Random rand) {
        EntityType<?>[] mobs = switch (dimension) {
            case "nether" -> NETHER_MOBS;
            case "end"    -> END_MOBS;
            default       -> OVERWORLD_MOBS;
        };
        return mobs[rand.nextInt(mobs.length)];
    }

    private ResourceKey<LootTable> pickLootTable(Random rand) {
        return switch (dimension) {
            case "nether" -> rand.nextInt(2) == 0
                ? BuiltInLootTables.NETHER_BRIDGE
                : BuiltInLootTables.BASTION_TREASURE;
            case "end"    -> BuiltInLootTables.END_CITY_TREASURE;
            default -> switch (rand.nextInt(5)) {
                case 0  -> BuiltInLootTables.SIMPLE_DUNGEON;
                case 1  -> BuiltInLootTables.ABANDONED_MINESHAFT;
                case 2  -> BuiltInLootTables.STRONGHOLD_LIBRARY;
                case 3  -> BuiltInLootTables.JUNGLE_TEMPLE;
                default -> BuiltInLootTables.DESERT_PYRAMID;
            };
        };
    }

    // -------------------------------------------------------------------------
    // Required overrides
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures,
                             RandomState randomState, ChunkAccess chunk) {}

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {}

    @Override public int getGenDepth() { return 384; }
    @Override public int getSeaLevel() { return 63;  }
    @Override public int getMinY()     { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmap,
                             LevelHeightAccessor level, RandomState randomState) {
        return level.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Sky Grid | Dim: " + dimension + " | Spacing: " + gridSpacing + " | Pos: " + pos.toShortString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isSapling(BlockState state) {
        return state.getBlock() instanceof SaplingBlock
            || state.is(Blocks.BAMBOO)
            || state.is(Blocks.AZALEA)
            || state.is(Blocks.FLOWERING_AZALEA)
            || state.is(Blocks.MANGROVE_PROPAGULE);
    }

    /** Returns true for any ore block — vanilla or modded (matches any block ID ending in _ore or ancient_debris). */
    private static boolean isOre(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /** Places a 2x2x2 or 3x3x3 cluster of the given block centred on (x, y, z), clipped to the chunk. */
    private static void placeCluster(ChunkAccess chunk, int x, int y, int z, BlockState state,
                                     int startX, int startZ, int minY, int maxY) {
        Random clusterRand = new Random(x * 341873128712L + y * 132897987541L + z * 4392818741L + 99999L);
        int size   = clusterRand.nextBoolean() ? 2 : 3;
        int origin = size == 2 ? 0 : -1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = origin; cx < origin + size; cx++) {
            for (int cy = origin; cy < origin + size; cy++) {
                for (int cz = origin; cz < origin + size; cz++) {
                    int bx = x + cx, by = y + cy, bz = z + cz;
                    if (bx < startX || bx >= startX + 16) continue;
                    if (bz < startZ || bz >= startZ + 16) continue;
                    if (by < minY   || by >= maxY)         continue;
                    pos.set(bx, by, bz);
                    chunk.setBlockState(pos, state, false);
                }
            }
        }
    }

    private static boolean needsFarmland(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("mysticalagriculture") && id.getPath().endsWith("_seeds");
    }

    private long hashPos(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z * 4392818741L ^ 0xDEADBEEFL;
    }
}
