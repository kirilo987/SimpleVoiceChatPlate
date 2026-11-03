package me.kxysl1k.simplevoicechatplate.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.List;
import java.util.UUID;

public class PlatePlayerGUI implements InventoryHolder {

    private final SimpleVoiceChatPlate plugin;
    private final Block jukeboxBlock;
    private final Inventory inventory;

    private int currentPage = 0;
    private final JukeboxManager.JukeboxSession session;
    private List<String> playlistTracks = List.of();
    private boolean isPlaylist = false;

    private static final Material[] TRACK_GLASS = {
            Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE,
            Material.BLUE_STAINED_GLASS_PANE,
            Material.PINK_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE
    };

    private static final String LEFT_ARROW = "http://textures.minecraft.net/texture/76ebaa41d1d405eb6b60845bb9ac724af70e85eac8a96a5544b9e23ad6c96c62";
    private static final String RIGHT_ARROW = "http://textures.minecraft.net/texture/8399e5da82ef7765fd5e472f3147ed118d981887730ea7bb80d7a1bed98d5ba";

    public PlatePlayerGUI(SimpleVoiceChatPlate plugin, Block jukeboxBlock) {
        this.plugin = plugin;
        this.jukeboxBlock = jukeboxBlock;
        this.session = plugin.getJukeboxManager().getOrRestoreSession(jukeboxBlock);

        if (session != null) {
            this.isPlaylist = session.isPlaylist;
            this.playlistTracks = session.tracks != null ? session.tracks : List.of();
        }

        int size = isPlaylist ? 54 : 27;
        String titleKey = isPlaylist ? "gui.title_playlist" : "gui.title_single";
        String titleStr = MiniMessage.miniMessage().stripTags(plugin.getMessages().getRaw(titleKey)).trim();
        this.inventory = Bukkit.createInventory(this, size,
                Component.text(titleStr.isEmpty() ? "SimpleVoiceChatPlate" : titleStr));
        setupGUI();
    }

    public void setupGUI() {
        if (session != null && session.isPlaylist && session.tracks != null) {
            this.playlistTracks = session.tracks;
        }
        inventory.clear();

        ItemStack filler = createItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        if (isPlaylist) {
            drawTopPlaylist();
        }
        drawControls();
        updateProgressBar();
    }

