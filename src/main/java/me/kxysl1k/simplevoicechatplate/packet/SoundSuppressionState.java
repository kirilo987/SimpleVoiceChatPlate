package me.kxysl1k.simplevoicechatplate.packet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Изолированное хранилище состояния подавления ванильных звуков без импортов PacketEvents.
 * Вынесено в отдельный класс от VanillaSoundSuppressor (реализующего PacketListener),
 * чтобы обращение из Bukkit-слушателей не вызывало загрузку классов PacketEvents в JVM
 * и NoClassDefFoundError на серверах, где опциональный плагин PacketEvents не установлен.
 */
public final class SoundSuppressionState {

    private static final Set<Integer> suppressedHornEntityIds = ConcurrentHashMap.newKeySet();

    private SoundSuppressionState() {
    }

    public static void markCustomHornUse(int entityId) {
        suppressedHornEntityIds.add(entityId);
    }

    public static void unmarkCustomHornUse(int entityId) {
        suppressedHornEntityIds.remove(entityId);
    }

    public static boolean isHornSuppressed(int entityId) {
        return suppressedHornEntityIds.contains(entityId);
    }

    public static boolean hasSuppressedHorns() {
        return !suppressedHornEntityIds.isEmpty();
    }
}
