package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;

import java.io.File;
import java.util.Locale;

/**
 * Resolves where the FFmpeg binary lives on this device. Most of the
 * complexity is for Android where the binary is delivered as an APK plugin
 * (the Pojav FFmpeg Plugin, package {@code net.kdt.pojavlaunch.ffmpeg}).
 *
 * <h2>Lookup order</h2>
 * <ol>
 *   <li><b>User config override</b> -- if the configured path is anything
 *       other than the literal string {@code "ffmpeg"}, trust it.</li>
 *   <li><b>{@code POJAV_FFMPEG_PATH} env var</b> -- set by PojavLauncher /
 *       Zalith on JVM startup. Always points at the plugin's executable.</li>
 *   <li><b>Plugin install paths</b> -- direct probe of
 *       {@code /data/data/net.kdt.pojavlaunch.ffmpeg/lib/<arch>/libffmpeg.so}
 *       across all four Android ABIs. Lets the mod work even if the JNI exec
 *       hook isn't installed (e.g. headless JVM, future launcher changes).</li>
 *   <li><b>Falls through to {@code "ffmpeg"}</b> -- relies on PATH on desktop;
 *       on Pojav-derived launchers the JNI hook will rewrite it transparently.</li>
 * </ol>
 *
 * <p>Result is cached for the JVM lifetime; call {@link #reset()} to re-probe.</p>
 */
public final class FFmpegLocator {
    /** Pojav's plugin package, never changes. */
    private static final String POJAV_FFMPEG_PACKAGE = "net.kdt.pojavlaunch.ffmpeg";
    /** Env var Pojav sets to the resolved plugin executable. */
    private static final String POJAV_ENV = "POJAV_FFMPEG_PATH";
    /** Filename Android requires for executable .so libs. */
    private static final String LIB_NAME = "libffmpeg.so";

    private static volatile String cachedResolved;
    private static volatile String cachedSource;

    private FFmpegLocator() {}

    /**
     * Resolve {@code configured} into an actual executable path. Returns the
     * configured value as-is if it's not the default string {@code "ffmpeg"};
     * otherwise walks the lookup chain above.
     */
    public static String resolve(String configured) {
        // Per-JVM cache: re-doing the filesystem probe on every record start
        // would be wasteful.
        String cached = cachedResolved;
        if (cached != null && (configured == null
                || configured.equalsIgnoreCase("ffmpeg"))) {
            return cached;
        }

        // 1) User override -- any explicit path wins.
        if (configured != null && !configured.isBlank()
                && !configured.equalsIgnoreCase("ffmpeg")) {
            log("config", configured);
            return configured;
        }

        // 2) Pojav env var -- canonical on Pojav/Zalith.
        String env = System.getenv(POJAV_ENV);
        if (env != null && !env.isBlank() && new File(env).exists()) {
            return cache("env:" + POJAV_ENV, env);
        }

        // 3) Plugin install dir, probed for whichever ABI the JVM picked.
        if (PlatformDetect.isAndroid()) {
            String fromPlugin = probePluginInstallDir();
            if (fromPlugin != null) {
                return cache("pojav-plugin", fromPlugin);
            }
            BackOnTrack.LOGGER.warn(
                    "Running on Android but no FFmpeg found. Install the Pojav " +
                    "FFmpeg Plugin: https://github.com/PojavLauncherTeam/FFmpegPlugin");
        }

        // 4) Trust the JNI hook (Pojav) or PATH (desktop).
        return cache("default", "ffmpeg");
    }

    /** Forget the cached resolution so the next call probes again. */
    public static synchronized void reset() {
        cachedResolved = null;
        cachedSource = null;
    }

    /** For diagnostics: which lookup tier produced the cached result. */
    public static String resolutionSource() {
        return cachedSource != null ? cachedSource : "uncached";
    }

    // ---- internals ----

    /**
     * Look at every ABI directory under the plugin's install path and return
     * the first one that actually contains the executable. Android namespaces
     * native libs by ABI, so the right path is e.g.
     * {@code /data/data/net.kdt.pojavlaunch.ffmpeg/lib/arm64-v8a/libffmpeg.so}.
     */
    private static String probePluginInstallDir() {
        // /data/data/<pkg> is the canonical install root on Android. Some
        // launchers run under /data/user/0/<pkg> (multi-user); we try both.
        String[] dataRoots = {
                "/data/data/" + POJAV_FFMPEG_PACKAGE + "/lib",
                "/data/user/0/" + POJAV_FFMPEG_PACKAGE + "/lib"
        };
        // ABI ordering matches the order Android prefers on a 64-bit device,
        // so we hit the right one first on most modern phones.
        String[] abis = { "arm64-v8a", "armeabi-v7a", "x86_64", "x86" };
        for (String root : dataRoots) {
            for (String abi : abis) {
                File f = new File(root + "/" + abi, LIB_NAME);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        // Last-ditch: scan whatever's actually there in case the ABI naming
        // scheme drifts in a future release.
        for (String root : dataRoots) {
            File rootDir = new File(root);
            File[] children = rootDir.listFiles();
            if (children == null) continue;
            for (File abiDir : children) {
                File f = new File(abiDir, LIB_NAME);
                if (f.exists() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static synchronized String cache(String source, String path) {
        cachedResolved = path;
        cachedSource = source;
        log(source, path);
        return path;
    }

    private static void log(String source, String path) {
        BackOnTrack.LOGGER.info("FFmpeg resolved via [{}]: {}",
                source, path.toLowerCase(Locale.ROOT).contains(LIB_NAME)
                        ? path + " (Pojav FFmpeg Plugin)"
                        : path);
    }
}
