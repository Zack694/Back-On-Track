package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Reads the final composited frame back from the GPU using Minecraft's
 * supported {@link ScreenshotRecorder} API and forwards it to the
 * {@link RecordingManager}. Because the screenshot recorder taps the
 * post-GUI framebuffer, the captured stream includes the HUD and any open
 * screens -- a true raw recording, mirroring Flashback's default.
 *
 * The pixel readback itself is performed asynchronously by the GPU command
 * encoder; the consumer below runs once the data has been transferred back
 * to system memory.
 */
public final class FrameCapture {
    private FrameCapture() {}

    public static void captureIfRecording() {
        RecordingManager manager = RecordingManager.get();
        if (!manager.shouldCaptureNow()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        Framebuffer fb = client.getFramebuffer();
        if (fb == null) return;
        if (fb.textureWidth <= 0 || fb.textureHeight <= 0) return;

        try {
            ScreenshotRecorder.takeScreenshot(fb, image -> handleImage(manager, image));
        } catch (Throwable t) {
            BackOnTrack.LOGGER.error("FrameCapture submit failed; stopping recording", t);
            try { manager.stop(client); } catch (Throwable ignored) {}
        }
    }

    private static void handleImage(RecordingManager manager, NativeImage image) {
        if (image == null) return;
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            // copyPixelsAbgr returns each pixel as an int laid out as ABGR in
            // memory (little-endian: low byte = R). When written to a byte
            // buffer in little-endian order this is exactly the RGBA byte
            // sequence FFmpeg expects.
            int[] argbPixels = image.copyPixelsAbgr();

            byte[] rgba = new byte[w * h * 4];
            ByteBuffer bb = ByteBuffer.wrap(rgba).order(ByteOrder.LITTLE_ENDIAN);
            IntBuffer ib = bb.asIntBuffer();
            ib.put(argbPixels);

            manager.submitFrame(w, h, rgba);
        } catch (Throwable t) {
            BackOnTrack.LOGGER.error("Failed to convert captured frame", t);
        } finally {
            try { image.close(); } catch (Throwable ignored) {}
        }
    }
}
