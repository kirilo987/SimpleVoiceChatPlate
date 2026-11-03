package me.kxysl1k.simplevoicechatplate.audio;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Дескриптор потокового или завершенного PCM-источника.
 * Позволяет начинать воспроизведение уже через ~1.5 секунды буферизации (пока FFmpeg продолжает дописывать PCM-файл),
 * а также мгновенно (0 мс) стартовать с предзагруженного 12-секундного вступления (.head.pcm) соседнего трека плейлиста
 * с последующим бесшовным переключением чтения на полный файл прямо во время воспроизведения.
 */
public final class PcmStreamHandle {

    private final File primaryFile;
    private final boolean temporaryLiveFile;
    private final AtomicLong writtenBytes = new AtomicLong(0L);
    private final AtomicInteger activeReaders = new AtomicInteger(0);
    private final Object monitor = new Object();

    private volatile boolean completed = false;
    private volatile Throwable failure = null;
    private volatile File headPreviewFile = null;

    private PcmStreamHandle(File primaryFile, long initialBytes, boolean completed, boolean temporaryLiveFile) {
        this.primaryFile = primaryFile;
        this.writtenBytes.set(initialBytes);
        this.completed = completed;
        this.temporaryLiveFile = temporaryLiveFile;
    }

    public static PcmStreamHandle completed(File pcmFile) {
        return new PcmStreamHandle(pcmFile, pcmFile.length(), true, false);
    }

    public static PcmStreamHandle liveGrowing(File tempLivePcmFile) {
        return new PcmStreamHandle(tempLivePcmFile, 0L, false, true);
    }

    public PcmStreamHandle withHeadPreview(File headFile) {
        this.headPreviewFile = headFile;
        return this;
    }

    public File getPrimaryFile() {
        return primaryFile;
    }

    public File getHeadPreviewFile() {
        return headPreviewFile;
    }

    public long getWrittenBytes() {
        return writtenBytes.get();
    }

    public boolean isCompleted() {
        return completed;
    }

    public Throwable getFailure() {
        return failure;
    }

    public void addWrittenBytes(long delta) {
        if (delta <= 0) return;
        writtenBytes.addAndGet(delta);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public void setWrittenBytes(long absoluteBytes) {
        writtenBytes.set(absoluteBytes);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public void markCompleted() {
        this.completed = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        cleanupTempIfIdle();
    }

    public void markFailed(Throwable error) {
        this.failure = error;
        this.completed = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        cleanupTempIfIdle();
    }

    public boolean waitForBytes(long requiredBytePosition, long maxWaitMs) {
        if (writtenBytes.get() >= requiredBytePosition || completed) {
            return writtenBytes.get() >= requiredBytePosition;
        }
        long deadline = System.currentTimeMillis() + maxWaitMs;
        synchronized (monitor) {
            while (!completed && writtenBytes.get() < requiredBytePosition) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    monitor.wait(Math.min(remaining, 50L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return writtenBytes.get() >= requiredBytePosition;
    }

    public void retainReader() {
        activeReaders.incrementAndGet();
    }

    public void releaseReader() {
        if (activeReaders.decrementAndGet() <= 0) {
            cleanupTempIfIdle();
        }
    }

    private void cleanupTempIfIdle() {
        if (temporaryLiveFile && completed && activeReaders.get() <= 0 && primaryFile != null && primaryFile.exists()) {
            primaryFile.delete();
        }
    }
}
