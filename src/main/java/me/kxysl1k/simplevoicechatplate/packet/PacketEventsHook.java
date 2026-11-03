package me.kxysl1k.simplevoicechatplate.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;

public final class PacketEventsHook {

    private static Object registeredListener;

    private PacketEventsHook() {
    }

    public static void register(SimpleVoiceChatPlate plugin) {
        if (PacketEvents.getAPI() == null) return;
        unregister();
        registeredListener = PacketEvents.getAPI().getEventManager().registerListener(
                new VanillaSoundSuppressor(plugin), PacketListenerPriority.HIGHEST);
    }

    public static void unregister() {
        if (registeredListener instanceof PacketListenerCommon common && PacketEvents.getAPI() != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(common);
            } catch (Throwable ignored) {
            }
            registeredListener = null;
        }
    }
}
