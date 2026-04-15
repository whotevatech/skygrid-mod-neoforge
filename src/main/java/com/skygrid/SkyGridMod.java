package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.minecraft.core.Registry;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(SkyGridMod.MOD_ID)
public class SkyGridMod {

    public static final String MOD_ID = "skygrid";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SkyGridMod(IEventBus modEventBus) {
        LOGGER.info("SkyGrid mod loading!");

        // Load all dimension configs — overworld, nether, and end each get their own
        SkyGridConfig.loadAll();

        // Register our chunk generator codec on the mod event bus
        modEventBus.addListener(this::registerChunkGenerator);

        // Place the starter platform the first time the overworld loads
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);

        // Register commands on the game event bus
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("SkyGrid mod initialized!");
    }

    private void registerChunkGenerator(RegisterEvent event) {
        event.register(
            BuiltInRegistries.CHUNK_GENERATOR.key(),
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "skygrid"),
            () -> SkyGridChunkGenerator.CODEC
        );
        LOGGER.info("SkyGrid chunk generator registered.");
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            SkyGridPlatform.placeIfNeeded(level);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SkyGridCommands.register(event.getDispatcher());
    }
}
