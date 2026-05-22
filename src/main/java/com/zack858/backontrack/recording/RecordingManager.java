package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;
import com.zack858.backontrack.audio.AudioCapture;
import com.zack858.backontrack.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton coordinator for an active recording. Owns the FFmpeg encoder,
 * the audio capture, and the state machine. Render-thread integration is
 * via {@link #captureFrameIfNeeded(int, int, byte[])}, called from
 * {@code MixinGameRenderer} after each frame is presented.
 */
public final class RecordingManager {
    private static final RecordingManager INSTANCE = new RecordingManager();
    public static RecordingManager get() { return INSTANCE; }

    private static final SystemToast.Type TOAST_TYPE =
            new SystemToast.Type(5000L);

    private final AtomicReference<RecordingState> state =
            new AtomicReference<>(RecordingState.IDLE);

    private FFmpegEncoder encoder;
    private AudioCapture audioCapture;
    private long startNanos;
    private long lastCaptureNanos;
    private long captureIntervalNanos;
    private int srcWidth;
    private int srcHeight;

    private RecordingManager() {}

    public RecordingState getState() { return state.get(); }
    public boolean isRecording() { return state.get() == RecordingState.RECORDING; }
    public boolean isIdle() { return state.get() == RecordingState.IDLE; }

    public long getRecordingNanos() {
        return state.get() == RecordingState.RECORDING
                ? System.nanoTime() - startNanos
                : 0L;
    }

    public long getFrameCount() {
        return encoder != null ? encoder.getFrameCount() : 0L;
    }

    /** Toggle recording. Safe from the client tick thread. */
    public void toggle(MinecraftClient client) {
        if (isRecording()) stop(client);
        else if (isIdle()) start(client);
    }

    /** Begin a new recording. No-op if already running. */
    public synchronized void start(MinecraftClient client) {
        if (!state.compareAndSet(RecordingState.IDLE, RecordingState.RECORDING)) {
            return;
        }
        ModConfig cfg = ModConfig.get();
        try {
            // Use the actual window framebuffer size for the input stream;
            // FFmpeg will rescale to cfg.width/height in the filter chain.
            srcWidth = Math.max(2, client.getWindow().getFramebufferWidth());
            srcHeight = Math.max(2, client.getWindow().getFramebufferHeight());

            encoder = new FFmpegEncoder(cfg, srcWidth, srcHeight);
            encoder.start();

            if (cfg.captureAudio) {
                audioCapture = new AudioCapture(cfg, encoder.getOutputFile());
                audioCapture.start();
            }

            captureIntervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, cfg.fps);
            startNanos = System.nanoTime();
            lastCaptureNanos = 0L; // first frame triggers immediately

            toast(client, Text.translatable("back_on_track.toast.start.title"),
                    Text.translatable("back_on_track.toast.start.body"));
        } catch (IOException e) {
            state.set(RecordingState.IDLE);
            BackOnTrack.LOGGER.error("Failed to start recording", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            boolean ffmpegMissing = msg != null
                    && (msg.contains("Cannot run program") || msg.contains("No such file"));
            toast(client,
                    Text.translatable("back_on_track.toast.error.title"),
                    ffmpegMissing
                            ? Text.translatable("back_on_track.toast.error.ffmpeg")
                            : Text.translatable("back_on_track.toast.error.generic", msg));
            cleanupAfterFailure();
        } catch (RuntimeException e) {
            state.set(RecordingState.IDLE);
            BackOnTrack.LOGGER.error("Failed to start recording", e);
            toast(client,
                    Text.translatable("back_on_track.toast.error.title"),
                    Text.translatable("back_on_track.toast.error.generic",
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            cleanupAfterFailure();
        }
    }

    /** Stop the active recording. Finalisation happens on a worker thread. */
    public synchronized void stop(MinecraftClient client) {
        if (!state.compareAndSet(RecordingState.RECORDING, RecordingState.STOPPING)) {
            return;
        }
        final FFmpegEncoder enc = this.encoder;
        final AudioCapture audio = this.audioCapture;
        this.encoder = null;
        this.audioCapture = null;

        // Run the (potentially slow) shutdown off the render thread.
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BackOnTrack-Finalize");
            t.setDaemon(true);
            return t;
        });
        exec.execute(() -> {
            try {
                Path videoPath = enc != null ? enc.stop() : null;
                Path audioPath = audio != null ? audio.stop() : null;

                Path finalPath = videoPath;
                if (videoPath != null && audioPath != null) {
                    Path muxed = AudioMuxer.mux(videoPath, audioPath, ModConfig.get());
                    if (muxed != null) finalPath = muxed;
                }
                final Path notify = finalPath;
                client.execute(() -> toast(client,
                        Text.translatable("back_on_track.toast.stop.title"),
                        Text.translatable("back_on_track.toast.stop.body",
                                notify != null ? notify.getFileName().toString() : "?")));
            } catch (Exception e) {
                BackOnTrack.LOGGER.error("Failed to finalise recording", e);
                client.execute(() -> toast(client,
                        Text.translatable("back_on_track.toast.error.title"),
                        Text.translatable("back_on_track.toast.error.generic",
                                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            } finally {
                state.set(RecordingState.IDLE);
                exec.shutdown();
            }
        });
    }

    /**
     * Called by MixinGameRenderer right after a frame is rendered. The
     * mixin is responsible for actually reading the GL framebuffer and
     * passing the bytes here; this method handles FPS throttling and
     * dispatching to the encoder.
     *
     * @return true if the caller should perform the (expensive) glReadPixels;
     *         false to skip this frame.
     */
    public boolean shouldCaptureNow() {
        if (!isRecording()) return false;
        long now = System.nanoTime();
        if (now - lastCaptureNanos < captureIntervalNanos) return false;
        lastCaptureNanos = now;
        return true;
    }

    /** Called from the render thread after a successful glReadPixels. */
    public void submitFrame(int width, int height, byte[] rgba) {
        if (!isRecording() || encoder == null) return;
        if (width != srcWidth || height != srcHeight) {
            // Window was resized mid-recording. We could restart the encoder,
            // but for v1 we just stop to avoid producing a corrupted file.
            BackOnTrack.LOGGER.info("Window resized during recording ({}x{} -> {}x{}); stopping.",
                    srcWidth, srcHeight, width, height);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.execute(() -> stop(mc));
            return;
        }
        encoder.submitFrame(rgba);
    }

    public int getSrcWidth() { return srcWidth; }
    public int getSrcHeight() { return srcHeight; }

    /** Called when the game client is shutting down. */
    public void shutdown() {
        if (state.get() == RecordingState.RECORDING) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) stop(mc);
        }
    }

    private void cleanupAfterFailure() {
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (audioCapture != null) {
            try { audioCapture.stop(); } catch (Exception ignored) {}
            audioCapture = null;
        }
    }

    private static void toast(MinecraftClient client, Text title, Text body) {
        if (client == null || client.getToastManager() == null) return;
        client.getToastManager().add(SystemToast.create(client, TOAST_TYPE, title, body));
    }
}
