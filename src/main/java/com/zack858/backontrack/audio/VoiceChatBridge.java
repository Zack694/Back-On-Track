package com.zack858.backontrack.audio;

import com.zack858.backontrack.BackOnTrack;
import com.zack858.backontrack.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Soft-dependency bridge to <a href="https://modrinth.com/mod/simple-voice-chat">Simple Voice Chat</a>.
 *
 * This class never directly references any voicechat API type, so it loads
 * even when Simple Voice Chat is absent. The actual API calls live in
 * {@link BackOnTrackVoicechatPlugin}, which is only classloaded when the
 * voicechat mod is present (via Fabric's plugin entrypoint mechanism).
 *
 * Voice samples received from other players are pushed into {@link #pushSamples}
 * by the plugin and consumed by {@link AudioCapture} via {@link #drainSamples}.
 */
public final class VoiceChatBridge {
    private static final AtomicBoolean enabled = new AtomicBoolean(false);
    /** Bounded ring of PCM-16LE byte chunks. */
    private static final BlockingQueue<byte[]> samples = new ArrayBlockingQueue<>(256);

    private VoiceChatBridge() {}

    /** Called once by the client initializer when voicechat is detected. */
    public static void tryInitialize() {
        if (!FabricLoader.getInstance().isModLoaded("voicechat")) return;
        enabled.set(true);
        BackOnTrack.LOGGER.info("Simple Voice Chat detected; voice samples will be available for recording.");
    }

    /** True if the plugin has been registered and the user wants voice captured. */
    public static boolean isAvailable() {
        return enabled.get() && ModConfig.get().captureVoiceChat;
    }

    /**
     * Called by {@link BackOnTrackVoicechatPlugin} for every incoming voice
     * frame. Converts mono short[] samples to 16-bit little-endian bytes,
     * upmixed to stereo if the recording is configured stereo.
     */
    public static void pushSamples(short[] mono, int sampleRate) {
        if (!enabled.get()) return;
        if (mono == null || mono.length == 0) return;
        ModConfig cfg = ModConfig.get();
        boolean stereo = cfg.audioChannels >= 2;
        int outShorts = stereo ? mono.length * 2 : mono.length;
        ByteBuffer buf = ByteBuffer.allocate(outShorts * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : mono) {
            buf.putShort(s);
            if (stereo) buf.putShort(s);
        }
        // Drop oldest samples if the queue is saturated; voice is transient anyway.
        if (!samples.offer(buf.array())) {
            samples.poll();
            samples.offer(buf.array());
        }
    }

    /**
     * Drain up to {@code budgetBytes} of buffered voice samples for mixing.
     * Returns null if voicechat is not available or no samples are queued.
     */
    public static byte[] drainSamples(int budgetBytes) {
        if (!isAvailable() || samples.isEmpty()) return null;
        ByteBuffer collected = ByteBuffer.allocate(budgetBytes);
        while (collected.remaining() > 0) {
            byte[] chunk = samples.poll();
            if (chunk == null) break;
            int copy = Math.min(chunk.length, collected.remaining());
            collected.put(chunk, 0, copy);
            // If we couldn't fit the whole chunk, push the remainder back.
            if (copy < chunk.length) {
                byte[] tail = new byte[chunk.length - copy];
                System.arraycopy(chunk, copy, tail, 0, tail.length);
                // best-effort; if full just drop the tail
                if (!samples.offer(tail)) {
                    samples.poll();
                    samples.offer(tail);
                }
            }
        }
        if (collected.position() == 0) return null;
        byte[] out = new byte[collected.position()];
        System.arraycopy(collected.array(), 0, out, 0, out.length);
        return out;
    }

    public static void clear() {
        samples.clear();
    }
}
