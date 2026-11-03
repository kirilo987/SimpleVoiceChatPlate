package me.kxysl1k.simplevoicechatplate.acoustics;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;

public final class MaterialAcoustics {

    public record Properties(float transmissionLossDb, float hfMuffle, float reflectivity) {
        public static final Properties AIR = new Properties(0.0f, 0.0f, 0.0f);
    }

    private MaterialAcoustics() {
    }

    public static Properties evaluate(Block block) {
        if (block == null) return Properties.AIR;
        Material type = block.getType();
        if (type.isAir()) return Properties.AIR;

        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return new Properties(2.2f, 0.72f, 0.35f);
        }
        if (type == Material.LAVA) {
            return new Properties(5.5f, 0.85f, 0.25f);
        }

        if (block.isPassable()) {
            String name = type.name();
            if (name.contains("CARPET") || name.contains("MOSS_CARPET")) {
                return new Properties(0.8f, 0.20f, 0.05f);
            }
            if (name.contains("COBWEB")) {
                return new Properties(0.9f, 0.25f, 0.05f);
            }
            return Properties.AIR;
        }

        BlockData data = block.getBlockData();

        if (data instanceof Openable openable && openable.isOpen()) {
            return Properties.AIR;
        }

        Properties base = classifyMaterial(type);

        float shapeFactor = 1.0f;
        if (data instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE) {
            shapeFactor = 0.55f;
        } else if (data instanceof Stairs) {
            shapeFactor = 0.72f;
        } else if (data instanceof TrapDoor) {
            shapeFactor = 0.48f;
        } else if (data instanceof Openable) {
            shapeFactor = type.name().contains("FENCE_GATE") ? 0.18f : 0.62f;
        } else {
            String name = type.name();
            if (name.endsWith("_PANE") || name.equals("IRON_BARS") || name.contains("CHAIN")) {
                shapeFactor = name.endsWith("GLASS_PANE") ? 0.65f : 0.15f;
            } else if (name.contains("FENCE") || name.endsWith("_WALL")) {
                shapeFactor = name.endsWith("_WALL") ? 0.60f : 0.22f;
            } else if (name.contains("CHEST") || name.contains("ANVIL") || name.contains("CAULDRON")
                    || name.contains("LECTERN") || name.contains("HOPPER") || name.contains("BELL")) {
                shapeFactor = 0.45f;
            }
        }

        if (shapeFactor <= 0.01f) return Properties.AIR;
        return new Properties(
                base.transmissionLossDb * shapeFactor,
                Math.min(1.0f, base.hfMuffle * (0.4f + 0.6f * shapeFactor)),
                base.reflectivity
        );
    }

    private static Properties classifyMaterial(Material type) {
        String name = type.name();

        if (name.contains("WOOL") || name.contains("SCULK") || name.contains("SPONGE")
                || name.equals("HAY_BLOCK") || name.equals("MOSS_BLOCK") || name.equals("SNOW_BLOCK")
                || name.equals("POWDER_SNOW")) {
            return new Properties(7.8f, 0.90f, 0.06f);
        }

        if (name.contains("OBSIDIAN") || name.contains("BEDROCK") || name.contains("REINFORCED_DEEPSLATE")
                || name.contains("NETHERITE") || name.equals("ANCIENT_DEBRIS") || name.equals("IRON_BLOCK")
                || name.equals("GOLD_BLOCK") || name.equals("DIAMOND_BLOCK") || name.equals("EMERALD_BLOCK")
                || name.contains("COPPER_BLOCK") || name.contains("RAW_IRON") || name.contains("RAW_GOLD")
                || name.contains("ANVIL")) {
            return new Properties(6.8f, 0.86f, 0.92f);
        }

        if (name.contains("DEEPSLATE") || name.contains("STONE") || name.contains("BRICK")
                || name.contains("CONCRETE") || name.contains("TERRACOTTA") || name.contains("QUARTZ")
                || name.contains("GRANITE") || name.contains("DIORITE") || name.contains("ANDESITE")
                || name.contains("CALCITE") || name.contains("TUFF") || name.contains("BASALT")
                || name.contains("PRISMARINE") || name.contains("PURPUR") || name.contains("AMETHYST")
                || name.contains("OBSERVER") || name.contains("FURNACE") || name.contains("DISPENSER")
                || name.contains("DROPPER")) {
            return new Properties(4.5f, 0.76f, 0.82f);
        }

        if (name.contains("GLASS") || name.contains("ICE") || name.contains("SEA_LANTERN") || name.contains("GLOWSTONE")) {
            return new Properties(2.8f, 0.54f, 0.68f);
        }

        if (name.contains("LEAVES") || name.contains("AZALEA") || name.contains("VINE") || name.contains("MANGROVE_ROOTS")) {
            return new Properties(0.85f, 0.22f, 0.10f);
        }

        if (name.contains("PLANKS") || name.contains("LOG") || name.contains("WOOD")
                || name.contains("STEM") || name.contains("HYPHAE") || name.contains("BAMBOO")
                || name.contains("BOOKSHELF") || name.contains("BARREL") || name.contains("CRAFTING_TABLE")
                || name.contains("NOTE_BLOCK") || name.contains("JUKEBOX") || name.contains("DOOR")
                || name.contains("TRAPDOOR")) {
            return new Properties(3.1f, 0.58f, 0.42f);
        }

        if (name.contains("DIRT") || name.contains("GRASS_BLOCK") || name.contains("SAND")
                || name.contains("GRAVEL") || name.contains("CLAY") || name.contains("MUD")
                || name.contains("PODZOL") || name.contains("MYCELIUM") || name.contains("SOUL_")
                || name.contains("NETHERRACK") || name.contains("NYLIUM")) {
            return new Properties(4.0f, 0.70f, 0.20f);
        }

        return new Properties(3.6f, 0.65f, 0.50f);
    }
}
