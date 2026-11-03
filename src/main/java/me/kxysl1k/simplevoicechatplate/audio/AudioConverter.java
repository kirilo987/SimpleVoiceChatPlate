package me.kxysl1k.simplevoicechatplate.audio;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import me.kxysl1k.simplevoicechatplate.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class AudioConverter {

    private final SimpleVoiceChatPlate plugin;

    public AudioConverter(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    /**
     * Формирует цепочку аудиофильтров FFmpeg под выбранный профиль качества:
     * - "ultra": 64-точечный sinc-интерполятор с широкой полосой пропускания (до 19.6 кГц при 48 кГц дискретизации),
     *   фильтр суб-инфразвука 20 Гц и мягкий потолок -0.35 dBFS (0.96) для предотвращения клиппинга в Opus.
     * - "raw": полное отключение фильтрации для побитово чистого декодирования исходной дорожки.
     */
    public String buildAudioFilterChain() {
        String custom = plugin.getSettings().getCustomFfmpegFilter();
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }

        String preset = plugin.getSettings().getAudioQualityPreset();
        float gain = plugin.getSettings().getAudioOutputGain();
        boolean normalize = plugin.getSettings().isNormalizeAudio();

        List<String> stages = new ArrayList<>();
        if (Math.abs(gain - 1.0f) > 0.01f) {
            stages.add(String.format(Locale.US, "volume=%.3f", gain));
        }

        if ("raw".equals(preset)) {
            return String.join(",", stages);
        }

        switch (preset) {
            case "ultra" -> {
                stages.add("highpass=f=20");
                stages.add("aresample=osr=48000:filter_size=64:phase_shift=10:cutoff=0.98");
            }
            case "high" -> {
                stages.add("highpass=f=22");
                stages.add("aresample=osr=48000:filter_size=32:phase_shift=10:cutoff=0.96");
            }
            default -> {
                stages.add("highpass=f=24");
                stages.add("aresample=48000");
            }
        }

        if (normalize) {
            stages.add("alimiter=limit=0.96:attack=5:release=50:level=disabled");
        }
        return String.join(",", stages);
    }

    public List<String> buildFfmpegCommand(File ffmpeg, String inputSpec, String outputSpec, long maxSeconds) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg.getAbsolutePath());
        command.add("-y");
        command.add("-v");
        command.add("error");
        command.add("-i");
        command.add(inputSpec);

        if (maxSeconds > 0) {
            command.add("-t");
            command.add(String.valueOf(maxSeconds));
        }

        String filter = buildAudioFilterChain();
        if (!filter.isEmpty()) {
            command.add("-af");
            command.add(filter);
        }

        command.add("-ac");
        command.add("1");
        command.add("-ar");
        command.add("48000");
        command.add("-f");
        command.add("s16le");
        command.add(outputSpec);
        return command;
    }

    public CompletableFuture<File> convertToPcm(File inputFile, File outputFile) {
        return convertToPcm(inputFile, outputFile, plugin.getSettings().getMaxAudioLengthSeconds());
    }

    public CompletableFuture<File> convertToPcm(File inputFile, File outputFile, long maxSeconds) {
        CompletableFuture<File> future = new CompletableFuture<>();

        Thread.ofVirtual().name("svcplate-ffmpeg-convert").start(() -> {
            try {
                File ffmpeg = plugin.getDependencyManager().getFfmpegExecutable();
                if (ffmpeg == null || !ffmpeg.exists()) {
                    future.completeExceptionally(new AudioProcessingException(
                            AudioProcessingException.ErrorType.FFMPEG_ERROR, "FFmpeg executable not found"));
                    return;
                }

                List<String> command = buildFfmpegCommand(ffmpeg, inputFile.getAbsolutePath(), outputFile.getAbsolutePath(), maxSeconds);

                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                Process process = builder.start();

                String result;
                try (Scanner s = new Scanner(process.getInputStream()).useDelimiter("\\A")) {
                    result = s.hasNext() ? s.next() : "";
                }

                int exitCode = process.waitFor();
                if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                    future.complete(outputFile);
                } else {
                    Map<String, Object> context = new HashMap<>();
                    context.put("Input", inputFile.getAbsolutePath() + " (" + inputFile.length() + " bytes)");
                    context.put("Output", outputFile.getAbsolutePath());

                    LogUtil.logProcessFailure("FFmpeg", exitCode, String.join(" ", command), result, context);

                    future.completeExceptionally(new AudioProcessingException(
                            AudioProcessingException.ErrorType.FFMPEG_ERROR, "FFmpeg exit " + exitCode + ": " + result));
                }
            } catch (Exception e) {
                Map<String, Object> context = new HashMap<>();
                context.put("Input", inputFile != null ? inputFile.getAbsolutePath() : "null");
                LogUtil.crash("FFmpeg conversion failure", e, context);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Конвейер прямой потоковой перекачки: stdout процесса yt-dlp подается напрямую в stdin процесса FFmpeg,
     * а готовые 16-битные PCM-кадры читаются из stdout FFmpeg и сбрасываются в livePcmFile порциями.
     * Как только объем записанных данных достигает initialReadyBytes (~1.5 сек аудио), readyFuture немедленно
     * завершается успехом и плеер начинает играть музыку без ожидания скачивания всего трека целиком.
     */
    public boolean pipeProcessesToLivePcm(
            List<String> producerCommand,
            File workDir,
            File livePcmFile,
            PcmStreamHandle streamHandle,
            long initialReadyBytes,
            long maxDurationSeconds,
            CompletableFuture<PcmStreamHandle> readyFuture
    ) {
        File ffmpeg = plugin.getDependencyManager().getFfmpegExecutable();
        if (ffmpeg == null || !ffmpeg.exists()) {
            return false;
        }

        Process producer = null;
        Process converter = null;
        try {
            ProcessBuilder prodBuilder = new ProcessBuilder(producerCommand);
            prodBuilder.environment().put("TMPDIR", workDir.getAbsolutePath());
            prodBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);

            List<String> ffmpegCmd = buildFfmpegCommand(ffmpeg, "pipe:0", "pipe:1", maxDurationSeconds);
            ProcessBuilder convBuilder = new ProcessBuilder(ffmpegCmd);
            convBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);

            producer = prodBuilder.start();
            converter = convBuilder.start();

            final Process prodRef = producer;
            final Process convRef = converter;

            Thread pumpThread = Thread.ofVirtual().name("svcplate-pipe-bridge").start(() -> {
                try (InputStream prodOut = prodRef.getInputStream();
                     OutputStream convIn = convRef.getOutputStream()) {
                    byte[] buf = new byte[32768];
                    int r;
                    while ((r = prodOut.read(buf)) != -1) {
                        convIn.write(buf, 0, r);
                    }
                    convIn.flush();
                } catch (Exception ignored) {
                } finally {
                    if (prodRef.isAlive()) {
                        prodRef.destroy();
                    }
                }
            });

            long totalWritten = 0L;
            try (InputStream pcmIn = converter.getInputStream();
                 FileOutputStream fileOut = new FileOutputStream(livePcmFile)) {
                byte[] pcmBuf = new byte[1920 * 8];
                int r;
                while ((r = pcmIn.read(pcmBuf)) != -1) {
                    fileOut.write(pcmBuf, 0, r);
                    totalWritten += r;
                    streamHandle.setWrittenBytes(totalWritten);

                    if (readyFuture != null && !readyFuture.isDone() && totalWritten >= initialReadyBytes) {
                        readyFuture.complete(streamHandle);
                    }
                }
                fileOut.flush();
            }

            int ffExit = converter.waitFor();
            pumpThread.join(1500L);
            if (producer.isAlive()) {
                producer.destroyForcibly();
            }

            return totalWritten >= 1920L && (ffExit == 0 || maxDurationSeconds > 0 && totalWritten >= maxDurationSeconds * 90_000L);
        } catch (Exception e) {
            LogUtil.debug("Потоковый конвейер pipe завершился с исключением: " + e.getMessage());
            return false;
        } finally {
            if (producer != null && producer.isAlive()) producer.destroyForcibly();
            if (converter != null && converter.isAlive()) converter.destroyForcibly();
        }
    }
}
