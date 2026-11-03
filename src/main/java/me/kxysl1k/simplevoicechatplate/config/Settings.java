package me.kxysl1k.simplevoicechatplate.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

public class Settings {

    private final ConfigManager configManager;

    public Settings(ConfigManager configManager) {
        this.configManager = configManager;
    }

    private FileConfiguration cfg() {
        return configManager.getConfig("config.yml");
    }

    public String getLanguage() { return cfg().getString("settings.language", "ru"); }
    public boolean isDebug() { return cfg().getBoolean("settings.debug", false); }
    public boolean isCreateCrashReports() { return cfg().getBoolean("logging.create_crash_reports", true); }
    public boolean isFileLoggingEnabled() { return cfg().getBoolean("logging.file_logging", true); }
    public int getLogRetentionDays() { return cfg().getInt("logging.retention_days", 14); }

    public String getFfmpegPath() { return cfg().getString("audio.ffmpeg_path", ""); }
    public String getAudioQualityPreset() { return cfg().getString("audio.quality_preset", "ultra").trim().toLowerCase(Locale.ROOT); }
    public String getOpusMode() { return cfg().getString("audio.opus_mode", "AUDIO").trim().toUpperCase(Locale.ROOT); }
    public float getAudioOutputGain() { return (float) Math.clamp(cfg().getDouble("audio.output_gain", 1.0), 0.1, 2.0); }
    public boolean isNormalizeAudio() { return cfg().getBoolean("audio.normalize_audio", true); }
    public String getCustomFfmpegFilter() { return cfg().getString("audio.custom_ffmpeg_filter", "").trim(); }
    public double getInitialBufferSeconds() { return Math.clamp(cfg().getDouble("audio.initial_buffer_seconds", 1.5), 0.4, 15.0); }
    public long getLoadingAnimationTicks() { return Math.clamp(cfg().getLong("audio.loading_animation_ticks", 8L), 2L, 40L); }
    public long getMaxAudioLengthSeconds() { return cfg().getLong("audio.max_length_seconds", 1800L); }
    public long getMaxDownloadSizeBytes() { return cfg().getLong("audio.max_download_size_bytes", 52428800L); }
    public int getMaxConcurrentProcesses() { return Math.max(1, cfg().getInt("audio.max_concurrent_processes", 4)); }

    public int getPlaylistMaxTracks() { return Math.clamp(cfg().getInt("playlist.max_tracks", 100), 5, 500); }
    public int getPlaylistInitialReadyTracks() { return Math.clamp(cfg().getInt("playlist.initial_ready_tracks", 3), 1, 20); }
    public int getPrefetchNextTracks() { return Math.clamp(cfg().getInt("playlist.prefetch_next_tracks", 3), 0, 10); }
    public int getPrefetchPreviousTracks() { return Math.clamp(cfg().getInt("playlist.prefetch_previous_tracks", 2), 0, 10); }
    public int getHeadPreloadSeconds() { return Math.clamp(cfg().getInt("playlist.head_preload_seconds", 12), 4, 45); }

    public boolean isAcousticsEnabled() { return cfg().getBoolean("acoustics.enabled", true); }
    public boolean isThroughWallsEnabled() { return cfg().getBoolean("acoustics.through_walls", true); }
    public boolean isDiffractionEnabled() { return cfg().getBoolean("acoustics.diffraction_enabled", true); }
    public boolean isRoomReverbEnabled() { return cfg().getBoolean("acoustics.room_reverb_enabled", false); }
    public boolean isAirAbsorptionEnabled() { return cfg().getBoolean("acoustics.air_absorption_enabled", false); }
    public boolean isUnderwaterMuffleEnabled() { return cfg().getBoolean("acoustics.underwater_muffle", true); }
    public float getWallMuffleStrength() { return (float) Math.max(0.0, cfg().getDouble("acoustics.wall_muffle_strength", 1.0)); }
    public float getWallBassResonance() { return (float) Math.max(0.0, cfg().getDouble("acoustics.wall_bass_resonance", 0.85)); }
    public float getMinWallGain() { return (float) Math.clamp(cfg().getDouble("acoustics.min_wall_gain", 0.04), 0.0, 1.0); }
    public long getAcousticsUpdateTicks() { return Math.max(1L, cfg().getLong("acoustics.update_interval_ticks", 3L)); }

    public String getCacheFolder() { return cfg().getString("folders.cache", "cache"); }
    public boolean isCacheAutoCleanup() { return cfg().getBoolean("cache.auto_cleanup", true); }
    public long getCacheCleanupDelay() { return Math.max(200L, cfg().getLong("cache.cleanup_delay_ticks", 1200L)); }
    public long getCacheRetentionDays() { return Math.max(1L, cfg().getLong("cache.retention_days", 7L)); }

