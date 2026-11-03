package me.kxysl1k.simplevoicechatplate.util;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class CrashLogger {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SimpleVoiceChatPlate plugin;
    private final File logsFolder;
    private final File crashFolder;
    private final Object writeLock = new Object();
    private final AtomicInteger crashCounter = new AtomicInteger(0);

    public CrashLogger(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
        this.logsFolder = new File(plugin.getDataFolder(), "logs");
        this.crashFolder = new File(plugin.getDataFolder(), "crash-reports");

        if (!logsFolder.exists()) logsFolder.mkdirs();
        if (!crashFolder.exists()) crashFolder.mkdirs();
    }

    public void init() {
        int retention = plugin.getSettings() != null ? plugin.getSettings().getLogRetentionDays() : 14;
        cleanupOldFiles(logsFolder, retention);
        cleanupOldFiles(crashFolder, retention);
    }

    public void log(String level, String message, Throwable t) {
        if (plugin.getSettings() != null && !plugin.getSettings().isFileLoggingEnabled()) {
            return;
        }
        synchronized (writeLock) {
            try {
                LocalDateTime now = LocalDateTime.now();
                String line = "[" + TIME_FORMAT.format(now) + "] [" + level + "] " + message + "\n";

                File latestLog = new File(logsFolder, "latest.log");
                try (FileWriter fw = new FileWriter(latestLog, StandardCharsets.UTF_8, true)) {
                    fw.write(line);
                    if (t != null) {
                        StringWriter sw = new StringWriter();
                        t.printStackTrace(new PrintWriter(sw));
                        fw.write(sw + "\n");
                    }
                }

                if ("WARN".equalsIgnoreCase(level) || "ERROR".equalsIgnoreCase(level) || "SEVERE".equalsIgnoreCase(level)) {
                    File errorLog = new File(logsFolder, "error-" + DAY_FORMAT.format(now) + ".log");
                    try (FileWriter fw = new FileWriter(errorLog, StandardCharsets.UTF_8, true)) {
                        fw.write(line);
                        if (t != null) {
                            StringWriter sw = new StringWriter();
                            t.printStackTrace(new PrintWriter(sw));
                            fw.write(sw + "\n");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public File saveCrashReport(String description, Throwable t, Map<String, Object> context) {
        if (plugin.getSettings() != null && !plugin.getSettings().isCreateCrashReports()) {
            return null;
        }
        synchronized (writeLock) {
            try {
                LocalDateTime now = LocalDateTime.now();
                int count = crashCounter.incrementAndGet();
                String fileName = "crash-" + FILE_DATE_FORMAT.format(now) + (count > 1 ? "_" + count : "") + ".txt";
                File reportFile = new File(crashFolder, fileName);

                StringBuilder sb = new StringBuilder();
                sb.append("---- Отчет об ошибке SimpleVoiceChatPlate ----\n");
                sb.append("// Автор плагина: Kxysl1k\n\n");

                sb.append("Время: ").append(DATE_FORMAT.format(now)).append("\n");
                sb.append("Описание: ").append(description != null ? description : "Непредвиденный сбой").append("\n\n");

                sb.append("--- Детали исключения ---\n");
                if (t != null) {
                    sb.append("Исключение: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    sb.append("Стек вызовов:\n").append(sw).append("\n");
                } else {
                    sb.append("Исключение отсутствует.\n\n");
                }

                sb.append("--- Контекст операции ---\n");
                if (context != null && !context.isEmpty()) {
                    for (Map.Entry<String, Object> entry : context.entrySet()) {
                        sb.append(entry.getKey()).append(": ").append(formatContextValue(entry.getValue())).append("\n");
                    }
                } else {
                    sb.append("Контекст не передан.\n");
                }
                sb.append("\n");

                sb.append("--- Системное окружение ---\n");
                sb.append("Версия SimpleVoiceChatPlate: ").append(plugin.getPluginMeta().getVersion()).append("\n");
                try {
                    sb.append("Сервер: ").append(Bukkit.getName()).append(" ").append(Bukkit.getVersion())
                            .append(" (MC: ").append(Bukkit.getMinecraftVersion()).append(")\n");
                } catch (Throwable ignored) {
                    sb.append("Сервер: Неизвестно\n");
                }
                sb.append("Java: ").append(System.getProperty("java.version"))
                        .append(" (").append(System.getProperty("java.vendor")).append(")\n");
                sb.append("ОС: ").append(System.getProperty("os.name"))
                        .append(" (").append(System.getProperty("os.arch")).append(") ")
                        .append(System.getProperty("os.version")).append("\n");

                long freeMem = Runtime.getRuntime().freeMemory() / 1048576L;
                long totalMem = Runtime.getRuntime().totalMemory() / 1048576L;
                long maxMem = Runtime.getRuntime().maxMemory() / 1048576L;
                sb.append("Память JVM: Свободно ").append(freeMem).append(" МБ / Выделено ").append(totalMem)
                        .append(" МБ / Макс. ").append(maxMem).append(" МБ\n");

                if (plugin.getDependencyManager() != null) {
                    File ytdlp = plugin.getDependencyManager().getYtDlpExecutable();
                    sb.append("yt-dlp: ").append(ytdlp != null ? ytdlp.getAbsolutePath() + " (Существует: " + ytdlp.exists() + ")" : "Не найден");
                    String ytVer = plugin.getDependencyManager().getYtDlpVersion();
                    if (ytVer != null && !ytVer.isEmpty()) {
                        sb.append(" [Версия: ").append(ytVer).append("]");
                    }
                    sb.append("\n");

                    File ffmpeg = plugin.getDependencyManager().getFfmpegExecutable();
                    sb.append("FFmpeg: ").append(ffmpeg != null ? ffmpeg.getAbsolutePath() + " (Существует: " + ffmpeg.exists() + ")" : "Не найден").append("\n");
                }

                sb.append("Simple Voice Chat API: ")
                        .append(plugin.getVoiceChatIntegration() != null && plugin.getVoiceChatIntegration().getServerApi() != null
                                ? "Подключен" : "Отключен / Не найден")
                        .append("\n");
                if (plugin.getCacheManager() != null) {
                    sb.append("Треков в кэше: ").append(plugin.getCacheManager().getCacheSize()).append("\n");
                }
                sb.append("----------------------------------------------\n");

                try (FileWriter fw = new FileWriter(reportFile, StandardCharsets.UTF_8)) {
                    fw.write(sb.toString());
                }

                LogUtil.warn("Сохранен подробный отчет об ошибке: " + reportFile.getPath());
                return reportFile;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public File logProcessFailure(String processName, int exitCode, String command, String output, Map<String, Object> context) {
        if (context == null) context = new ConcurrentHashMap<>();
        context.put("Процесс", processName);
        context.put("Код завершения", exitCode);
        context.put("Команда", command != null ? command : "Н/Д");
        context.put("Вывод процесса", output != null && !output.isEmpty() ? "\n" + output : "Пусто");

        String desc = processName + " завершился с ошибкой (код " + exitCode + ")";
        log("ERROR", desc + "\n" + (output != null ? output : ""), null);
        return saveCrashReport(desc, null, context);
    }

    private String formatContextValue(Object val) {
        if (val == null) return "null";
        if (val instanceof Player p) {
            Location loc = p.getLocation();
            String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
            return p.getName() + " (UUID: " + p.getUniqueId() + ", Мир: " + worldName + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")";
        }
        if (val instanceof Location loc) {
            String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
            return worldName + " [" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "]";
        }
        return val.toString();
    }

    private void cleanupOldFiles(File folder, int days) {
        if (!folder.exists() || days <= 0) return;
        long cutoff = System.currentTimeMillis() - (days * 86400000L);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.lastModified() < cutoff) {
                    f.delete();
                }
            }
        }
    }
}
