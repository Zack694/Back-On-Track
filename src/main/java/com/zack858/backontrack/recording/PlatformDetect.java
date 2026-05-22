package com.zack858.backontrack.recording;

/**
 * Lightweight runtime platform detection. Used to pick sensible defaults for
 * the wildly different performance envelopes we have to support: a desktop
 * with a GPU encoder vs. a phone running Minecraft via Zalith / PojavLauncher.
 *
 * <p>Detection is reflective so this class is safe to load on any JVM
 * (a regular desktop JVM does not ship the {@code android.os.Build} class).</p>
 */
public final class PlatformDetect {
    private static final boolean ANDROID = detectAndroid();
    private static final int CPU_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());

    private PlatformDetect() {}

    /**
     * @return true when the JVM is running on Android (e.g. Zalith Launcher
     *         or PojavLauncher). On normal desktop JVMs this is false.
     */
    public static boolean isAndroid() { return ANDROID; }

    /**
     * Number of logical CPUs reported by the JVM. Used to size encoder thread
     * pools so libx264 doesn't take every core away from the game.
     */
    public static int cpuCount() { return CPU_COUNT; }

    /**
     * Recommended thread count for software video encoders: leave one core
     * for the render thread and one for the OS / FFmpeg I/O. Floors at 1.
     */
    public static int recommendedEncoderThreads() {
        return Math.max(1, CPU_COUNT - 2);
    }

    private static boolean detectAndroid() {
        try {
            // Loading android.os.Build via reflection avoids a hard dep that
            // would crash the desktop JVM at link time.
            Class.forName("android.os.Build");
            return true;
        } catch (Throwable ignored) {
            // Some launchers stub out android.* classes; fall back to a
            // properties check.
            String runtime = System.getProperty("java.runtime.name", "");
            String vendor = System.getProperty("java.vendor", "");
            return runtime.toLowerCase().contains("android")
                    || vendor.toLowerCase().contains("android");
        }
    }
}
