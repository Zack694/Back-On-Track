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
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps an FFmpeg subprocess that reads raw RGBA frames from stdin and emits
 * an encoded video file. Frames are queued by the render thread and drained
 * by a dedicated writer thread to avoid blocking rendering on stdout backpressure.
 *
 * <h2>Timing model</h2>
 * Frames do <em>not</em> arrive at a constant rate -- the game renders at
 * whatever framerate it can manage. We therefore tell FFmpeg to stamp each
 * input frame with its wall-clock arrival time
 * ({@code -use_wallclock_as_timestamps 1}) and emit a constant-framerate
 * output ({@code -vsync cfr -r N}). FFmpeg duplicates frames as needed so
 * the recording plays back at real speed instead of being sped up when our
 * capture lags.
 *
 * <h2>Memory</h2>
 * Frame byte[]s are recycled through {@link #acquireBuffer()} /
 * {@link #releaseBuffer(byte[])} so we don't churn 8 MB+ per frame at 1080p60.
 *
 * Works with any FFmpeg build that supports the configured encoder
 * (libx264 by default; libx265, h264_nvenc, hevc_nvenc, h264_amf, hevc_amf,
 * h264_qsv, h264_videotoolbox are all wired up). FFmpeg must be present
 * either on PATH or at {@link ModConfig#ffmpegPath}.
 */
public final class FFmpegEncoder {
    /** Bounded queue: prevents unbounded memory growth if FFmpeg can't keep up. */
    private static final int QUEUE_CAPACITY = 16;
    /** Maximum number of recycled byte[] frame buffers we retain. */
    private static final int POOL_LIMIT = 32;
    /** Sentinel to signal end-of-stream to the writer thread. */
    private static final byte[] POISON = new byte[0];

    private final ModConfig cfg;
    private final int srcWidth;
    private final int srcHeight;
    private final int frameByteSize;
    private final Path outputFile;

    private Process process;
    private Thread writerThread;
    private Thread stderrThread;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /**
     * Buffer pool reused between {@link FrameCapture} and the writer thread so
     * we don't allocate a fresh ~{@code width*height*4} byte[] per frame.
     */
    private final ConcurrentLinkedDeque<byte[]> bufferPool = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);

    public FFmpegEncoder(ModConfig cfg, int srcWidth, int srcHeight) {
        this.cfg = cfg;
        this.srcWidth = srcWidth;
        this.srcHeight = srcHeight;
        this.frameByteSize = srcWidth * srcHeight * 4;
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
     * Acquire a frame-sized byte[] from the pool, or allocate a fresh one if
     * the pool is empty. Ownership transfers to the caller; submitting it via
     * {@link #submitFrame(byte[])} (or returning it via
     * {@link #releaseBuffer(byte[])}) hands ownership back.
     */
    public byte[] acquireBuffer() {
        byte[] buf = bufferPool.pollFirst();
        if (buf == null || buf.length != frameByteSize) {
            return new byte[frameByteSize];
        }
        return buf;
    }

    /** Return a buffer to the pool. No-op if it doesn't fit (e.g. resized). */
    public void releaseBuffer(byte[] buf) {
        if (buf == null || buf == POISON) return;
        if (buf.length != frameByteSize) return;
        if (bufferPool.size() < POOL_LIMIT) {
            bufferPool.offerFirst(buf);
        }
    }

    /**
     * Submit a frame for encoding. Non-blocking: drops the frame and increments
     * the drop counter if the queue is full (encoder can't keep up). Dropped
     * buffers are recycled back into the pool.
     */
    public void submitFrame(byte[] rgbaFrame) {
        if (!running.get()) {
            releaseBuffer(rgbaFrame);
            return;
        }
        if (!queue.offer(rgbaFrame)) {
            droppedFrames.incrementAndGet();
            releaseBuffer(rgbaFrame);
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

        bufferPool.clear();

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
                releaseBuffer(frame);
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
                String lower = line.toLowerCase(Locale.ROOT);
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
        // No input -r: frames arrive at variable real-world rates (whatever
        // the game manages to render). Wall-clock timestamps preserve the real
        // capture cadence so playback isn't sped up if we miss the target fps.
        cmd.add("-f"); cmd.add("rawvideo");
        cmd.add("-pix_fmt"); cmd.add("rgba");
        cmd.add("-s"); cmd.add(srcWidth + "x" + srcHeight);
        cmd.add("-thread_queue_size"); cmd.add("512");
        cmd.add("-use_wallclock_as_timestamps"); cmd.add("1");
        cmd.add("-i"); cmd.add("-");

        // ---- Filter chain (only when we actually need to scale) ----
        // bicubic is a great speed/quality compromise for live recording;
        // lanczos costs noticeably more CPU.
        if (srcWidth != cfg.width || srcHeight != cfg.height) {
            cmd.add("-vf");
            cmd.add("scale=" + cfg.width + ":" + cfg.height + ":flags=bicubic");
        }

        // ---- Output framerate: lock to cfg.fps with frame duplication ----
        // Combined with wall-clock input timestamps, this produces a CFR file
        // that plays at real time, duplicating frames when capture lags.
        cmd.add("-r"); cmd.add(String.valueOf(cfg.fps));
        cmd.add("-vsync"); cmd.add("cfr");

        // ---- Encoder-specific args ----
        cmd.add("-c:v"); cmd.add(cfg.videoCodec);
        applyEncoderArgs(cmd);

        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-movflags"); cmd.add("+faststart");

        cmd.add(outputFile.toString());
        return cmd;
    }

    /**
     * Tune args per-encoder so we don't waste CPU/GPU on unhelpful presets.
     * Each branch sets sensible rate-control + preset for that backend.
     */
    private void applyEncoderArgs(List<String> cmd) {
        String codec = cfg.videoCodec.toLowerCase(Locale.ROOT);
        int bitrate = cfg.bitrateKbps;
        if (codec.equals("libx264") || codec.equals("libx265")) {
            // CPU x264/x265: zerolatency disables b-frames + lookahead, which
            // is exactly what a real-time recorder wants.
            cmd.add("-preset"); cmd.add(cfg.preset);
            cmd.add("-tune"); cmd.add("zerolatency");
            cmd.add("-b:v"); cmd.add(bitrate + "k");
            cmd.add("-maxrate"); cmd.add(bitrate + "k");
            cmd.add("-bufsize"); cmd.add((bitrate * 2) + "k");
        } else if (codec.endsWith("_nvenc")) {
            // NVIDIA NVENC: p1 (fastest) -> p7 (best quality). p5 is balanced.
            cmd.add("-preset"); cmd.add("p5");
            cmd.add("-tune"); cmd.add("hq");
            cmd.add("-rc"); cmd.add("vbr");
            cmd.add("-cq"); cmd.add("23");
            cmd.add("-b:v"); cmd.add(bitrate + "k");
            cmd.add("-maxrate"); cmd.add((bitrate * 2) + "k");
        } else if (codec.endsWith("_amf")) {
            // AMD AMF: 'speed' quality preset is the right pick for live encode.
            cmd.add("-quality"); cmd.add("speed");
            cmd.add("-rc"); cmd.add("vbr_peak");
            cmd.add("-b:v"); cmd.add(bitrate + "k");
            cmd.add("-maxrate"); cmd.add((bitrate * 2) + "k");
        } else if (codec.endsWith("_qsv")) {
            // Intel QuickSync.
            cmd.add("-preset"); cmd.add("veryfast");
            cmd.add("-b:v"); cmd.add(bitrate + "k");
            cmd.add("-maxrate"); cmd.add((bitrate * 2) + "k");
        } else if (codec.endsWith("_videotoolbox")) {
            // Apple VideoToolbox.
            cmd.add("-realtime"); cmd.add("1");
            cmd.add("-b:v"); cmd.add(bitrate + "k");
        } else {
            // Unknown encoder: pass user-configured preset and bitrate, hope
            // for the best.
            cmd.add("-preset"); cmd.add(cfg.preset);
            cmd.add("-b:v"); cmd.add(bitrate + "k");
        }
    }

    private Path computeOutputPath() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String filename = "back-on-track_" + stamp + "." + cfg.container;
        return cfg.outputDirAbsolute().resolve(filename);
    }
}
