package me.kxysl1k.simplevoicechatplate.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.cache.CachedTrack;
import me.kxysl1k.simplevoicechatplate.playlist.PlaylistManager;
import me.kxysl1k.simplevoicechatplate.source.LocalResolver;
import me.kxysl1k.simplevoicechatplate.source.SourceResolver;
import me.kxysl1k.simplevoicechatplate.source.UrlResolver;
import me.kxysl1k.simplevoicechatplate.source.YouTubeResolver;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AudioManager {

    private final SimpleVoiceChatPlate plugin;
    private final AudioConverter converter;
    private final YouTubeResolver youTubeResolver;
    private final List<SourceResolver> resolvers = new ArrayList<>();
    private final Semaphore downloadSemaphore;
    private final Semaphore prefetchSemaphore;

    private final ConcurrentHashMap<String, CompletableFuture<CachedTrack>> inFlightDownloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LiveStreamTask> inFlightStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> inFlightHeadPreloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> titleCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> playlistCache = new ConcurrentHashMap<>();

    private record LiveStreamTask(PcmStreamHandle handle, CompletableFuture<PcmStreamHandle> readyFuture) {
    }

    public AudioManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        this.converter = new AudioConverter(plugin);
        this.youTubeResolver = new YouTubeResolver(plugin);

        int maxProcs = plugin.getSettings().getMaxConcurrentProcesses();
        this.downloadSemaphore = new Semaphore(maxProcs);
        this.prefetchSemaphore = new Semaphore(Math.max(1, maxProcs / 2));

        resolvers.add(youTubeResolver);
        resolvers.add(new LocalResolver(plugin));
        resolvers.add(new UrlResolver(plugin));
    }

    /**
     * Открывает аудиопоток с минимальной задержкой старта:
     * 1. Если полный PCM-файл уже есть в кэше — мгновенный старт (0 мс).
     * 2. Если трек был предзагружен системой окна плейлиста (.head.pcm, первые 12 секунд) — мгновенный старт (0 мс)
     *    с чтения .head.pcm и параллельный фоновый запуск докачки полного файла для бесшовного перехвата.
     * 3. Если трек холодный (YouTube) — запускается конвейер yt-dlp -> FFmpeg pipe, отдающий управление плееру
     *    сразу после набора initial_buffer_seconds (~1.5 сек аудио), пока остальной трек докачивается в фоне.
     */
    public CompletableFuture<PcmStreamHandle> openPlaybackStream(String input) {
        if (input == null || input.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Empty audio source"));
        }

        String id = plugin.getCacheManager().generateHashId(input);
        Optional<CachedTrack> cached = plugin.getCacheManager().getTrack(id);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(PcmStreamHandle.completed(cached.get().getAudioFile()));
        }

        Optional<File> headOpt = plugin.getCacheManager().getHeadPreview(id);
        LiveStreamTask streamTask = getOrStartLiveStream(input, id, downloadSemaphore);

        if (headOpt.isPresent()) {
            File headFile = headOpt.get();
            streamTask.handle().withHeadPreview(headFile);
            return CompletableFuture.completedFuture(streamTask.handle());
        }

        return streamTask.readyFuture();
    }

    private LiveStreamTask getOrStartLiveStream(String input, String trackHash, Semaphore semaphore) {
        return inFlightStreams.computeIfAbsent(trackHash, key -> {
            File liveFile = plugin.getCacheManager().getTempFile(".live.pcm");
            PcmStreamHandle handle = PcmStreamHandle.liveGrowing(liveFile);
            CompletableFuture<PcmStreamHandle> readyFuture = new CompletableFuture<>();

            long initialReadyBytes = Math.max(1920L * 15L,
                    ((long) (plugin.getSettings().getInitialBufferSeconds() * 96_000.0) / 1920L) * 1920L);

            Thread.ofVirtual().name("svcplate-live-stream-" + key.substring(0, 6)).start(() -> {
                boolean acquired = false;
                try {
                    semaphore.acquire();
                    acquired = true;

                    Optional<CachedTrack> raceCached = plugin.getCacheManager().getTrack(key);
                    if (raceCached.isPresent()) {
                        File f = raceCached.get().getAudioFile();
                        PcmStreamHandle done = PcmStreamHandle.completed(f);
                        readyFuture.complete(done);
                        handle.markCompleted();
                        return;
                    }

                    boolean streamedOk = false;
                    if (youTubeResolver.canHandle(input)) {
                        File ytDlp = plugin.getDependencyManager().getYtDlpExecutable();
                        if (ytDlp != null && ytDlp.exists()) {
                            List<String> strategies = youTubeResolver.buildStrategies();
                            long maxSec = plugin.getSettings().getMaxAudioLengthSeconds();
                            for (int i = 0; i < Math.min(2, strategies.size()); i++) {
                                if (liveFile.exists() && handle.getWrittenBytes() == 0) {
                                    liveFile.delete();
                                }
                                List<String> ytCmd = youTubeResolver.buildYtDlpCommand(ytDlp, input, "-", strategies.get(i));
                                streamedOk = converter.pipeProcessesToLivePcm(
                                        ytCmd, plugin.getDataFolder(), liveFile, handle, initialReadyBytes, maxSec, readyFuture);
                                if (streamedOk) {
                                    break;
                                }
                                if (handle.getWrittenBytes() > 0) {
                                    break;
                                }
                            }
                        }
                    }

                    if (streamedOk && liveFile.exists() && liveFile.length() > 0) {
                        plugin.getCacheManager().addTrackFromSharedTemp(key, liveFile, 0L);
                        handle.setWrittenBytes(liveFile.length());
                        handle.markCompleted();
                        if (!readyFuture.isDone()) {
                            readyFuture.complete(handle);
                        }
                        return;
                    }

                    if (readyFuture.isDone() && handle.getWrittenBytes() >= initialReadyBytes && liveFile.exists()) {
                        handle.markCompleted();
                        return;
                    }

                    CachedTrack fallbackTrack = downloadAndConvertFallback(input, key);
                    File finalFile = fallbackTrack.getAudioFile();
                    java.nio.file.Files.copy(finalFile.toPath(), liveFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    handle.setWrittenBytes(liveFile.length());
                    handle.markCompleted();
                    if (!readyFuture.isDone()) {
                        readyFuture.complete(handle);
                    }
                } catch (Throwable ex) {
                    handle.markFailed(ex);
                    if (!readyFuture.isDone()) {
                        readyFuture.completeExceptionally(ex);
                    }
                } finally {
                    inFlightStreams.remove(key);
                    if (acquired) semaphore.release();
                }
            });

            return new LiveStreamTask(handle, readyFuture);
        });
    }

    private CachedTrack downloadAndConvertFallback(String input, String trackHash) throws Exception {
        SourceResolver matchedResolver = null;
        for (SourceResolver r : resolvers) {
            if (r.canHandle(input)) {
                matchedResolver = r;
                break;
            }
        }
        if (matchedResolver == null) {
            throw new AudioProcessingException(AudioProcessingException.ErrorType.UNKNOWN, "No source resolver matched: " + input);
        }

        File tempDownload = plugin.getCacheManager().getTempFile(".tmpaudio");
        File tempPcm = plugin.getCacheManager().getTempFile(".pcm");
        try {
            File downloaded = matchedResolver.resolve(input, tempDownload).join();
            File converted = converter.convertToPcm(downloaded, tempPcm).join();
            plugin.getCacheManager().addTrack(trackHash, converted, 0L);
            return plugin.getCacheManager().getTrack(trackHash)
                    .orElseThrow(() -> new AudioProcessingException(AudioProcessingException.ErrorType.FFMPEG_ERROR, "Cached PCM missing"));
        } finally {
            if (tempDownload.exists()) tempDownload.delete();
            if (tempPcm.exists()) tempPcm.delete();
        }
    }

    public CompletableFuture<Boolean> preloadHead(String input, int headSeconds) {
        if (input == null || input.isBlank()) return CompletableFuture.completedFuture(false);
        String id = plugin.getCacheManager().generateHashId(input);
        if (plugin.getCacheManager().hasHeadOrFull(id) || inFlightStreams.containsKey(id)) {
            return CompletableFuture.completedFuture(true);
        }

        return inFlightHeadPreloads.computeIfAbsent(id, key -> {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.whenComplete((r, e) -> inFlightHeadPreloads.remove(key));

            Thread.ofVirtual().name("svcplate-head-preload-" + key.substring(0, 6)).start(() -> {
                boolean acquired = false;
                File tempHead = plugin.getCacheManager().getTempFile(".head.tmp.pcm");
                try {
                    prefetchSemaphore.acquire();
                    acquired = true;

                    if (plugin.getCacheManager().hasHeadOrFull(key) || inFlightStreams.containsKey(key)) {
                        future.complete(true);
                        return;
                    }

                    boolean ok = false;
                    if (youTubeResolver.canHandle(input)) {
                        File ytDlp = plugin.getDependencyManager().getYtDlpExecutable();
                        if (ytDlp != null && ytDlp.exists()) {
                            List<String> ytCmd = youTubeResolver.buildYtDlpCommand(ytDlp, input, "-", "");
                            PcmStreamHandle dummy = PcmStreamHandle.liveGrowing(tempHead);
                            ok = converter.pipeProcessesToLivePcm(
                                    ytCmd, plugin.getDataFolder(), tempHead, dummy, Long.MAX_VALUE, headSeconds, null);
                            dummy.markCompleted();
                        }
                    }

                    if (ok && tempHead.exists() && tempHead.length() >= 1920L) {
                        plugin.getCacheManager().addHeadPreview(key, tempHead);
                        future.complete(true);
                    } else {
                        if (tempHead.exists()) tempHead.delete();
                        future.complete(false);
                    }
                } catch (Throwable t) {
                    if (tempHead.exists()) tempHead.delete();
                    future.complete(false);
                } finally {
                    if (acquired) prefetchSemaphore.release();
                }
            });
            return future;
        });
    }

    public void preloadFullInBackground(String input) {
        if (input == null || input.isBlank()) return;
        String id = plugin.getCacheManager().generateHashId(input);
        if (plugin.getCacheManager().hasFullTrack(id) || inFlightStreams.containsKey(id)) {
            return;
        }
        getOrStartLiveStream(input, id, prefetchSemaphore);
    }

    public CompletableFuture<CachedTrack> processAudio(String input) {
        if (input == null || input.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Empty audio source"));
        }

        String id = plugin.getCacheManager().generateHashId(input);
        Optional<CachedTrack> cached = plugin.getCacheManager().getTrack(id);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }

        return inFlightDownloads.computeIfAbsent(id, trackHash -> {
            CompletableFuture<CachedTrack> future = new CompletableFuture<>();
            future.whenComplete((r, ex) -> inFlightDownloads.remove(trackHash));

            Thread.ofVirtual().name("svcplate-audio-pipeline").start(() -> {
                boolean acquired = false;
                try {
                    downloadSemaphore.acquire();
                    acquired = true;
                    CachedTrack result = downloadAndConvertFallback(input, trackHash);
                    future.complete(result);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    if (acquired) downloadSemaphore.release();
                }
            });

            return future;
        });
    }

    public static boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("list=")
                || lower.contains("/playlist")
                || lower.contains("/sets/")
                || lower.contains("drive.google.com/drive/folders/")
                || lower.contains("drive.google.com/embeddedfolderview");
    }

    private String fetchGoogleDriveTitle(String input) {
        String fileId = null;
        if (input.contains("drive.google.com/file/d/")) {
            Matcher matcher = Pattern.compile("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)").matcher(input);
            if (matcher.find()) fileId = matcher.group(1);
        } else if (input.contains("drive.google.com/uc")) {
            Matcher matcher = Pattern.compile("id=([a-zA-Z0-9_-]+)").matcher(input);
            if (matcher.find()) fileId = matcher.group(1);
        }

        if (fileId == null) return null;

        try {
            String viewUrl = "https://drive.google.com/file/d/" + fileId + "/view";
            HttpURLConnection conn = (HttpURLConnection) URI.create(viewUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                Pattern ogTitlePattern = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"");
                Pattern titlePattern = Pattern.compile("<title>([^<]+)</title>");
                while ((line = reader.readLine()) != null) {
                    Matcher m = ogTitlePattern.matcher(line);
                    if (m.find()) {
                        return cleanDriveTitle(m.group(1));
                    }
                    m = titlePattern.matcher(line);
                    if (m.find()) {
                        String title = m.group(1);
                        if (title.endsWith(" - Google Drive")) {
                            title = title.substring(0, title.length() - 15);
                        }
                        return cleanDriveTitle(title);
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            LogUtil.debug("Google Drive title fetch failed: " + e.getMessage());
        }
        return null;
    }

    private String cleanDriveTitle(String raw) {
        String title = raw.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
        int lastDot = title.lastIndexOf('.');
        if (lastDot > 0) title = title.substring(0, lastDot);
        return title.trim();
    }

    private void parseGoogleDriveFolderRecursive(String folderId, List<String> tracks, int depth, int maxTracks) {
        if (depth > 4 || tracks.size() >= maxTracks) return;
        try {
            String embedUrl = "https://drive.google.com/embeddedfolderview?id=" + folderId;
            HttpURLConnection conn = (HttpURLConnection) URI.create(embedUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            StringBuilder htmlBuilder = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    htmlBuilder.append(line).append("\n");
                }
            } finally {
                conn.disconnect();
            }

            String html = htmlBuilder.toString();
            String gdriveDefault = plugin.getMessages().getRaw("defaults.gdrive_track");
            if (gdriveDefault.isBlank()) gdriveDefault = plugin.getMessages().getDefaultTrackTitle();

            Pattern filePattern = Pattern.compile("<a[^>]*href=\"([^\"]*drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)/view[^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Matcher fileMatcher = filePattern.matcher(html);
            while (fileMatcher.find() && tracks.size() < maxTracks) {
                String fileId = fileMatcher.group(2);
                String fileName = fileMatcher.group(3).replaceAll("<[^>]*>", "").trim();
                fileName = cleanDriveTitle(fileName);
                if (fileName.isEmpty()) fileName = gdriveDefault;

                String trackUrl = "https://drive.google.com/file/d/" + fileId + "/view";
                tracks.add(PlaylistManager.formatEntry(fileName, trackUrl));
            }

            Pattern folderPattern = Pattern.compile("<a[^>]*href=\"([^\"]*drive\\.google\\.com/drive/folders/([a-zA-Z0-9_-]+)[^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Matcher folderMatcher = folderPattern.matcher(html);
            List<String> subfolders = new ArrayList<>();
            while (folderMatcher.find()) {
                subfolders.add(folderMatcher.group(2));
            }

            for (String subFolderId : subfolders) {
                if (tracks.size() < maxTracks) {
                    parseGoogleDriveFolderRecursive(subFolderId, tracks, depth + 1, maxTracks);
                }
            }
        } catch (Exception e) {
            LogUtil.error("Google Drive folder parse error: " + folderId, e);
        }
    }

    public CompletableFuture<String> fetchTitle(String input) {
        String cached = titleCache.get(input);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        String fallbackTitle = plugin.getMessages().getDefaultTrackTitle();
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread.ofVirtual().name("svcplate-fetch-title").start(() -> {
            try {
                if (input.contains("drive.google.com/file/d/") || input.contains("drive.google.com/uc")) {
                    String title = fetchGoogleDriveTitle(input);
                    if (title != null && !title.isEmpty()) {
                        titleCache.put(input, title);
                        future.complete(title);
                        return;
                    }
                }
                if (input.contains("youtube.com") || input.contains("youtu.be")) {
                    String encoded = URLEncoder.encode(input, StandardCharsets.UTF_8);
                    String oEmbedUrl = "https://www.youtube.com/oembed?url=" + encoded + "&format=json";
                    HttpURLConnection conn = (HttpURLConnection) URI.create(oEmbedUrl).toURL().openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);

                    try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        if (json.has("title")) {
                            String title = json.get("title").getAsString().trim();
                            if (!title.isEmpty()) {
                                titleCache.put(input, title);
                                future.complete(title);
                                return;
                            }
                        }
                    } finally {
                        conn.disconnect();
                    }
                }

                String fileName = input.substring(input.lastIndexOf('/') + 1);
                int qIdx = fileName.indexOf('?');
                if (qIdx >= 0) fileName = fileName.substring(0, qIdx);
                fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
                int dotIdx = fileName.lastIndexOf('.');
                if (dotIdx > 0) fileName = fileName.substring(0, dotIdx);
                if (fileName.isBlank()) fileName = fallbackTitle;

                titleCache.put(input, fileName);
                future.complete(fileName);
            } catch (Exception e) {
                future.complete(fallbackTitle);
            }
        });
        return future;
    }

    public CompletableFuture<List<String>> searchYouTube(String query) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        Thread.ofVirtual().name("svcplate-yt-search").start(() -> {
            try {
                File ytDlp = plugin.getDependencyManager().getYtDlpExecutable();
                if (ytDlp == null || !ytDlp.exists()) {
                    future.complete(List.of());
                    return;
                }

                List<String> command = new ArrayList<>();
                command.add(ytDlp.getAbsolutePath());
                command.add("ytsearch5:" + query.trim());
                command.add("--flat-playlist");
                command.add("--dump-json");
                command.add("-4");
                command.add("--no-warnings");

                File cookiesFile = new File(plugin.getDataFolder(), "cookies.txt");
                if (cookiesFile.exists()) {
                    command.add("--cookies");
                    command.add(cookiesFile.getAbsolutePath());
                }

                ProcessBuilder builder = new ProcessBuilder(command);
                builder.environment().put("TMPDIR", plugin.getDataFolder().getAbsolutePath());
                builder.redirectErrorStream(true);
                Process process = builder.start();

                List<String> results = new ArrayList<>();
                StringBuilder rawOut = new StringBuilder();

                try (Scanner s = new Scanner(process.getInputStream(), StandardCharsets.UTF_8)) {
                    while (s.hasNextLine()) {
                        String line = s.nextLine();
                        if (line.trim().startsWith("{")) {
                            try {
                                JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                                String title = json.has("title") && !json.get("title").isJsonNull() ? json.get("title").getAsString() : "";
                                String id = json.has("id") && !json.get("id").isJsonNull() ? json.get("id").getAsString() : "";
                                if (!title.isBlank() && !id.isBlank()) {
                                    results.add(PlaylistManager.formatEntry(title, "https://youtu.be/" + id));
                                }
                            } catch (Exception ignored) {
                            }
                        } else {
                            rawOut.append(line).append("\n");
                        }
                    }
                }
                int exitCode = process.waitFor();

                if (results.isEmpty() && exitCode != 0) {
                    Map<String, Object> context = new HashMap<>();
                    context.put("Query", query);
                    LogUtil.logProcessFailure("yt-dlp search", exitCode, String.join(" ", command), rawOut.toString(), context);
                }

                future.complete(results);
            } catch (Exception e) {
                Map<String, Object> context = new HashMap<>();
                context.put("Query", query);
                LogUtil.crash("YouTube search failure", e, context);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Двухфазное потоковое извлечение плейлистов:
     * 1. Не форсирует youtube:player_client=ios (так как клиент iOS в yt-dlp не поддерживает вкладки плейлистов
     *    и миксы RD..., из-за чего парсинг плейлистов падал с пустым списком), а явно включает --yes-playlist.
     * 2. Читает JSON-строки по мере их поступления из stdout yt-dlp: как только получены первые initial_ready_tracks
     *    (например, 3 трека за ~1 секунду), сразу вызывает onInitialBatch, позволяя мгновенно выдать готовую пластинку
     *    и начать предзагрузку первой песни, пока оставшиеся треки плейлиста дочитываются в фоне до onComplete.
     */
    public void fetchPlaylistProgressive(
            String url,
            Consumer<List<String>> onInitialBatch,
            Consumer<List<String>> onComplete,
            Consumer<Throwable> onError
    ) {
        List<String> cached = playlistCache.get(url);
        if (cached != null && !cached.isEmpty()) {
            if (onInitialBatch != null) onInitialBatch.accept(cached);
            if (onComplete != null) onComplete.accept(cached);
            return;
        }

        String defaultTitle = plugin.getMessages().getDefaultTrackTitle();
        int maxTracks = plugin.getSettings().getPlaylistMaxTracks();
        int initialBatchCount = plugin.getSettings().getPlaylistInitialReadyTracks();

        Thread.ofVirtual().name("svcplate-fetch-playlist").start(() -> {
            try {
                if (url.contains("drive.google.com/drive/folders/") || url.contains("drive.google.com/embeddedfolderview")) {
                    List<String> tracks = new ArrayList<>();
                    String folderId = null;
                    if (url.contains("drive.google.com/drive/folders/")) {
                        int index = url.indexOf("/folders/");
                        String temp = url.substring(index + 9);
                        if (temp.contains("?")) temp = temp.substring(0, temp.indexOf('?'));
                        if (temp.contains("/")) temp = temp.substring(0, temp.indexOf('/'));
                        folderId = temp.trim();
                    } else {
                        Matcher matcher = Pattern.compile("id=([a-zA-Z0-9_-]+)").matcher(url);
                        if (matcher.find()) folderId = matcher.group(1);
                    }

                    if (folderId != null) {
                        parseGoogleDriveFolderRecursive(folderId, tracks, 0, maxTracks);
                    }
                    List<String> snapshot = List.copyOf(tracks);
                    if (!snapshot.isEmpty()) {
                        playlistCache.put(url, snapshot);
                        if (onInitialBatch != null) onInitialBatch.accept(snapshot);
                    }
                    if (onComplete != null) onComplete.accept(snapshot);
                    return;
                }

                File ytDlp = plugin.getDependencyManager().getYtDlpExecutable();
                if (ytDlp == null || !ytDlp.exists()) {
                    if (onError != null) {
                        onError.accept(new AudioProcessingException(AudioProcessingException.ErrorType.UNKNOWN, "yt-dlp binary not found"));
                    }
                    return;
                }

                String normalizedUrl = normalizePlaylistUrl(url);
                File cookiesFile = new File(plugin.getDataFolder(), "cookies.txt");

                String[] tabStrategies = {"", "youtube:player_client=web,mweb"};
                List<String> tracks = new ArrayList<>();
                StringBuilder errorOutput = new StringBuilder();
                int lastExitCode = -1;
                List<String> lastCommand = List.of();
                boolean initialFired = false;

                for (String strategy : tabStrategies) {
                    tracks.clear();
                    errorOutput.setLength(0);

                    List<String> command = new ArrayList<>();
                    command.add(ytDlp.getAbsolutePath());
                    command.add("--yes-playlist");
                    command.add("--flat-playlist");
                    command.add("--dump-json");
                    command.add("--ignore-errors");
                    command.add("--no-warnings");
                    command.add("--playlist-end");
                    command.add(String.valueOf(maxTracks));
                    command.add("-4");

                    if (!strategy.isEmpty()) {
                        command.add("--extractor-args");
                        command.add(strategy);
                    }
                    if (cookiesFile.exists()) {
                        command.add("--cookies");
                        command.add(cookiesFile.getAbsolutePath());
                    }
                    command.add(normalizedUrl);
                    lastCommand = command;

                    ProcessBuilder builder = new ProcessBuilder(command);
                    builder.environment().put("TMPDIR", plugin.getDataFolder().getAbsolutePath());
                    builder.redirectErrorStream(true);
                    Process process = builder.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("{")) {
                                String formatted = parsePlaylistJsonEntry(trimmed, defaultTitle);
                                if (formatted != null) {
                                    tracks.add(formatted);
                                    if (!initialFired && tracks.size() >= initialBatchCount) {
                                        initialFired = true;
                                        if (onInitialBatch != null) {
                                            onInitialBatch.accept(List.copyOf(tracks));
                                        }
                                    }
                                }
                            } else if (!trimmed.isEmpty()) {
                                errorOutput.append(trimmed).append("\n");
                            }
                        }
                    }

                    lastExitCode = process.waitFor();
                    if (!tracks.isEmpty()) {
                        break;
                    }
                }

                if (tracks.isEmpty() && lastExitCode != 0) {
                    AudioProcessingException.ErrorType errorType = AudioProcessingException.classifyFromOutput(errorOutput.toString());
                    Map<String, Object> context = new HashMap<>();
                    context.put("URL", normalizedUrl);
                    context.put("ErrorType", errorType.name());
                    LogUtil.logProcessFailure("yt-dlp playlist", lastExitCode, String.join(" ", lastCommand), errorOutput.toString(), context);
                    if (onError != null) {
                        onError.accept(new AudioProcessingException(errorType, "yt-dlp exit " + lastExitCode + ": " + errorOutput));
                    }
                    return;
                }

                List<String> finalSnapshot = List.copyOf(tracks);
                if (!finalSnapshot.isEmpty()) {
                    playlistCache.put(url, finalSnapshot);
                    if (!initialFired && onInitialBatch != null) {
                        onInitialBatch.accept(finalSnapshot);
                    }
                }
                if (onComplete != null) {
                    onComplete.accept(finalSnapshot);
                }
            } catch (Exception e) {
                Map<String, Object> context = new HashMap<>();
                context.put("URL", url);
                LogUtil.crash("Playlist fetch failure", e, context);
                if (onError != null) onError.accept(e);
            }
        });
    }

    public CompletableFuture<List<String>> fetchPlaylist(String url) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        fetchPlaylistProgressive(url, null, future::complete, future::completeExceptionally);
        return future;
    }

    private String normalizePlaylistUrl(String url) {
        if (url == null) return "";
        String cleaned = url.trim().replace("music.youtube.com/", "www.youtube.com/");
        if (cleaned.contains("youtube.com/watch?") && cleaned.contains("list=") && !cleaned.contains("list=RD")) {
            Matcher m = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(cleaned);
            if (m.find()) {
                return "https://www.youtube.com/playlist?list=" + m.group(1);
            }
        }
        return cleaned;
    }

    private String parsePlaylistJsonEntry(String jsonLine, String defaultTitle) {
        try {
            JsonObject json = JsonParser.parseString(jsonLine).getAsJsonObject();
            String title = json.has("title") && !json.get("title").isJsonNull()
                    ? json.get("title").getAsString().trim()
                    : defaultTitle;
            if (title.equalsIgnoreCase("[Deleted video]")
                    || title.equalsIgnoreCase("[Private video]")
                    || title.equalsIgnoreCase("[Unavailable video]")) {
                return null;
            }

            String id = json.has("id") && !json.get("id").isJsonNull() ? json.get("id").getAsString().trim() : "";
            String trackUrl = json.has("url") && !json.get("url").isJsonNull() ? json.get("url").getAsString().trim() : "";
            String webpageUrl = json.has("webpage_url") && !json.get("webpage_url").isJsonNull()
                    ? json.get("webpage_url").getAsString().trim() : "";

            String resolvedUrl;
            if (!trackUrl.isEmpty() && trackUrl.startsWith("http")) {
                resolvedUrl = trackUrl;
            } else if (!webpageUrl.isEmpty() && webpageUrl.startsWith("http")) {
                resolvedUrl = webpageUrl;
            } else if (!id.isEmpty()) {
                resolvedUrl = "https://www.youtube.com/watch?v=" + id;
            } else if (!trackUrl.isEmpty()) {
                resolvedUrl = "https://www.youtube.com/watch?v=" + trackUrl;
            } else {
                return null;
            }

            return PlaylistManager.formatEntry(title.isEmpty() ? defaultTitle : title, resolvedUrl);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void clearMemoryCache() {
        titleCache.clear();
        playlistCache.clear();
    }
}
