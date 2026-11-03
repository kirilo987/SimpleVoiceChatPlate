package me.kxysl1k.simplevoicechatplate.playlist;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.jukebox.JukeboxManager.JukeboxSession;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Координатор двунаправленной упреждающей подгрузки окна плейлиста (Bidirectional Sliding-Window Prefetcher).
 *
 * Логика работы вокруг текущего индекса currentIndex:
 * 1. Фаза быстрого старта соседних треков (через ~1.2 с после выбора песни): для следующих N треков (current + 1..N)
 *    и предыдущих M треков (current - 1..M) в порядке близости к текущей позиции предзагружаются только первые
 *    head_preload_seconds (~12 секунд, .head.pcm). За счет малого объема (~200 КБ сжатого потока на трек) все окно
 *    вперед и назад становится готовым к мгновенному переключению (0 мс) за пару секунд.
 * 2. Фаза полного кэширования при прослушивании (через ~5 с удержания на текущем треке): если слушатель не листает
 *    треки подряд, а реально слушает песню, ближайшие следующие треки (next 1, next 2) фоново докачиваются целиком.
 */
public class PlaylistPrefetchCoordinator {

    private final SimpleVoiceChatPlate plugin;
    private final ConcurrentHashMap<String, AtomicLong> sessionGenerations = new ConcurrentHashMap<>();

    public PlaylistPrefetchCoordinator(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public void onSessionTrackActivated(JukeboxSession session) {
        if (session == null || !session.isPlaylist || session.tracks == null || session.tracks.size() <= 1) {
            return;
        }

        String sessionKey = session.posKey.toStreamKey();
        long generation = sessionGenerations.computeIfAbsent(sessionKey, k -> new AtomicLong(0L)).incrementAndGet();

        int currentIdx = session.currentIndex;
        List<String> snapshot = List.copyOf(session.tracks);
        int nextCount = plugin.getSettings().getPrefetchNextTracks();
        int prevCount = plugin.getSettings().getPrefetchPreviousTracks();
        int headSeconds = plugin.getSettings().getHeadPreloadSeconds();

        Set<Integer> orderedNeighbors = new LinkedHashSet<>();
        int maxRadius = Math.max(nextCount, prevCount);
        int size = snapshot.size();

        for (int offset = 1; offset <= maxRadius; offset++) {
            if (offset <= nextCount) {
                orderedNeighbors.add(Math.floorMod(currentIdx + offset, size));
            }
            if (offset <= prevCount) {
                orderedNeighbors.add(Math.floorMod(currentIdx - offset, size));
            }
        }
        orderedNeighbors.remove(currentIdx);

        List<String> neighborUrls = new ArrayList<>();
        for (int idx : orderedNeighbors) {
            String url = PlaylistManager.parseEntry(snapshot.get(idx)).url();
            if (!url.isBlank()) {
                neighborUrls.add(url);
            }
        }

        List<String> fullUpgradeUrls = new ArrayList<>();
        for (int offset = 1; offset <= Math.min(2, nextCount); offset++) {
            int idx = Math.floorMod(currentIdx + offset, size);
            if (idx != currentIdx) {
                String url = PlaylistManager.parseEntry(snapshot.get(idx)).url();
                if (!url.isBlank()) {
                    fullUpgradeUrls.add(url);
                }
            }
        }

        Thread.ofVirtual().name("svcplate-window-prefetch").start(() -> {
            try {
                Thread.sleep(1200L);
                if (!isCurrentGeneration(sessionKey, generation)) return;

                for (String neighborUrl : neighborUrls) {
                    if (!isCurrentGeneration(sessionKey, generation)) return;
                    plugin.getAudioManager().preloadHead(neighborUrl, headSeconds).join();
                }

                Thread.sleep(3800L);
                if (!isCurrentGeneration(sessionKey, generation)) return;

                for (String nextUrl : fullUpgradeUrls) {
                    if (!isCurrentGeneration(sessionKey, generation)) return;
                    plugin.getAudioManager().preloadFullInBackground(nextUrl);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public void cancelSessionPrefetch(String streamKey) {
        if (streamKey == null) return;
        AtomicLong gen = sessionGenerations.remove(streamKey);
        if (gen != null) {
            gen.incrementAndGet();
        }
    }

    private boolean isCurrentGeneration(String sessionKey, long expectedGen) {
        AtomicLong gen = sessionGenerations.get(sessionKey);
        return gen != null && gen.get() == expectedGen;
    }
}
