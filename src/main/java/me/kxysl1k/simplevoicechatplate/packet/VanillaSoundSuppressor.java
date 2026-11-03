package me.kxysl1k.simplevoicechatplate.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;

public class VanillaSoundSuppressor implements PacketListener {

    private final SimpleVoiceChatPlate plugin;

    public VanillaSoundSuppressor(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();

        if (type == PacketType.Play.Server.EFFECT) {
            try {
                WrapperPlayServerEffect effectPacket = new WrapperPlayServerEffect(event);
                if (effectPacket.getType() == 1010) {
                    Vector3i pos = effectPacket.getPosition();
                    if (pos != null && plugin.getJukeboxManager().hasCustomDiscAtCoords(pos.getX(), pos.getY(), pos.getZ())) {
                        event.setCancelled(true);
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        if (type == PacketType.Play.Server.SOUND_EFFECT) {
            try {
                WrapperPlayServerSoundEffect soundPacket = new WrapperPlayServerSoundEffect(event);
                if (soundPacket.getSound() == null || soundPacket.getSound().getSoundId() == null) return;
                String soundName = soundPacket.getSound().getSoundId().getKey();

                if (soundName.contains("music_disc")) {
                    Vector3d pos = soundPacket.getPosition();
                    if (pos != null) {
                        int bx = (int) Math.floor(pos.getX());
                        int by = (int) Math.floor(pos.getY());
                        int bz = (int) Math.floor(pos.getZ());
                        if (plugin.getJukeboxManager().hasCustomDiscAtCoords(bx, by, bz)) {
                            event.setCancelled(true);
                        }
                    }
                } else if (soundName.contains("goat_horn") && SoundSuppressionState.hasSuppressedHorns()) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        if (type == PacketType.Play.Server.ENTITY_SOUND_EFFECT && SoundSuppressionState.hasSuppressedHorns()) {
            try {
                WrapperPlayServerEntitySoundEffect entitySoundPacket = new WrapperPlayServerEntitySoundEffect(event);
                if (SoundSuppressionState.isHornSuppressed(entitySoundPacket.getEntityId())
                        && entitySoundPacket.getSound() != null
                        && entitySoundPacket.getSound().getSoundId() != null
                        && entitySoundPacket.getSound().getSoundId().getKey().contains("goat_horn")) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
