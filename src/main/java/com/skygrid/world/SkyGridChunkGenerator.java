package com.skygrid.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skygrid.SkyGridConfig;
import com.skygrid.SkyGridMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
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

/**
 * NeoForge version of SkyGridChunkGenerator.
 * Uses Mojang's official mappings — same logic as the Fabric version
 * but with different package/method names throughout.
 */
public class SkyGridChunkGenerator extends ChunkGenerator {

    public static final MapCodec<SkyGridChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(gen -> gen.biomeSource),
            Codec.INT.optionalFieldOf("grid_spacing", 4).forGetter(gen -> gen.gridSpacing)
        ).apply(instance, SkyGridChunkGenerator::new)
    );

    // -------------------------------------------------------------------------
    // Blocks that are always excluded regardless of config
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
    // Dynamic block pool — built from all registered blocks on first use
    // -------------------------------------------------------------------------
    private static volatile BlockState[] dynamicBlockPool = null;

    public static BlockState[] getPublicBlockPool() {
        return dynamicBlockPool;
    }

    private static BlockState[] getBlockPool() {
        if (dynamicBlockPool == null) {
            synchronized (SkyGridChunkGenerator.class) {
                if (dynamicBlockPool == null) {
                    dynamicBlockPool = buildBlockPool();
                }
            }
        }
        return dynamicBlockPool;
    }

    private static BlockState[] buildBlockPool() {
        List<BlockState> pool = new ArrayList<>();
        SkyGridConfig config = SkyGridConfig.get();

        for (Block block : BuiltInRegistries.BLOCK) {
            if (EXCLUDED_BLOCKS.contains(block)) continue;
            BlockState state = block.defaultBlockState();
            if (state.isAir()) continue;
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (!config.isAllowed(blockId)) continue;
            pool.add(state);
        }

        SkyGridMod.LOGGER.info("SkyGrid block pool built: {}/{} blocks allowed (mode: {}).",
            pool.size(), BuiltInRegistries.BLOCK.size(), config.getMode());
        return pool.toArray(new BlockState[0]);
    }

    // -------------------------------------------------------------------------
    // Mob types for spawners
    // -------------------------------------------------------------------------
    private static final EntityType<?>[] SPAWNER_MOBS = {
        EntityType.ZOMBIE,
        EntityType.SKELETON,
        EntityType.SPIDER,
        EntityType.CAVE_SPIDER,
        EntityType.CREEPER,
        EntityType.ENDERMAN,
        EntityType.WITCH,
        EntityType.BLAZE,
        EntityType.SLIME,
        EntityType.PHANTOM,
        EntityType.HUSK,
        EntityType.STRAY,
        EntityType.DROWNED,
        EntityType.SILVERFISH,
    };

    private final int gridSpacing;

    public SkyGridChunkGenerator(BiomeSource biomeSource, int gridSpacing) {
        super(biomeSource);
        this.gridSpacing = gridSpacing;
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

                    if (x % gridSpacing == 0 && y % gridSpacing == 0 && z % gridSpacing == 0) {
                        mutablePos.set(x, y, z);

                        Random rand = new Random(hashPos(x, y, z));
                        double roll = rand.nextDouble();

                        BlockState state;
                        if (roll < 0.008) {
                            state = Blocks.SPAWNER.defaultBlockState();
                        } else if (roll < 0.022) {
                            state = Blocks.CHEST.defaultBlockState();
                        } else {
                            BlockState[] pool = getBlockPool();
                            state = pool[rand.nextInt(pool.length)];
                        }

                        // Leaves placed during world gen must be persistent so they don't decay
                        if (state.getBlock() instanceof LeavesBlock) {
                            state = state.setValue(LeavesBlock.PERSISTENT, true);
                        }

                        chunk.setBlockState(mutablePos, state, false);

                        // Saplings need dirt below them to survive and grow
                        if (isSapling(state) && y - 1 >= minY) {
                            mutablePos.set(x, y - 1, z);
                            chunk.setBlockState(mutablePos, Blocks.DIRT.defaultBlockState(), false);
                            mutablePos.set(x, y, z);
                        }
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

                    if (x % gridSpacing == 0 && y % gridSpacing == 0 && z % gridSpacing == 0) {
                        pos.set(x, y, z);
                        BlockState state = region.getBlockState(pos);

                        if (state.is(Blocks.SPAWNER)) {
                            if (region.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                                Random rand = new Random(hashPos(x, y, z) + 999L);
                                EntityType<?> mob = SPAWNER_MOBS[rand.nextInt(SPAWNER_MOBS.length)];
                                spawner.setEntityId(mob, region.getRandom());
                            }

                        } else if (state.is(Blocks.CHEST)) {
                            if (region.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                                Random rand = new Random(hashPos(x, y, z) + 777L);
                                ResourceKey<LootTable> lootTable = pickLootTable(rand);
                                chest.setLootTable(lootTable, hashPos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
    }

    private ResourceKey<LootTable> pickLootTable(Random rand) {
        return switch (rand.nextInt(5)) {
            case 0  -> BuiltInLootTables.SIMPLE_DUNGEON;
            case 1  -> BuiltInLootTables.ABANDONED_MINESHAFT;
            case 2  -> BuiltInLootTables.STRONGHOLD_LIBRARY;
            case 3  -> BuiltInLootTables.JUNGLE_TEMPLE;
            default -> BuiltInLootTables.DESERT_PYRAMID;
        };
    }

    // -------------------------------------------------------------------------
    // Required overrides
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures,
                             RandomState randomState, ChunkAccess chunk) {
        // Sky Grid: all blocks placed in fillFromNoise
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Sky Grid: no cave carving
    }

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public int getMinY() {
        return -64;
    }

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
        info.add("Sky Grid | Spacing: " + gridSpacing + " | Pos: " + pos.toShortString());
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

    private long hashPos(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z * 4392818741L ^ 0xDEADBEEFL;
    }
}
