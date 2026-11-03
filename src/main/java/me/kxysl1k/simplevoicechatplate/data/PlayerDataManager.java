package me.kxysl1k.simplevoicechatplate.data;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private final SimpleVoiceChatPlate plugin;
    private final File folder;
    private final Map<UUID, Map<String, Integer>> usageCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> hornNotifyCache = new ConcurrentHashMap<>();

    public PlayerDataManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "userdata");
        if (!folder.exists()) folder.mkdirs();
    }

    private File getFile(UUID uuid) {
        return new File(folder, uuid.toString() + ".yml");
    }

    private Map<String, Integer> getOrLoad(UUID uuid) {
        return usageCache.computeIfAbsent(uuid, id -> {
            Map<String, Integer> map = new ConcurrentHashMap<>();
            File f = getFile(id);
            if (f.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                var sec = cfg.getConfigurationSection("usage");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        map.put(key, Math.max(0, sec.getInt(key, 0)));
                    }
                }
                if (cfg.contains("settings.horn_notify")) {
                    hornNotifyCache.put(id, cfg.getBoolean("settings.horn_notify", true));
                }
            }
            return map;
        });
    }

    public int getUsage(UUID uuid, String type) {
        return getOrLoad(uuid).getOrDefault(type, 0);
    }

    public void modifyUsage(UUID uuid, String type, int delta) {
        Map<String, Integer> map = getOrLoad(uuid);
        int updated = Math.max(0, map.getOrDefault(type, 0) + delta);
        map.put(type, updated);
        persistAsync(uuid);
    }

    public boolean isHornNotifyEnabled(UUID uuid) {
        getOrLoad(uuid);
        return hornNotifyCache.getOrDefault(uuid, true);
    }

    public void setHornNotifyEnabled(UUID uuid, boolean enabled) {
        getOrLoad(uuid);
        hornNotifyCache.put(uuid, enabled);
        persistAsync(uuid);
    }

    private void persistAsync(UUID uuid) {
        Map<String, Integer> snapshot = Map.copyOf(getOrLoad(uuid));
        Boolean hornNotify = hornNotifyCache.get(uuid);
        Thread.ofVirtual().name("svcplate-userdata-save").start(() -> saveSnapshot(uuid, snapshot, hornNotify));
    }

    private synchronized void saveSnapshot(UUID uuid, Map<String, Integer> snapshot, Boolean hornNotify) {
        File f = getFile(uuid);
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
            cfg.set("usage." + entry.getKey(), entry.getValue());
        }
        if (hornNotify != null) {
            cfg.set("settings.horn_notify", hornNotify);
        }
        try {
            cfg.save(f);
        } catch (IOException e) {
            LogUtil.error("Не удалось сохранить данные игрока " + uuid, e);
        }
    }

    public int getLimit(Player player, String type) {
        if (player.hasPermission("simplevoicechatplate.admin")
                || player.hasPermission("simplevoicechatplate.limit." + type + ".-1")) {
            return -1;
        }

        int max = plugin.getSettings().getDefaultLimit(type);
        String prefix = "simplevoicechatplate.limit." + type + ".";

        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase();
            if (perm.startsWith(prefix)) {
                try {
                    int val = Integer.parseInt(perm.substring(prefix.length()));
                    if (val == -1) return -1;
                    if (val > max) max = val;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    public boolean canCreate(Player player, String type) {
        int limit = getLimit(player, type);
        if (limit == -1) return true;
        return getUsage(player.getUniqueId(), type) < limit;
    }
}
