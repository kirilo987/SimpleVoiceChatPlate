package me.kxysl1k.simplevoicechatplate.listener;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class HopperListener implements Listener {

    private final SimpleVoiceChatPlate plugin;

    public HopperListener(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPull(InventoryMoveItemEvent event) {
        if (event.getSource().getType() != InventoryType.JUKEBOX) return;
        if (event.getSource().getLocation() == null) return;

        Block block = event.getSource().getLocation().getBlock();
        if (plugin.getJukeboxManager().hasCustomDisc(block.getLocation())) {
            plugin.getJukeboxManager().stopDisc(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperInsert(InventoryMoveItemEvent event) {
        if (event.getDestination().getType() != InventoryType.JUKEBOX) return;
        if (event.getDestination().getLocation() == null) return;

        ItemStack item = event.getItem();
        if (!plugin.getDiscManager().isCustomDisc(item)) return;

        Block block = event.getDestination().getLocation().getBlock();
        plugin.getJukeboxManager().registerCustomJukebox(block.getLocation());

        ItemStack cloned = item.clone();
        cloned.setAmount(1);
        plugin.getDiscManager().stripVanillaPlayable(cloned);
        event.setItem(cloned);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (block.getState() instanceof Jukebox jukebox) {
                jukebox.stopPlaying();
                jukebox.update(true, false);
            }
            plugin.getJukeboxManager().playDisc(block, cloned, null);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispenserInsertIntoJukebox(BlockDispenseEvent event) {
        ItemStack dispensed = event.getItem();
        if (!plugin.getDiscManager().isCustomDisc(dispensed)) return;

        Block sourceBlock = event.getBlock();
        if (!(sourceBlock.getBlockData() instanceof Directional directional)) return;

        Block targetBlock = sourceBlock.getRelative(directional.getFacing());
        if (targetBlock.getType() != Material.JUKEBOX) return;
        if (!(targetBlock.getState() instanceof Jukebox jukebox) || jukebox.hasRecord()) return;

        event.setCancelled(true);

        ItemStack record = dispensed.clone();
        record.setAmount(1);
        plugin.getDiscManager().stripVanillaPlayable(record);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(sourceBlock.getState() instanceof Container container)) return;
            if (!(targetBlock.getState() instanceof Jukebox liveJb) || liveJb.hasRecord()) return;

            Inventory inv = container.getInventory();
            int foundSlot = -1;
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack slotItem = inv.getItem(i);
                if (slotItem != null && plugin.getDiscManager().isCustomDisc(slotItem)
                        && plugin.getDiscManager().getTrackId(slotItem).equals(plugin.getDiscManager().getTrackId(record))) {
                    foundSlot = i;
                    break;
                }
            }
            if (foundSlot == -1) return;

            ItemStack slotItem = inv.getItem(foundSlot);
            if (slotItem != null) {
                if (slotItem.getAmount() > 1) {
                    slotItem.setAmount(slotItem.getAmount() - 1);
                } else {
                    inv.setItem(foundSlot, null);
                }
            }

            plugin.getJukeboxManager().registerCustomJukebox(targetBlock.getLocation());
            liveJb.setRecord(record);
            liveJb.stopPlaying();
            liveJb.update(true, false);

            if (targetBlock.getState() instanceof Jukebox verifyJb) {
                verifyJb.stopPlaying();
                verifyJb.update(true, false);
            }

            plugin.getJukeboxManager().playDisc(targetBlock, record, null);
        });
    }
}
