package me.kxysl1k.simplevoicechatplate.cache;

import java.io.File;

public class CachedTrack {

    private final String trackId;
    private final File audioFile;
    private final long durationMs;
    private final long lastAccessedTime;

    public CachedTrack(String trackId, File audioFile, long durationMs, long lastAccessedTime) {
        this.trackId = trackId;
        this.audioFile = audioFile;
        this.durationMs = durationMs > 0 ? durationMs : (audioFile != null && audioFile.exists() ? (audioFile.length() * 1000L) / 96000L : 0L);
        this.lastAccessedTime = lastAccessedTime;
    }

    public String getTrackId() {
        return trackId;
    }

    public File getAudioFile() {
        return audioFile;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getLastAccessedTime() {
        return lastAccessedTime;
    }
}
