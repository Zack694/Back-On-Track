package com.zack858.backontrack.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.zack858.backontrack.BackOnTrack;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON-backed user config. Values borrow defaults from Flashback's recommended
 * settings (720p / 60fps / libx264 / veryfast) while remaining tunable by the user.
 *
 * Lookup is via the static {@link #get()} singleton; mutations should be
 * followed by {@link #save()} to persist.
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
    /** ffmpeg encoder name (libx264, libx265, h264_nvenc, hevc_nvenc, h264_qsv...). */
    public String videoCodec = "libx264";
    /** ffmpeg preset (ultrafast, superfast, veryfast, faster, fast, medium, slow). */
    public String preset = "veryfast";
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
            INSTANCE = new ModConfig();
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
}
