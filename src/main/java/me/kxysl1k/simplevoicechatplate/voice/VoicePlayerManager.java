package me.kxysl1k.simplevoicechatplate.voice;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.acoustics.AcousticDspProcessor;
import me.kxysl1k.simplevoicechatplate.acoustics.AcousticEnvironment;
import me.kxysl1k.simplevoicechatplate.acoustics.AcousticRaytracer;
import me.kxysl1k.simplevoicechatplate.audio.PcmStreamHandle;
import me.kxysl1k.simplevoicechatplate.jukebox.BlockPosKey;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class VoicePlayerManager {

    private static final int SAMPLES_PER_FRAME = 960;
    private static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2;
    private static final long BYTES_PER_SECOND = 96_000L;
    private static final long FRAME_DURATION_NANOS = 20_000_000L;

    private final SimpleVoiceChatPlate plugin;
    private final AcousticRaytracer acousticRaytracer = new AcousticRaytracer();
    private final ConcurrentHashMap<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();
    private BukkitTask acousticsTask;
    private BukkitTask particleTask;

    public VoicePlayerManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        startBackgroundUpdaters();
    }

    public void startBackgroundUpdaters() {
        stopBackgroundUpdaters();
        long updateTicks = plugin.getSettings().getAcousticsUpdateTicks();
        acousticsTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAllStreamsAcoustics, 1L, updateTicks);
        particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickJukeboxBlocksAndParticles, 10L, 10L);
    }

    public void stopBackgroundUpdaters() {
        if (acousticsTask != null) {
            acousticsTask.cancel();
            acousticsTask = null;
        }
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    public int getActiveStreamCount() {
        return activeStreams.size();
    }

    public boolean isStreamRunning(String streamKey) {
        return streamKey != null && activeStreams.containsKey(streamKey);
    }

    public void skipForward(String streamKey, int seconds) {
        ActiveStream stream = activeStreams.get(streamKey);
        if (stream != null) stream.seekRelativeSeconds(seconds);
    }

    public void skipBackward(String streamKey, int seconds) {
        ActiveStream stream = activeStreams.get(streamKey);
        if (stream != null) stream.seekRelativeSeconds(-seconds);
    }

    public float addVolume(String streamKey, float amount) {
        ActiveStream stream = activeStreams.get(streamKey);
        if (stream != null) {
            float vol = Math.clamp(stream.getVolume() + amount, 0.0f, 1.5f);
            stream.setVolume(vol);
            return vol;
        }
        return -1f;
    }

    public void setVolume(String streamKey, float volume) {
        if (streamKey == null) return;
        ActiveStream stream = activeStreams.get(streamKey);
        if (stream != null) {
            stream.setVolume(Math.clamp(volume, 0.0f, 1.5f));
        }
    }

    public float getVolume(String streamKey) {
        ActiveStream stream = activeStreams.get(streamKey);
        return stream != null ? stream.getVolume() : -1f;
    }

    public double getEffectiveRange(String streamKey) {
        ActiveStream stream = activeStreams.get(streamKey);
        return stream != null ? stream.maxDistance : plugin.getSettings().getJukeboxDistance();
    }

    public boolean togglePause(String streamKey) {
        ActiveStream stream = activeStreams.get(streamKey);
        if (stream != null) {
            boolean newState = !stream.isPaused();
            stream.setPaused(newState);
            return newState;
        }
        return false;
    }

    public int[] getProgress(String streamKey) {
        ActiveStream stream = activeStreams.get(streamKey);
        if (stream != null) {
            int currentSeconds = (int) (stream.currentBytePosition / BYTES_PER_SECOND);
            int totalSeconds = (int) (Math.max(stream.currentBytePosition, stream.totalBytes) / BYTES_PER_SECOND);
            return new int[]{currentSeconds, totalSeconds};
        }
        return new int[]{0, 0};
    }

    private OpusEncoderMode resolveOpusMode() {
        return switch (plugin.getSettings().getOpusMode()) {
            case "VOIP" -> OpusEncoderMode.VOIP;
            case "RESTRICTED_LOWDELAY" -> OpusEncoderMode.RESTRICTED_LOWDELAY;
            default -> OpusEncoderMode.AUDIO;
        };
    }

    public void playAudioAtJukebox(Block jukeboxBlock, File pcmFile, String trackId, Runnable onHalfway, Runnable onFinish) {
        playAudioAtJukebox(jukeboxBlock, PcmStreamHandle.completed(pcmFile), trackId, plugin.getSettings().getJukeboxVolume(), onHalfway, onFinish);
    }

    public void playAudioAtJukebox(Block jukeboxBlock, PcmStreamHandle streamHandle, String trackId, Runnable onHalfway, Runnable onFinish) {
        playAudioAtJukebox(jukeboxBlock, streamHandle, trackId, plugin.getSettings().getJukeboxVolume(), onHalfway, onFinish);
    }

    public void playAudioAtJukebox(
            Block jukeboxBlock,
            PcmStreamHandle streamHandle,
            String trackId,
            float initialVolume,
            Runnable onHalfway,
            Runnable onFinish
    ) {
        BlockPosKey posKey = BlockPosKey.of(jukeboxBlock.getLocation());
        if (posKey == null || streamHandle == null) return;
        String streamKey = posKey.toStreamKey();
        stopAudio(streamKey);

        VoiceChatIntegration vcIntegration = plugin.getVoiceChatIntegration();
        VoicechatServerApi api = vcIntegration != null ? vcIntegration.getServerApi() : null;
        if (api == null) return;

        double distance = AcousticRaytracer.calculateJukeboxDistance(jukeboxBlock, plugin.getSettings());
        float volume = Math.clamp(initialVolume, 0.0f, 1.5f);
        String categoryId = vcIntegration.getMusicCategory() != null
                ? vcIntegration.getMusicCategory().getId()
                : VoiceChatIntegration.MUSIC_CATEGORY_ID;

        ActiveStream stream = new ActiveStream(
                api,
                streamKey,
                streamHandle,
                posKey,
                null,
                categoryId,
                distance,
                volume,
                -1L,
                onHalfway,
                onFinish
        );

        if (Bukkit.isPrimaryThread()) {
            updateSingleStreamAcoustics(stream, api);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> updateSingleStreamAcoustics(stream, api));
        }

        activeStreams.put(streamKey, stream);
        Thread.ofVirtual().name("svcplate-stream-" + streamKey).start(stream::runPlaybackLoop);
    }

    public void playAudioAtPlayer(Player player, File pcmFile, String trackId, boolean portableMode) {
        playAudioAtPlayer(player, PcmStreamHandle.completed(pcmFile), trackId, portableMode);
    }

    public void playAudioAtPlayer(Player player, PcmStreamHandle streamHandle, String trackId, boolean portableMode) {
        float volume = portableMode
                ? plugin.getSettings().getPortablePlayerVolume()
                : plugin.getSettings().getGoatHornVolume();
        double distance = portableMode
                ? plugin.getSettings().getPortablePlayerDistance()
                : plugin.getSettings().getGoatHornDistance();
        long maxDurationMs = portableMode
                ? -1L
                : plugin.getSettings().getGoatHornMaxDurationSeconds() * 1000L;
        playAudioAtPlayer(player, streamHandle, trackId, portableMode, volume, distance, maxDurationMs);
    }

    public void playAudioAtPlayer(
            Player player,
            PcmStreamHandle streamHandle,
            String trackId,
            boolean portableMode,
            float customVolume,
            double customDistance,
            long customMaxDurationMs
    ) {
        if (player == null || streamHandle == null) return;
        String streamKey = "player_" + player.getUniqueId();
        stopAudio(streamKey);

        VoiceChatIntegration vcIntegration = plugin.getVoiceChatIntegration();
        VoicechatServerApi api = vcIntegration != null ? vcIntegration.getServerApi() : null;
        if (api == null) return;

        String categoryId = vcIntegration.getHornCategory() != null
                ? vcIntegration.getHornCategory().getId()
                : VoiceChatIntegration.HORN_CATEGORY_ID;

        ActiveStream stream = new ActiveStream(
                api,
                streamKey,
                streamHandle,
                null,
                player.getUniqueId(),
                categoryId,
                customDistance,
                customVolume,
                customMaxDurationMs,
                null,
                null
        );

        if (Bukkit.isPrimaryThread()) {
            updateSingleStreamAcoustics(stream, api);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> updateSingleStreamAcoustics(stream, api));
        }

        activeStreams.put(streamKey, stream);
        Thread.ofVirtual().name("svcplate-stream-" + streamKey).start(stream::runPlaybackLoop);
    }

    public void stopAudio(String streamKey) {
        if (streamKey == null) return;
        ActiveStream stream = activeStreams.remove(streamKey);
        if (stream != null) {
            stream.stopGracefully();
        }
    }

    public void stopAll() {
        List<String> keys = new ArrayList<>(activeStreams.keySet());
        for (String key : keys) {
            stopAudio(key);
        }
    }

    public void stopInWorld(World world) {
        if (world == null) return;
        String worldName = world.getName();
        for (Map.Entry<String, ActiveStream> entry : activeStreams.entrySet()) {
            ActiveStream s = entry.getValue();
            if (s.jukeboxPos != null && s.jukeboxPos.worldName().equals(worldName)) {
                stopAudio(entry.getKey());
            }
        }
    }

    public int stopAround(Location center, double radius) {
        if (center == null || center.getWorld() == null) return 0;
        int stopped = 0;
        double radiusSq = radius * radius;
        String centerWorld = center.getWorld().getName();

        for (Map.Entry<String, ActiveStream> entry : new ArrayList<>(activeStreams.entrySet())) {
            String key = entry.getKey();
            ActiveStream stream = entry.getValue();

            if (stream.jukeboxPos != null && stream.jukeboxPos.worldName().equals(centerWorld)) {
                double dx = (stream.jukeboxPos.x() + 0.5) - center.getX();
                double dy = (stream.jukeboxPos.y() + 0.5) - center.getY();
                double dz = (stream.jukeboxPos.z() + 0.5) - center.getZ();
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    BlockPosKey pos = stream.jukeboxPos;
                    stopAudio(key);
                    stopped++;

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        Block b = pos.toBlock();
                        if (b != null) {
                            plugin.getJukeboxManager().ejectCustomDiscCleanly(b);
                        }
                    });
                }
            } else if (stream.sourcePlayerUuid != null) {
                Player p = Bukkit.getPlayer(stream.sourcePlayerUuid);
                if (p != null && p.getWorld().getName().equals(centerWorld)
                        && p.getLocation().distanceSquared(center) <= radiusSq) {
                    stopAudio(key);
                    stopped++;
                }
            }
        }
        return stopped;
    }

    private void tickJukeboxBlocksAndParticles() {
        for (ActiveStream stream : activeStreams.values()) {
            if (!stream.running || stream.jukeboxPos == null) continue;
            World w = Bukkit.getWorld(stream.jukeboxPos.worldName());
            if (w == null) {
                stream.stopGracefully();
                continue;
            }
            int bx = stream.jukeboxPos.x();
            int by = stream.jukeboxPos.y();
            int bz = stream.jukeboxPos.z();

            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;

            Block b = w.getBlockAt(bx, by, bz);
            if (b.getType() != Material.JUKEBOX) {
                plugin.getJukeboxManager().stopDisc(b);
                continue;
            }
            if (b.getState() instanceof Jukebox jb && !jb.hasRecord()) {
                plugin.getJukeboxManager().stopDisc(b);
                continue;
            }

            stream.maxDistance = AcousticRaytracer.calculateJukeboxDistance(b, plugin.getSettings());

            if (!stream.isPaused()) {
                w.spawnParticle(Particle.NOTE, bx + 0.5, by + 1.22, bz + 0.5, 1, 0.25, 0.05, 0.25, Math.random());
            }
        }
    }

    private void updateAllStreamsAcoustics() {
        if (activeStreams.isEmpty()) return;
        VoiceChatIntegration vcIntegration = plugin.getVoiceChatIntegration();
        VoicechatServerApi api = vcIntegration != null ? vcIntegration.getServerApi() : null;
        if (api == null) return;

        for (ActiveStream stream : activeStreams.values()) {
            if (stream.running) {
                updateSingleStreamAcoustics(stream, api);
            }
        }
    }

    private void updateSingleStreamAcoustics(ActiveStream stream, VoicechatServerApi api) {
        World world;
        double srcX, srcY, srcZ;
        int ignX = Integer.MIN_VALUE, ignY = Integer.MIN_VALUE, ignZ = Integer.MIN_VALUE;

        if (stream.jukeboxPos != null) {
            world = Bukkit.getWorld(stream.jukeboxPos.worldName());
            if (world == null) return;
            ignX = stream.jukeboxPos.x();
            ignY = stream.jukeboxPos.y();
            ignZ = stream.jukeboxPos.z();
            srcX = ignX + 0.5;
            srcY = ignY + 0.65;
            srcZ = ignZ + 0.5;
        } else if (stream.sourcePlayerUuid != null) {
            Player srcPlayer = Bukkit.getPlayer(stream.sourcePlayerUuid);
            if (srcPlayer == null || !srcPlayer.isOnline()) {
                stream.stopGracefully();
                return;
            }
            world = srcPlayer.getWorld();
            Location eye = srcPlayer.getEyeLocation();
            srcX = eye.getX();
            srcY = eye.getY() - 0.15;
            srcZ = eye.getZ();
        } else {
            return;
        }

        double maxDist = stream.maxDistance;
        double activationRadiusSq = (maxDist + 8.0) * (maxDist + 8.0);
        Set<UUID> activeListenerIds = new HashSet<>();

        ServerLevel serverLevel = api.fromServerLevel(world);

        for (Player listener : world.getPlayers()) {
            if (!listener.isOnline()) continue;
            Location lLoc = listener.getLocation();
            double dx = lLoc.getX() - srcX;
            double dy = lLoc.getY() - srcY;
            double dz = lLoc.getZ() - srcZ;
            if (dx * dx + dy * dy + dz * dz > activationRadiusSq) continue;

            UUID listenerUuid = listener.getUniqueId();
            VoicechatConnection conn = api.getConnectionOf(listenerUuid);
            if (conn == null || !conn.isConnected() || conn.isDisabled()) continue;

            activeListenerIds.add(listenerUuid);

            AcousticEnvironment env;
            if (listenerUuid.equals(stream.sourcePlayerUuid)) {
                boolean underWater = plugin.getSettings().isUnderwaterMuffleEnabled()
                        && listener.getEyeLocation().getBlock().getType() == Material.WATER;
                env = new AcousticEnvironment(1.0f, underWater ? 420.0f : 20000.0f, 0.0f, 0.0f, srcX, srcY, srcZ, false, underWater);
            } else {
                env = acousticRaytracer.trace(
                        world, srcX, srcY, srcZ, ignX, ignY, ignZ, listener, maxDist, plugin.getSettings());
            }

            stream.upsertListener(api, serverLevel, listenerUuid, env, (float) maxDist);
        }

        stream.pruneInactiveListeners(activeListenerIds);
    }

    /**
     * Изолированный аудио-терминал одного конкретного слушателя.
     * Персональный LocationalAudioChannel с UUID-фильтром сохраняет клиентское 3D OpenAL HRTF-панорамирование
     * и при этом использует полнополосный музыкальный кодек OpusEncoderMode.AUDIO (CELT 20 Гц – 20 кГц) вместо
     * речевого VOIP-фильтра по умолчанию, а также индивидуальный DSP-фильтр преград для каждого игрока.
     */
    private static final class ListenerEndpoint {
        final UUID listenerUuid;
        final LocationalAudioChannel channel;
        final OpusEncoder encoder;
        final AcousticDspProcessor dsp;
        double smoothX;
        double smoothY;
        double smoothZ;
        volatile boolean closed = false;

        ListenerEndpoint(UUID listenerUuid, LocationalAudioChannel channel, OpusEncoder encoder, double initX, double initY, double initZ) {
            this.listenerUuid = listenerUuid;
            this.channel = channel;
            this.encoder = encoder;
            this.dsp = new AcousticDspProcessor();
            this.smoothX = initX;
            this.smoothY = initY;
            this.smoothZ = initZ;
        }

        synchronized void sendProcessedFrame(float[] monoNormalized, float streamVolume, float envelope) {
            if (closed || encoder.isClosed()) return;
            short[] processed = dsp.processFrame(monoNormalized, streamVolume, envelope);
            byte[] opusPacket = encoder.encode(processed);
            channel.send(opusPacket);
        }

        synchronized void flush() {
            if (!closed) {
                channel.flush();
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                channel.flush();
            } catch (Exception ignored) {
            }
            try {
                if (!encoder.isClosed()) {
                    encoder.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private final class ActiveStream {
        private final VoicechatServerApi api;
        private final String streamKey;
        private final PcmStreamHandle streamHandle;
        private final BlockPosKey jukeboxPos;
        private final UUID sourcePlayerUuid;
        private final String categoryId;
        private volatile double maxDistance;
        private volatile float volume;
        private final long maxDurationMs;
        private final Runnable onHalfway;
        private final Runnable onFinish;

        private final ConcurrentHashMap<UUID, ListenerEndpoint> listeners = new ConcurrentHashMap<>();
        private final Object channelSeekLock = new Object();
        private final AtomicBoolean readerReleased = new AtomicBoolean(false);

        private volatile long totalBytes = 0L;
        private volatile long currentBytePosition = 0L;
        private volatile long pendingSeekBytePosition = -1L;
        private volatile boolean paused = false;
        private volatile boolean running = true;
        private volatile boolean manuallyStopped = false;
        private volatile int microFadeInFrames = 2;

        ActiveStream(
                VoicechatServerApi api,
                String streamKey,
                PcmStreamHandle streamHandle,
                BlockPosKey jukeboxPos,
                UUID sourcePlayerUuid,
                String categoryId,
                double maxDistance,
                float volume,
                long maxDurationMs,
                Runnable onHalfway,
                Runnable onFinish
        ) {
            this.api = api;
            this.streamKey = streamKey;
            this.streamHandle = streamHandle;
            this.streamHandle.retainReader();
            this.jukeboxPos = jukeboxPos;
            this.sourcePlayerUuid = sourcePlayerUuid;
            this.categoryId = categoryId;
            this.maxDistance = maxDistance;
            this.volume = volume;
            this.maxDurationMs = maxDurationMs;
            this.onHalfway = onHalfway;
            this.onFinish = onFinish;

            File head = streamHandle.getHeadPreviewFile();
            long headLen = head != null && head.exists() ? head.length() : 0L;
            this.totalBytes = Math.max(headLen, streamHandle.getWrittenBytes());
        }

        float getVolume() {
            return volume;
        }

        void setVolume(float v) {
            this.volume = v;
        }

        boolean isPaused() {
            return paused;
        }

        void setPaused(boolean p) {
            this.paused = p;
            if (p) {
                for (ListenerEndpoint ep : listeners.values()) {
                    ep.flush();
                }
            } else {
                this.microFadeInFrames = 2;
            }
        }

        /*
         * Смещение позиции в сыром s16le PCM-потоке обязано быть строго кратно размеру 20-мс кадра (1920 байт):
         * нечетный сдвиг переворачивает старший и младший байты каждого 16-битного сэмпла (превращая музыку в белый шум),
         * а невыровненный кадр нарушает синхронизацию кольцевого буфера Opus-декодера на клиенте.
         */
        void seekRelativeSeconds(int deltaSeconds) {
            long deltaBytes = deltaSeconds * BYTES_PER_SECOND;
            synchronized (channelSeekLock) {
                long available = Math.max(totalBytes, streamHandle.getWrittenBytes());
                long rawTarget = Math.clamp(currentBytePosition + deltaBytes, 0L, Math.max(0L, available - BYTES_PER_FRAME));
                pendingSeekBytePosition = (rawTarget / BYTES_PER_FRAME) * BYTES_PER_FRAME;
                microFadeInFrames = 2;
            }
        }

        void upsertListener(VoicechatServerApi vcApi, ServerLevel level, UUID listenerUuid, AcousticEnvironment env, float distance) {
            if (!running) return;
            Position initialPos = vcApi.createPosition(env.apparentX(), env.apparentY(), env.apparentZ());

            ListenerEndpoint endpoint = listeners.computeIfAbsent(listenerUuid, id -> {
                LocationalAudioChannel channel = vcApi.createLocationalAudioChannel(UUID.randomUUID(), level, initialPos);
                if (channel == null) return null;
                channel.setCategory(categoryId);
                channel.setDistance(distance);
                channel.setFilter(serverPlayer -> serverPlayer.getUuid().equals(id));
                OpusEncoder encoder = vcApi.createEncoder(resolveOpusMode());
                return new ListenerEndpoint(id, channel, encoder, env.apparentX(), env.apparentY(), env.apparentZ());
            });

            if (endpoint != null && !endpoint.closed) {
                double dx = env.apparentX() - endpoint.smoothX;
                double dy = env.apparentY() - endpoint.smoothY;
                double dz = env.apparentZ() - endpoint.smoothZ;
                if (dx * dx + dy * dy + dz * dz > 256.0) {
                    endpoint.smoothX = env.apparentX();
                    endpoint.smoothY = env.apparentY();
                    endpoint.smoothZ = env.apparentZ();
                } else {
                    endpoint.smoothX += dx * 0.25;
                    endpoint.smoothY += dy * 0.25;
                    endpoint.smoothZ += dz * 0.25;
                }
                endpoint.channel.setDistance(distance);
                endpoint.channel.updateLocation(vcApi.createPosition(endpoint.smoothX, endpoint.smoothY, endpoint.smoothZ));
                endpoint.dsp.updateTargets(env, plugin.getSettings().getAcousticTransitionTimeMs());
            }
        }

        void pruneInactiveListeners(Set<UUID> activeIds) {
            listeners.entrySet().removeIf(entry -> {
                if (!activeIds.contains(entry.getKey())) {
                    entry.getValue().close();
                    return true;
                }
                return false;
            });
        }

        /*
         * Бесшовный потоковый цикл чтения PCM:
         * 1. Если у трека есть предзагруженное вступление (.head.pcm), чтение стартует за 0 мс из него.
         *    Как только фоновый конвейер записывает в основной файл позицию currentBytePosition + запас,
         *    активный FileChannel прозрачно переключается на основной файл на границе 20-мс кадра.
         * 2. Если основной файл еще растет (live pipe из yt-dlp -> FFmpeg), при нехватке байтов цикл мягко ждет
         *    поступления следующей порции через waitForBytes(...) вместо преждевременного завершения по EOF.
         */
        void runPlaybackLoop() {
            long startWallMs = System.currentTimeMillis();
            boolean halfwayTriggered = false;

            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
            float[] normalizedMono = new float[SAMPLES_PER_FRAME];

            File headFile = streamHandle.getHeadPreviewFile();
            File primaryFile = streamHandle.getPrimaryFile();
            boolean readingFromHead = headFile != null && headFile.exists() && headFile.length() >= BYTES_PER_FRAME;
            long headLength = readingFromHead ? headFile.length() : 0L;

            RandomAccessFile activeRaf = null;
            FileChannel activeChannel = null;

            try {
                File initialFile = readingFromHead ? headFile : primaryFile;
                activeRaf = new RandomAccessFile(initialFile, "r");
                activeChannel = activeRaf.getChannel();

                long nextFrameDeadlineNanos = System.nanoTime();
                boolean wasPaused = false;

                while (running) {
                    if (paused) {
                        wasPaused = true;
                        LockSupport.parkNanos(50_000_000L);
                        continue;
                    }
                    if (wasPaused) {
                        nextFrameDeadlineNanos = System.nanoTime();
                        wasPaused = false;
                    }

                    long seekTarget;
                    synchronized (channelSeekLock) {
                        seekTarget = pendingSeekBytePosition;
                        pendingSeekBytePosition = -1L;
                    }
                    if (seekTarget >= 0L) {
                        currentBytePosition = seekTarget;
                    }

                    long writtenPrimary = streamHandle.getWrittenBytes();
                    totalBytes = Math.max(headLength, writtenPrimary);

                    if (readingFromHead) {
                        boolean primaryReadyForSwitch = primaryFile != null
                                && primaryFile.exists()
                                && writtenPrimary >= currentBytePosition + BYTES_PER_FRAME * 6L;
                        boolean headExhausted = currentBytePosition + BYTES_PER_FRAME > headLength;

                        if (primaryReadyForSwitch || headExhausted) {
                            if (!primaryReadyForSwitch && !streamHandle.isCompleted()) {
                                streamHandle.waitForBytes(currentBytePosition + BYTES_PER_FRAME, 10_000L);
                            }
                            if (primaryFile != null && primaryFile.exists() && streamHandle.getWrittenBytes() >= currentBytePosition + BYTES_PER_FRAME) {
                                closeQuietly(activeChannel, activeRaf);
                                activeRaf = new RandomAccessFile(primaryFile, "r");
                                activeChannel = activeRaf.getChannel();
                                readingFromHead = false;
                                microFadeInFrames = Math.max(microFadeInFrames, 1);
                                nextFrameDeadlineNanos = System.nanoTime();
                            } else if (headExhausted) {
                                break;
                            }
                        }
                    }

                    if (!readingFromHead && !streamHandle.isCompleted()) {
                        if (streamHandle.getWrittenBytes() < currentBytePosition + BYTES_PER_FRAME) {
                            streamHandle.waitForBytes(currentBytePosition + BYTES_PER_FRAME, 8_000L);
                            nextFrameDeadlineNanos = System.nanoTime();
                        }
                        totalBytes = Math.max(headLength, streamHandle.getWrittenBytes());
                    }

                    byteBuffer.clear();
                    activeChannel.position(currentBytePosition);
                    int readBytes = activeChannel.read(byteBuffer);

                    if (readBytes < BYTES_PER_FRAME && !readingFromHead && !streamHandle.isCompleted()) {
                        if (streamHandle.waitForBytes(currentBytePosition + BYTES_PER_FRAME, 8_000L)) {
                            byteBuffer.clear();
                            activeChannel.position(currentBytePosition);
                            readBytes = activeChannel.read(byteBuffer);
                            nextFrameDeadlineNanos = System.nanoTime();
                        }
                    }

                    if (readBytes <= 0) {
                        break;
                    }
                    currentBytePosition += readBytes;

                    while (byteBuffer.hasRemaining()) {
                        byteBuffer.put((byte) 0);
                    }
                    byteBuffer.flip();

                    if (!halfwayTriggered && onHalfway != null && streamHandle.isCompleted()
                            && totalBytes > 0 && currentBytePosition >= totalBytes / 2) {
                        halfwayTriggered = true;
                        Thread.ofVirtual().name("svcplate-preload").start(onHalfway);
                    }

                    if (maxDurationMs > 0 && (System.currentTimeMillis() - startWallMs) >= maxDurationMs) {
                        break;
                    }

                    if (!listeners.isEmpty()) {
                        for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
                            normalizedMono[i] = byteBuffer.getShort() * (1.0f / 32768.0f);
                        }

                        float envelope = 1.0f;
                        int fade = microFadeInFrames;
                        if (fade > 0) {
                            envelope = fade == 2 ? 0.45f : 0.85f;
                            microFadeInFrames = fade - 1;
                        }
                        if (streamHandle.isCompleted() && !readingFromHead) {
                            long remainingBytes = totalBytes - currentBytePosition;
                            if (remainingBytes >= 0 && remainingBytes < BYTES_PER_FRAME * 2L) {
                                envelope *= Math.clamp((float) remainingBytes / (BYTES_PER_FRAME * 2.0f), 0.1f, 1.0f);
                            }
                        }

                        float vol = this.volume;
                        for (ListenerEndpoint endpoint : listeners.values()) {
                            endpoint.sendProcessedFrame(normalizedMono, vol, envelope);
                        }
                    }

                    /*
                     * Монотонный наносекундный пейсинг кадров (ровно 20 000 000 нс на один Opus-пакет):
                     * обычный Thread.sleep(20) на Windows/Linux накапливает системный джиттер (21–31 мс),
                     * из-за чего буфер Simple Voice Chat периодически опустошается и звук заикается.
                     * Привязка к абсолютному дедлайну System.nanoTime() полностью компенсирует время DSP-расчетов.
                     */
                    nextFrameDeadlineNanos += FRAME_DURATION_NANOS;
                    long waitNanos = nextFrameDeadlineNanos - System.nanoTime();
                    if (waitNanos > 250_000L) {
                        LockSupport.parkNanos(waitNanos - 100_000L);
                    } else if (waitNanos < -100_000_000L) {
                        nextFrameDeadlineNanos = System.nanoTime();
                    }
                }
            } catch (Exception e) {
                if (running && !manuallyStopped) {
                    Map<String, Object> context = new HashMap<>();
                    context.put("StreamKey", streamKey);
                    context.put("File", primaryFile != null ? primaryFile.getAbsolutePath() : "null");
                    LogUtil.crash("Audio stream playback exception", e, context);
                }
            } finally {
                running = false;
                closeQuietly(activeChannel, activeRaf);
                releaseHandleOnce();

                for (ListenerEndpoint ep : listeners.values()) {
                    ep.close();
                }
                listeners.clear();
                activeStreams.remove(streamKey, this);

                if (onFinish != null && !manuallyStopped) {
                    onFinish.run();
                }
            }
        }

        private void closeQuietly(FileChannel ch, RandomAccessFile raf) {
            try {
                if (ch != null) ch.close();
            } catch (Exception ignored) {
            }
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
        }

        private void releaseHandleOnce() {
            if (readerReleased.compareAndSet(false, true)) {
                streamHandle.releaseReader();
            }
        }

        void stopGracefully() {
            manuallyStopped = true;
            running = false;
        }
    }
}