    public boolean isYouTubeEnabled() { return cfg().getBoolean("youtube.enabled", true); }
    public String getYtDlpPath() { return cfg().getString("youtube.ytdlp_path", ""); }
    public String getPlayerClients() { return cfg().getString("youtube.player_clients", "mweb,web,ios,android"); }
    public String getExtractorArgs() { return cfg().getString("youtube.extractor_args", ""); }
    public boolean isAutoUpdateYtDlp() { return cfg().getBoolean("youtube.auto_update", true); }
    public List<String> getBlacklistedDomains() { return cfg().getStringList("blacklist.domains"); }

    public float getJukeboxVolume() { return (float) Math.clamp(cfg().getDouble("jukebox.volume_multiplier", 0.75), 0.0, 1.5); }
    public double getJukeboxDistance() { return Math.max(4.0, cfg().getDouble("jukebox.distance", 65.0)); }
    public boolean isDropDiscOnBreak() { return cfg().getBoolean("jukebox.drop_disc_on_break", true); }
    public boolean isJukeboxLoadingAnimation() { return cfg().getBoolean("jukebox.show_loading_animation", true); }
    public boolean isJukeboxPlayingNotification() { return cfg().getBoolean("jukebox.show_playing_notification", true); }
    public boolean isBeaconDistanceEnabled() { return cfg().getBoolean("jukebox.enable_beacon_like_distance", true); }
    public List<Double> getBeaconDistances() { return cfg().getDoubleList("jukebox.beacon_like_distances"); }

    public boolean isGoatHornEnabled() { return cfg().getBoolean("goat_horn.enabled", true); }
    public float getGoatHornVolume() { return (float) Math.clamp(cfg().getDouble("goat_horn.volume_multiplier", 0.85), 0.0, 1.5); }
    public double getGoatHornDistance() { return Math.max(4.0, cfg().getDouble("goat_horn.distance", 24.0)); }
    public long getGoatHornMaxDurationSeconds() { return Math.max(1L, cfg().getLong("goat_horn.max_duration_seconds", 15L)); }
    public int getGoatHornCooldownTicks() { return Math.max(0, cfg().getInt("goat_horn.cooldown_ticks", 100)); }
    public String getGoatHornDefaultMode() { return cfg().getString("goat_horn.default_mode", "toggle").trim().toLowerCase(Locale.ROOT); }
    public boolean isGoatHornLoadingAnimation() { return cfg().getBoolean("goat_horn.show_loading_animation", true); }
    public boolean isGoatHornPlayingNotification() { return cfg().getBoolean("goat_horn.show_playing_notification", true); }
    public boolean isGoatHornStoppedNotification() { return cfg().getBoolean("goat_horn.show_stopped_notification", true); }

    public boolean isPortablePlayerEnabled() { return cfg().getBoolean("portable_player.enabled", false); }
    public float getPortablePlayerVolume() { return (float) Math.clamp(cfg().getDouble("portable_player.volume_multiplier", 0.60), 0.0, 1.5); }
    public double getPortablePlayerDistance() { return Math.max(4.0, cfg().getDouble("portable_player.distance", 18.0)); }
    public boolean isPortableLoadingAnimation() { return cfg().getBoolean("portable_player.show_loading_animation", true); }
    public boolean isPortablePlayingNotification() { return cfg().getBoolean("portable_player.show_playing_notification", true); }
    public boolean isPortableStoppedNotification() { return cfg().getBoolean("portable_player.show_stopped_notification", true); }

    public boolean isAnvilEnabled() { return cfg().getBoolean("anvil.enabled", true); }
    public int getAnvilShearsDamage() { return Math.max(0, cfg().getInt("anvil.shears_damage", 34)); }
    public boolean isRequireBurnableTag() { return cfg().getBoolean("burnable_tag.require_burnable_tag", false); }
    public boolean isEnableDefaultRecipe() { return cfg().getBoolean("burnable_tag.enable_default_recipe", false); }
    public String getDefaultRecipeItem() { return cfg().getString("burnable_tag.default_recipe_item", "minecraft:diamond"); }
    public int getDefaultRecipeCost() { return Math.clamp(cfg().getInt("burnable_tag.default_recipe_cost", 4), 1, 8); }
    public String getDefaultRecipeLore() { return cfg().getString("burnable_tag.default_recipe_lore", "<aqua>Чистая пластинка для записи</aqua>"); }

    public int getGuiRewindSeconds() { return Math.max(1, cfg().getInt("gui.rewind_seconds", 15)); }
    public float getGuiVolumeSmallStep() { return (float) Math.max(0.01, cfg().getDouble("gui.volume_small_step", 0.05)); }
    public float getGuiVolumeLargeStep() { return (float) Math.max(0.01, cfg().getDouble("gui.volume_large_step", 0.15)); }
    public int getProgressBarLength() { return Math.clamp(cfg().getInt("gui.progress_bar_length", 22), 5, 40); }

    public int getDefaultLimit(String type) { return cfg().getInt("limits." + type, 0); }
}
