package me.kxysl1k.simplevoicechatplate.source;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.audio.AudioProcessingException;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlResolver implements SourceResolver {

    private static final Pattern GDRIVE_FILE_PATTERN = Pattern.compile("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)");
    private final SimpleVoiceChatPlate plugin;

    public UrlResolver(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean canHandle(String input) {
        return input != null && (input.startsWith("http://") || input.startsWith("https://"));
    }

    private String convertToDirectLink(String url) {
        if (url.contains("dropbox.com")) {
            return url.replace("www.dropbox.com", "dl.dropboxusercontent.com")
                    .replace("?dl=0", "")
                    .replace("&dl=0", "");
        }
        if (url.contains("drive.google.com/file/d/")) {
            Matcher matcher = GDRIVE_FILE_PATTERN.matcher(url);
            if (matcher.find()) {
                return "https://drive.google.com/uc?export=download&id=" + matcher.group(1);
            }
        }
        return url;
    }

    /**
     * Защита от SSRF (Server-Side Request Forgery): резолвит DNS-запись хоста до открытия сокета
     * и повторно после HTTP 30x редиректа, блокируя доступ к loopback (127.0.0.1/::1),
     * link-local (169.254.x.x — облачные метаданные AWS/GCP) и приватным подсетям (10/8, 172.16/12, 192.168/16),
     * чтобы игрок не мог просканировать внутреннюю сеть хостинга через ссылку на наковальне.
     */
    private void validateHostSafety(String host) throws Exception {
        if (host == null || host.isBlank()) {
            throw new SecurityException("Некорректный хост в URL.");
        }
        String lowerHost = host.toLowerCase();
        if (plugin.getSettings().getBlacklistedDomains().stream().anyMatch(lowerHost::contains)) {
            throw new SecurityException("Домен находится в черном списке: " + host);
        }
        InetAddress addr = InetAddress.getByName(host);
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            throw new SecurityException("Запросы к локальным/внутренним сетевым адресам запрещены: " + host);
        }
    }

    @Override
    public CompletableFuture<File> resolve(String input, File targetFile) {
        CompletableFuture<File> future = new CompletableFuture<>();

        Thread.ofVirtual().name("svcplate-url-resolver").start(() -> {
            try {
                String directUrl = convertToDirectLink(input);
                LogUtil.debug("UrlResolver: Загрузка по прямой ссылке -> " + directUrl);

                URL url = URI.create(directUrl).toURL();
                validateHostSafety(url.getHost());

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "SimpleVoiceChatPlate/1.0");
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(20000);

                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == 303 || status == 307 || status == 308) {
                    String newUrlStr = connection.getHeaderField("Location");
                    connection.disconnect();
                    URL redirectedUrl = URI.create(newUrlStr).toURL();
                    validateHostSafety(redirectedUrl.getHost());
                    connection = (HttpURLConnection) redirectedUrl.openConnection();
                    connection.setRequestProperty("User-Agent", "SimpleVoiceChatPlate/1.0");
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(20000);
                }

                long size = connection.getContentLengthLong();
                long maxSize = plugin.getSettings().getMaxDownloadSizeBytes();
                if (size > maxSize) {
                    connection.disconnect();
                    future.completeExceptionally(new AudioProcessingException(
                            AudioProcessingException.ErrorType.NETWORK_ERROR,
                            "Файл превышает допустимый размер (" + size + " > " + maxSize + " байт)"));
                    return;
                }

                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(targetFile)) {

                    byte[] buffer = new byte[16384];
                    int read;
                    long downloaded = 0;

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (downloaded > maxSize) {
                            out.close();
                            targetFile.delete();
                            future.completeExceptionally(new AudioProcessingException(
                                    AudioProcessingException.ErrorType.NETWORK_ERROR,
                                    "Превышен лимит размера аудиофайла при загрузке!"));
                            return;
                        }
                    }
                } finally {
                    connection.disconnect();
                }

                LogUtil.debug("UrlResolver: Аудиофайл успешно скачан (" + targetFile.length() + " байт).");
                future.complete(targetFile);
            } catch (Exception e) {
                LogUtil.error("Ошибка загрузки аудио по прямой ссылке: " + input, e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}
