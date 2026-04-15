package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Places a small starter platform at the world spawn point the first time
 * a Sky Grid world is loaded. Without it, players would spawn in mid-air
 * and immediately fall into the void.
 *
 * The platform is 5x5 oak planks. It is only placed once — detected by
 * checking whether a non-air block already exists at the spawn centre.
 */
public class SkyGridPlatform {

    /** Size of the platform on each side of centre (2 = 5x5). */
    private static final int RADIUS = 2;

    /** Y level for the platform. */
    private static final int PLATFORM_Y = 64;

    public static void placeIfNeeded(ServerLevel level) {
        // Only place a platform in the overworld
        if (level.dimension() != Level.OVERWORLD) return;

        // Only place in a Sky Grid world
        if (!(level.getChunkSource().getGenerator() instanceof SkyGridChunkGenerator)) return;

        BlockPos spawnPos = level.getSharedSpawnPos();
        BlockPos centre = new BlockPos(spawnPos.getX(), PLATFORM_Y, spawnPos.getZ());

        // Already placed if a non-air block exists at centre
        if (!level.getBlockState(centre).isAir()) {
            return;
        }

        SkyGridMod.LOGGER.info("Placing SkyGrid starter platform at {}", centre);

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                level.setBlock(
                    centre.offset(x, 0, z),
                    Blocks.OAK_PLANKS.defaultBlockState(),
                    3 // BLOCK_UPDATE | SEND_TO_CLIENT
                );
            }
        }

        // Move the world spawn on top of the platform so players respawn safely
        level.setDefaultSpawnPos(centre.above(), 0f);
        SkyGridMod.LOGGER.info("Starter platform placed. Spawn set to {}", centre.above());
    }
}