    private void drawTopPlaylist() {
        if (playlistTracks == null || playlistTracks.isEmpty()) return;
        int maxPage = Math.max(0, (playlistTracks.size() - 1) / 7);

        if (maxPage > 0) {
            inventory.setItem(0, createHead(plugin.getMessages().getMessageNoPrefix("gui.prev_page"), LEFT_ARROW));
            inventory.setItem(8, createHead(plugin.getMessages().getMessageNoPrefix("gui.next_page"), RIGHT_ARROW));
        }

        String defaultTitle = plugin.getMessages().getDefaultTrackTitle();
        int startIndex = currentPage * 7;
        for (int i = 0; i < 7; i++) {
            int trackIndex = startIndex + i;
            if (trackIndex < playlistTracks.size()) {
                String songName = PlaylistManager.parseEntry(playlistTracks.get(trackIndex), defaultTitle).title();
                boolean currentlyPlaying = session != null && trackIndex == session.currentIndex;

                Material iconMat = currentlyPlaying
                        ? Material.LIME_STAINED_GLASS_PANE
                        : TRACK_GLASS[trackIndex % TRACK_GLASS.length];

                Component comp = plugin.getMessages().getMessageNoPrefix(
                        currentlyPlaying ? "gui.track_playing" : "gui.track_queued",
                        Messages.placeholder("title", songName));
                inventory.setItem(i + 1, createItem(iconMat, comp));
            } else {
                inventory.setItem(i + 1, createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")));
            }
        }
    }

    private void drawControls() {
        int r = isPlaylist ? 45 : 9;

        int volSmall = Math.round(plugin.getSettings().getGuiVolumeSmallStep() * 100);
        int volLarge = Math.round(plugin.getSettings().getGuiVolumeLargeStep() * 100);
        int rewindLarge = plugin.getSettings().getGuiRewindSeconds();
        int rewindSmall = 5;

        inventory.setItem(r, createItem(Material.REDSTONE_BLOCK, plugin.getMessages().getMessageNoPrefix("gui.vol_down_large", Messages.placeholder("val", String.valueOf(volLarge)))));
        inventory.setItem(r + 1, createItem(Material.REDSTONE, plugin.getMessages().getMessageNoPrefix("gui.vol_down_small", Messages.placeholder("val", String.valueOf(volSmall)))));

        inventory.setItem(r + 2, createItem(Material.RECOVERY_COMPASS, plugin.getMessages().getMessageNoPrefix("gui.rewind_back", Messages.placeholder("val", String.valueOf(rewindLarge)))));
        inventory.setItem(r + 3, createItem(Material.CLOCK, plugin.getMessages().getMessageNoPrefix("gui.rewind_back", Messages.placeholder("val", String.valueOf(rewindSmall)))));

        inventory.setItem(r + 4, createItem(Material.AMETHYST_SHARD, plugin.getMessages().getMessageNoPrefix("gui.pause_resume")));

        inventory.setItem(r + 5, createItem(Material.CLOCK, plugin.getMessages().getMessageNoPrefix("gui.rewind_forward", Messages.placeholder("val", String.valueOf(rewindSmall)))));
        inventory.setItem(r + 6, createItem(Material.COMPASS, plugin.getMessages().getMessageNoPrefix("gui.rewind_forward", Messages.placeholder("val", String.valueOf(rewindLarge)))));

        inventory.setItem(r + 7, createItem(Material.GLOWSTONE_DUST, plugin.getMessages().getMessageNoPrefix("gui.vol_up_small", Messages.placeholder("val", String.valueOf(volSmall)))));
        inventory.setItem(r + 8, createItem(Material.GLOWSTONE, plugin.getMessages().getMessageNoPrefix("gui.vol_up_large", Messages.placeholder("val", String.valueOf(volLarge)))));

        String streamKey = JukeboxManager.getStreamKey(jukeboxBlock.getLocation());
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        double range = vp != null ? vp.getEffectiveRange(streamKey) : plugin.getSettings().getJukeboxDistance();
        Component acState = plugin.getMessages().getMessageNoPrefix(plugin.getSettings().isAcousticsEnabled() ? "gui.state_on" : "gui.state_off");
        Component acBadge = plugin.getMessages().getMessageNoPrefix("gui.acoustics_badge",
                Messages.placeholder("state", acState != null ? acState : Component.empty()),
                Messages.placeholder("range", String.valueOf((int) Math.round(range))));
        inventory.setItem(isPlaylist ? 22 : 4, createItem(Material.SCULK_SENSOR, acBadge));

        if (session != null) {
            String defaultTitle = plugin.getMessages().getDefaultTrackTitle();
            String currentTrackName = defaultTitle;
            if (session.tracks != null && !session.tracks.isEmpty() && session.currentIndex < session.tracks.size()) {
                currentTrackName = PlaylistManager.parseEntry(session.tracks.get(session.currentIndex), defaultTitle).title();
            }

            Component repeatState;
            if (session.repeatMode == 0) {
                repeatState = plugin.getMessages().getMessageNoPrefix("gui.state_off");
            } else if (session.repeatMode == 1) {
                repeatState = session.isPlaylist
                        ? plugin.getMessages().getMessageNoPrefix("gui.state_playlist")
                        : plugin.getMessages().getMessageNoPrefix("gui.state_on");
            } else {
                repeatState = plugin.getMessages().getMessageNoPrefix("gui.state_one", Messages.placeholder("title", currentTrackName));
            }

            Component repeatBtn = plugin.getMessages().getMessageNoPrefix("gui.repeat",
                    Messages.placeholder("state", repeatState != null ? repeatState : Component.empty()));

            if (isPlaylist) {
                inventory.setItem(38, createItem(Material.SPECTRAL_ARROW, plugin.getMessages().getMessageNoPrefix("gui.prev_track")));
                inventory.setItem(39, createItem(Material.REPEATER, repeatBtn));
                Component shuffleState = session.shuffle
                        ? plugin.getMessages().getMessageNoPrefix("gui.state_on")
                        : plugin.getMessages().getMessageNoPrefix("gui.state_off");
                Component shuffleBtn = plugin.getMessages().getMessageNoPrefix("gui.shuffle",
                        Messages.placeholder("state", shuffleState != null ? shuffleState : Component.empty()));
                inventory.setItem(41, createItem(Material.COMPARATOR, shuffleBtn));
                inventory.setItem(42, createItem(Material.SPECTRAL_ARROW, plugin.getMessages().getMessageNoPrefix("gui.next_track")));
            } else {
                inventory.setItem(22, createItem(Material.REPEATER, repeatBtn));
            }
        }
    }

    private void updateProgressBar() {
        String streamKey = JukeboxManager.getStreamKey(jukeboxBlock.getLocation());
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        int[] prog = vp != null ? vp.getProgress(streamKey) : new int[]{0, 0};
        int cur = prog[0];
        int tot = prog[1];

        String curTime = String.format("%02d:%02d", cur / 60, cur % 60);
        String totTime = String.format("%02d:%02d", tot / 60, tot % 60);

        int barLength = plugin.getSettings().getProgressBarLength();
        int filled = tot > 0 ? Math.clamp((int) Math.round((double) cur / tot * barLength), 0, barLength) : 0;

        String filledColorStr = plugin.getMessages().getRaw("gui.progress_bar_color_filled");
        String emptyColorStr = plugin.getMessages().getRaw("gui.progress_bar_color_empty");

        StringBuilder bar = new StringBuilder();
        bar.append(filledColorStr);
        bar.append("━".repeat(Math.max(0, filled)));
        bar.append(emptyColorStr);
        bar.append("━".repeat(Math.max(0, barLength - filled)));

        int slot = isPlaylist ? 31 : 13;

        Component title;
        if (tot <= 0) {
            title = plugin.getMessages().getMessageNoPrefix("gui.progress_waiting");
        } else {
            title = plugin.getMessages().getMessageNoPrefix("gui.progress_bar",
                    Messages.placeholder("current", curTime),
                    Messages.miniPlaceholder("bar", bar.toString()),
                    Messages.placeholder("total", totTime)
            );
        }

        inventory.setItem(slot, createItem(Material.MUSIC_DISC_5, title));
    }

    private void startProgressTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (inventory.getViewers().isEmpty()) {
                    this.cancel();
                    return;
                }
                if (session != null && session.isPlaylist && session.tracks != null
                        && session.tracks.size() != playlistTracks.size()) {
                    setupGUI();
                    return;
                }
                updateProgressBar();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void open(Player player) {
        player.openInventory(inventory);
        startProgressTask();
    }

    private ItemStack createItem(Material mat, Component name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createHead(Component name, String urlString) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            if (name != null) skullMeta.displayName(name);
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(urlString).toURL());
                profile.setTextures(textures);
                skullMeta.setPlayerProfile(profile);
            } catch (Exception ignored) {
            }
            head.setItemMeta(skullMeta);
        }
        return head;
    }

    public Block getJukeboxBlock() { return jukeboxBlock; }
    public JukeboxManager.JukeboxSession getSession() { return session; }
    public boolean isPlaylist() { return isPlaylist; }
    @Override public @NotNull Inventory getInventory() { return inventory; }
    public int getCurrentPage() { return currentPage; }
    public void setPage(int page) { this.currentPage = page; }
    public void refresh() { setupGUI(); }
}
