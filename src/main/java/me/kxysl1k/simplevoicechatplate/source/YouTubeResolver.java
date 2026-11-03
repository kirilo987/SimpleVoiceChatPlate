package me.kxysl1k.simplevoicechatplate.source;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.audio.AudioProcessingException;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class YouTubeResolver implements SourceResolver {

    private final SimpleVoiceChatPlate plugin;

    public YouTubeResolver(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean canHandle(String input) {
        return input != null && plugin.getSettings().isYouTubeEnabled()
                && (input.contains("youtube.com") || input.contains("youtu.be"));
    }

    public String getFormatSelector() {
        String preset = plugin.getSettings().getAudioQualityPreset();
        return switch (preset) {
            case "balanced" -> "bestaudio[acodec=opus]/bestaudio/best";
            default -> "bestaudio[acodec=opus][abr>=128]/bestaudio[ext=webm][abr>=100]/bestaudio[abr>=128]/bestaudio/best";
        };
    }

    public List<String> buildStrategies() {
        String customExtractorArgs = plugin.getSettings().getExtractorArgs();
        List<String> strategies = new ArrayList<>();
        if (customExtractorArgs != null && !customExtractorArgs.isBlank()) {
            strategies.add(customExtractorArgs.trim());
            return strategies;
        }

        // Пустая строка на первой попытке позволяет yt-dlp использовать свой актуальный нативный профиль,
        // который отдает оригинальный поток 48 кГц Opus 160 кбит/с (itag 251) вместо низкобитрейтного HLS на iOS.
        strategies.add("");

        String configuredClients = plugin.getSettings().getPlayerClients();
        if (configuredClients != null && !configuredClients.isBlank()) {
            strategies.add("youtube:player_client=" + configuredClients.trim());
        }
        strategies.add("youtube:player_client=mweb,web");
        strategies.add("youtube:player_client=ios");
        strategies.add("youtube:player_client=android");
        return strategies;
    }

    public List<String> buildYtDlpCommand(File ytDlp, String input, String outputTarget, String extractorArg) {
        List<String> command = new ArrayList<>();
        command.add(ytDlp.getAbsolutePath());
        command.add("--quiet");
        command.add("--no-warnings");
        command.add("-f");
        command.add(getFormatSelector());
        if (!"balanced".equals(plugin.getSettings().getAudioQualityPreset())) {
            command.add("-S");
            command.add("abr,asr");
        }
        command.add("--no-playlist");
        command.add("--no-check-certificate");
        if (!"-".equals(outputTarget)) {
            command.add("--concurrent-fragments");
            command.add("4");
        }
        command.add("-4");

        if (extractorArg != null && !extractorArg.isBlank()) {
            command.add("--extractor-args");
            command.add(extractorArg);
        }

        File cookiesFile = new File(plugin.getDataFolder(), "cookies.txt");
        if (cookiesFile.exists()) {
            command.add("--cookies");
            command.add(cookiesFile.getAbsolutePath());
        }

        command.add("-o");
        command.add(outputTarget);
        command.add(input);
        return command;
    }

    @Override
    public CompletableFuture<File> resolve(String input, File targetFile) {
        CompletableFuture<File> future = new CompletableFuture<>();

        Thread.ofVirtual().name("svcplate-ytdlp-download").start(() -> {
            File ytDlp = plugin.getDependencyManager().getYtDlpExecutable();
            if (ytDlp == null || !ytDlp.exists()) {
                future.completeExceptionally(new AudioProcessingException(
                        AudioProcessingException.ErrorType.UNKNOWN, "Модуль yt-dlp не установлен или не найден!"));
                return;
            }

            LogUtil.debug("yt-dlp начинает загрузку: " + input);
            List<String> strategies = buildStrategies();
            File cookiesFile = new File(plugin.getDataFolder(), "cookies.txt");

            int lastExitCode = -1;
            String lastOutput = "";
            String lastCommandStr = "";

            for (int attempt = 0; attempt < strategies.size(); attempt++) {
                String extractorArg = strategies.get(attempt);
                if (attempt > 0) {
                    LogUtil.debug("Повторная попытка yt-dlp (" + (attempt + 1) + "/" + strategies.size() + ") с профилем: " + extractorArg);
                }

                List<String> command = buildYtDlpCommand(ytDlp, input, targetFile.getAbsolutePath(), extractorArg);
                lastCommandStr = String.join(" ", command);

                try {
                    if (targetFile.exists()) targetFile.delete();
                    ProcessBuilder builder = new ProcessBuilder(command);
                    builder.environment().put("TMPDIR", plugin.getDataFolder().getAbsolutePath());
                    builder.redirectErrorStream(true);
                    Process process = builder.start();

                    String result;
                    try (Scanner s = new Scanner(process.getInputStream()).useDelimiter("\\A")) {
                        result = s.hasNext() ? s.next() : "";
                    }

                    int exitCode = process.waitFor();
                    lastExitCode = exitCode;
                    lastOutput = result;

                    if (exitCode == 0 && targetFile.exists() && targetFile.length() > 0) {
                        LogUtil.debug("yt-dlp успешно скачал аудио: " + targetFile.getName() + " (" + targetFile.length() + " байт)");
                        future.complete(targetFile);
                        return;
                    } else {
                        AudioProcessingException.ErrorType errorType = AudioProcessingException.classifyFromOutput(result);
                        if (errorType == AudioProcessingException.ErrorType.UNAVAILABLE_DELETED
                                || (errorType == AudioProcessingException.ErrorType.AGE_RESTRICTED && !cookiesFile.exists())) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    lastOutput = e.getMessage();
                }
            }

            AudioProcessingException.ErrorType finalErrorType = AudioProcessingException.classifyFromOutput(lastOutput);

            Map<String, Object> context = new HashMap<>();
            context.put("Действие", "Загрузка аудио с YouTube");
            context.put("Ссылка", input);
            context.put("Целевой файл", targetFile.getAbsolutePath());
            context.put("Классификация ошибки", finalErrorType.name());

            LogUtil.logProcessFailure("yt-dlp", lastExitCode, lastCommandStr, lastOutput, context);

            future.completeExceptionally(new AudioProcessingException(
                    finalErrorType, "код выхода yt-dlp " + lastExitCode + ": " + cleanErrorMessage(lastOutput)));
        });

        return future;
    }

    private String cleanErrorMessage(String rawOutput) {
        if (rawOutput == null) return "Неизвестная ошибка";
        for (String line : rawOutput.split("\n")) {
            if (line.contains("ERROR:")) {
                return line.trim();
            }
        }
        return rawOutput.length() > 200 ? rawOutput.substring(0, 200) + "..." : rawOutput.trim();
    }
}
