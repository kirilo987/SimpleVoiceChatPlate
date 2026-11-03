package me.kxysl1k.simplevoicechatplate.listener;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.audio.AudioManager;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnvilListener implements Listener {

    private static final Pattern LIST_PARAM_PATTERN = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)");

    private final SimpleVoiceChatPlate plugin;

    public AnvilListener(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public static String expandUrl(String input) {
        if (input == null || input.isBlank()) return "";
        input = input.trim();

        String result;
        if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("local:")) {
            result = input;
        } else {
            String upper = input.toUpperCase();
            if (upper.startsWith("PL") || upper.startsWith("OL") || upper.startsWith("UU")
                    || upper.startsWith("FL") || upper.startsWith("LL")
                    || upper.startsWith("RDCLAK") || upper.startsWith("RDTMAK")) {
                result = "https://www.youtube.com/playlist?list=" + input;
            } else if (upper.startsWith("RD") && input.length() == 13) {
                String videoId = input.substring(2);
                result = "https://www.youtube.com/watch?v=" + videoId + "&list=" + input;
            } else if (input.length() == 11 && !input.contains(" ") && !input.contains("/")) {
                result = "https://youtu.be/" + input;
            } else if (input.contains("list=") || input.contains("watch?v=")) {
                result = "https://www.youtube.com/" + (input.startsWith("list=") ? "playlist?" : "") + input;
            } else if (input.startsWith("youtube.com/") || input.startsWith("www.youtube.com/")
                    || input.startsWith("music.youtube.com/") || input.startsWith("youtu.be/")) {
                result = "https://" + input;
            } else {
                result = input;
            }
        }

        if (result.contains("list=RD") && !result.contains("v=")) {
            Matcher m = LIST_PARAM_PATTERN.matcher(result);
            if (m.find()) {
                String listId = m.group(1);
                String upperList = listId.toUpperCase();
                if (upperList.startsWith("RD") && !upperList.startsWith("RDCL") && !upperList.startsWith("RDTM")
                        && !upperList.startsWith("RDAO") && listId.length() == 13) {
                    String videoId = listId.substring(2);
                    result = "https://www.youtube.com/watch?v=" + videoId + "&list=" + listId;
                }
            }
        }

        return result;
    }

    private boolean isValidSource(String text) {
        return text.startsWith("http://") || text.startsWith("https://") || text.startsWith("local:");
    }

    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        if (!plugin.getSettings().isAnvilEnabled()) return;
        if (!(event.getView() instanceof AnvilView anvilView)) return;

        AnvilInventory inv = anvilView.getTopInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (left == null || left.getType() == Material.AIR) return;

        if (plugin.getDiscManager().isCustomDisc(left)) {
            if (right != null && right.getType() == Material.SHEARS) {
                if (event.getView().getPlayer() instanceof Player player
                        && !player.hasPermission("simplevoicechatplate.anvil.erase")) {
                    event.setResult(null);
                    return;
                }
                ItemStack result = left.clone();
                result.setAmount(1);
                plugin.getDiscManager().removeCustomData(result);
                event.setResult(result);
                anvilView.setRepairCost(1);
            } else {
                event.setResult(null);
            }
            return;
        }

        String rawInput = anvilView.getRenameText();
        if (rawInput == null || rawInput.isBlank()) return;

        String urlPart = rawInput;
        String customName = MiniMessage.miniMessage().stripTags(plugin.getMessages().getRaw("anvil.auto_name"));

        if (rawInput.contains("|")) {
            String[] parts = rawInput.split("\\|", 2);
            urlPart = parts[0].trim();
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                customName = parts[1].trim();
            }
        }

        String expandedUrl = expandUrl(urlPart);

        if ((plugin.getDiscManager().isMusicDisc(left) || left.getType() == Material.GOAT_HORN)
                && (right == null || right.getType() == Material.AIR)
                && isValidSource(expandedUrl)) {

            if (left.getType() == Material.GOAT_HORN && AudioManager.isPlaylistUrl(expandedUrl)) {
                event.setResult(null);
                return;
            }

            if (event.getView().getPlayer() instanceof Player player) {
                if (!player.hasPermission("simplevoicechatplate.anvil.burn")) {
                    event.setResult(null);
                    return;
                }
                if (!plugin.getDiscManager().isBurnable(left)
                        && !player.hasPermission("simplevoicechatplate.burn.bypass")) {
                    event.setResult(null);
                    return;
                }
            }

            ItemStack result = left.clone();
            result.setAmount(1);
            ItemMeta meta = result.getItemMeta();
            if (meta != null) {
                Component nameComp = plugin.getMessages().getMessageNoPrefix(
                        "anvil.record_name", Messages.placeholder("name", customName));
                if (nameComp != null) meta.displayName(nameComp);
                result.setItemMeta(meta);
            }
            event.setResult(result);
            anvilView.setRepairCost(1);
        }
    }

    @EventHandler
    public void onAnvilClick(InventoryClickEvent event) {
        if (!plugin.getSettings().isAnvilEnabled()) return;
        if (!(event.getView() instanceof AnvilView anvilView)) return;
        if (event.getRawSlot() != 2) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) return;

        AnvilInventory inv = anvilView.getTopInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (left == null || left.getType() == Material.AIR) return;

        if (!event.isShiftClick() && !player.getItemOnCursor().getType().isAir()) {
            return;
        }

        if (plugin.getDiscManager().isCustomDisc(left)) {
            if (right != null && right.getType() == Material.SHEARS) {
                if (!player.hasPermission("simplevoicechatplate.anvil.erase")) {
                    event.setCancelled(true);
                    plugin.getMessages().sendMessage(player, "commands.no_permission");
                    return;
                }

                String type = plugin.getDiscManager().getDiscType(left);
                String creatorUuidStr = plugin.getDiscManager().getCreatorUUID(left);
                if (creatorUuidStr != null) {
                    try {
                        UUID creator = UUID.fromString(creatorUuidStr);
                        plugin.getPlayerDataManager().modifyUsage(creator, type, -1);
                    } catch (Exception ignored) {
                    }
                }

                event.setCancelled(true);
                ItemStack cleaned = result.clone();
                cleaned.setAmount(1);
                giveOrCursor(player, cleaned, event.isShiftClick());

                if (left.getAmount() > 1) {
                    left.setAmount(left.getAmount() - 1);
                    inv.setItem(0, left);
                } else {
                    inv.setItem(0, null);
                }

                ItemMeta shearsMeta = right.getItemMeta();
                if (shearsMeta instanceof Damageable damageable) {
                    int newDamage = damageable.getDamage() + plugin.getSettings().getAnvilShearsDamage();
                    if (newDamage >= right.getType().getMaxDurability()) {
                        inv.setItem(1, null);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    } else {
                        damageable.setDamage(newDamage);
                        right.setItemMeta(shearsMeta);
                        inv.setItem(1, right);
                    }
                }
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
            }
            return;
        }

        String rawInput = anvilView.getRenameText();
        if (rawInput == null || rawInput.isBlank()) return;

        String urlPart = rawInput;
        String customName = null;

        if (rawInput.contains("|")) {
            String[] parts = rawInput.split("\\|", 2);
            urlPart = parts[0].trim();
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                String rawName = parts[1].trim();
                Component legacyComp = LegacyComponentSerializer.legacyAmpersand().deserialize(rawName);
                customName = MiniMessage.miniMessage().serialize(legacyComp);
            }
        }

        final String finalUrl = expandUrl(urlPart);
        final String finalCustomName = customName;

        if ((plugin.getDiscManager().isMusicDisc(left) || left.getType() == Material.GOAT_HORN)
                && (right == null || right.getType() == Material.AIR)
                && isValidSource(finalUrl)) {

            if (!player.hasPermission("simplevoicechatplate.anvil.burn")) {
                event.setCancelled(true);
                plugin.getMessages().sendMessage(player, "commands.no_permission");
                return;
            }
            if (!plugin.getDiscManager().isBurnable(left) && !player.hasPermission("simplevoicechatplate.burn.bypass")) {
                event.setCancelled(true);
                plugin.getMessages().sendMessage(player, "commands.burn_unsupported");
                return;
            }

            if (player.getLevel() < 1 && player.getGameMode() != GameMode.CREATIVE) return;

            if (left.getType() == Material.GOAT_HORN && AudioManager.isPlaylistUrl(finalUrl)) {
                event.setCancelled(true);
                plugin.getMessages().sendMessage(player, "commands.burn_playlist_horn");
                return;
            }

            String type = (left.getType() == Material.GOAT_HORN)
                    ? "horn"
                    : (AudioManager.isPlaylistUrl(finalUrl) ? "playlist" : "single");

            if (!plugin.getPlayerDataManager().canCreate(player, type)) {
                event.setCancelled(true);
                plugin.getMessages().sendMessage(player, "chat.limit_reached");
                player.closeInventory();
                return;
            }

            event.setCancelled(true);
            boolean isPlaylistBurn = AudioManager.isPlaylistUrl(finalUrl);
            String plId = isPlaylistBurn ? "pl_" + UUID.randomUUID().toString().substring(0, 8) : null;
            String trackId = isPlaylistBurn ? plId : UUID.randomUUID().toString().substring(0, 8);
            String initialSource = isPlaylistBurn ? "playlist:" + plId : finalUrl;
            String defaultTrackTitle = plugin.getMessages().getDefaultTrackTitle();
            String tempName = (finalCustomName != null) ? finalCustomName : defaultTrackTitle;

            ItemStack loadingItem = left.clone();
            loadingItem.setAmount(1);
            String loadingTitle = plugin.getMessages().getRaw("anvil.loading").replace("<name>", tempName);
            plugin.getDiscManager().updateDisc(loadingItem, trackId, initialSource, player, loadingTitle);

            giveOrCursor(player, loadingItem, event.isShiftClick());

            if (player.getGameMode() != GameMode.CREATIVE) {
                player.setLevel(Math.max(0, player.getLevel() - 1));
            }

            if (left.getAmount() > 1) {
                left.setAmount(left.getAmount() - 1);
                inv.setItem(0, left);
            } else {
                inv.setItem(0, null);
            }
            inv.setItem(2, null);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);

            String animKey = "burn_" + trackId;

            if (isPlaylistBurn) {
                plugin.getPlaylistManager().markPending(plId);
                AtomicBoolean usageCounted = new AtomicBoolean(false);

                plugin.getMessages().sendMessage(player, "anvil.bg_loading");
                plugin.getLoadingBarAnimator().startForPlayer(player, animKey);

                plugin.getAudioManager().fetchPlaylistProgressive(
                        finalUrl,
                        initialTracks -> {
                            if (initialTracks.isEmpty()) return;
                            String firstUrl = PlaylistManager.parseEntry(initialTracks.getFirst()).url();
                            plugin.getAudioManager().preloadHead(firstUrl, plugin.getSettings().getHeadPreloadSeconds());

                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                plugin.getPlaylistManager().savePlaylist(plId, initialTracks);
                                String title = (finalCustomName != null)
                                        ? finalCustomName
                                        : plugin.getMessages().getRaw("anvil.playlist_default")
                                        .replace("<count>", initialTracks.size() + "+");
                                updateLoadingItems(player, plId, "playlist:" + plId, title, plId);
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
                                updateLoadingItems(player, plId, finalUrl, plugin.getMessages().getRaw("anvil.bg_failed"), null);
                                plugin.getMessages().sendMessage(player, "commands.burn_playlist_failed");
                                return;
                            }

                            plugin.getPlaylistManager().savePlaylist(plId, allTracks);
                            plugin.getJukeboxManager().refreshPlaylistTracks(plId, allTracks);

                            String title = (finalCustomName != null)
                                    ? finalCustomName
                                    : plugin.getMessages().getRaw("anvil.playlist_default")
                                    .replace("<count>", String.valueOf(allTracks.size()));
                            updateLoadingItems(player, plId, "playlist:" + plId, title, plId);

                            if (usageCounted.compareAndSet(false, true)) {
                                plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                            }
                            plugin.getMessages().sendMessage(player, "commands.burn_playlist_success",
                                    Messages.placeholder("count", String.valueOf(allTracks.size())));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        }),
                        ex -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                            plugin.getLoadingBarAnimator().stop(animKey);
                            plugin.getPlaylistManager().failPending(plId, ex);
                            if (player.isOnline()) player.sendActionBar(Component.empty());
                            updateLoadingItems(player, plId, finalUrl, plugin.getMessages().getRaw("anvil.bg_failed"), null);
                            plugin.getMessages().sendMessage(player, JukeboxManager.resolveErrorMessageKey(ex));
                        })
                );
            } else if (finalCustomName != null) {
                plugin.getAudioManager().preloadHead(finalUrl, plugin.getSettings().getHeadPreloadSeconds());
                updateLoadingItems(player, trackId, finalUrl, finalCustomName, trackId);
                plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                plugin.getMessages().sendMessage(player, "commands.burn_success",
                        Messages.placeholder("title", MiniMessage.miniMessage().stripTags(finalCustomName)));
            } else {
                plugin.getAudioManager().preloadHead(finalUrl, plugin.getSettings().getHeadPreloadSeconds());
                plugin.getMessages().sendMessage(player, "commands.burn_fetching");
                plugin.getLoadingBarAnimator().startForPlayer(player, animKey);

                plugin.getAudioManager().fetchTitle(finalUrl).thenAccept(realTitle -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLoadingBarAnimator().stop(animKey);
                        if (player.isOnline()) player.sendActionBar(Component.empty());
                        updateLoadingItems(player, trackId, finalUrl, realTitle, trackId);
                        plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                        plugin.getMessages().sendMessage(player, "commands.burn_success",
                                Messages.placeholder("title", realTitle));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                    });
                }).exceptionally(ex -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getLoadingBarAnimator().stop(animKey);
                        if (player.isOnline()) player.sendActionBar(Component.empty());
                        updateLoadingItems(player, trackId, finalUrl, defaultTrackTitle, trackId);
                        plugin.getPlayerDataManager().modifyUsage(player.getUniqueId(), type, 1);
                        plugin.getMessages().sendMessage(player, "commands.burn_success",
                                Messages.placeholder("title", defaultTrackTitle));
                    });
                    return null;
                });
            }
        }
    }

    private void giveOrCursor(Player player, ItemStack item, boolean shiftClick) {
        if (shiftClick) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        } else {
            player.setItemOnCursor(item);
        }
    }

    private void updateLoadingItems(Player player, String targetTrackId, String source, String title, String newTrackId) {
        if (!player.isOnline()) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && plugin.getDiscManager().getTrackId(item).orElse("").equals(targetTrackId)) {
                plugin.getDiscManager().updateDisc(item, newTrackId != null ? newTrackId : targetTrackId, source, player, title);
                player.getInventory().setItem(slot, item);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir() && plugin.getDiscManager().getTrackId(cursor).orElse("").equals(targetTrackId)) {
            plugin.getDiscManager().updateDisc(cursor, newTrackId != null ? newTrackId : targetTrackId, source, player, title);
            player.setItemOnCursor(cursor);
        }
    }
}
