package me.kxysl1k.simplevoicechatplate.listener;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class PortablePlayerListener implements Listener {

    private final SimpleVoiceChatPlate plugin;

    public PortablePlayerListener(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDiscAirClick(PlayerInteractEvent event) {
        if (!plugin.getSettings().isPortablePlayerEnabled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().isRightClick()) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.JUKEBOX) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !plugin.getDiscManager().isMusicDisc(item)) return;
        if (!plugin.getDiscManager().isCustomDisc(item)) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) {
            plugin.getMessages().sendMessage(player, "commands.voicechat_unavailable");
            return;
        }

        String streamKey = "player_" + player.getUniqueId();
        String animKey = "portable_" + player.getUniqueId();

        if (player.isSneaking()) {
            plugin.getLoadingBarAnimator().stop(animKey);
            if (vp.isStreamRunning(streamKey)) {
                vp.stopAudio(streamKey);
                if (plugin.getSettings().isPortableStoppedNotification()) {
                    plugin.getMessages().sendActionBar(player, "actionbar.portable_stopped");
                }
            }
            return;
        }

        if (!player.hasPermission("simplevoicechatplate.play.portable")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return;
        }

        plugin.getDiscManager().getSource(item).ifPresent(rawSource -> {
            String urlToPlay = rawSource;
            if (rawSource.startsWith("playlist:")) {
                String plId = rawSource.substring("playlist:".length());
                List<String> tracks = plugin.getPlaylistManager().getPlaylist(plId);
                if (tracks.isEmpty()) {
                    plugin.getMessages().sendMessage(player, "chat.playlist_empty");
                    return;
                }
                urlToPlay = PlaylistManager.parseEntry(tracks.getFirst(), plugin.getMessages().getDefaultTrackTitle()).url();
            }

            final String targetUrl = urlToPlay;
            String trackId = plugin.getDiscManager().getTrackId(item).orElse(targetUrl);
            String hash = plugin.getCacheManager().generateHashId(targetUrl);
            boolean instantReady = plugin.getCacheManager().hasHeadOrFull(hash);

            if (!instantReady && plugin.getSettings().isPortableLoadingAnimation()) {
                plugin.getLoadingBarAnimator().startForPlayer(player, animKey);
            }

            plugin.getAudioManager().openPlaybackStream(targetUrl).thenAccept(streamHandle -> {
                plugin.getLoadingBarAnimator().stop(animKey);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        streamHandle.retainReader();
                        streamHandle.releaseReader();
                        return;
                    }
                    vp.playAudioAtPlayer(player, streamHandle, trackId, true);
                    if (plugin.getSettings().isPortablePlayingNotification()) {
                        plugin.getMessages().sendActionBar(player, "actionbar.playing_hand");
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
}
