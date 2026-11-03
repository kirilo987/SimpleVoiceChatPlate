package me.kxysl1k.simplevoicechatplate.jukebox;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.gui.PlatePlayerGUI;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class JukeboxListener implements Listener {

    private final SimpleVoiceChatPlate plugin;

    public JukeboxListener(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJukeboxInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (!(block.getState() instanceof Jukebox jukebox)) return;

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;

        boolean hasRecordInside = jukebox.hasRecord();
        boolean isCustomInside = hasRecordInside && (plugin.getJukeboxManager().hasCustomDisc(block.getLocation())
                || plugin.getDiscManager().isCustomDisc(jukebox.getRecord()));

        if (isCustomInside) {
            event.setCancelled(true);
            if (hand != EquipmentSlot.HAND) return;

            if (player.isSneaking()) {
                if (!player.hasPermission("simplevoicechatplate.play.jukebox")) {
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return;
                }
                plugin.getJukeboxManager().getOrRestoreSession(block);
                PlatePlayerGUI gui = new PlatePlayerGUI(plugin, block);
                gui.open(player);
            } else {
                plugin.getJukeboxManager().ejectCustomDiscCleanly(block);
            }
            return;
        }

        if (hasRecordInside) {
            if (hand == EquipmentSlot.HAND) {
                plugin.getJukeboxManager().stopDisc(block);
            }
            return;
        }

        ItemStack itemInHand = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();

        if (!plugin.getDiscManager().isCustomDisc(itemInHand)) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("simplevoicechatplate.play.jukebox")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return;
        }

        plugin.getJukeboxManager().registerCustomJukebox(block.getLocation());

        ItemStack record = itemInHand.clone();
        record.setAmount(1);
        plugin.getDiscManager().stripVanillaPlayable(record);

        if (player.getGameMode() != GameMode.CREATIVE) {
            itemInHand.setAmount(itemInHand.getAmount() - 1);
        }

        /*
         * В Paper BlockState (jukebox) является отсоединенным снимком (snapshot) состояния TileEntity.
         * Если вызвать stopPlaying() только ДО update(), то update() сначала поместит пластинку в живой
         * JukeboxBlockEntity и мгновенно запустит ванильный JukeboxSongPlayer (LevelEvent 1010).
         * Поэтому после первичного update() обязательно повторно берется свежий снимок живого блока
         * и принудительно останавливается ванильное проигрывание до начала тика сервера.
         */
        jukebox.setRecord(record);
        jukebox.stopPlaying();
        jukebox.update(true, false);

        if (block.getState() instanceof Jukebox liveJukebox) {
            liveJukebox.stopPlaying();
            liveJukebox.update(true, false);
        }

        plugin.getJukeboxManager().playDisc(block, record, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJukeboxBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) return;

        plugin.getJukeboxManager().stopDisc(block);

        if (!plugin.getSettings().isDropDiscOnBreak()) {
            if (block.getState() instanceof Jukebox jb && jb.hasRecord()
                    && plugin.getDiscManager().isCustomDisc(jb.getRecord())) {
                jb.setRecord(null);
                jb.stopPlaying();
                jb.update(true, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (block.getType() == Material.JUKEBOX) {
                plugin.getJukeboxManager().stopDisc(block);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            if (block.getType() == Material.JUKEBOX) {
                plugin.getJukeboxManager().stopDisc(block);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (plugin.getVoicePlayerManager() != null) {
            plugin.getVoicePlayerManager().stopInWorld(event.getWorld());
        }
    }
}
