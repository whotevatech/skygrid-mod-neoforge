# Sky Grid — NeoForge Edition

A Minecraft 1.21.1 mod for **NeoForge** that generates a world of floating blocks arranged on a 3D grid in the void — no ground, no sky, just blocks spaced 4 apart in every direction.

> Looking for the Fabric version? → [skygrid-mod](https://github.com/whotevatech/skygrid-mod)

---

## Features

- **3D grid generation** — blocks placed every 4 blocks in X, Y, and Z, floating in the void
- **Full mod support** — every block from every installed mod is included automatically
- **Persistent leaves** — leaf blocks never decay
- **Sapling + dirt pairs** — saplings always spawn on a dirt block so they can grow
- **Mob spawners** — randomly assigned hostile mobs
- **Loot chests** — random vanilla loot tables (dungeon, mineshaft, stronghold, etc.)
- **JSON config** — whitelist or blacklist specific blocks

---

## Installation

1. Install [NeoForge 1.21.1](https://neoforged.net/)
2. Drop `skygrid-mod-neoforge-1.0.0.jar` into your `mods/` folder
3. Launch Minecraft, create a new world, and select **Sky Grid** as the world type

---

## Configuration

On first launch a config file is created at:

```
.minecraft/config/skygrid.json
```

Default config (whitelist mode — only listed blocks spawn):

```json
{
  "mode": "whitelist",
  "blocks": [
    "minecraft:stone",
    "minecraft:dirt",
    "minecraft:oak_log"
  ]
}
```

### Modes

| Mode | Behaviour |
|------|-----------|
| `whitelist` | Only blocks in the list will spawn |
| `blacklist` | All blocks spawn *except* those in the list |

### Finding block IDs

Press **F3 + H** in-game to enable advanced tooltips. Hover over any block in your inventory — the block ID appears in grey below the name (e.g. `biomesoplenty:redwood_log`).

You can also run `/skygrid blocks` in-game to list every block currently in the spawn pool, or `/skygrid blocks log` to write the full list to the log file.

---

## Mod Support

Any installed mod's blocks are added to the pool automatically. To limit which blocks appear, switch to **whitelist** mode and add only the IDs you want.

**Example — including Biomes O' Plenty wood types:**

```json
{
  "mode": "whitelist",
  "blocks": [
    "minecraft:oak_log",
    "minecraft:oak_sapling",
    "minecraft:oak_leaves",
    "biomesoplenty:redwood_log",
    "biomesoplenty:redwood_sapling",
    "biomesoplenty:redwood_leaves"
  ]
}
```

> **Note:** Delete your existing config file and relaunch to regenerate it cleanly after editing.

---

## Building from Source

### Requirements

- Java 21
- IntelliJ IDEA (recommended)
- Internet connection (Gradle downloads NeoForge and Minecraft on first run)

### Steps

1. Clone the repository
2. Run `SETUP.bat` to download the Gradle wrapper
3. Open the folder in IntelliJ IDEA
4. Let Gradle sync finish (may take several minutes on first run)
5. In the Gradle panel: `Tasks → build → build`
6. The built JAR will be in `build/libs/`

---

## License

MIT — see [LICENSE](LICENSE)
