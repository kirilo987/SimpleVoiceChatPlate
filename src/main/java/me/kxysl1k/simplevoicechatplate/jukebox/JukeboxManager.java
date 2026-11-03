package me.kxysl1k.simplevoicechatplate.jukebox;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.audio.AudioManager;
import me.kxysl1k.simplevoicechatplate.audio.AudioProcessingException;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class JukeboxManager {

    private final SimpleVoiceChatPlate plugin;
    private final ConcurrentHashMap<BlockPosKey, JukeboxSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<Long> activePackedCoords = ConcurrentHashMap.newKeySet();

    public JukeboxManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public int getActiveJukeboxCount() {
        return activeSessions.size();
    }

    public static String getStreamKey(Location loc) {
        BlockPosKey key = BlockPosKey.of(loc);
        return key != null ? key.toStreamKey() : null;
    }

    public void registerCustomJukebox(Location loc) {
        BlockPosKey key = BlockPosKey.of(loc);
        if (key != null) {
            activeSessions.putIfAbsent(key, new JukeboxSession(key));
            activePackedCoords.add(key.packedCoords());
        }
    }

    public boolean hasCustomDisc(Location loc) {
        BlockPosKey key = BlockPosKey.of(loc);
        if (key == null) return false;
        if (activeSessions.containsKey(key)) return true;

        Block b = key.toBlock();
        if (b != null && b.getType() == Material.JUKEBOX && b.getState() instanceof Jukebox jb) {
            return plugin.getDiscManager().isCustomDisc(jb.getRecord());
        }
        return false;
    }

    public boolean hasCustomDiscAtCoords(int x, int y, int z) {
        return activePackedCoords.contains(BlockPosKey.packBlockPos(x, y, z));
    }

    public JukeboxSession getSession(Location loc) {
        BlockPosKey key = BlockPosKey.of(loc);
        return key != null ? activeSessions.get(key) : null;
    }

    public JukeboxSession getOrRestoreSession(Block jukeboxBlock) {
        BlockPosKey key = BlockPosKey.of(jukeboxBlock);
        if (key == null) return null;
        JukeboxSession existing = activeSessions.get(key);
        if (existing != null && existing.tracks != null && !existing.tracks.isEmpty()) {
            return existing;
        }
        if (jukeboxBlock.getType() == Material.JUKEBOX && jukeboxBlock.getState() instanceof Jukebox jb) {
            ItemStack record = jb.getRecord();
            if (plugin.getDiscManager().isCustomDisc(record)) {
                JukeboxSession restored = buildSessionFromDisc(key, record);
                if (restored != null) {
                    activeSessions.put(key, restored);
                    activePackedCoords.add(key.packedCoords());
                    return restored;
                }
            }
        }
        return existing;
    }

    public void refreshPlaylistTracks(String playlistId, List<String> updatedTracks) {
        if (playlistId == null || updatedTracks == null || updatedTracks.isEmpty()) return;
        for (JukeboxSession session : activeSessions.values()) {
            if (session.isPlaylist && playlistId.equals(session.playlistId)) {
                session.tracks = updatedTracks;
                plugin.getPlaylistPrefetchCoordinator().onSessionTrackActivated(session);

                Block block = session.posKey.toBlock();
                if (block != null && block.getType() == Material.JUKEBOX && block.getState() instanceof Jukebox jb && jb.hasRecord()) {
                    ItemStack rec = jb.getRecord();
                    ItemMeta meta = rec.getItemMeta();
                    if (meta != null && meta.displayName() != null) {
                        String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                        if (plain.contains("+") || plain.contains("⏳")) {
                            String updatedTitle = plugin.getMessages().getRaw("anvil.playlist_default")
                                    .replace("<count>", String.valueOf(updatedTracks.size()));
                            plugin.getDiscManager().updateDisc(rec, playlistId, "playlist:" + playlistId, null, updatedTitle);
                            jb.setRecord(rec);
                            jb.stopPlaying();
                            jb.update(true, false);
                        }
                    }
                }
            }
        }
    }

    private JukeboxSession buildSessionFromDisc(BlockPosKey key, ItemStack discItem) {
        String source = plugin.getDiscManager().getSource(discItem).orElse(null);
        if (source == null) return null;

        JukeboxSession session = new JukeboxSession(key);
        if (source.startsWith("playlist:")) {
            String plId = source.substring("playlist:".length());
            List<String> tracks = plugin.getPlaylistManager().getPlaylist(plId);
            session.isPlaylist = true;
            session.playlistId = plId;
            session.tracks = tracks;
        } else {
            session.isPlaylist = false;
            String title = plugin.getMessages().getDefaultTrackTitle();
            ItemMeta meta = discItem.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                title = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            }
            session.tracks = List.of(PlaylistManager.formatEntry(title, source));
        }
        return session;
    }

    public void silenceVanillaJukebox(Block jukeboxBlock) {
        if (jukeboxBlock == null) return;
        if (jukeboxBlock.getType() == Material.JUKEBOX && jukeboxBlock.getState() instanceof Jukebox liveJb) {
            if (liveJb.isPlaying()) {
                liveJb.stopPlaying();
                liveJb.update(true, false);
            }
        }
        Location center = jukeboxBlock.getLocation().add(0.5, 0.5, 0.5);
        World world = jukeboxBlock.getWorld();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= 96.0 * 96.0) {
                p.stopSound(SoundCategory.RECORDS);
            }
        }
    }

    public void playDisc(Block jukebox, ItemStack discItem, Player player) {
        BlockPosKey posKey = BlockPosKey.of(jukebox);
        if (posKey == null) return;

        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) {
            if (player != null) plugin.getMessages().sendMessage(player, "commands.voicechat_unavailable");
            return;
        }

        vp.stopAudio(posKey.toStreamKey());

        JukeboxSession session = buildSessionFromDisc(posKey, discItem);
        if (session == null) return;

        activeSessions.put(posKey, session);
        activePackedCoords.add(posKey.packedCoords());

        silenceVanillaJukebox(jukebox);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> silenceVanillaJukebox(jukebox), 1L);

        if (session.isPlaylist && (session.tracks == null || session.tracks.isEmpty()) && session.playlistId != null) {
            var pending = plugin.getPlaylistManager().getPendingFuture(session.playlistId);
            if (pending != null) {
                String animKey = "jukebox_" + posKey.toStreamKey();
                if (plugin.getSettings().isJukeboxLoadingAnimation()) {
                    plugin.getLoadingBarAnimator().startAtLocation(jukebox.getLocation().add(0.5, 0.5, 0.5), 48.0, animKey);
                }
                pending.thenAccept(readyTracks -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getLoadingBarAnimator().stop(animKey);
                    if (!activeSessions.containsKey(posKey)) return;
                    session.tracks = readyTracks;
                    playTrackFromSession(session);
                })).exceptionally(ex -> {
                    plugin.getLoadingBarAnimator().stop(animKey);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        removeSessionInternal(posKey);
                        if (player != null && player.isOnline()) {
                            plugin.getMessages().sendMessage(player, resolveErrorMessageKey(ex));
                        }
                    });
                    return null;
                });
                return;
            }
        }

        String rawSource = plugin.getDiscManager().getSource(discItem).orElse("");
        if (!session.isPlaylist && AudioManager.isPlaylistUrl(rawSource)) {
            String plId = "pl_" + plugin.getCacheManager().generateHashId(rawSource).substring(0, 8);
            session.isPlaylist = true;
            session.playlistId = plId;
            List<String> existingPl = plugin.getPlaylistManager().getPlaylist(plId);
            if (!existingPl.isEmpty()) {
                session.tracks = existingPl;
                playTrackFromSession(session);
                return;
            }

            String animKey = "jukebox_" + posKey.toStreamKey();
            if (plugin.getSettings().isJukeboxLoadingAnimation()) {
                plugin.getLoadingBarAnimator().startAtLocation(jukebox.getLocation().add(0.5, 0.5, 0.5), 48.0, animKey);
            }
            java.util.concurrent.atomic.AtomicBoolean startedPlayback = new java.util.concurrent.atomic.AtomicBoolean(false);
            plugin.getAudioManager().fetchPlaylistProgressive(
                    rawSource,
                    initial -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (initial.isEmpty() || !activeSessions.containsKey(posKey)) return;
                        plugin.getPlaylistManager().savePlaylist(plId, initial);
                        session.tracks = initial;
                        if (startedPlayback.compareAndSet(false, true)) {
                            plugin.getLoadingBarAnimator().stop(animKey);
                            playTrackFromSession(session);
                        }
                    }),
                    all -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLoadingBarAnimator().stop(animKey);
                        if (!activeSessions.containsKey(posKey)) return;
                        if (all.isEmpty()) {
                            removeSessionInternal(posKey);
                            if (player != null && player.isOnline()) {
                                plugin.getMessages().sendMessage(player, "chat.playlist_empty");
                            }
                            return;
                        }
                        plugin.getPlaylistManager().savePlaylist(plId, all);
                        refreshPlaylistTracks(plId, all);
                        if (startedPlayback.compareAndSet(false, true)) {
                            playTrackFromSession(session);
                        }
                    }),
                    ex -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLoadingBarAnimator().stop(animKey);
                        removeSessionInternal(posKey);
                        if (player != null && player.isOnline()) {
                            plugin.getMessages().sendMessage(player, resolveErrorMessageKey(ex));
                        }
                    })
            );
            return;
        }

        if (session.tracks == null || session.tracks.isEmpty()) {
            removeSessionInternal(posKey);
            if (player != null) plugin.getMessages().sendMessage(player, "chat.playlist_empty");
            return;
        }

        playTrackFromSession(session);
    }

    public void playTrackFromSession(JukeboxSession session) {
        if (session == null || session.tracks == null || session.tracks.isEmpty()
                || session.currentIndex < 0 || session.currentIndex >= session.tracks.size()) {
            return;
        }

        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) return;

        PlaylistManager.TrackEntry entry = PlaylistManager.parseEntry(
                session.tracks.get(session.currentIndex), plugin.getMessages().getDefaultTrackTitle());
        String title = entry.title();
        String url = entry.url();

        String streamKey = session.posKey.toStreamKey();
        String animKey = "jukebox_" + streamKey;
        plugin.getLoadingBarAnimator().stop(animKey);
        vp.stopAudio(streamKey);
        session.expectedUrl = url;

        plugin.getPlaylistPrefetchCoordinator().onSessionTrackActivated(session);

        String trackHash = plugin.getCacheManager().generateHashId(url);
        boolean instantReady = plugin.getCacheManager().hasHeadOrFull(trackHash);
        if (!instantReady && plugin.getSettings().isJukeboxLoadingAnimation()) {
            Block jbBlock = session.posKey.toBlock();
            if (jbBlock != null) {
                plugin.getLoadingBarAnimator().startAtLocation(
                        jbBlock.getLocation().add(0.5, 0.5, 0.5), 48.0, animKey);
            }
        }

        plugin.getAudioManager().openPlaybackStream(url).thenAccept(streamHandle -> {
            plugin.getLoadingBarAnimator().stop(animKey);

            if (!activeSessions.containsKey(session.posKey) || !url.equals(session.expectedUrl)) {
                streamHandle.retainReader();
                streamHandle.releaseReader();
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Block block = session.posKey.toBlock();
                if (block == null || block.getType() != Material.JUKEBOX) {
                    removeSessionInternal(session.posKey);
                    streamHandle.retainReader();
                    streamHandle.releaseReader();
                    return;
                }
                if (!(block.getState() instanceof Jukebox jb) || !jb.hasRecord()) {
                    removeSessionInternal(session.posKey);
                    streamHandle.retainReader();
                    streamHandle.releaseReader();
                    return;
                }

                silenceVanillaJukebox(block);

                Location center = block.getLocation().add(0.5, 0.5, 0.5);
                boolean showPlaying = plugin.getSettings().isJukeboxPlayingNotification();
                for (Player p : block.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(center) <= 68.0 * 68.0) {
                        if (showPlaying) {
                            plugin.getMessages().sendActionBar(p, "actionbar.playing", Messages.placeholder("title", title));
                        } else if (!instantReady) {
                            p.sendActionBar(Component.empty());
                        }
                    }
                }

                Runnable onHalfway = () -> {
                    if (!session.isPlaylist) return;
                    int nextIndex = peekNextIndex(session);
                    if (nextIndex != -1 && nextIndex < session.tracks.size()) {
                        String nextUrl = PlaylistManager.parseEntry(session.tracks.get(nextIndex)).url();
                        plugin.getAudioManager().preloadFullInBackground(nextUrl);
                    }
                };

                Runnable onFinish = () -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!activeSessions.containsKey(session.posKey)) return;
                    int nextIndex = getNextIndex(session);
                    if (nextIndex != -1) {
                        session.currentIndex = nextIndex;
                        playTrackFromSession(session);
                    }
                });

                vp.playAudioAtJukebox(block, streamHandle, url, onHalfway, onFinish);
            });
        }).exceptionally(ex -> {
            plugin.getLoadingBarAnimator().stop(animKey);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String errorMsgKey = resolveErrorMessageKey(ex);
                Block block = session.posKey.toBlock();
                if (block != null) {
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    for (Player p : block.getWorld().getPlayers()) {
                        if (p.getLocation().distanceSquared(center) <= 32.0 * 32.0) {
                            plugin.getMessages().sendMessage(p, errorMsgKey);
                        }
                    }
                }

                if (session.isPlaylist) {
                    int nextIndex = getNextIndex(session);
                    if (nextIndex != -1 && nextIndex != session.currentIndex) {
                        session.currentIndex = nextIndex;
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> playTrackFromSession(session), 40L);
                    }
                }
            });
            return null;
        });
    }

    private int peekNextIndex(JukeboxSession session) {
        if (session.repeatMode == 2) return session.currentIndex;
        if (session.repeatMode == 1 && !session.isPlaylist) return session.currentIndex;
        if (session.shuffle && session.tracks.size() > 1) return -1;
        int next = session.currentIndex + 1;
        if (next >= session.tracks.size()) {
            return session.repeatMode == 1 ? 0 : -1;
        }
        return next;
    }

    private int getNextIndex(JukeboxSession session) {
        if (session.repeatMode == 2) return session.currentIndex;
        if (session.repeatMode == 1 && !session.isPlaylist) return session.currentIndex;

        if (session.shuffle && session.tracks.size() > 1) {
            int next;
            do {
                next = ThreadLocalRandom.current().nextInt(session.tracks.size());
            } while (next == session.currentIndex);
            return next;
        }

        int next = session.currentIndex + 1;
        if (next >= session.tracks.size()) {
            return session.repeatMode == 1 ? 0 : -1;
        }
        return next;
    }

    public void ejectCustomDiscCleanly(Block block) {
        stopDisc(block);
        if (block == null || block.getType() != Material.JUKEBOX) return;
        if (block.getState() instanceof Jukebox jb && jb.hasRecord()) {
            ItemStack record = jb.getRecord().clone();
            jb.setRecord(null);
            jb.stopPlaying();
            jb.update(true, false);

            if (record.getType() != Material.AIR) {
                Location dropLoc = block.getLocation().add(0.5, 1.05, 0.5);
                Item dropped = block.getWorld().dropItem(dropLoc, record);
                dropped.setVelocity(new Vector(
                        ThreadLocalRandom.current().nextDouble(-0.08, 0.08),
                        0.22,
                        ThreadLocalRandom.current().nextDouble(-0.08, 0.08)
                ));
            }
            silenceVanillaJukebox(block);
        }
    }

    public void stopDisc(Block jukebox) {
        BlockPosKey posKey = BlockPosKey.of(jukebox);
        if (posKey != null) {
            String streamKey = posKey.toStreamKey();
            plugin.getLoadingBarAnimator().stop("jukebox_" + streamKey);
            plugin.getPlaylistPrefetchCoordinator().cancelSessionPrefetch(streamKey);
            removeSessionInternal(posKey);
            VoicePlayerManager vp = plugin.getVoicePlayerManager();
            if (vp != null) {
                vp.stopAudio(streamKey);
            }
        }
    }

    private void removeSessionInternal(BlockPosKey posKey) {
        activeSessions.remove(posKey);
        long packed = posKey.packedCoords();
        boolean stillUsed = activeSessions.keySet().stream().anyMatch(k -> k.packedCoords() == packed);
        if (!stillUsed) {
            activePackedCoords.remove(packed);
        }
    }

    public static String resolveErrorMessageKey(Throwable ex) {
        Throwable cause = ex;
        while (cause != null && cause.getCause() != null && !(cause instanceof AudioProcessingException)) {
            cause = cause.getCause();
        }
        if (cause instanceof AudioProcessingException ape) {
            return ape.getMessageKey();
        }
        return "chat.audio_error";
    }

    public static class JukeboxSession {
        public final BlockPosKey posKey;
        public boolean isPlaylist = false;
        public String playlistId = null;
        public volatile List<String> tracks = List.of();
        public int currentIndex = 0;
        public boolean shuffle = false;
        public int repeatMode = 0;
        public volatile String expectedUrl = null;

        public JukeboxSession(BlockPosKey posKey) {
            this.posKey = posKey;
        }
    }
}
