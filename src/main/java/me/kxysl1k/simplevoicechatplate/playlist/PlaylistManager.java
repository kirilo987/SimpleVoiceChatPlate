package me.kxysl1k.simplevoicechatplate.playlist;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PlaylistManager {

    public record TrackEntry(String title, String url) {
    }

    private final SimpleVoiceChatPlate plugin;
    private final File folder;
    private final Gson gson = new Gson();
    private final Map<String, List<String>> memoryPlaylists = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<String>>> pendingPlaylists = new ConcurrentHashMap<>();

    public PlaylistManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "playlists");
        if (!folder.exists()) folder.mkdirs();
    }

    public static String formatEntry(String title, String url) {
        String safeTitle = (title == null || title.isBlank()) ? "Track" : title.replace('|', '｜').trim();
        return safeTitle + "|" + url;
    }

    public static TrackEntry parseEntry(String raw) {
        return parseEntry(raw, "Track");
    }

    /**
     * Разделяет запись плейлиста строго по ПОСЛЕДНЕМУ разделителю '|', а не через String.split("\\|"):
     * названия музыкальных клипов на YouTube регулярно содержат вертикальные черты (например, "Artist | Official Video"),
     * из-за чего обычный split обрезал ссылку и превращал половину названия в битый URL.
     */
    public static TrackEntry parseEntry(String raw, String fallbackTitle) {
        String def = (fallbackTitle == null || fallbackTitle.isBlank()) ? "Track" : fallbackTitle;
        if (raw == null || raw.isEmpty()) {
            return new TrackEntry(def, "");
        }
        int sep = raw.lastIndexOf('|');
        if (sep <= 0 || sep >= raw.length() - 1) {
            return new TrackEntry(def, raw.trim());
        }
        String title = raw.substring(0, sep).trim();
        String url = raw.substring(sep + 1).trim();
        if (title.isEmpty()) title = def;
        return new TrackEntry(title, url);
    }

    public void markPending(String id) {
        pendingPlaylists.putIfAbsent(id, new CompletableFuture<>());
    }

    public CompletableFuture<List<String>> getPendingFuture(String id) {
        return pendingPlaylists.get(id);
    }

    public void failPending(String id, Throwable ex) {
        CompletableFuture<List<String>> future = pendingPlaylists.remove(id);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    public void savePlaylist(String id, List<String> tracks) {
        List<String> copy = List.copyOf(tracks);
        if (!copy.isEmpty()) {
            memoryPlaylists.put(id, copy);
            CompletableFuture<List<String>> pending = pendingPlaylists.remove(id);
            if (pending != null) {
                pending.complete(copy);
            }
        }

        Thread.ofVirtual().name("svcplate-playlist-save").start(() -> {
            if (!folder.exists()) folder.mkdirs();
            File file = new File(folder, id + ".json");
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(copy, writer);
            } catch (Exception e) {
                LogUtil.error("Playlist save error (" + id + ")", e);
            }
        });
    }

    public List<String> getPlaylist(String id) {
        List<String> cached = memoryPlaylists.get(id);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        File file = new File(folder, id + ".json");
        if (!file.exists()) {
            File ymlFile = new File(folder, id + ".yml");
            if (ymlFile.exists()) {
                List<String> ymlTracks = List.copyOf(YamlConfiguration.loadConfiguration(ymlFile).getStringList("tracks"));
                if (!ymlTracks.isEmpty()) {
                    memoryPlaylists.put(id, ymlTracks);
                }
                return ymlTracks;
            }
            return Collections.emptyList();
        }

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<String>>() {}.getType();
            List<String> loaded = gson.fromJson(reader, type);
            if (loaded != null && !loaded.isEmpty()) {
                List<String> immutable = List.copyOf(loaded);
                memoryPlaylists.put(id, immutable);
                return immutable;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            LogUtil.error("Playlist load error (" + id + ")", e);
            return Collections.emptyList();
        }
    }
}
