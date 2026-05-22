package com.zack858.backontrack.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.zack858.backontrack.BackOnTrack;
import com.zack858.backontrack.recording.PlatformDetect;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON-backed user config. Defaults are tuned per-platform on first launch:
 * a desktop gets 1280x720@60fps with a hardware encoder if available; a
 * phone (Zalith / PojavLauncher) gets 1280x720@30fps with libx264 ultrafast
 * since the CPU has to share with the game.
 *
 * <p>Lookup is via the static {@link #get()} singleton; mutations should be
 * followed by {@link #save()} to persist.</p>
 */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "back-on-track.json";
    private static ModConfig INSTANCE;

    // ---- Video ----
    public int width = 1280;
    public int height = 720;
    public int fps = 60;
    public int bitrateKbps = 8000;
    /**
     * FFmpeg encoder name, or {@code "auto"} to let the mod pick the best
     * one available on this machine via {@link
     * com.zack858.backontrack.recording.EncoderProbe}.
     *
     * <p>Auto-selection prefers (in order):
     * <ol>
     *   <li>{@code h264_mediacodec} - Android hardware encoder (Zalith/PojavLauncher)</li>
     *   <li>{@code h264_v4l2m2m} - ARM Linux SBCs</li>
     *   <li>{@code h264_nvenc} - NVIDIA</li>
     *   <li>{@code h264_amf} - AMD</li>
     *   <li>{@code h264_qsv} - Intel QuickSync</li>
     *   <li>{@code h264_videotoolbox} - macOS</li>
     *   <li>{@code h264_vaapi} - Linux VA-API</li>
     *   <li>{@code libx264} - software fallback</li>
     * </ol></p>
     *
     * <p>Set this to a specific encoder name to override auto-selection
     * (useful if the auto pick misbehaves on your system).</p>
     */
    public String videoCodec = "auto";
    /**
     * Encoder preset. Meaning depends on the encoder:
     * <ul>
     *   <li>libx264/libx265: {@code ultrafast, superfast, veryfast, faster, fast, medium, slow}.
     *       For real-time recording use {@code veryfast} or {@code ultrafast}.</li>
     *   <li>Hardware encoders (NVENC, AMF, QSV, VideoToolbox, MediaCodec):
     *       ignored - encoder-specific defaults are applied in
     *       {@link com.zack858.backontrack.recording.FFmpegEncoder}.</li>
     * </ul>
     */
    public String preset = "veryfast";
    /**
     * Use CRF (Constant Rate Factor) quality control for software encoders.
     * CRF gives predictable visual quality regardless of motion complexity,
     * with a hard bitrate cap from {@link #bitrateKbps} to prevent files
     * blowing up. Only applies to libx264/libx265; hardware encoders use
     * their own rate-control modes.
     */
    public boolean useCrf = true;
    /**
     * CRF value (0-51, lower = better). Sensible range:
     * <ul>
     *   <li>{@code 18} - visually lossless, big files</li>
     *   <li>{@code 23} - "good quality" default</li>
     *   <li>{@code 28} - smaller files, visible compression</li>
     * </ul>
     * Also reused as the {@code -cq} value for NVENC.
     */
    public int crf = 23;
    /** Output container: mp4, mkv, mov. */
    public String container = "mp4";
    /** Capture HUD/screens (Flashback-style raw recording). */
    public boolean captureGui = true;

    // ---- Audio ----
    public boolean captureAudio = true;
    public boolean captureVoiceChat = true;
    public int audioSampleRate = 48000;
    public int audioChannels = 2;
    public int audioBitrateKbps = 192;

    // ---- I/O ----
    /** Either "ffmpeg" (use PATH) or absolute path to a binary. */
    public String ffmpegPath = "ffmpeg";
    /** Output directory relative to the game directory. */
    public String outputDir = "recordings";

    // ---- HUD ----
    public boolean showHud = true;

    public static ModConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
        }
        return INSTANCE;
    }

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            // First run: pick defaults that match the host's performance
            // envelope, then write the file so the user can edit it.
            INSTANCE = new ModConfig();
            INSTANCE.applyPlatformDefaults();
            save();
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            ModConfig parsed = GSON.fromJson(json, ModConfig.class);
            INSTANCE = parsed != null ? parsed : new ModConfig();
        } catch (IOException | JsonSyntaxException e) {
            BackOnTrack.LOGGER.warn("Failed to read config, using defaults: {}", e.getMessage());
            INSTANCE = new ModConfig();
            INSTANCE.applyPlatformDefaults();
        }
    }

    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BackOnTrack.LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Resolved absolute path of the output directory. */
    public Path outputDirAbsolute() {
        Path base = FabricLoader.getInstance().getGameDir().resolve(outputDir);
        return base.toAbsolutePath().normalize();
    }

    /**
     * Adjust defaults for the host platform's performance envelope. Only
     * called when no config file exists yet -- never overrides user values.
     */
    private void applyPlatformDefaults() {
        if (PlatformDetect.isAndroid()) {
            // Mobile / Snapdragon-class: 1080p60 with a software encoder is
            // not realistic. Drop to 720p30 ultrafast, smaller bitrate.
            // If the bundled FFmpeg has h264_mediacodec, EncoderProbe will
            // pick it automatically and you'll get hardware encoding for
            // free; the software-fallback path is the worst-case here.
            this.width = 1280;
            this.height = 720;
            this.fps = 30;
            this.bitrateKbps = 4000;
            this.preset = "ultrafast";
            BackOnTrack.LOGGER.info("Applied mobile defaults: {}x{}@{}fps {} kbps (preset={})",
                    width, height, fps, bitrateKbps, preset);
        } else {
            BackOnTrack.LOGGER.info("Applied desktop defaults: {}x{}@{}fps {} kbps (preset={})",
                    width, height, fps, bitrateKbps, preset);
        }
    }
}
