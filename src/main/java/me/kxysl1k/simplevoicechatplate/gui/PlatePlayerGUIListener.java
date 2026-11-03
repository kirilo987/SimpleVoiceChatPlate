package me.kxysl1k.simplevoicechatplate.gui;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager.JukeboxSession;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class PlatePlayerGUIListener implements Listener {

    private final SimpleVoiceChatPlate plugin;

    public PlatePlayerGUIListener(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PlatePlayerGUI) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PlatePlayerGUI gui)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        JukeboxSession session = gui.getSession();
        if (session == null) return;
        boolean hasTracks = session.tracks != null && !session.tracks.isEmpty();

        String defaultTitle = plugin.getMessages().getDefaultTrackTitle();

        if (gui.isPlaylist()) {
            int maxPage = hasTracks ? Math.max(0, (session.tracks.size() - 1) / 7) : 0;

            if (slot == 0 && maxPage > 0) {
                gui.setPage(gui.getCurrentPage() > 0 ? gui.getCurrentPage() - 1 : maxPage);
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }
            if (slot == 8 && maxPage > 0) {
                gui.setPage(gui.getCurrentPage() < maxPage ? gui.getCurrentPage() + 1 : 0);
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }
            if (slot >= 1 && slot <= 7 && hasTracks) {
                int trackIndex = (gui.getCurrentPage() * 7) + (slot - 1);
                if (trackIndex < session.tracks.size()) {
                    session.currentIndex = trackIndex;
                    gui.refresh();
                    plugin.getJukeboxManager().playTrackFromSession(session);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
                    String title = PlaylistManager.parseEntry(session.tracks.get(trackIndex), defaultTitle).title();
                    plugin.getMessages().sendMessage(player, "chat.track_start", Messages.placeholder("title", title));
                }
                return;
            }
            if (slot == 38 && hasTracks) {
                int nextIndex = session.currentIndex - 1;
                if (nextIndex < 0) nextIndex = session.tracks.size() - 1;
                session.currentIndex = nextIndex;
                plugin.getJukeboxManager().playTrackFromSession(session);
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                String title = PlaylistManager.parseEntry(session.tracks.get(nextIndex), defaultTitle).title();
                plugin.getMessages().sendMessage(player, "chat.track_start", Messages.placeholder("title", title));
                return;
            }
            if (slot == 39) {
                session.repeatMode = (session.repeatMode + 1) % 3;
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }
            if (slot == 41) {
                session.shuffle = !session.shuffle;
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }
            if (slot == 42 && hasTracks) {
                int nextIndex = (session.currentIndex + 1) % session.tracks.size();
                session.currentIndex = nextIndex;
                plugin.getJukeboxManager().playTrackFromSession(session);
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                String title = PlaylistManager.parseEntry(session.tracks.get(nextIndex), defaultTitle).title();
                plugin.getMessages().sendMessage(player, "chat.track_start", Messages.placeholder("title", title));
                return;
            }
        } else {
            if (slot == 22) {
                session.repeatMode = (session.repeatMode == 0) ? 1 : 0;
                gui.refresh();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                return;
            }
        }

        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) {
            plugin.getMessages().sendMessage(player, "commands.voicechat_unavailable");
            return;
        }

        String streamKey = JukeboxManager.getStreamKey(gui.getJukeboxBlock().getLocation());
        int r = gui.isPlaylist() ? 45 : 9;
        float smStep = plugin.getSettings().getGuiVolumeSmallStep();
        float lgStep = plugin.getSettings().getGuiVolumeLargeStep();
        int rSec = plugin.getSettings().getGuiRewindSeconds();

        if (slot == r) {
            updateVolume(player, session, vp, streamKey, -lgStep);
        } else if (slot == r + 1) {
            updateVolume(player, session, vp, streamKey, -smStep);
        } else if (slot == r + 2) {
            vp.skipBackward(streamKey, rSec);
            plugin.getMessages().sendMessage(player, "chat.rewind_back");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        } else if (slot == r + 3) {
            vp.skipBackward(streamKey, 5);
            plugin.getMessages().sendMessage(player, "chat.rewind_back");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        } else if (slot == r + 4) {
            if (vp.isStreamRunning(streamKey)) {
                boolean isPaused = vp.togglePause(streamKey);
                plugin.getMessages().sendMessage(player, isPaused ? "chat.paused" : "chat.resumed");
                player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 1f);
            } else if (hasTracks) {
                plugin.getJukeboxManager().playTrackFromSession(session);
                plugin.getMessages().sendMessage(player, "chat.resumed");
                player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 1f);
            }
        } else if (slot == r + 5) {
            vp.skipForward(streamKey, 5);
            plugin.getMessages().sendMessage(player, "chat.rewind_forward");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        } else if (slot == r + 6) {
            vp.skipForward(streamKey, rSec);
            plugin.getMessages().sendMessage(player, "chat.rewind_forward");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        } else if (slot == r + 7) {
            updateVolume(player, session, vp, streamKey, smStep);
        } else if (slot == r + 8) {
            updateVolume(player, session, vp, streamKey, lgStep);
        }
    }

    private void updateVolume(Player player, JukeboxSession session, VoicePlayerManager vp, String streamKey, float delta) {
        float newVol = Math.clamp(session.volume + delta, 0.0f, 1.5f);
        session.volume = newVol;
        vp.setVolume(streamKey, newVol);
        plugin.getMessages().sendMessage(player, "chat.volume_set",
                Messages.placeholder("vol", String.valueOf(Math.round(newVol * 100))));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }
}
