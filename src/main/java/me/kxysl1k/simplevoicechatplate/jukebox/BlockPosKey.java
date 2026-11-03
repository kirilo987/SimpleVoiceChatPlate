package me.kxysl1k.simplevoicechatplate.jukebox;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record BlockPosKey(String worldName, int x, int y, int z) {

    public static BlockPosKey of(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return new BlockPosKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static BlockPosKey of(Block block) {
        if (block == null) return null;
        return new BlockPosKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public String toStreamKey() {
        return "jukebox:" + worldName + ":" + x + ":" + y + ":" + z;
    }

    public Block toBlock() {
        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
        return world.getBlockAt(x, y, z);
    }

    public Location toCenterLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    /**
     * Упаковывает мировые координаты блока в единый 64-битный ключ по схеме протокола Minecraft
     * (26 бит X | 26 бит Z | 12 бит Y). Это позволяет перехватчику пакетов PacketEvents мгновенно проверять
     * наличие кастомной пластинки прямо из сетевого потока Netty без обращения к потоконебезопасному Bukkit API.
     */
    public static long packBlockPos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    public long packedCoords() {
        return packBlockPos(x, y, z);
    }
}
