package com.skygrid;

import com.mojang.brigadier.CommandDispatcher;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import static net.minecraft.commands.Commands.literal;

/**
 * Registers /skygrid commands for in-game debugging.
 * NeoForge version — uses CommandDispatcher directly.
 *
 * Usage:
 *   /skygrid blocks       — shows pool size + first 50 blocks in chat
 *   /skygrid blocks log   — dumps full list to game log
 */
public class SkyGridCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            literal("skygrid")
                .then(literal("blocks")
                    .executes(ctx -> listBlocks(ctx.getSource(), false))
                    .then(literal("log")
                        .executes(ctx -> listBlocks(ctx.getSource(), true))
                    )
                )
        );
    }

    private static int listBlocks(CommandSourceStack source, boolean logOnly) {
        BlockState[] pool = SkyGridChunkGenerator.getPublicBlockPool();

        if (pool == null || pool.length == 0) {
            source.sendSuccess(() -> Component.literal("§cSkyGrid block pool is empty or not built yet!"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "§a[SkyGrid] §f" + pool.length + " blocks in pool (mode: §e"
            + SkyGridConfig.get().getMode() + "§f)"
        ), false);

        if (logOnly) {
            SkyGridMod.LOGGER.info("=== SkyGrid Block Pool ({} blocks) ===", pool.length);
            for (BlockState state : pool) {
                SkyGridMod.LOGGER.info("  {}", BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            }
            SkyGridMod.LOGGER.info("=== End of SkyGrid Block Pool ===");
            source.sendSuccess(() -> Component.literal("§a[SkyGrid] §fFull list dumped to game log."), false);
        } else {
            int limit = Math.min(pool.length, 50);
            source.sendSuccess(() -> Component.literal(
                "§7Showing first " + limit + " of " + pool.length + " — use §f/skygrid blocks log§7 for full list:"
            ), false);
            for (int i = 0; i < limit; i++) {
                String id = BuiltInRegistries.BLOCK.getKey(pool[i].getBlock()).toString();
                final String display = "§7" + (i + 1) + ". §f" + id;
                source.sendSuccess(() -> Component.literal(display), false);
            }
        }

        return 1;
    }
}
