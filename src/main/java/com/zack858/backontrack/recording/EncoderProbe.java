package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes the configured FFmpeg binary for available H.264 encoders and
 * returns them ranked by hardware-acceleration tier. This is what powers
 * {@code videoCodec = "auto"} in the config: at startup we ask FFmpeg what
 * it can actually do, then pick the fastest encoder available on this
 * machine.
 *
 * <p>This mirrors Flashback's encoder discovery in spirit -- prefer hardware,
 * fall back to software -- but we do it via the FFmpeg CLI rather than the
 * JavaCPP bindings since we're a much smaller mod.</p>
 *
 * <h2>Priority order</h2>
 * <ol>
 *   <li>{@code h264_mediacodec} - Android hardware (this is the right pick on
 *       Zalith / PojavLauncher when the FFmpeg build supports it)</li>
 *   <li>{@code h264_v4l2m2m} - ARM Linux (Pi, some SBCs)</li>
 *   <li>{@code h264_nvenc} - NVIDIA</li>
 *   <li>{@code h264_amf} - AMD</li>
 *   <li>{@code h264_qsv} - Intel QuickSync</li>
 *   <li>{@code h264_videotoolbox} - macOS</li>
 *   <li>{@code h264_vaapi} - Linux VA-API (mostly Intel/AMD)</li>
 *   <li>{@code libx264} - software fallback, always available in any modern
 *       FFmpeg build</li>
 * </ol>
 */
public final class EncoderProbe {
    /** H.264 encoders ordered by preference, highest priority first. */
    private static final List<String> H264_PRIORITY = List.of(
            "h264_mediacodec",
            "h264_v4l2m2m",
            "h264_nvenc",
            "h264_amf",
            "h264_qsv",
            "h264_videotoolbox",
            "h264_vaapi",
            "libx264"
    );

    /** Matches video encoder lines in {@code ffmpeg -encoders} output. */
    private static final Pattern ENCODER_LINE =
            Pattern.compile("^\\s*V[FSXBD\\.]+\\s+(\\S+)");

    /** Cached probe result; cleared via {@link #reset()}. */
    private static volatile List<String> cachedRanked;
    /** Cached set of all known video encoders, for explicit-name validation. */
    private static volatile Set<String> cachedAvailable;

    private EncoderProbe() {}

    /**
     * Run the probe (if not already cached) and return the H.264 encoders
     * available on this machine, in priority order. The first entry is the
     * "best" pick. Always includes {@code libx264} as a guaranteed last
     * resort even if the probe failed.
     */
    public static synchronized List<String> rankedH264(String ffmpegPath) {
        if (cachedRanked != null) return cachedRanked;
        Set<String> found = probe(ffmpegPath);
        cachedAvailable = found;

        List<String> ranked = new ArrayList<>();
        for (String name : H264_PRIORITY) {
            if (found.contains(name)) ranked.add(name);
        }
        if (ranked.isEmpty()) {
            // Probe failed (no ffmpeg, parse error, etc.). Optimistically
            // assume libx264 is there; if it isn't, FFmpegEncoder.start()
            // will surface the real error to the user via the toast handler.
            BackOnTrack.LOGGER.warn(
                    "FFmpeg encoder probe returned no H.264 encoders; assuming libx264.");
            ranked.add("libx264");
        }
        cachedRanked = List.copyOf(ranked);
        BackOnTrack.LOGGER.info("Available H.264 encoders (in preference order): {}", cachedRanked);
        return cachedRanked;
    }

    /**
     * Resolve a possibly-{@code "auto"} encoder name into a concrete encoder.
     * Explicit names (e.g. {@code h264_nvenc}) are returned unchanged so the
     * user can always force a specific encoder.
     */
    public static String resolve(String configured, String ffmpegPath) {
        if (configured == null || configured.isBlank()) return "libx264";
        String lower = configured.toLowerCase(Locale.ROOT);
        if (!lower.equals("auto") && !lower.equals("default")) {
            return configured; // user knows what they want
        }
        return rankedH264(ffmpegPath).get(0);
    }

    /** Forget cached probe results so the next call re-runs the probe. */
    public static synchronized void reset() {
        cachedRanked = null;
        cachedAvailable = null;
    }

    private static Set<String> probe(String ffmpegPath) {
        Set<String> found = new HashSet<>();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-hide_banner", "-encoders");
            pb.redirectErrorStream(true);
            p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = ENCODER_LINE.matcher(line);
                    if (m.find()) found.add(m.group(1));
                }
            }
            // Don't block forever on a wedged ffmpeg.
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                BackOnTrack.LOGGER.warn("FFmpeg encoder probe timed out; killing.");
                p.destroyForcibly();
            }
        } catch (Exception e) {
            BackOnTrack.LOGGER.warn("FFmpeg encoder probe failed ({}): {}",
                    ffmpegPath, e.getMessage());
            if (p != null) p.destroyForcibly();
        }
        return found;
    }
}
