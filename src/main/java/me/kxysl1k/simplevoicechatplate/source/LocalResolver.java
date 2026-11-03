package me.kxysl1k.simplevoicechatplate.source;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

public class LocalResolver implements SourceResolver {

    private final SimpleVoiceChatPlate plugin;

    public LocalResolver(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean canHandle(String input) {
        if (input == null || input.isBlank()) return false;
        if (input.startsWith("http://") || input.startsWith("https://")) return false;
        return input.startsWith("local:") || new File(plugin.getDataFolder(), input).exists();
    }

    @Override
    public CompletableFuture<File> resolve(String input, File targetFile) {
        CompletableFuture<File> future = new CompletableFuture<>();

        Thread.ofVirtual().name("svcplate-local-resolver").start(() -> {
            try {
                String cleanInput = input.startsWith("local:") ? input.substring("local:".length()) : input;
                File localFile = new File(plugin.getDataFolder(), cleanInput);

                /**
                 * Сравнение getCanonicalPath() с обязательным завершающим разделителем File.separator
                 * предотвращает Path Traversal (например, local:../../server.properties или обход префикса
                 * через соседнюю директорию с похожим именем SimpleVoiceChatPlate_backup).
                 */
                String canonicalDataFolder = plugin.getDataFolder().getCanonicalPath() + File.separator;
                String canonicalLocalFile = localFile.getCanonicalPath();

                if (!canonicalLocalFile.startsWith(canonicalDataFolder)) {
                    future.completeExceptionally(new SecurityException("Попытка выхода за пределы папки данных плагина заблокирована!"));
                    return;
                }

                if (!localFile.exists() || !localFile.isFile()) {
                    future.completeExceptionally(new RuntimeException("Локальный аудиофайл не найден: " + cleanInput));
                    return;
                }

                Files.copy(localFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                future.complete(targetFile);
            } catch (Exception e) {
                LogUtil.error("Ошибка чтения локального аудиофайла: " + input, e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}
