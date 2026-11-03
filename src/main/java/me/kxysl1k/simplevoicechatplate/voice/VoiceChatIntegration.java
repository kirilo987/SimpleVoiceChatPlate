package me.kxysl1k.simplevoicechatplate.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;

import java.nio.charset.StandardCharsets;

/**
 * Интеграция с Simple Voice Chat API и регистрация ползунков громкости на клиенте.
 * В сетевом пакете AddCategoryPacket (VolumeCategoryImpl#toBytes) поле name кодируется через
 * FriendlyByteBuf#writeUtf(name, 16). В сборках voicechat-bukkit <= 2.6.25 метод writeUtf проверяет
 * длину массива байтов UTF-8 (а не длину String в символах), выбрасывая EncoderException при превышении
 * 16 байт (что соответствует максимум 8 кириллическим символам). Поэтому все названия и описания категорий
 * проходят жесткую обрезку по границам кодовых точек Unicode с контролем лимита байтов UTF-8.
 */
public class VoiceChatIntegration implements VoicechatPlugin {

    public static final String PLUGIN_ID = "simplevoicechatplate";
    public static final String MUSIC_CATEGORY_ID = "svcplate_music";
    public static final String HORN_CATEGORY_ID = "svcplate_horn";

    private static final int MAX_NAME_UTF8_BYTES = 16;
    private static final int MAX_DESC_UTF8_BYTES = 256;

    private final SimpleVoiceChatPlate plugin;
    private volatile VoicechatApi api;
    private volatile VoicechatServerApi serverApi;
    private volatile VolumeCategory musicCategory;
    private volatile VolumeCategory hornCategory;

    public VoiceChatIntegration(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public static VoiceChatIntegration register(SimpleVoiceChatPlate plugin) {
        BukkitVoicechatService vcService = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (vcService == null) {
            return null;
        }
        VoiceChatIntegration integration = new VoiceChatIntegration(plugin);
        vcService.registerPlugin(integration);
        return integration;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        if (api instanceof VoicechatServerApi sApi) {
            this.serverApi = sApi;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        this.serverApi = event.getVoicechat();
        refreshCategories();
    }

    public void refreshCategories() {
        VoicechatServerApi vc = getServerApi();
        if (vc == null) return;

        try {
            if (musicCategory != null) vc.unregisterVolumeCategory(musicCategory);
            if (hornCategory != null) vc.unregisterVolumeCategory(hornCategory);
        } catch (Exception ignored) {
        }

        String musicName = sanitizeCategoryName(
                plugin.getMessages().getRaw("voicechat.category_music_name"), "Music Discs", "voicechat.category_music_name");
        String musicDesc = truncateUtf8(
                nonBlankOr(plugin.getMessages().getRaw("voicechat.category_music_desc"), "SimpleVoiceChatPlate music discs"),
                MAX_DESC_UTF8_BYTES, "SimpleVoiceChatPlate music discs", "voicechat.category_music_desc");

        String hornName = sanitizeCategoryName(
                plugin.getMessages().getRaw("voicechat.category_horn_name"), "Soundpad Horns", "voicechat.category_horn_name");
        String hornDesc = truncateUtf8(
                nonBlankOr(plugin.getMessages().getRaw("voicechat.category_horn_desc"), "SimpleVoiceChatPlate goat horns"),
                MAX_DESC_UTF8_BYTES, "SimpleVoiceChatPlate goat horns", "voicechat.category_horn_desc");

        musicCategory = vc.volumeCategoryBuilder()
                .setId(MUSIC_CATEGORY_ID)
                .setName(musicName)
                .setDescription(musicDesc)
                .build();

        hornCategory = vc.volumeCategoryBuilder()
                .setId(HORN_CATEGORY_ID)
                .setName(hornName)
                .setDescription(hornDesc)
                .build();

        vc.registerVolumeCategory(musicCategory);
        vc.registerVolumeCategory(hornCategory);
    }

    private static String sanitizeCategoryName(String raw, String fallback, String keyName) {
        String value = nonBlankOr(raw, fallback).trim();
        if ("Музыкальные пластинки".equals(value)) {
            value = "Музыка";
        } else if ("Звуковые рога".equals(value)) {
            value = "Рога";
        }
        return truncateUtf8(value, MAX_NAME_UTF8_BYTES, fallback, keyName);
    }

    private static String truncateUtf8(String value, int maxBytes, String fallback, String keyName) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= maxBytes && value.length() <= maxBytes) {
            return value;
        }

        StringBuilder sb = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            int charCount = Character.charCount(cp);
            int cpBytes = cp <= 0x7F ? 1 : (cp <= 0x7FF ? 2 : (cp <= 0xFFFF ? 3 : 4));
            if (usedBytes + cpBytes > maxBytes || sb.length() + charCount > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            usedBytes += cpBytes;
            offset += charCount;
        }

        String trimmed = sb.toString().trim();
        if (trimmed.isEmpty()) {
            trimmed = fallback;
        }
        LogUtil.warn("Значение '" + keyName + "' превышало лимит протокола Simple Voice Chat ("
                + maxBytes + " байт UTF-8) и было сокращено до: '" + trimmed + "'");
        return trimmed;
    }

    private static String nonBlankOr(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val;
    }

    public void shutdown() {
        VoicechatServerApi vc = this.serverApi;
        if (vc != null) {
            try {
                if (musicCategory != null) vc.unregisterVolumeCategory(musicCategory);
                if (hornCategory != null) vc.unregisterVolumeCategory(hornCategory);
            } catch (Exception ignored) {
            }
        }
    }

    public VoicechatApi getApi() {
        return api;
    }

    public VoicechatServerApi getServerApi() {
        if (serverApi != null) return serverApi;
        if (api instanceof VoicechatServerApi sApi) return sApi;
        return null;
    }

    public VolumeCategory getMusicCategory() {
        return musicCategory;
    }

    public VolumeCategory getHornCategory() {
        return hornCategory;
    }
}
