package me.kxysl1k.simplevoicechatplate;

import me.kxysl1k.simplevoicechatplate.audio.AudioManager;
import me.kxysl1k.simplevoicechatplate.cache.CacheManager;
import me.kxysl1k.simplevoicechatplate.command.PlateCommand;
import me.kxysl1k.simplevoicechatplate.config.ConfigManager;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import me.kxysl1k.simplevoicechatplate.config.Settings;
import me.kxysl1k.simplevoicechatplate.data.PlayerDataManager;
import me.kxysl1k.simplevoicechatplate.disc.DiscManager;
import me.kxysl1k.simplevoicechatplate.gui.PlatePlayerGUIListener;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxListener;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.listener.AnvilListener;
import me.kxysl1k.simplevoicechatplate.listener.GoatHornListener;
import me.kxysl1k.simplevoicechatplate.listener.HopperListener;
import me.kxysl1k.simplevoicechatplate.listener.PortablePlayerListener;
import me.kxysl1k.simplevoicechatplate.packet.PacketEventsHook;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistPrefetchCoordinator;
import me.kxysl1k.simplevoicechatplate.util.CrashLogger;
import me.kxysl1k.simplevoicechatplate.util.DependencyManager;
import me.kxysl1k.simplevoicechatplate.util.LoadingBarAnimator;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;
import me.kxysl1k.simplevoicechatplate.voice.VoiceChatIntegration;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class SimpleVoiceChatPlate extends JavaPlugin {

    private static SimpleVoiceChatPlate instance;

    private CrashLogger crashLogger;
    private ConfigManager configManager;
    private Settings settings;
    private Messages messages;
    private DependencyManager dependencyManager;
    private CacheManager cacheManager;
    private AudioManager audioManager;
    private DiscManager discManager;
    private JukeboxManager jukeboxManager;
    private VoiceChatIntegration voiceChatIntegration;
    private VoicePlayerManager voicePlayerManager;
    private PlaylistManager playlistManager;
    private PlaylistPrefetchCoordinator playlistPrefetchCoordinator;
    private PlayerDataManager playerDataManager;
    private LoadingBarAnimator loadingBarAnimator;
    private final List<NamespacedKey> registeredRecipeKeys = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        LogUtil.setLogger(getLogger());

        crashLogger = new CrashLogger(this);
        LogUtil.setCrashLogger(crashLogger);

        configManager = new ConfigManager(this);
        configManager.loadAll();

        settings = new Settings(configManager);
        messages = new Messages(configManager);
        LogUtil.setDebugMode(settings.isDebug());
        crashLogger.init();

        dependencyManager = new DependencyManager(this);
        dependencyManager.init();

        cacheManager = new CacheManager(this);
        audioManager = new AudioManager(this);
        discManager = new DiscManager(this);
        jukeboxManager = new JukeboxManager(this);
        playlistManager = new PlaylistManager(this);
        playlistPrefetchCoordinator = new PlaylistPrefetchCoordinator(this);
        playerDataManager = new PlayerDataManager(this);
        loadingBarAnimator = new LoadingBarAnimator(this);

        PlateCommand command = new PlateCommand(this);
        PluginCommand pluginCmd = getCommand("simplevoicechatplate");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(command);
            pluginCmd.setTabCompleter(command);
        }

        var pm = getServer().getPluginManager();
        pm.registerEvents(new JukeboxListener(this), this);
        pm.registerEvents(new AnvilListener(this), this);
        pm.registerEvents(new GoatHornListener(this), this);
        pm.registerEvents(new HopperListener(this), this);
        pm.registerEvents(new PortablePlayerListener(this), this);
        pm.registerEvents(new PlatePlayerGUIListener(this), this);

        if (pm.isPluginEnabled("packetevents")) {
            try {
                PacketEventsHook.register(this);
            } catch (Throwable t) {
                LogUtil.warn("PacketEvents hook error: " + t.getMessage());
            }
        }

        if (pm.isPluginEnabled("voicechat")) {
            try {
                voiceChatIntegration = VoiceChatIntegration.register(this);
                if (voiceChatIntegration != null) {
                    voicePlayerManager = new VoicePlayerManager(this);
                }
            } catch (Throwable t) {
                LogUtil.warn("Simple Voice Chat hook error: " + t.getMessage());
            }
        }
        if (voiceChatIntegration == null) {
            LogUtil.warn("Simple Voice Chat not detected! Custom audio playback is unavailable.");
        }

        registerRecipes();
        LogUtil.info("SimpleVoiceChatPlate v" + getPluginMeta().getVersion()
                + " (author: Kxysl1k, lang: " + configManager.getActiveLanguageCode() + ") enabled on Paper 26.3.");
    }

    public void reloadPlugin() {
        configManager.reloadAll();
        settings = new Settings(configManager);
        messages = new Messages(configManager);
        LogUtil.setDebugMode(settings.isDebug());

        if (crashLogger != null) crashLogger.init();
        if (audioManager != null) audioManager.clearMemoryCache();
        if (cacheManager != null) cacheManager.startCleanupTask();
        if (voiceChatIntegration != null) voiceChatIntegration.refreshCategories();
        if (voicePlayerManager != null) voicePlayerManager.startBackgroundUpdaters();

        unregisterRecipes();
        registerRecipes();
    }

    private void registerRecipes() {
        if (!settings.isEnableDefaultRecipe()) return;

        Material costMaterial = Material.matchMaterial(settings.getDefaultRecipeItem());
        if (costMaterial == null) costMaterial = Material.DIAMOND;

        int costAmount = settings.getDefaultRecipeCost();
        Component loreComp = MiniMessage.miniMessage()
                .deserialize(settings.getDefaultRecipeLore())
                .decoration(TextDecoration.ITALIC, false);

        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.name().startsWith("MUSIC_DISC_")) {
                NamespacedKey key = new NamespacedKey(this, "burnable_" + m.name().toLowerCase());
                ItemStack result = new ItemStack(m);
                ItemMeta meta = result.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(discManager.getBurnableKey(), PersistentDataType.BYTE, (byte) 1);
                    meta.lore(List.of(loreComp));
                    result.setItemMeta(meta);
                }

                ShapelessRecipe recipe = new ShapelessRecipe(key, result);
                recipe.addIngredient(m);
                recipe.addIngredient(costAmount, costMaterial);
                if (getServer().addRecipe(recipe)) {
                    registeredRecipeKeys.add(key);
                }
            }
        }
    }

    private void unregisterRecipes() {
        for (NamespacedKey key : registeredRecipeKeys) {
            getServer().removeRecipe(key);
        }
        registeredRecipeKeys.clear();
    }

    @Override
    public void onDisable() {
        if (loadingBarAnimator != null) {
            loadingBarAnimator.stopAll();
        }
        if (voicePlayerManager != null) {
            voicePlayerManager.stopBackgroundUpdaters();
            voicePlayerManager.stopAll();
        }
        if (cacheManager != null) {
            cacheManager.stopCleanupTask();
        }
        if (getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try {
                PacketEventsHook.unregister();
            } catch (Throwable ignored) {
            }
        }
        if (voiceChatIntegration != null) {
            voiceChatIntegration.shutdown();
        }
        unregisterRecipes();
    }

    public static SimpleVoiceChatPlate getInstance() { return instance; }
    public CrashLogger getCrashLogger() { return crashLogger; }
    public ConfigManager getConfigManager() { return configManager; }
    public Settings getSettings() { return settings; }
    public Messages getMessages() { return messages; }
    public DependencyManager getDependencyManager() { return dependencyManager; }
    public CacheManager getCacheManager() { return cacheManager; }
    public AudioManager getAudioManager() { return audioManager; }
    public DiscManager getDiscManager() { return discManager; }
    public JukeboxManager getJukeboxManager() { return jukeboxManager; }
    public VoiceChatIntegration getVoiceChatIntegration() { return voiceChatIntegration; }
    public VoicePlayerManager getVoicePlayerManager() { return voicePlayerManager; }
    public PlaylistManager getPlaylistManager() { return playlistManager; }
    public PlaylistPrefetchCoordinator getPlaylistPrefetchCoordinator() { return playlistPrefetchCoordinator; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public LoadingBarAnimator getLoadingBarAnimator() { return loadingBarAnimator; }
}
