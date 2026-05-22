package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;

import java.io.File;

/**
 * Resolves where the FFmpeg binary lives on this device, with special
 * handling for Android's Pojav FFmpeg Plugin.
 *
 * <h2>Why this is non-trivial on Android</h2>
 * The plugin ships ffmpeg as {@code libffmpeg.so} inside an APK at
 * {@code /data/data/net.kdt.pojavlaunch.ffmpeg/lib/<abi>/}. Pojav-derived
 * launchers install a JNI exec hook that rewrites any
 * {@link ProcessBuilder} call where the program basename is literally
 * {@code "ffmpeg"} -- the hook substitutes the plugin path <em>and</em>
 * configures {@code LD_LIBRARY_PATH} so the plugin's bundled {@code .so}
 * dependencies (libavcodec, libx264, libssl, etc.) resolve correctly.
 *
 * <p><b>Critical:</b> if we pre-resolve to the absolute plugin path and
 * pass that to ProcessBuilder, the hook does <em>not</em> fire, no
 * {@code LD_LIBRARY_PATH} gets set, and ffmpeg crashes the moment it tries
 * to load any of its bundled deps. So we must keep the program name as
 * {@code "ffmpeg"} whenever the hook is available.</p>
 *
 * <h2>Resolution chain</h2>
 * <ol>
 *   <li><b>User override</b> -- explicit non-default path is trusted.</li>
 *   <li><b>{@code POJAV_FFMPEG_PATH} env var present</b> -- the JNI hook is
 *       wired up. Return the literal {@code "ffmpeg"} so the hook can do
 *       its work.</li>
 *   <li><b>Plugin installed but no hook</b> -- return the absolute path,
 *       and a {@link Resolution#ldLibraryPath} for the caller to splice
 *       into the child process environment.</li>
 *   <li><b>Default</b> -- {@code "ffmpeg"} via PATH.</li>
 * </ol>
 */
public final class FFmpegLocator {
    private static final String POJAV_FFMPEG_PACKAGE = "net.kdt.pojavlaunch.ffmpeg";
    private static final String POJAV_ENV = "POJAV_FFMPEG_PATH";
    private static final String LIB_NAME = "libffmpeg.so";

    private static volatile Resolution cached;

    private FFmpegLocator() {}

    /**
     * Result of {@link #resolve(String)}. {@link #exec} is what to pass to
     * {@link ProcessBuilder}; {@link #ldLibraryPath} is non-null only when
     * the caller must splice it into the child env (the no-JNI-hook path).
     */
    public static final class Resolution {
        public final String exec;
        public final String ldLibraryPath;
        public final String source;

        Resolution(String exec, String ldLibraryPath, String source) {
            this.exec = exec;
            this.ldLibraryPath = ldLibraryPath;
            this.source = source;
        }
    }

    public static Resolution resolve(String configured) {
        Resolution c = cached;
        boolean defaultRequested = configured == null
                || configured.isBlank()
                || configured.equalsIgnoreCase("ffmpeg");
        if (c != null && defaultRequested) return c;

        // 1) User override -- any explicit path wins.
        if (!defaultRequested) {
            Resolution r = new Resolution(configured, null, "config");
            BackOnTrack.LOGGER.info("FFmpeg [{}]: {}", r.source, r.exec);
            return r;
        }

        // 2) Pojav JNI hook is wired up (env var is the smoking gun).
        // Use the literal "ffmpeg" so the hook fires and sets
        // LD_LIBRARY_PATH for the plugin's bundled deps.
        String env = System.getenv(POJAV_ENV);
        if (env != null && !env.isBlank() && new File(env).exists()) {
            return cache(new Resolution("ffmpeg", null, "pojav-hook"),
                    "Pojav JNI hook detected (POJAV_FFMPEG_PATH=" + env + ")");
        }

        // 3) Plugin installed but no hook -- invoke directly + set LD path.
        if (PlatformDetect.isAndroid()) {
            String absPath = probePluginInstallDir();
            if (absPath != null) {
                String libDir = new File(absPath).getParent();
                return cache(new Resolution(absPath, libDir, "pojav-plugin-direct"),
                        "Pojav FFmpeg Plugin found; LD_LIBRARY_PATH=" + libDir);
            }
            BackOnTrack.LOGGER.warn(
                    "Running on Android but no FFmpeg found. Install the Pojav " +
                    "FFmpeg Plugin: https://github.com/PojavLauncherTeam/FFmpegPlugin");
        }

        // 4) Default: trust PATH.
        return cache(new Resolution("ffmpeg", null, "default"),
                "using 'ffmpeg' from PATH");
    }

    public static synchronized void reset() { cached = null; }

    public static String resolutionSource() {
        Resolution c = cached;
        return c != null ? c.source : "uncached";
    }

    private static String probePluginInstallDir() {
        String[] dataRoots = {
                "/data/data/" + POJAV_FFMPEG_PACKAGE + "/lib",
                "/data/user/0/" + POJAV_FFMPEG_PACKAGE + "/lib"
        };
        String[] abis = { "arm64-v8a", "armeabi-v7a", "x86_64", "x86" };
        for (String root : dataRoots) {
            for (String abi : abis) {
                File f = new File(root + "/" + abi, LIB_NAME);
                if (f.exists() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        // Last-ditch: scan whatever's there in case ABI naming drifts.
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

    private static synchronized Resolution cache(Resolution r, String detail) {
        cached = r;
        BackOnTrack.LOGGER.info("FFmpeg [{}]: {} ({})", r.source, r.exec, detail);
        return r;
    }
}
