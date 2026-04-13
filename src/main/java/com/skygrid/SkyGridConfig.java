package com.skygrid;

import com.google.gson.*;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads and writes config/skygrid.json.
 * NeoForge version — uses FMLPaths instead of FabricLoader.
 * Logic is identical to the Fabric version.
 */
public class SkyGridConfig {

    private static final List<String> DEFAULT_WHITELIST = List.of(
        "minecraft:stone", "minecraft:deepslate", "minecraft:dirt",
        "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol",
        "minecraft:mycelium", "minecraft:sand", "minecraft:red_sand",
        "minecraft:gravel", "minecraft:clay", "minecraft:ice",
        "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:snow_block",
        "minecraft:obsidian", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
        "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
        "minecraft:tuff", "minecraft:calcite",
        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore",
        "minecraft:gold_ore", "minecraft:lapis_ore", "minecraft:redstone_ore",
        "minecraft:diamond_ore", "minecraft:emerald_ore",
        "minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore",
        "minecraft:deepslate_copper_ore", "minecraft:deepslate_gold_ore",
        "minecraft:deepslate_lapis_ore", "minecraft:deepslate_redstone_ore",
        "minecraft:deepslate_diamond_ore", "minecraft:deepslate_emerald_ore",
        "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
        "minecraft:mangrove_log", "minecraft:cherry_log",
        "minecraft:bamboo_block", "minecraft:crimson_stem", "minecraft:warped_stem",
        "minecraft:oak_leaves", "minecraft:birch_leaves", "minecraft:spruce_leaves",
        "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
        "minecraft:mangrove_leaves", "minecraft:cherry_leaves",
        "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves",
        "minecraft:oak_sapling", "minecraft:birch_sapling", "minecraft:spruce_sapling",
        "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling",
        "minecraft:mangrove_propagule", "minecraft:cherry_sapling",
        "minecraft:bamboo", "minecraft:azalea", "minecraft:flowering_azalea",
        "minecraft:netherrack", "minecraft:soul_sand", "minecraft:soul_soil",
        "minecraft:glowstone", "minecraft:magma_block", "minecraft:basalt",
        "minecraft:blackstone", "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore",
        "minecraft:crimson_nylium", "minecraft:warped_nylium",
        "minecraft:end_stone", "minecraft:purpur_block",
        "minecraft:water", "minecraft:lava",
        "minecraft:melon", "minecraft:pumpkin", "minecraft:hay_block",
        "minecraft:bone_block", "minecraft:slime_block", "minecraft:sponge",
        "minecraft:bookshelf", "minecraft:crafting_table", "minecraft:furnace",
        "minecraft:glass", "minecraft:shroomlight", "minecraft:amethyst_block",
        "minecraft:raw_iron_block", "minecraft:raw_copper_block",
        "minecraft:raw_gold_block", "minecraft:cactus"
    );

    private String mode;
    private Set<String> blockList;
    private static SkyGridConfig instance;

    public static SkyGridConfig get() {
        if (instance == null) throw new IllegalStateException("SkyGridConfig not loaded yet!");
        return instance;
    }

    public boolean isAllowed(String blockId) {
        if ("whitelist".equalsIgnoreCase(mode)) return blockList.contains(blockId);
        return !blockList.contains(blockId);
    }

    public String getMode() { return mode; }
    public Set<String> getBlockList() { return Collections.unmodifiableSet(blockList); }

    public static void load() {
        // NeoForge: use FMLPaths for the config directory
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("skygrid.json");
        File configFile = configPath.toFile();

        if (!configFile.exists()) {
            SkyGridMod.LOGGER.info("No config found — creating default at {}", configPath);
            instance = createDefault();
            save(instance, configFile);
        } else {
            SkyGridMod.LOGGER.info("Loading config from {}", configPath);
            instance = loadFrom(configFile);
        }

        SkyGridMod.LOGGER.info("SkyGrid config loaded: mode={}, {} entries", instance.mode, instance.blockList.size());
    }

    private static SkyGridConfig createDefault() {
        SkyGridConfig cfg = new SkyGridConfig();
        cfg.mode = "whitelist";
        cfg.blockList = new LinkedHashSet<>(DEFAULT_WHITELIST);
        return cfg;
    }

    private static SkyGridConfig loadFrom(File file) {
        try (Reader reader = new FileReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            SkyGridConfig cfg = new SkyGridConfig();
            cfg.mode = json.has("mode") ? json.get("mode").getAsString() : "whitelist";
            cfg.blockList = new LinkedHashSet<>();
            String key = "whitelist".equalsIgnoreCase(cfg.mode) ? "whitelist" : "blacklist";
            if (json.has(key))
                for (JsonElement el : json.getAsJsonArray(key))
                    cfg.blockList.add(el.getAsString());
            return cfg;
        } catch (Exception e) {
            SkyGridMod.LOGGER.error("Failed to read skygrid.json — using defaults: {}", e.getMessage());
            return createDefault();
        }
    }

    private static void save(SkyGridConfig cfg, File file) {
        try {
            file.getParentFile().mkdirs();
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            JsonObject json = new JsonObject();
            json.addProperty("_comment", "SkyGrid Config - set mode to whitelist (only listed blocks spawn) or blacklist (all blocks except listed ones spawn)");
            json.addProperty("mode", cfg.mode);
            JsonArray arr = new JsonArray();
            for (String id : cfg.blockList) arr.add(id);
            json.add(cfg.mode, arr);
            try (Writer writer = new FileWriter(file)) {
                gson.toJson(json, writer);
            }
        } catch (Exception e) {
            SkyGridMod.LOGGER.error("Failed to write skygrid.json: {}", e.getMessage());
        }
    }
}
