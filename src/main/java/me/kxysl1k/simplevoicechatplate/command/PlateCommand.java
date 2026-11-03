package me.kxysl1k.simplevoicechatplate.command;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.audio.AudioManager;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.listener.AnvilListener;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.voice.VoicePlayerManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlateCommand implements CommandExecutor, TabCompleter {

    private static final String[] ADMIN_PERMISSIONS = {
            "simplevoicechatplate.command.cancel.other",
            "simplevoicechatplate.command.stoparound",
            "simplevoicechatplate.command.status",
            "simplevoicechatplate.command.cache.clear",
            "simplevoicechatplate.command.updatedeps",
            "simplevoicechatplate.command.reload"
    };

    private static final String[] HORN_SUB_PERMISSIONS = {
            "simplevoicechatplate.command.horn",
            "simplevoicechatplate.command.horn.info",
            "simplevoicechatplate.command.horn.volume",
            "simplevoicechatplate.command.horn.distance",
            "simplevoicechatplate.command.horn.duration",
            "simplevoicechatplate.command.horn.mode",
            "simplevoicechatplate.command.horn.notify"
    };

    private final SimpleVoiceChatPlate plugin;

    public PlateCommand(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            return handleHelp(sender);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "help" -> { return handleHelp(sender); }
            case "reload" -> { return handleReload(sender); }
            case "cache" -> { return handleCache(sender, args); }
            case "updatedeps" -> { return handleUpdateDeps(sender); }
            case "status" -> { return handleStatus(sender); }
        }

        if (!(sender instanceof Player player)) {
            plugin.getMessages().sendMessage(sender, "commands.player_only");
            return true;
        }

        return switch (sub) {
            case "cancel" -> handleCancel(player, args);
            case "stoparound" -> handleStopAround(player, args);
            case "search" -> handleSearch(player, args);
            case "erase" -> handleErase(player);
            case "burn" -> handleBurn(player, args);
            case "horn" -> handleHorn(player, args);
            default -> {
                plugin.getMessages().sendMessage(sender, "commands.unknown_subcommand");
                yield true;
            }
        };
    }

    private boolean handleHelp(CommandSender sender) {
        if (!sender.hasPermission("simplevoicechatplate.command.help")) {
            plugin.getMessages().sendMessage(sender, "commands.no_permission");
            return true;
        }

        plugin.getMessages().sendMessage(sender, "commands.help_header");
        sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.burn", "commands.help_lines.burn");
        sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.erase", "commands.help_lines.erase");
        sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.search", "commands.help_lines.search");
        if (hasAnyHornPermission(sender)) {
            plugin.getMessages().sendMessageNoPrefix(sender, "commands.help_lines.horn");
        }
        sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.cancel", "commands.help_lines.cancel");

        if (hasAnyAdminPermission(sender)) {
            plugin.getMessages().sendMessageNoPrefix(sender, "commands.help_admin_header");
            sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.cancel.other", "commands.help_lines.cancel_other");
            sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.stoparound", "commands.help_lines.stoparound");
            sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.status", "commands.help_lines.status");
            sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.cache.clear", "commands.help_lines.cache_clear");
            sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.updatedeps", "commands.help_lines.updatedeps");
            sendHelpLineIfPermitted(sender, "simplevoicechatplate.command.reload", "commands.help_lines.reload");
        }
        return true;
    }

    private boolean hasAnyAdminPermission(CommandSender sender) {
        for (String perm : ADMIN_PERMISSIONS) {
            if (sender.hasPermission(perm)) return true;
        }
        return false;
    }

    private boolean hasAnyHornPermission(CommandSender sender) {
        for (String perm : HORN_SUB_PERMISSIONS) {
            if (sender.hasPermission(perm)) return true;
        }
        return false;
    }

    private void sendHelpLineIfPermitted(CommandSender sender, String permission, String messageKey) {
        if (sender.hasPermission(permission)) {
            plugin.getMessages().sendMessageNoPrefix(sender, messageKey);
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("simplevoicechatplate.command.reload")) {
            plugin.getMessages().sendMessage(sender, "commands.no_permission");
            return true;
        }
        plugin.reloadPlugin();
        plugin.getMessages().sendMessage(sender, "commands.reload_success",
                Messages.placeholder("lang", plugin.getConfigManager().getActiveLanguageCode()));
        return true;
    }

    private boolean handleCache(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simplevoicechatplate.command.cache.clear")) {
            plugin.getMessages().sendMessage(sender, "commands.no_permission");
            return true;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
            plugin.getCacheManager().clearCache();
            plugin.getAudioManager().clearMemoryCache();
            plugin.getMessages().sendMessage(sender, "commands.cache_cleared");
        } else {
            plugin.getMessages().sendMessage(sender, "commands.cache_usage");
        }
        return true;
    }

    private boolean handleUpdateDeps(CommandSender sender) {
        if (!sender.hasPermission("simplevoicechatplate.command.updatedeps")) {
            plugin.getMessages().sendMessage(sender, "commands.no_permission");
            return true;
        }
        plugin.getMessages().sendMessage(sender, "commands.updatedeps_in_progress");
        plugin.getDependencyManager().forceUpdateYtDlp().thenAccept(newVer -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getMessages().sendMessage(sender, "commands.updatedeps_success_version",
                            Messages.placeholder("version", newVer != null ? newVer : plugin.getMessages().getUnknownLabel())));
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getMessages().sendMessage(sender, "commands.updatedeps_failed"));
            return null;
        });
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("simplevoicechatplate.command.status")) {
            plugin.getMessages().sendMessage(sender, "commands.no_permission");
            return true;
        }
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        int streams = vp != null ? vp.getActiveStreamCount() : 0;
        int jukeboxes = plugin.getJukeboxManager().getActiveJukeboxCount();
        int cacheCount = plugin.getCacheManager().getCacheSize();
        String acousticsState = plugin.getMessages().getRaw(
                plugin.getSettings().isAcousticsEnabled() ? "commands.status_acoustics_on" : "commands.status_acoustics_off");
        String ytVer = plugin.getDependencyManager().getYtDlpVersion();
        if (ytVer == null || ytVer.isBlank()) {
            ytVer = plugin.getMessages().getUnknownLabel();
        }

        plugin.getMessages().sendMessage(sender, "commands.status_info",
                Messages.placeholder("lang", plugin.getConfigManager().getActiveLanguageCode()),
                Messages.placeholder("quality", plugin.getSettings().getAudioQualityPreset().toUpperCase(Locale.ROOT)),
                Messages.placeholder("opus", plugin.getSettings().getOpusMode()),
                Messages.placeholder("streams", String.valueOf(streams)),
                Messages.placeholder("jukeboxes", String.valueOf(jukeboxes)),
                Messages.placeholder("cache", String.valueOf(cacheCount)),
                Messages.placeholder("acoustics", acousticsState),
                Messages.placeholder("ytdlp", ytVer));
        return true;
    }

    private boolean handleCancel(Player player, String[] args) {
        if (!player.hasPermission("simplevoicechatplate.command.cancel")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return true;
        }
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) {
            plugin.getMessages().sendMessage(player, "commands.voicechat_unavailable");
            return true;
        }

        Player target = player;
        if (args.length > 1) {
            if (!player.hasPermission("simplevoicechatplate.command.cancel.other")) {
                plugin.getMessages().sendMessage(player, "commands.no_permission");
                return true;
            }
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.getMessages().sendMessage(player, "commands.player_not_found");
                return true;
            }
        }
        UUID targetUuid = target.getUniqueId();
        plugin.getLoadingBarAnimator().stop("horn_" + targetUuid);
        plugin.getLoadingBarAnimator().stop("portable_" + targetUuid);
        vp.stopAudio("player_" + targetUuid);
        plugin.getMessages().sendMessage(player, "commands.horn_stopped");
        return true;
    }

    private boolean handleStopAround(Player player, String[] args) {
        if (!player.hasPermission("simplevoicechatplate.command.stoparound")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return true;
        }
        VoicePlayerManager vp = plugin.getVoicePlayerManager();
        if (vp == null) {
            plugin.getMessages().sendMessage(player, "commands.voicechat_unavailable");
            return true;
        }

        double radius = 15.0;
        if (args.length > 1) {
            try {
                radius = Math.clamp(Double.parseDouble(args[1]), 1.0, 256.0);
            } catch (NumberFormatException ignored) {
            }
        }
        int stopped = vp.stopAround(player.getLocation(), radius);
        plugin.getMessages().sendMessage(player, "commands.stopped_around",
                Messages.placeholder("count", String.valueOf(stopped)));
        return true;
    }

    private boolean handleHorn(Player player, String[] args) {
        if (!hasAnyHornPermission(player)) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return true;
        }

        if (args.length < 2) {
            plugin.getMessages().sendMessage(player, "commands.horn_usage");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("notify")) {
            if (!player.hasPermission("simplevoicechatplate.command.horn.notify")) {
                plugin.getMessages().sendMessage(player, "commands.no_permission");
                return true;
            }
            UUID uuid = player.getUniqueId();
            boolean current = plugin.getPlayerDataManager().isHornNotifyEnabled(uuid);
            boolean nextState = !current;
            if (args.length >= 3) {
                String argVal = args[2].toLowerCase(Locale.ROOT);
                if (argVal.equals("on") || argVal.equals("true") || argVal.equals("enable") || argVal.equals("вкл")) {
                    nextState = true;
                } else if (argVal.equals("off") || argVal.equals("false") || argVal.equals("disable") || argVal.equals("выкл")) {
                    nextState = false;
                }
            }
            plugin.getPlayerDataManager().setHornNotifyEnabled(uuid, nextState);
            plugin.getMessages().sendMessage(player,
                    nextState ? "commands.horn_notify_enabled" : "commands.horn_notify_disabled");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.GOAT_HORN || !plugin.getDiscManager().isCustomDisc(item)) {
            plugin.getMessages().sendMessage(player, "commands.hold_custom_horn");
            return true;
        }

        switch (action) {
            case "info" -> {
                if (!player.hasPermission("simplevoicechatplate.command.horn.info")) {
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return true;
                }
                int volPct = Math.round(plugin.getDiscManager().getHornVolume(item) * 100);
                int dist = (int) Math.round(plugin.getDiscManager().getHornDistance(item));
                int dur = plugin.getDiscManager().getHornDurationSeconds(item);
                String modeRaw = plugin.getDiscManager().getHornMode(item);
                String modeLabel = plugin.getMessages().getRaw(
                        "restart".equalsIgnoreCase(modeRaw) ? "commands.horn_mode_restart" : "commands.horn_mode_toggle");
                boolean notifyOn = plugin.getPlayerDataManager().isHornNotifyEnabled(player.getUniqueId());
                String notifyLabel = MiniMessage.miniMessage().stripTags(
                        plugin.getMessages().getRaw(notifyOn ? "gui.state_on" : "gui.state_off"));

                plugin.getMessages().sendMessage(player, "commands.horn_info",
                        Messages.placeholder("volume", String.valueOf(volPct)),
                        Messages.placeholder("distance", String.valueOf(dist)),
                        Messages.placeholder("duration", String.valueOf(dur)),
                        Messages.placeholder("mode", modeLabel),
                        Messages.placeholder("notify", notifyLabel));
            }
            case "volume" -> {
                if (!player.hasPermission("simplevoicechatplate.command.horn.volume")) {
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return true;
                }
                int minVol = 5;
                int maxVol = 150;
                Integer pct = args.length >= 3 ? parseIntSafe(args[2]) : null;
                if (pct == null || pct < minVol || pct > maxVol) {
                    plugin.getMessages().sendMessage(player, "commands.horn_invalid_number",
                            Messages.placeholder("min", String.valueOf(minVol)),
                            Messages.placeholder("max", String.valueOf(maxVol)));
                    return true;
                }
                plugin.getDiscManager().setHornVolume(item, pct / 100.0f);
                plugin.getMessages().sendMessage(player, "commands.horn_volume_set",
                        Messages.placeholder("val", String.valueOf(pct)));
            }
            case "distance" -> {
                if (!player.hasPermission("simplevoicechatplate.command.horn.distance")) {
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return true;
                }
                int minDist = 4;
                int maxDist = player.hasPermission("simplevoicechatplate.admin")
                        ? 128
                        : Math.max(32, (int) Math.round(plugin.getSettings().getGoatHornDistance() * 2.0));
                Integer dist = args.length >= 3 ? parseIntSafe(args[2]) : null;
                if (dist == null || dist < minDist || dist > maxDist) {
                    plugin.getMessages().sendMessage(player, "commands.horn_invalid_number",
                            Messages.placeholder("min", String.valueOf(minDist)),
                            Messages.placeholder("max", String.valueOf(maxDist)));
                    return true;
                }
                plugin.getDiscManager().setHornDistance(item, dist);
                plugin.getMessages().sendMessage(player, "commands.horn_distance_set",
                        Messages.placeholder("val", String.valueOf(dist)));
            }
            case "duration" -> {
                if (!player.hasPermission("simplevoicechatplate.command.horn.duration")) {
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return true;
                }
                int minDur = 1;
                int maxDur = player.hasPermission("simplevoicechatplate.admin")
                        ? 600
                        : (int) Math.max(30L, plugin.getSettings().getGoatHornMaxDurationSeconds() * 2L);
                Integer sec = args.length >= 3 ? parseIntSafe(args[2]) : null;
                if (sec == null || sec < minDur || sec > maxDur) {
                    plugin.getMessages().sendMessage(player, "commands.horn_invalid_number",
                            Messages.placeholder("min", String.valueOf(minDur)),
                            Messages.placeholder("max", String.valueOf(maxDur)));
                    return true;
                }
                plugin.getDiscManager().setHornDurationSeconds(item, sec);
                plugin.getMessages().sendMessage(player, "commands.horn_duration_set",
                        Messages.placeholder("val", String.valueOf(sec)));
            }
            case "mode" -> {
                if (!player.hasPermission("simplevoicechatplate.command.horn.mode")) {
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return true;
                }
                String currentMode = plugin.getDiscManager().getHornMode(item);
                String targetMode;
                if (args.length >= 3) {
                    String m = args[2].toLowerCase(Locale.ROOT);
                    if (!m.equals("toggle") && !m.equals("restart")) {
                        plugin.getMessages().sendMessage(player, "commands.horn_usage");
                        return true;
                    }
                    targetMode = m;
                } else {
                    targetMode = "toggle".equalsIgnoreCase(currentMode) ? "restart" : "toggle";
                }
                plugin.getDiscManager().setHornMode(item, targetMode);
                String modeLabel = plugin.getMessages().getRaw(
                        "restart".equals(targetMode) ? "commands.horn_mode_restart" : "commands.horn_mode_toggle");
                plugin.getMessages().sendMessage(player, "commands.horn_mode_set",
                        Messages.placeholder("mode", modeLabel));
            }
            default -> plugin.getMessages().sendMessage(player, "commands.horn_usage");
        }
        return true;
    }

    private Integer parseIntSafe(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean handleSearch(Player player, String[] args) {
        if (!player.hasPermission("simplevoicechatplate.command.search")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            plugin.getMessages().sendMessage(player, "commands.search_query");
            return true;
        }
        StringBuilder q = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) q.append(' ');
            q.append(args[i]);
        }

        String animKey = "search_" + player.getUniqueId();
        plugin.getMessages().sendMessage(player, "commands.search_searching");
        plugin.getLoadingBarAnimator().startForPlayer(player, animKey);

        plugin.getAudioManager().searchYouTube(q.toString()).thenAccept(results -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getLoadingBarAnimator().stop(animKey);
                if (!player.isOnline()) return;
                player.sendActionBar(Component.empty());
                if (results.isEmpty()) {
                    plugin.getMessages().sendMessage(player, "commands.search_not_found");
                    return;
                }
                plugin.getMessages().sendMessage(player, "commands.search_results");
                String defaultTitle = plugin.getMessages().getDefaultTrackTitle();
                for (String res : results) {
                    PlaylistManager.TrackEntry entry = PlaylistManager.parseEntry(res, defaultTitle);
                    if (entry.url().isEmpty()) continue;
                    Component msg = plugin.getMessages().getMessageNoPrefix(
                            "commands.search_result_item", Messages.placeholder("title", entry.title()));
                    if (msg != null) {
                        player.sendMessage(msg.clickEvent(ClickEvent.suggestCommand("/svcplate burn " + entry.url())));
                    }
                }
            });
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getLoadingBarAnimator().stop(animKey);
                if (player.isOnline()) {
                    player.sendActionBar(Component.empty());
                    plugin.getMessages().sendMessage(player, "commands.search_not_found");
                }
            });
            return null;
        });
        return true;
    }

    private boolean handleErase(Player player) {
        if (!player.hasPermission("simplevoicechatplate.command.erase")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.GOAT_HORN && !plugin.getDiscManager().isMusicDisc(item)) {
            plugin.getMessages().sendMessage(player, "commands.hold_item");
            return true;
        }
        if (!plugin.getDiscManager().isCustomDisc(item)) {
            plugin.getMessages().sendMessage(player, "commands.not_custom_item");
            return true;
        }

        String type = plugin.getDiscManager().getDiscType(item);
        String creatorUuidStr = plugin.getDiscManager().getCreatorUUID(item);
        if (creatorUuidStr != null) {
            try {
                UUID creator = UUID.fromString(creatorUuidStr);
                plugin.getPlayerDataManager().modifyUsage(creator, type, -1);
            } catch (Exception ignored) {
            }
        }
        plugin.getDiscManager().removeCustomData(item);
        plugin.getMessages().sendMessage(player, "commands.erased");
        return true;
    }

    private boolean handleBurn(Player player, String[] args) {
        if (!player.hasPermission("simplevoicechatplate.command.burn")) {
            plugin.getMessages().sendMessage(player, "commands.no_permission");
            return true;
        }
        if (args.length < 2) {
            plugin.getMessages().sendMessage(player, "commands.burn_usage");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.GOAT_HORN && !plugin.getDiscManager().isMusicDisc(item)) {
            plugin.getMessages().sendMessage(player, "commands.hold_item");
            return true;
        }
        if (plugin.getDiscManager().isCustomDisc(item)) {
            plugin.getMessages().sendMessage(player, "commands.burn_already_recorded");
            return true;
        }
        if (!plugin.getDiscManager().isBurnable(item) && !player.hasPermission("simplevoicechatplate.burn.bypass")) {
            plugin.getMessages().sendMessage(player, "commands.burn_unsupported");
            return true;
        }

        String finalUrl = AnvilListener.expandUrl(args[1]);
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://") && !finalUrl.startsWith("local:")) {
            finalUrl = "https://" + finalUrl;
        }

        boolean isPlaylist = AudioManager.isPlaylistUrl(finalUrl);
        if (isPlaylist && item.getType() == Material.GOAT_HORN) {
            plugin.getMessages().sendMessage(player, "commands.burn_playlist_horn");
            return true;
        }

        String type = (item.getType() == Material.GOAT_HORN) ? "horn" : (isPlaylist ? "playlist" : "single");
        if (!plugin.getPlayerDataManager().canCreate(player, type)) {
            plugin.getMessages().sendMessage(player, "chat.limit_reached");
            return true;
        }

        String trackId = UUID.randomUUID().toString().substring(0, 8);
        String customName = null;
        if (args.length > 2) {
            StringBuilder nameBuilder = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (i > 2) nameBuilder.append(' ');
                nameBuilder.append(args[i]);
            }
            Component legacyComp = LegacyComponentSerializer.legacyAmpersand().deserialize(nameBuilder.toString().trim());
            customName = MiniMessage.miniMessage().serialize(legacyComp);
        }
        final String finalName = customName;
        final String sourceUrl = finalUrl;
        String fallbackTitle = plugin.getMessages().getDefaultTrackTitle();

        if (item.getAmount() > 1) {
            ItemStack remainder = item.clone();
            remainder.setAmount(item.getAmount() - 1);
            item.setAmount(1);
            player.getInventory().addItem(remainder).values()
                    .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }

        String animKey = "cmd_burn_" + trackId;

        if (isPlaylist) {
            String plId = "pl_" + UUID.randomUUID().toString().substring(0, 8);
            String tempName = finalName != null ? finalName : fallbackTitle;
            String loadingTitle = plugin.getMessages().getRaw("anvil.loading").replace("<name>", tempName);
            plugin.getDiscManager().updateDisc(item, plId, "playlist:" + plId, player, loadingTitle);
            plugin.getPlaylistManager().markPending(plId);

            AtomicBoolean usageCounted = new AtomicBoolean(false);

            plugin.getMessages().sendMessage(player, "commands.burn_playlist_loading");
            plugin.getLoadingBarAnimator().startForPlayer(player, animKey);

            plugin.getAudioManager().fetchPlaylistProgressive(
                    sourceUrl,
                    initialTracks -> {
                        if (initialTracks.isEmpty()) return;
                        String firstUrl = PlaylistManager.parseEntry(initialTracks.getFirst()).url();
                        plugin.getAudioManager().preloadHead(firstUrl, plugin.getSettings().getHeadPreloadSeconds());

                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            plugin.getPlaylistManager().savePlaylist(plId, initialTracks);
                            String title = (finalName != null)
                                    ? finalName
                                    : plugin.getMessages().getRaw("anvil.playlist_default")
                                    .replace("<count>", initialTracks.size() + "+");
                            updateDiscInInventory(player, plId, plId, "playlist:" + plId, title);
                            if (usageCounted.compareAndSet(false, true)) {
                                plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                            }
                            if (player.isOnline() && initialTracks.size() >= plugin.getSettings().getPlaylistInitialReadyTracks()) {
                                plugin.getMessages().sendMessage(player, "commands.burn_playlist_partial",
                                        Messages.placeholder("count", String.valueOf(initialTracks.size())));
                            }
                        });
                    },
                    allTracks -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLoadingBarAnimator().stop(animKey);
                        if (player.isOnline()) player.sendActionBar(Component.empty());

                        if (allTracks.isEmpty()) {
                            plugin.getPlaylistManager().failPending(plId, new IllegalStateException("Empty playlist"));
                            revertOrMarkFailedInInventory(player, plId);
                            if (player.isOnline()) {
                                plugin.getMessages().sendMessage(player, "commands.burn_playlist_failed");
                            }
                            return;
                        }

                        plugin.getPlaylistManager().savePlaylist(plId, allTracks);
                        plugin.getJukeboxManager().refreshPlaylistTracks(plId, allTracks);

                        String title = (finalName != null)
                                ? finalName
                                : plugin.getMessages().getRaw("anvil.playlist_default")
                                .replace("<count>", String.valueOf(allTracks.size()));
                        updateDiscInInventory(player, plId, plId, "playlist:" + plId, title);

                        if (usageCounted.compareAndSet(false, true)) {
                            plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                        }
                        if (player.isOnline()) {
                            plugin.getMessages().sendMessage(player, "commands.burn_playlist_success",
                                    Messages.placeholder("count", String.valueOf(allTracks.size())));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        }
                    }),
                    ex -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLoadingBarAnimator().stop(animKey);
                        plugin.getPlaylistManager().failPending(plId, ex);
                        revertOrMarkFailedInInventory(player, plId);
                        if (player.isOnline()) {
                            player.sendActionBar(Component.empty());
                            plugin.getMessages().sendMessage(player, JukeboxManager.resolveErrorMessageKey(ex));
                        }
                    })
            );
        } else if (finalName != null) {
            plugin.getAudioManager().preloadHead(sourceUrl, plugin.getSettings().getHeadPreloadSeconds());
            plugin.getDiscManager().updateDisc(item, trackId, sourceUrl, player, finalName);
            plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
            plugin.getMessages().sendMessage(player, "commands.burn_success",
                    Messages.placeholder("title", MiniMessage.miniMessage().stripTags(finalName)));
        } else {
            String loadingTitle = plugin.getMessages().getRaw("anvil.loading").replace("<name>", fallbackTitle);
            plugin.getDiscManager().updateDisc(item, trackId, sourceUrl, player, loadingTitle);
            plugin.getAudioManager().preloadHead(sourceUrl, plugin.getSettings().getHeadPreloadSeconds());

            plugin.getMessages().sendMessage(player, "commands.burn_fetching");
            plugin.getLoadingBarAnimator().startForPlayer(player, animKey);

            plugin.getAudioManager().fetchTitle(sourceUrl).thenAccept(title -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getLoadingBarAnimator().stop(animKey);
                    updateDiscInInventory(player, trackId, trackId, sourceUrl, title);
                    plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                    if (player.isOnline()) {
                        player.sendActionBar(Component.empty());
                        plugin.getMessages().sendMessage(player, "commands.burn_success",
                                Messages.placeholder("title", title));
                    }
                });
            }).exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getLoadingBarAnimator().stop(animKey);
                    updateDiscInInventory(player, trackId, trackId, sourceUrl, fallbackTitle);
                    plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                    if (player.isOnline()) {
                        player.sendActionBar(Component.empty());
                        plugin.getMessages().sendMessage(player, "commands.burn_success",
                                Messages.placeholder("title", fallbackTitle));
                    }
                });
                return null;
            });
        }
        return true;
    }

    private void updateDiscInInventory(Player player, String lookupTrackId, String newTrackId, String source, String title) {
        if (!player.isOnline()) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack slotItem = contents[slot];
            if (slotItem != null && plugin.getDiscManager().getTrackId(slotItem).orElse("").equals(lookupTrackId)) {
                plugin.getDiscManager().updateDisc(slotItem, newTrackId, source, player, title);
                player.getInventory().setItem(slot, slotItem);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir() && plugin.getDiscManager().getTrackId(cursor).orElse("").equals(lookupTrackId)) {
            plugin.getDiscManager().updateDisc(cursor, newTrackId, source, player, title);
            player.setItemOnCursor(cursor);
        }
    }

    private void revertOrMarkFailedInInventory(Player player, String lookupTrackId) {
        if (!player.isOnline()) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack slotItem = contents[slot];
            if (slotItem != null && plugin.getDiscManager().getTrackId(slotItem).orElse("").equals(lookupTrackId)) {
                plugin.getDiscManager().removeCustomData(slotItem);
                player.getInventory().setItem(slot, slotItem);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir() && plugin.getDiscManager().getTrackId(cursor).orElse("").equals(lookupTrackId)) {
            plugin.getDiscManager().removeCustomData(cursor);
            player.setItemOnCursor(cursor);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("simplevoicechatplate.command.help")) completions.add("help");
            if (sender.hasPermission("simplevoicechatplate.command.burn")) completions.add("burn");
            if (sender.hasPermission("simplevoicechatplate.command.erase")) completions.add("erase");
            if (sender.hasPermission("simplevoicechatplate.command.search")) completions.add("search");
            if (hasAnyHornPermission(sender)) completions.add("horn");
            if (sender.hasPermission("simplevoicechatplate.command.cancel")) completions.add("cancel");
            if (sender.hasPermission("simplevoicechatplate.command.stoparound")) completions.add("stoparound");
            if (sender.hasPermission("simplevoicechatplate.command.status")) completions.add("status");
            if (sender.hasPermission("simplevoicechatplate.command.cache.clear")) completions.add("cache");
            if (sender.hasPermission("simplevoicechatplate.command.updatedeps")) completions.add("updatedeps");
            if (sender.hasPermission("simplevoicechatplate.command.reload")) completions.add("reload");
        } else if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            switch (first) {
                case "horn" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.horn.info")) completions.add("info");
                    if (sender.hasPermission("simplevoicechatplate.command.horn.volume")) completions.add("volume");
                    if (sender.hasPermission("simplevoicechatplate.command.horn.distance")) completions.add("distance");
                    if (sender.hasPermission("simplevoicechatplate.command.horn.duration")) completions.add("duration");
                    if (sender.hasPermission("simplevoicechatplate.command.horn.mode")) completions.add("mode");
                    if (sender.hasPermission("simplevoicechatplate.command.horn.notify")) completions.add("notify");
                }
                case "cache" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.cache.clear")) completions.add("clear");
                }
                case "cancel" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.cancel.other")) {
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            completions.add(online.getName());
                        }
                    }
                }
                case "stoparound" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.stoparound")) {
                        completions.addAll(List.of("10", "25", "50", "100"));
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("horn")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "volume" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.horn.volume")) {
                        completions.addAll(List.of("20", "50", "75", "100", "125", "150"));
                    }
                }
                case "distance" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.horn.distance")) {
                        completions.addAll(List.of("12", "24", "32", "48"));
                    }
                }
                case "duration" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.horn.duration")) {
                        completions.addAll(List.of("5", "10", "15", "30"));
                    }
                }
                case "mode" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.horn.mode")) {
                        completions.addAll(List.of("toggle", "restart"));
                    }
                }
                case "notify" -> {
                    if (sender.hasPermission("simplevoicechatplate.command.horn.notify")) {
                        completions.addAll(List.of("on", "off"));
                    }
                }
            }
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return completions.stream()
                .filter(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
