package com.zack858.backontrack.audio;

import com.zack858.backontrack.BackOnTrack;
import com.zack858.backontrack.config.ModConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures system audio to a WAV file alongside a video recording.
 *
 * Uses Java Sound's TargetDataLine API. Because Minecraft outputs through
 * OpenAL (not the JVM's audio subsystem), capturing requires a system
 * "loopback" / "monitor" / "Stereo Mix" device. We auto-detect a sensible
 * one; if none is available the recording proceeds video-only and a warning
 * is logged. Users can install platform-specific loopback drivers
 * (e.g. VB-CABLE on Windows, loopback module on PulseAudio) for reliable capture.
 *
 * The file is a standard 16-bit PCM WAV that {@link com.zack858.backontrack.recording.AudioMuxer}
 * later muxes into the final video container.
 */
public final class AudioCapture {
    private static final String[] LOOPBACK_HINTS = {
            "stereo mix", "loopback", "monitor of", "what u hear", "wave out mix"
    };

    private final ModConfig cfg;
    private final Path wavPath;
    private final AudioFormat format;

    private TargetDataLine line;
    private Thread captureThread;
    private RandomAccessFile raf;
    private OutputStream out;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private long bytesWritten;

    public AudioCapture(ModConfig cfg, Path videoPath) {
        this.cfg = cfg;
        this.wavPath = videoPath.resolveSibling(stripExt(videoPath.getFileName().toString()) + ".wav");
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                cfg.audioSampleRate,
                16,
                cfg.audioChannels,
                cfg.audioChannels * 2,
                cfg.audioSampleRate,
                false /* little-endian */
        );
    }

    public Path getWavPath() { return wavPath; }

    /** Begin capture. Throws if no usable line is found and the user wants audio. */
    public void start() {
        Mixer.Info mixer = findLoopbackMixer();
        if (mixer == null) {
            BackOnTrack.LOGGER.warn(
                    "No system loopback / 'Stereo Mix' device found. Audio will not be recorded. "
                  + "Install a loopback driver (VB-CABLE, PulseAudio module-loopback, BlackHole) "
                  + "and pick it as the OS default to enable audio capture.");
            return;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getMixer(mixer).getLine(info);
            line.open(format);

            Files.createDirectories(wavPath.getParent());
            raf = new RandomAccessFile(wavPath.toFile(), "rw");
            writeWavHeader(raf, format, 0);
            out = new BufferedOutputStream(new java.io.FileOutputStream(raf.getFD()));

            line.start();
            running.set(true);

            captureThread = new Thread(this::captureLoop, "BackOnTrack-Audio");
            captureThread.setDaemon(true);
            captureThread.start();

            BackOnTrack.LOGGER.info("Audio capture started using mixer: {}", mixer.getName());
        } catch (LineUnavailableException | IOException e) {
            BackOnTrack.LOGGER.warn("Audio capture failed to start: {}", e.getMessage());
            stopQuietly();
        }
    }

    /** Stop capture and finalise the WAV header. Returns the WAV path or null. */
    public Path stop() {
        if (!running.compareAndSet(true, false)) return null;
        if (line != null) {
            line.stop();
            line.close();
        }
        if (captureThread != null) {
            try { captureThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try {
            if (out != null) { out.flush(); }
            if (raf != null) {
                // Patch the WAV header sizes now that we know the data length.
                raf.seek(4);
                writeIntLE(raf, (int) (36 + bytesWritten));
                raf.seek(40);
                writeIntLE(raf, (int) bytesWritten);
                raf.close();
            }
        } catch (IOException e) {
            BackOnTrack.LOGGER.warn("Failed to finalise WAV header: {}", e.getMessage());
        }
        return Files.exists(wavPath) ? wavPath : null;
    }

    // ---- internals ----

    private void captureLoop() {
        byte[] buf = new byte[8192];
        try {
            while (running.get()) {
                int read = line.read(buf, 0, buf.length);
                if (read > 0) {
                    out.write(buf, 0, read);
                    bytesWritten += read;
                    // Mix in voice-chat samples if present.
                    byte[] voice = VoiceChatBridge.drainSamples(read);
                    if (voice != null) {
                        out.write(voice, 0, voice.length);
                        bytesWritten += voice.length;
                    }
                }
            }
        } catch (IOException e) {
            BackOnTrack.LOGGER.warn("Audio capture loop terminated: {}", e.getMessage());
        }
    }

    private void stopQuietly() {
        running.set(false);
        try { if (line != null) { line.stop(); line.close(); } } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (raf != null) raf.close(); } catch (IOException ignored) {}
    }

    private static Mixer.Info findLoopbackMixer() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixers) {
            String name = info.getName().toLowerCase(Locale.ROOT);
            String desc = info.getDescription().toLowerCase(Locale.ROOT);
            for (String hint : LOOPBACK_HINTS) {
                if (name.contains(hint) || desc.contains(hint)) {
                    return info;
                }
            }
        }
        return null;
    }

    private static void writeWavHeader(RandomAccessFile out, AudioFormat fmt, long dataLen) throws IOException {
        out.writeBytes("RIFF");
        writeIntLE(out, (int) (36 + dataLen));
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, (short) 1); // PCM
        writeShortLE(out, (short) fmt.getChannels());
        writeIntLE(out, (int) fmt.getSampleRate());
        writeIntLE(out, (int) (fmt.getSampleRate() * fmt.getChannels() * 2));
        writeShortLE(out, (short) (fmt.getChannels() * 2));
        writeShortLE(out, (short) 16);
        out.writeBytes("data");
        writeIntLE(out, (int) dataLen);
    }

    private static void writeIntLE(RandomAccessFile out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile out, short v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
