package me.kxysl1k.simplevoicechatplate.disc;

import io.papermc.paper.datacomponent.DataComponentTypes;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DiscManager {

    private final SimpleVoiceChatPlate plugin;
    private final NamespacedKey trackIdKey;
    private final NamespacedKey sourceKey;
    private final NamespacedKey creatorKey;
    private final NamespacedKey creatorUuidKey;
    private final NamespacedKey burnableKey;

    private final NamespacedKey hornVolumeKey;
    private final NamespacedKey hornDistanceKey;
    private final NamespacedKey hornDurationKey;
    private final NamespacedKey hornModeKey;

    public DiscManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        this.trackIdKey = new NamespacedKey(plugin, "track_id");
        this.sourceKey = new NamespacedKey(plugin, "audio_source");
        this.creatorKey = new NamespacedKey(plugin, "creator_name");
        this.creatorUuidKey = new NamespacedKey(plugin, "creator_uuid");
        this.burnableKey = new NamespacedKey(plugin, "burnable");

        this.hornVolumeKey = new NamespacedKey(plugin, "horn_volume");
        this.hornDistanceKey = new NamespacedKey(plugin, "horn_distance");
        this.hornDurationKey = new NamespacedKey(plugin, "horn_duration");
        this.hornModeKey = new NamespacedKey(plugin, "horn_mode");
    }

    public NamespacedKey getBurnableKey() {
        return burnableKey;
    }

    /**
     * В Minecraft 1.21+ / Paper 26.3 клиент и сервер запускают стандартную музыку пластинки или звук козьего рога
     * на основе встроенных DataComponents (JUKEBOX_PLAYABLE / INSTRUMENT) еще до отправки звуковых пакетов.
     * Поэтому при записи кастомного аудио эти компоненты принудительно удаляются через unsetData(...), а вместо
     * фиктивного зачарования Mending используется ENCHANTMENT_GLINT_OVERRIDE (что исключает фарм опыта в точиле).
     */
    public ItemStack updateDisc(ItemStack item, String trackId, String source, Player creator, String displayName) {
        if (item == null || item.getType() == Material.AIR) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component nameComp = parseDisplayName(displayName);
            meta.displayName(nameComp);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(trackIdKey, PersistentDataType.STRING, trackId);
            pdc.set(sourceKey, PersistentDataType.STRING, source != null ? source : trackId);
            if (creator != null) {
                pdc.set(creatorKey, PersistentDataType.STRING, creator.getName());
                pdc.set(creatorUuidKey, PersistentDataType.STRING, creator.getUniqueId().toString());
            }
            pdc.remove(burnableKey);

            String creatorName = pdc.getOrDefault(creatorKey, PersistentDataType.STRING,
                    creator != null ? creator.getName() : plugin.getMessages().getUnknownLabel());
            meta.lore(buildItemLore(item.getType(), source, creatorName, pdc));
            item.setItemMeta(meta);
        }

        stripVanillaPlayable(item);
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return item;
    }

    public void rebuildLore(ItemStack item) {
        if (item == null || !isCustomDisc(item)) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String source = pdc.getOrDefault(sourceKey, PersistentDataType.STRING, "");
        String creator = pdc.getOrDefault(creatorKey, PersistentDataType.STRING, plugin.getMessages().getUnknownLabel());
        meta.lore(buildItemLore(item.getType(), source, creator, pdc));
        item.setItemMeta(meta);
    }

    private List<Component> buildItemLore(Material mat, String source, String creatorName, PersistentDataContainer pdc) {
        boolean isHorn = mat == Material.GOAT_HORN;
        boolean isPlaylist = source != null && source.startsWith("playlist:");

        List<Component> lore = new ArrayList<>();
        Component typeBadge = plugin.getMessages().getMessageNoPrefix(
                isHorn ? "disc.lore_type_horn" : (isPlaylist ? "disc.lore_type_playlist" : "disc.lore_type_single"));
        if (typeBadge != null) lore.add(typeBadge);

        lore.add(Component.empty());
        Component creatorLine = plugin.getMessages().getMessageNoPrefix(
                "disc.lore_creator", Messages.placeholder("creator", creatorName));
        if (creatorLine != null) lore.add(creatorLine);

        String displaySource;
        if (isPlaylist) {
            String plId = source.substring("playlist:".length());
            String tpl = plugin.getMessages().getRaw("disc.source_playlist");
            displaySource = tpl.isBlank() ? plId : tpl.replace("<id>", plId);
        } else {
            displaySource = shortenSource(source);
        }
        Component sourceLine = plugin.getMessages().getMessageNoPrefix(
                "disc.lore_source", Messages.placeholder("source", displaySource));
        if (sourceLine != null) lore.add(sourceLine);

        if (isHorn && (pdc.has(hornVolumeKey, PersistentDataType.FLOAT)
                || pdc.has(hornDistanceKey, PersistentDataType.DOUBLE)
                || pdc.has(hornDurationKey, PersistentDataType.INTEGER))) {
            float vol = pdc.getOrDefault(hornVolumeKey, PersistentDataType.FLOAT, plugin.getSettings().getGoatHornVolume());
            double dist = pdc.getOrDefault(hornDistanceKey, PersistentDataType.DOUBLE, plugin.getSettings().getGoatHornDistance());
            int dur = pdc.getOrDefault(hornDurationKey, PersistentDataType.INTEGER, (int) plugin.getSettings().getGoatHornMaxDurationSeconds());
            Component paramsLine = plugin.getMessages().getMessageNoPrefix("disc.lore_horn_params",
                    Messages.placeholder("volume", String.valueOf(Math.round(vol * 100))),
                    Messages.placeholder("distance", String.valueOf((int) Math.round(dist))),
                    Messages.placeholder("duration", String.valueOf(dur)));
            if (paramsLine != null) lore.add(paramsLine);
        }

        return lore;
    }

    public void stripVanillaPlayable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        if (isMusicDisc(item)) {
            item.unsetData(DataComponentTypes.JUKEBOX_PLAYABLE);
        } else if (item.getType() == Material.GOAT_HORN) {
            item.unsetData(DataComponentTypes.INSTRUMENT);
        }
    }

    public ItemStack removeCustomData(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.remove(trackIdKey);
            pdc.remove(sourceKey);
            pdc.remove(creatorKey);
            pdc.remove(creatorUuidKey);
            pdc.remove(hornVolumeKey);
            pdc.remove(hornDistanceKey);
            pdc.remove(hornDurationKey);
            pdc.remove(hornModeKey);

            meta.displayName(null);
            meta.lore(null);
            item.setItemMeta(meta);
        }

        if (isMusicDisc(item)) {
            item.resetData(DataComponentTypes.JUKEBOX_PLAYABLE);
        } else if (item.getType() == Material.GOAT_HORN) {
            item.resetData(DataComponentTypes.INSTRUMENT);
        }
        item.resetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);

        return item;
    }

    public float getHornVolume(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(hornVolumeKey, PersistentDataType.FLOAT)) {
                Float v = meta.getPersistentDataContainer().get(hornVolumeKey, PersistentDataType.FLOAT);
                if (v != null) return Math.clamp(v, 0.05f, 1.5f);
            }
        }
        return plugin.getSettings().getGoatHornVolume();
    }

    public void setHornVolume(ItemStack item, float volume) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hornVolumeKey, PersistentDataType.FLOAT, Math.clamp(volume, 0.05f, 1.5f));
            item.setItemMeta(meta);
            rebuildLore(item);
        }
    }

    public double getHornDistance(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(hornDistanceKey, PersistentDataType.DOUBLE)) {
                Double d = meta.getPersistentDataContainer().get(hornDistanceKey, PersistentDataType.DOUBLE);
                if (d != null) return Math.clamp(d, 4.0, 128.0);
            }
        }
        return plugin.getSettings().getGoatHornDistance();
    }

    public void setHornDistance(ItemStack item, double distance) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hornDistanceKey, PersistentDataType.DOUBLE, Math.clamp(distance, 4.0, 128.0));
            item.setItemMeta(meta);
            rebuildLore(item);
        }
    }

    public int getHornDurationSeconds(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(hornDurationKey, PersistentDataType.INTEGER)) {
                Integer s = meta.getPersistentDataContainer().get(hornDurationKey, PersistentDataType.INTEGER);
                if (s != null) return Math.clamp(s, 1, 600);
            }
        }
        return (int) plugin.getSettings().getGoatHornMaxDurationSeconds();
    }

    public void setHornDurationSeconds(ItemStack item, int seconds) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hornDurationKey, PersistentDataType.INTEGER, Math.clamp(seconds, 1, 600));
            item.setItemMeta(meta);
            rebuildLore(item);
        }
    }

    public String getHornMode(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(hornModeKey, PersistentDataType.STRING)) {
                String m = meta.getPersistentDataContainer().get(hornModeKey, PersistentDataType.STRING);
                if ("restart".equalsIgnoreCase(m) || "toggle".equalsIgnoreCase(m)) {
                    return m.toLowerCase();
                }
            }
        }
        return plugin.getSettings().getGoatHornDefaultMode();
    }

    public void setHornMode(ItemStack item, String mode) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hornModeKey, PersistentDataType.STRING,
                    "restart".equalsIgnoreCase(mode) ? "restart" : "toggle");
            item.setItemMeta(meta);
        }
    }

    private Component parseDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Component.text(plugin.getMessages().getDefaultTrackTitle())
                    .decoration(TextDecoration.ITALIC, false);
        }
        if (displayName.indexOf('§') >= 0) {
            return LegacyComponentSerializer.legacySection().deserialize(displayName)
                    .decoration(TextDecoration.ITALIC, false);
        }
        if (displayName.indexOf('&') >= 0 && displayName.matches(".*&[0-9a-fk-orA-FK-OR].*")) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(displayName)
                    .decoration(TextDecoration.ITALIC, false);
        }
        try {
            if (displayName.indexOf('<') >= 0 && displayName.indexOf('>') > displayName.indexOf('<')) {
                return MiniMessage.miniMessage().deserialize(displayName)
                        .decoration(TextDecoration.ITALIC, false);
            }
        } catch (Exception ignored) {
        }
        return Component.text(displayName).decoration(TextDecoration.ITALIC, false);
    }

    private String shortenSource(String source) {
        if (source == null) return plugin.getMessages().getUnknownLabel();
        if (source.length() <= 42) return source;
        return source.substring(0, 39) + "...";
    }

    public boolean isMusicDisc(ItemStack item) {
        if (item == null) return false;
        return item.getType().name().startsWith("MUSIC_DISC_");
    }

    public boolean isCustomDisc(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(trackIdKey, PersistentDataType.STRING);
    }

    public boolean isBurnable(ItemStack item) {
        if (!plugin.getSettings().isRequireBurnableTag()) return true;
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(burnableKey, PersistentDataType.BYTE);
    }

    public Optional<String> getTrackId(ItemStack item) {
        if (!isCustomDisc(item)) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Optional.empty();
        return Optional.ofNullable(meta.getPersistentDataContainer().get(trackIdKey, PersistentDataType.STRING));
    }

    public Optional<String> getSource(ItemStack item) {
        if (!isCustomDisc(item)) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Optional.empty();
        String src = meta.getPersistentDataContainer().get(sourceKey, PersistentDataType.STRING);
        if (src != null) return Optional.of(src);
        return getTrackId(item);
    }

    public String getCreatorUUID(ItemStack item) {
        if (!isCustomDisc(item)) return null;
        ItemMeta meta = item.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer().get(creatorUuidKey, PersistentDataType.STRING) : null;
    }

    public String getDiscType(ItemStack item) {
        if (item.getType() == Material.GOAT_HORN) return "horn";
        String source = getSource(item).orElse("");
        if (source.startsWith("playlist:")) return "playlist";
        return "single";
    }
}
