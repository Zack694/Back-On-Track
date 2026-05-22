package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;
import com.zack858.backontrack.config.ModConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps an FFmpeg subprocess that reads raw RGBA frames from stdin and emits
 * an encoded video file. Frames are queued by the render thread and drained
 * by a dedicated writer thread to avoid blocking rendering on stdout backpressure.
 *
 * Works with any FFmpeg build that supports the configured encoder
 * (libx264 by default). FFmpeg must be present either on PATH or at
 * {@link ModConfig#ffmpegPath}.
 */
public final class FFmpegEncoder {
    /** Bounded queue: prevents unbounded memory growth if FFmpeg can't keep up. */
    private static final int QUEUE_CAPACITY = 8;
    /** Sentinel to signal end-of-stream to the writer thread. */
    private static final byte[] POISON = new byte[0];

    private final ModConfig cfg;
    private final int srcWidth;
    private final int srcHeight;
    private final Path outputFile;

    private Process process;
    private Thread writerThread;
    private Thread stderrThread;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);

    public FFmpegEncoder(ModConfig cfg, int srcWidth, int srcHeight) {
        this.cfg = cfg;
        this.srcWidth = srcWidth;
        this.srcHeight = srcHeight;
        this.outputFile = computeOutputPath();
    }

    /**
     * Spawn FFmpeg and start the writer thread. Throws on launch failure
     * (binary not found, invalid args, etc).
     */
    public void start() throws IOException {
        Files.createDirectories(outputFile.getParent());

        ProcessBuilder pb = new ProcessBuilder(buildCommand())
                .redirectErrorStream(false);
        // Inherit env; we don't want to override anything.
        process = pb.start();
        running.set(true);

        // Drain stderr so FFmpeg never blocks on full stderr buffer, and so we
        // capture useful diagnostic logs.
        stderrThread = new Thread(this::drainStderr, "BackOnTrack-FFmpeg-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        // Writer thread: pulls frames from the queue and writes them to FFmpeg.
        writerThread = new Thread(this::writeLoop, "BackOnTrack-FFmpeg-writer");
        writerThread.setDaemon(true);
        writerThread.start();

        BackOnTrack.LOGGER.info("FFmpeg started -> {}", outputFile);
    }

    /**
     * Submit a frame for encoding. Non-blocking: drops the frame and increments
     * the drop counter if the queue is full (encoder can't keep up).
     */
    public void submitFrame(byte[] rgbaFrame) {
        if (!running.get()) return;
        if (!queue.offer(rgbaFrame)) {
            droppedFrames.incrementAndGet();
        } else {
            frameCount.incrementAndGet();
        }
    }

    /**
     * Closes the input stream, waits for FFmpeg to flush, and returns the
     * output path. Safe to call from any thread.
     */
    public Path stop() {
        if (!running.compareAndSet(true, false)) {
            return outputFile;
        }
        try {
            queue.put(POISON);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Wait for writer to drain.
        try {
            if (writerThread != null) writerThread.join(TimeUnit.SECONDS.toMillis(15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Wait for FFmpeg to finalise the file (mp4 moov atom, etc.).
        if (process != null) {
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    BackOnTrack.LOGGER.warn("FFmpeg did not exit within 30s; killing.");
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if (stderrThread != null) {
            try { stderrThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        BackOnTrack.LOGGER.info("FFmpeg finished. Frames: {}, dropped: {}, file: {}",
                frameCount.get(), droppedFrames.get(), outputFile);
        return outputFile;
    }

    public Path getOutputFile() { return outputFile; }
    public long getFrameCount() { return frameCount.get(); }
    public long getDroppedFrames() { return droppedFrames.get(); }

    // ---- internals ----

    private void writeLoop() {
        try (OutputStream out = process.getOutputStream()) {
            while (true) {
                byte[] frame = queue.take();
                if (frame == POISON) break;
                out.write(frame);
            }
            out.flush();
        } catch (IOException e) {
            // Broken pipe usually means ffmpeg crashed; logged in stderr drainer.
            BackOnTrack.LOGGER.warn("Writer thread terminated: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainStderr() {
        if (process == null) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // FFmpeg is *very* chatty; only log warnings/errors at INFO so we
                // don't spam the console during recording.
                String lower = line.toLowerCase();
                if (lower.contains("error") || lower.contains("invalid")
                        || lower.contains("could not") || lower.contains("failed")) {
                    BackOnTrack.LOGGER.warn("[ffmpeg] {}", line);
                } else {
                    BackOnTrack.LOGGER.debug("[ffmpeg] {}", line);
                }
            }
        } catch (IOException ignored) {
            // Process closed; expected on shutdown.
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.ffmpegPath);
        cmd.add("-y"); // overwrite output if exists (we use timestamped names anyway)
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("warning");

        // ---- Input: raw RGBA frames from stdin ----
        cmd.add("-f"); cmd.add("rawvideo");
        cmd.add("-pix_fmt"); cmd.add("rgba");
        cmd.add("-s"); cmd.add(srcWidth + "x" + srcHeight);
        cmd.add("-r"); cmd.add(String.valueOf(cfg.fps));
        cmd.add("-i"); cmd.add("-");

        // ---- Filter: scale to target output (NativeImage is top-down already) ----
        String filter = "scale=" + cfg.width + ":" + cfg.height + ":flags=lanczos";
        cmd.add("-vf"); cmd.add(filter);

        // ---- Encoding ----
        cmd.add("-c:v"); cmd.add(cfg.videoCodec);
        cmd.add("-preset"); cmd.add(cfg.preset);
        cmd.add("-b:v"); cmd.add(cfg.bitrateKbps + "k");
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-movflags"); cmd.add("+faststart");

        cmd.add(outputFile.toString());
        return cmd;
    }

    private Path computeOutputPath() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = "back-on-track_" + stamp + "." + cfg.container;
        return cfg.outputDirAbsolute().resolve(filename);
    }
}
