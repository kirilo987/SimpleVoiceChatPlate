package me.kxysl1k.simplevoicechatplate.cache;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CacheManager {

    private final SimpleVoiceChatPlate plugin;
    private final File cacheFolder;
    private final File tempFolder;
    private final File songsFolder;
    private final ConcurrentHashMap<String, CachedTrack> cacheMap = new ConcurrentHashMap<>();
    private BukkitTask cleanupTask;

    public CacheManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        String cacheDirName = plugin.getSettings().getCacheFolder();
        this.cacheFolder = new File(plugin.getDataFolder(), cacheDirName);
        this.tempFolder = new File(cacheFolder, "temp");
        this.songsFolder = new File(cacheFolder, "songs");

        initFolders();
        loadExistingCache();
        startCleanupTask();
    }

    private void initFolders() {
        if (!cacheFolder.exists()) cacheFolder.mkdirs();
        if (!tempFolder.exists()) tempFolder.mkdirs();
        if (!songsFolder.exists()) songsFolder.mkdirs();
    }

    private void loadExistingCache() {
        File[] files = songsFolder.listFiles((dir, name) -> name.endsWith(".pcm") && !name.endsWith(".head.pcm"));
        if (files == null) return;

        for (File f : files) {
            if (f.length() > 0) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                long durationMs = (f.length() * 1000L) / 96000L;
                cacheMap.put(id, new CachedTrack(id, f, durationMs, f.lastModified()));
            } else {
                f.delete();
            }
        }
        LogUtil.info("Загружено кэшированных треков: " + cacheMap.size());
    }

    public Optional<CachedTrack> getTrack(String id) {
        CachedTrack track = cacheMap.get(id);
        if (track != null && track.getAudioFile().exists() && track.getAudioFile().length() > 0) {
            long now = System.currentTimeMillis();
            track.getAudioFile().setLastModified(now);
            CachedTrack updated = new CachedTrack(id, track.getAudioFile(), track.getDurationMs(), now);
            cacheMap.put(id, updated);
            return Optional.of(updated);
        } else if (track != null) {
            cacheMap.remove(id);
        }
        return Optional.empty();
    }

    public boolean hasFullTrack(String id) {
        CachedTrack track = cacheMap.get(id);
        return track != null && track.getAudioFile().exists() && track.getAudioFile().length() > 0;
    }

    public Optional<File> getHeadPreview(String id) {
        if (hasFullTrack(id)) {
            return Optional.empty();
        }
        File headFile = new File(songsFolder, id + ".head.pcm");
        if (headFile.exists() && headFile.length() >= 1920L) {
            headFile.setLastModified(System.currentTimeMillis());
            return Optional.of(headFile);
        }
        return Optional.empty();
    }

    public boolean hasHeadOrFull(String id) {
        if (hasFullTrack(id)) return true;
        File headFile = new File(songsFolder, id + ".head.pcm");
        return headFile.exists() && headFile.length() >= 1920L;
    }

    public void addHeadPreview(String id, File headSourceFile) {
        if (hasFullTrack(id)) {
            if (headSourceFile != null && headSourceFile.exists()) headSourceFile.delete();
            return;
        }
        initFolders();
        File targetHead = new File(songsFolder, id + ".head.pcm");
        try {
            if (!headSourceFile.getAbsolutePath().equals(targetHead.getAbsolutePath())) {
                Files.copy(headSourceFile.toPath(), targetHead.toPath(), StandardCopyOption.REPLACE_EXISTING);
                headSourceFile.delete();
            }
            LogUtil.debug("Вступление трека сохранено в предкэш: " + id + " (" + targetHead.length() + " байт)");
        } catch (Exception e) {
            LogUtil.debug("Не удалось сохранить head-превью " + id + ": " + e.getMessage());
        }
    }

    public void addTrack(String id, File pcmFile, long durationMs) {
        addTrackInternal(id, pcmFile, durationMs, false);
    }

    public void addTrackFromSharedTemp(String id, File sharedTempFile, long durationMs) {
        addTrackInternal(id, sharedTempFile, durationMs, true);
    }

    private void addTrackInternal(String id, File pcmFile, long durationMs, boolean copyOnly) {
        initFolders();
        File targetFile = new File(songsFolder, id + ".pcm");
        try {
            if (!pcmFile.getAbsolutePath().equals(targetFile.getAbsolutePath())) {
                if (copyOnly) {
                    Files.copy(pcmFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    try {
                        Files.move(pcmFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception moveEx) {
                        Files.copy(pcmFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        pcmFile.delete();
                    }
                }
            }
            long actualDuration = durationMs > 0 ? durationMs : (targetFile.length() * 1000L) / 96000L;
            cacheMap.put(id, new CachedTrack(id, targetFile, actualDuration, System.currentTimeMillis()));

            File obsoleteHead = new File(songsFolder, id + ".head.pcm");
            if (obsoleteHead.exists()) {
                obsoleteHead.delete();
            }
            LogUtil.debug("Трек сохранен в кэш: " + id);
        } catch (Exception e) {
            LogUtil.error("Не удалось сохранить PCM-файл в кэш: " + id, e);
        }
    }

    public File getTempFile(String extension) {
        initFolders();
        return new File(tempFolder, UUID.randomUUID() + extension);
    }

    public String generateHashId(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hashBytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public void clearCache() {
        File[] songs = songsFolder.listFiles();
        if (songs != null) {
            for (File f : songs) f.delete();
        }
        File[] temps = tempFolder.listFiles();
        if (temps != null) {
            for (File f : temps) f.delete();
        }
        cacheMap.clear();
        LogUtil.info("Аудио-кэш полностью очищен.");
    }

    public void startCleanupTask() {
        stopCleanupTask();
        if (!plugin.getSettings().isCacheAutoCleanup()) return;

        long delayTicks = plugin.getSettings().getCacheCleanupDelay();
        long retentionMs = plugin.getSettings().getCacheRetentionDays() * 86_400_000L;

        cleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();

            File[] tempFiles = tempFolder.listFiles();
            if (tempFiles != null) {
                for (File f : tempFiles) {
                    if (now - f.lastModified() > 3_600_000L) {
                        f.delete();
                    }
                }
            }

            File[] headFiles = songsFolder.listFiles((dir, name) -> name.endsWith(".head.pcm"));
            if (headFiles != null) {
                for (File hf : headFiles) {
                    if (now - hf.lastModified() > retentionMs) {
                        hf.delete();
                    }
                }
            }

            cacheMap.entrySet().removeIf(entry -> {
                CachedTrack track = entry.getValue();
                if (now - track.getLastAccessedTime() > retentionMs) {
                    if (track.getAudioFile().exists()) {
                        track.getAudioFile().delete();
                        LogUtil.debug("Удален устаревший кэш-файл: " + track.getTrackId());
                    }
                    return true;
                }
                return false;
            });
        }, delayTicks, delayTicks);
    }

    public void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    public int getCacheSize() {
        return cacheMap.size();
    }
}
