package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes the configured FFmpeg binary for available H.264 encoders and
 * returns them ranked by hardware-acceleration tier. Powers
 * {@code videoCodec = "auto"} in the config.
 *
 * <p>Mirrors Flashback's discovery in spirit -- prefer hardware, fall back
 * to software -- but uses the FFmpeg CLI rather than JavaCPP bindings.</p>
 *
 * <h2>Priority order</h2>
 * <ol>
 *   <li>{@code h264_mediacodec} - Android hardware (Zalith/PojavLauncher when
 *       the FFmpeg build supports it; the stock Pojav plugin currently does
 *       not, so this falls through to libx264 there)</li>
 *   <li>{@code h264_v4l2m2m} - ARM Linux SBCs</li>
 *   <li>{@code h264_nvenc} - NVIDIA</li>
 *   <li>{@code h264_amf} - AMD</li>
 *   <li>{@code h264_qsv} - Intel QuickSync</li>
 *   <li>{@code h264_videotoolbox} - macOS</li>
 *   <li>{@code h264_vaapi} - Linux VA-API</li>
 *   <li>{@code libx264} - software fallback</li>
 * </ol>
 */
public final class EncoderProbe {
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

    private static final Pattern ENCODER_LINE =
            Pattern.compile("^\\s*V[FSXBD\\.]+\\s+(\\S+)");

    private static volatile List<String> cachedRanked;

    private EncoderProbe() {}

    public static synchronized List<String> rankedH264(FFmpegLocator.Resolution res) {
        if (cachedRanked != null) return cachedRanked;
        Set<String> found = probe(res);

        List<String> ranked = new ArrayList<>();
        for (String name : H264_PRIORITY) {
            if (found.contains(name)) ranked.add(name);
        }
        if (ranked.isEmpty()) {
            BackOnTrack.LOGGER.warn(
                    "FFmpeg encoder probe returned no H.264 encoders; assuming libx264.");
            ranked.add("libx264");
        }
        cachedRanked = List.copyOf(ranked);
        BackOnTrack.LOGGER.info("Available H.264 encoders (preferred order): {}", cachedRanked);
        return cachedRanked;
    }

    public static String resolve(String configured, FFmpegLocator.Resolution res) {
        if (configured == null || configured.isBlank()) return "libx264";
        String lower = configured.toLowerCase(Locale.ROOT);
        if (!lower.equals("auto") && !lower.equals("default")) {
            return configured;
        }
        return rankedH264(res).get(0);
    }

    public static synchronized void reset() { cachedRanked = null; }

    private static Set<String> probe(FFmpegLocator.Resolution res) {
        Set<String> found = new HashSet<>();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(res.exec, "-hide_banner", "-encoders");
            // Splice LD_LIBRARY_PATH for the no-hook plugin path. The probe
            // itself would otherwise crash before printing the encoder list.
            if (res.ldLibraryPath != null) {
                Map<String, String> env = pb.environment();
                String existing = env.get("LD_LIBRARY_PATH");
                env.put("LD_LIBRARY_PATH", existing != null && !existing.isEmpty()
                        ? res.ldLibraryPath + ":" + existing
                        : res.ldLibraryPath);
            }
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
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                BackOnTrack.LOGGER.warn("FFmpeg encoder probe timed out; killing.");
                p.destroyForcibly();
            }
        } catch (Exception e) {
            BackOnTrack.LOGGER.warn("FFmpeg encoder probe failed ({}): {}",
                    res.exec, e.getMessage());
            if (p != null) p.destroyForcibly();
        }
        return found;
    }
}
