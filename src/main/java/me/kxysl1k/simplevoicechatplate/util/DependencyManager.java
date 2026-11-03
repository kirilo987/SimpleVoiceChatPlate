package me.kxysl1k.simplevoicechatplate.util;

import me.kxysl1k.simplevoicechatplate.SimpleVoiceChatPlate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DependencyManager {

    private final SimpleVoiceChatPlate plugin;
    private volatile File ytDlpExecutable;
    private volatile File ffmpegExecutable;
    private volatile String ytDlpVersion = "";

    public DependencyManager(SimpleVoiceChatPlate plugin) {
        this.plugin = plugin;
    }

    public void init() {
        Thread.ofVirtual().name("svcplate-deps-init").start(() -> {
            checkFfmpeg();
            checkYtDlp();

            if (ytDlpExecutable != null && ytDlpExecutable.exists() && plugin.getSettings().isAutoUpdateYtDlp()) {
                try {
                    updateYtDlpInternal();
                } catch (Exception e) {
                    LogUtil.debug("yt-dlp background update skipped: " + e.getMessage());
                }
            }
        });
    }

    private void checkFfmpeg() {
        String configuredPath = plugin.getSettings().getFfmpegPath();
        if (configuredPath != null && !configuredPath.isBlank()) {
            File f = new File(configuredPath);
            if (f.exists()) {
                ffmpegExecutable = f;
                LogUtil.info("FFmpeg attached: " + f.getAbsolutePath());
                return;
            }
        }

        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");
        String binName = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";

        File localBin = new File(plugin.getDataFolder(), binName);
        if (localBin.exists()) {
            ffmpegExecutable = localBin;
            LogUtil.info("FFmpeg attached: " + localBin.getAbsolutePath());
            return;
        }

        if (isCommandAvailable(binName)) {
            ffmpegExecutable = new File(binName);
            LogUtil.info("FFmpeg found in system PATH.");
            return;
        }

        if (!os.contains("win")) {
            LogUtil.info("Downloading static FFmpeg build for Linux...");
            try {
                String downloadUrl = isArm
                        ? "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffmpeg-linux-arm64"
                        : "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffmpeg-linux-x64";

                downloadFile(downloadUrl, localBin, 0);
                Files.setPosixFilePermissions(localBin.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));

                ffmpegExecutable = localBin;
                LogUtil.info("FFmpeg downloaded successfully.");
            } catch (Exception e) {
                LogUtil.error("Automatic FFmpeg download failed", e);
            }
        } else {
            LogUtil.warn("FFmpeg not found! Place ffmpeg.exe into plugins/SimpleVoiceChatPlate/ or add it to system PATH.");
        }
    }

    private void checkYtDlp() {
        if (!plugin.getSettings().isYouTubeEnabled()) return;

        String configuredPath = plugin.getSettings().getYtDlpPath();
        if (configuredPath != null && !configuredPath.isBlank()) {
            File f = new File(configuredPath);
            if (f.exists()) {
                ytDlpExecutable = f;
                readYtDlpVersion();
                LogUtil.info("yt-dlp (" + ytDlpVersion + ") attached: " + f.getAbsolutePath());
                return;
            }
        }

        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");
        String binName = os.contains("win") ? "yt-dlp.exe" : "yt-dlp";

        File localBin = new File(plugin.getDataFolder(), binName);
        if (localBin.exists()) {
            ytDlpExecutable = localBin;
            readYtDlpVersion();
            LogUtil.info("yt-dlp (" + ytDlpVersion + ") attached: " + localBin.getAbsolutePath());
            return;
        }

        if (isCommandAvailable(binName)) {
            ytDlpExecutable = new File(binName);
            readYtDlpVersion();
            LogUtil.info("yt-dlp (" + ytDlpVersion + ") found in system PATH.");
            return;
        }

        LogUtil.info("Downloading latest yt-dlp binary...");
        try {
            downloadYtDlpBinary(localBin, os, isArm);
            ytDlpExecutable = localBin;
            readYtDlpVersion();
            LogUtil.info("yt-dlp (" + ytDlpVersion + ") downloaded successfully.");
        } catch (Exception e) {
            LogUtil.error("yt-dlp download failed", e);
        }
    }

    private void downloadYtDlpBinary(File target, String os, boolean isArm) throws Exception {
        String downloadUrl = os.contains("win")
                ? "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
                : (isArm ? "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64"
                : "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux");

        downloadFile(downloadUrl, target, 0);

        if (!os.contains("win")) {
            try {
                Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (Exception ignored) {
            }
        }
    }

    public void readYtDlpVersion() {
        File exe = ytDlpExecutable;
        if (exe == null) return;
        try {
            Process process = new ProcessBuilder(exe.getAbsolutePath(), "--version").start();
            try (Scanner s = new Scanner(process.getInputStream())) {
                if (s.hasNextLine()) {
                    ytDlpVersion = s.nextLine().trim();
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            ytDlpVersion = "";
        }
    }

    public CompletableFuture<String> forceUpdateYtDlp() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread.ofVirtual().name("svcplate-ytdlp-update").start(() -> {
            try {
                String newVersion = updateYtDlpInternal();
                future.complete(newVersion);
            } catch (Exception e) {
                LogUtil.error("yt-dlp update error: " + e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private String updateYtDlpInternal() throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");
        String binName = os.contains("win") ? "yt-dlp.exe" : "yt-dlp";
        File localBin = new File(plugin.getDataFolder(), binName);

        File exe = ytDlpExecutable;
        if (exe != null && exe.exists()) {
            try {
                Process p = new ProcessBuilder(exe.getAbsolutePath(), "-U").start();
                p.waitFor(30, TimeUnit.SECONDS);
                readYtDlpVersion();
            } catch (Exception ignored) {
            }
        }

        try {
            downloadYtDlpBinary(localBin, os, isArm);
            ytDlpExecutable = localBin;
            readYtDlpVersion();
        } catch (Exception e) {
            if (ytDlpExecutable == null || !ytDlpExecutable.exists()) {
                throw e;
            }
        }

        return ytDlpVersion;
    }

    private void downloadFile(String urlStr, File target, int redirectDepth) throws Exception {
        if (redirectDepth > 5) {
            throw new IllegalStateException("Too many redirects while downloading: " + urlStr);
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "SimpleVoiceChatPlate/1.0");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            downloadFile(newUrl, target, redirectDepth + 1);
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        File tempFile = new File(parent, target.getName() + ".tmp");
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[16384];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        } finally {
            conn.disconnect();
        }

        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder(command, "-version").start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished;
        } catch (Exception e) {
            return false;
        }
    }

    public File getYtDlpExecutable() {
        return ytDlpExecutable;
    }

    public String getYtDlpVersion() {
        return ytDlpVersion;
    }

    public File getFfmpegExecutable() {
        return ffmpegExecutable;
    }
}
