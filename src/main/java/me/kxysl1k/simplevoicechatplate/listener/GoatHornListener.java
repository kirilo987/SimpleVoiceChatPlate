package me.kxysl1k.simplevoicechatplate.listener;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.packet.SoundSuppressionState;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class GoatHornListener implements Listener {

    private final SimpleVoiceChatPlate plugin;

    public GoatHornListener(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHornInteract(PlayerInteractEvent event) {
        if (!plugin.getSettings().isGoatHornEnabled()) return;
        if (!event.getAction().isRightClick()) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.JUKEBOX) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.GOAT_HORN) return;
        if (!plugin.getDiscManager().isCustomDisc(item)) return;

        event.setCancelled(true);
        plugin.getDiscManager().stripVanillaPlayable(item);

        Player player = event.getPlayer();
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) {
            plugin.getMessages().sendMessage(player, "commands.voicechat_unavailable");
            return;
        }

        UUID uuid = player.getUniqueId();
        String streamKey = "player_" + uuid;
        String animKey = "horn_" + uuid;
        String hornMode = plugin.getDiscManager().getHornMode(item);
        boolean notifyPlayer = plugin.getPlayerDataManager().isHornNotifyEnabled(uuid);

        if (player.isSneaking() || ("toggle".equalsIgnoreCase(hornMode) && vp.isStreamRunning(streamKey))) {
            plugin.getLoadingBarAnimator().stop(animKey);
            if (vp.isStreamRunning(streamKey)) {
                vp.stopAudio(streamKey);
                if (notifyPlayer && plugin.getSettings().isGoatHornStoppedNotification()) {
                    plugin.getMessages().sendActionBar(player, "actionbar.horn_stopped");
                }
            }
            return;
        }

        if (player.hasCooldown(Material.GOAT_HORN)) return;
        if (!player.hasPermission("simplevoicechatplate.play.horn")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return;
        }

        int entityId = player.getEntityId();
        SoundSuppressionState.markCustomHornUse(entityId);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> SoundSuppressionState.unmarkCustomHornUse(entityId), 5L);

        player.setCooldown(Material.GOAT_HORN, plugin.getSettings().getGoatHornCooldownTicks());

        for (int delay : new int[]{0, 1}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                for (Player nearby : player.getWorld().getPlayers()) {
                    if (nearby.getLocation().distanceSquared(player.getLocation()) <= 128.0 * 128.0) {
                        for (int i = 0; i < 8; i++) {
                            nearby.stopSound("minecraft:item.goat_horn.sound." + i, SoundCategory.RECORDS);
                            nearby.stopSound("minecraft:item.goat_horn.sound." + i, SoundCategory.PLAYERS);
                        }
                    }
                }
            }, delay);
        }

        float hornVolume = plugin.getDiscManager().getHornVolume(item);
        double hornDistance = plugin.getDiscManager().getHornDistance(item);
        long hornDurationMs = plugin.getDiscManager().getHornDurationSeconds(item) * 1000L;

        plugin.getDiscManager().getSource(item).ifPresent(source -> {
            String trackId = plugin.getDiscManager().getTrackId(item).orElse(source);
            String hash = plugin.getCacheManager().generateHashId(source);
            boolean instantReady = plugin.getCacheManager().hasHeadOrFull(hash);

            if (!instantReady && plugin.getSettings().isGoatHornLoadingAnimation()) {
                plugin.getLoadingBarAnimator().startForPlayer(player, animKey);
            }

            plugin.getAudioManager().openPlaybackStream(source).thenAccept(streamHandle -> {
                plugin.getLoadingBarAnimator().stop(animKey);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        streamHandle.retainReader();
                        streamHandle.releaseReader();
                        return;
                    }
                    vp.playAudioAtPlayer(player, streamHandle, trackId, false, hornVolume, hornDistance, hornDurationMs);
                    if (plugin.getPlayerDataManager().isHornNotifyEnabled(uuid)
                            && plugin.getSettings().isGoatHornPlayingNotification()) {
                        plugin.getMessages().sendActionBar(player, "actionbar.playing_horn");
                    } else if (!instantReady) {
                        player.sendActionBar(Component.empty());
                    }
                });
            }).exceptionally(ex -> {
                plugin.getLoadingBarAnimator().stop(animKey);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendActionBar(Component.empty());
                        plugin.getMessages().sendMessage(player, JukeboxManager.resolveErrorMessageKey(ex));
                    }
                });
                return null;
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getLoadingBarAnimator().stop("horn_" + uuid);
        plugin.getLoadingBarAnimator().stop("portable_" + uuid);
        SoundSuppressionState.unmarkCustomHornUse(event.getPlayer().getEntityId());
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp != null) {
            vp.stopAudio("player_" + uuid);
        }
    }
}
