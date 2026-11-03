package me.kxysl1k.simplevoicechatplate.util;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoadingBarAnimator {

    private static final String[] DOTS_FRAMES = {".", "..", "..."};

    private final SimpleVoiceChatPlate plugin;
    private final ConcurrentHashMap<String, BukkitTask> activeAnimations = new ConcurrentHashMap<>();

    public LoadingBarAnimator(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public void startForPlayer(Player player, String key) {
        if (player == null || key == null) return;
        stop(key);

        UUID uuid = player.getUniqueId();
        AtomicInteger step = new AtomicInteger(0);
        long interval = plugin.getSettings().getLoadingAnimationTicks();

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                stop(key);
                return;
            }
            sendAnimatedFrame(p, step.getAndIncrement());
        }, 0L, interval);

        BukkitTask previous = activeAnimations.put(key, task);
        if (previous != null) previous.cancel();
    }

    public void startAtLocation(Location center, double radius, String key) {
        if (center == null || center.getWorld() == null || key == null) return;
        stop(key);

        String worldName = center.getWorld().getName();
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();
        double radiusSq = radius * radius;
        AtomicInteger step = new AtomicInteger(0);
        long interval = plugin.getSettings().getLoadingAnimationTicks();

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World w = Bukkit.getWorld(worldName);
            if (w == null) {
                stop(key);
                return;
            }
            int frameIdx = step.getAndIncrement();
            for (Player p : w.getPlayers()) {
                Location loc = p.getLocation();
                double dx = loc.getX() - cx;
                double dy = loc.getY() - cy;
                double dz = loc.getZ() - cz;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    sendAnimatedFrame(p, frameIdx);
                }
            }
        }, 0L, interval);

        BukkitTask previous = activeAnimations.put(key, task);
        if (previous != null) previous.cancel();
    }

    private void sendAnimatedFrame(Player player, int frameCounter) {
        String dots = DOTS_FRAMES[Math.floorMod(frameCounter, DOTS_FRAMES.length)];
        Component msg = plugin.getMessages().getMessageNoPrefix(
                "actionbar.loading_base", Messages.placeholder("dots", dots));
        if (msg != null) {
            player.sendActionBar(msg);
        }
    }

    public void stop(String key) {
        if (key == null) return;
        BukkitTask task = activeAnimations.remove(key);
        if (task != null) {
            task.cancel();
        }
    }

    public void stopAll() {
        for (BukkitTask task : activeAnimations.values()) {
            task.cancel();
        }
        activeAnimations.clear();
    }
}
