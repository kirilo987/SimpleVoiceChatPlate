package me.kxysl1k.simplevoicechatplate.config;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    private static final String[] BUNDLED_LANGUAGES = {"ru", "en"};

    private final SimpleVoiceChatPlate plugin;
    private final Map<String, FileConfiguration> configs = new ConcurrentHashMap<>();
    private volatile FileConfiguration activeLanguageConfig;
    private volatile String activeLanguageCode = "ru";

    public ConfigManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            LogUtil.warn("Failed to create plugin data folder: " + dataFolder.getAbsolutePath());
        }

        loadFile("config.yml");
        extractBundledLanguages();

        String requestedLang = getConfig("config.yml").getString("settings.language", "ru");
        loadActiveLanguage(requestedLang);

        LogUtil.setDebugMode(getConfig("config.yml").getBoolean("settings.debug", false));
    }

    public void reloadAll() {
        configs.clear();
        loadAll();
    }

    private void extractBundledLanguages() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            LogUtil.warn("Failed to create lang directory: " + langDir.getAbsolutePath());
        }

        for (String code : BUNDLED_LANGUAGES) {
            String resourcePath = "lang/" + code + ".yml";
            File target = new File(plugin.getDataFolder(), resourcePath);
            if (!target.exists()) {
                plugin.saveResource(resourcePath, false);
            } else {
                upgradeLangFileIfOutdated(target, resourcePath, code);
            }
        }
    }

    private void upgradeLangFileIfOutdated(File langFile, String resourcePath, String code) {
        try {
            String content = Files.readString(langFile.toPath(), StandardCharsets.UTF_8);
            if (!content.contains("loading_base:") || !content.contains("horn_notify_enabled:")) {
                plugin.saveResource(resourcePath, true);
                return;
            }
            if ("ru".equals(code)) {
                migrateOversizedRussianCategoryNames(langFile);
            }
        } catch (IOException ignored) {
        }
    }

    private void migrateOversizedRussianCategoryNames(File ruFile) {
        try {
            String content = Files.readString(ruFile.toPath(), StandardCharsets.UTF_8);
            String updated = content
                    .replace("category_music_name: \"Музыкальные пластинки\"", "category_music_name: \"Музыка\"")
                    .replace("category_horn_name: \"Звуковые рога\"", "category_horn_name: \"Рога\"");
            if (!updated.equals(content)) {
                Files.writeString(ruFile.toPath(), updated, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    private void loadActiveLanguage(String rawCode) {
        String code = normalizeLangCode(rawCode);
        File langFile = new File(new File(plugin.getDataFolder(), "lang"), code + ".yml");

        if (!langFile.exists()) {
            LogUtil.warn("Языковой файл lang/" + code + ".yml не найден. Используется русский язык по умолчанию (lang/ru.yml).");
            code = "ru";
            langFile = new File(new File(plugin.getDataFolder(), "lang"), "ru.yml");
        }

        FileConfiguration langCfg = YamlConfiguration.loadConfiguration(langFile);
        String defaultResource = plugin.getResource("lang/" + code + ".yml") != null
                ? "lang/" + code + ".yml"
                : "lang/ru.yml";

        try (InputStream defStream = plugin.getResource(defaultResource)) {
            if (defStream != null) {
                YamlConfiguration defCfg = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                langCfg.setDefaults(defCfg);
                langCfg.options().copyDefaults(true);
            }
        } catch (IOException e) {
            LogUtil.warn("Не удалось загрузить встроенные значения по умолчанию для " + defaultResource + ": " + e.getMessage());
        }

        this.activeLanguageCode = code;
        this.activeLanguageConfig = langCfg;
    }

    private String normalizeLangCode(String raw) {
        if (raw == null || raw.isBlank()) return "ru";
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        if (cleaned.endsWith(".yml")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        if (cleaned.startsWith("ru") || cleaned.equals("russian")) return "ru";
        if (cleaned.startsWith("en") || cleaned.equals("english")) return "en";
        return cleaned.replaceAll("[^a-z0-9_-]", "");
    }

    private void loadFile(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        } else if ("config.yml".equals(fileName)) {
            upgradeMainConfigIfOutdated(file);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        try (InputStream defStream = plugin.getResource(fileName)) {
            if (defStream != null) {
                YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                config.setDefaults(defConfig);
                config.options().copyDefaults(true);
            }
        } catch (IOException e) {
            LogUtil.warn("Ошибка чтения ресурса " + fileName + ": " + e.getMessage());
        }

        configs.put(fileName, config);
    }

    private void upgradeMainConfigIfOutdated(File configFile) {
        try {
            String raw = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            if (!raw.contains("transition_time_ms:")
                    || !raw.contains("incognito_radio_recommendations:")
                    || !raw.contains("recommendation_lang: \"auto\"")) {
                FileConfiguration oldCfg = YamlConfiguration.loadConfiguration(configFile);
                String prevLang = oldCfg.getString("settings.language", "ru");
                boolean prevDebug = oldCfg.getBoolean("settings.debug", false);

                plugin.saveResource("config.yml", true);

                String fresh = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
                fresh = fresh.replace("language: \"ru\"", "language: \"" + prevLang + "\"");
                if (prevDebug) {
                    fresh = fresh.replace("debug: false", "debug: true");
                }
                Files.writeString(configFile.toPath(), fresh, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    public FileConfiguration getConfig(String fileName) {
        return configs.get(fileName);
    }

    public FileConfiguration getLanguageConfig() {
        return activeLanguageConfig;
    }

    public String getActiveLanguageCode() {
        return activeLanguageCode;
    }
}
