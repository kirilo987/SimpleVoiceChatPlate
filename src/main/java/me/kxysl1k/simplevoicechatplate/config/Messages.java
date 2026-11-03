package me.kxysl1k.simplevoicechatplate.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class Messages {

    private final ConfigManager configManager;
    private final MiniMessage miniMessage;

    public Messages(ConfigManager configManager) {
        this.configManager = configManager;
        this.miniMessage = MiniMessage.miniMessage();
    }

    private FileConfiguration lang() {
        return configManager.getLanguageConfig();
    }

    public Component getMessage(String path, TagResolver... placeholders) {
        FileConfiguration cfg = lang();
        if (cfg == null) return null;
        String msg = cfg.getString(path);
        if (msg == null || msg.isEmpty()) return null;
        String prefix = cfg.getString("prefix", "");
        return miniMessage.deserialize(prefix + msg, placeholders);
    }

    public Component getMessageNoPrefix(String path, TagResolver... placeholders) {
        FileConfiguration cfg = lang();
        if (cfg == null) return null;
        String msg = cfg.getString(path);
        if (msg == null || msg.isEmpty()) return null;
        return miniMessage.deserialize(msg, placeholders)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void sendMessage(CommandSender sender, String path, TagResolver... placeholders) {
        Component comp = getMessage(path, placeholders);
        if (comp != null) sender.sendMessage(comp);
    }

    public void sendMessageNoPrefix(CommandSender sender, String path, TagResolver... placeholders) {
        Component comp = getMessageNoPrefix(path, placeholders);
        if (comp != null) sender.sendMessage(comp);
    }

    public void sendActionBar(Player player, String path, TagResolver... placeholders) {
        Component comp = getMessageNoPrefix(path, placeholders);
        if (comp != null) player.sendActionBar(comp);
    }

    public String getRaw(String path) {
        FileConfiguration cfg = lang();
        return cfg != null ? cfg.getString(path, "") : "";
    }

    public String getDefaultTrackTitle() {
        String title = getRaw("defaults.track_title");
        return title.isBlank() ? "Music Track" : title;
    }

    public String getUnknownLabel() {
        String label = getRaw("defaults.unknown");
        return label.isBlank() ? "Unknown" : label;
    }

    /**
     * Подставляет внешнюю строку (например, название видео с YouTube или ник игрока) как безопасный Component,
     * а не сырой MiniMessage-шаблон: это предотвращает инъекцию тегов (<click>, <hover>) и поломку парсера
     * от скобок < > в пользовательских названиях треков, при этом сохраняя поддержку цветовых кодов & / §.
     */
    public static TagResolver placeholder(String key, String value) {
        if (value == null) {
            return Placeholder.component(key, Component.empty());
        }
        Component component;
        if (value.indexOf('§') >= 0) {
            component = LegacyComponentSerializer.legacySection().deserialize(value);
        } else if (value.indexOf('&') >= 0 && value.matches(".*&[0-9a-fk-orA-FK-OR].*")) {
            component = LegacyComponentSerializer.legacyAmpersand().deserialize(value);
        } else {
            component = Component.text(value);
        }
        return Placeholder.component(key, component);
    }

    public static TagResolver miniPlaceholder(String key, String miniValue) {
        if (miniValue == null || miniValue.isEmpty()) {
            return Placeholder.component(key, Component.empty());
        }
        return Placeholder.parsed(key, miniValue);
    }

    public static TagResolver placeholder(String key, Component value) {
        return Placeholder.component(key, value != null ? value : Component.empty());
    }
}
