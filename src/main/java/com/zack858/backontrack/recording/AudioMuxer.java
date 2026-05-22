package com.zack858.backontrack.recording;

import com.zack858.backontrack.BackOnTrack;
import com.zack858.backontrack.config.ModConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One-shot FFmpeg invocation that muxes a video file and a WAV audio file into
 * a single container, deletes the loose video, and returns the muxed output.
 * The muxer copies the video stream (no re-encode) and encodes audio to AAC.
 */
public final class AudioMuxer {
    private AudioMuxer() {}

    public static Path mux(Path videoFile, Path audioFile, ModConfig cfg) {
        if (!Files.exists(videoFile) || !Files.exists(audioFile)) {
            BackOnTrack.LOGGER.warn("Skipping mux: video={} audio={}", videoFile, audioFile);
            return videoFile;
        }
        Path output = videoFile.resolveSibling(
                stripExtension(videoFile.getFileName().toString()) + "-muxed." + cfg.container);
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.ffmpegPath);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("warning");
        cmd.add("-i"); cmd.add(videoFile.toString());
        cmd.add("-i"); cmd.add(audioFile.toString());
        cmd.add("-c:v"); cmd.add("copy");
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add(cfg.audioBitrateKbps + "k");
        cmd.add("-shortest");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add(output.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().transferTo(System.out);
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                BackOnTrack.LOGGER.warn("Mux timed out; keeping video-only file.");
                return videoFile;
            }
            if (proc.exitValue() != 0) {
                BackOnTrack.LOGGER.warn("Mux exited with code {}; keeping video-only file.", proc.exitValue());
                return videoFile;
            }
            // Replace original video file with the muxed result.
            Files.deleteIfExists(videoFile);
            Files.deleteIfExists(audioFile);
            Path renamed = videoFile.resolveSibling(videoFile.getFileName().toString());
            Files.move(output, renamed);
            return renamed;
        } catch (IOException e) {
            BackOnTrack.LOGGER.warn("Mux failed: {}", e.getMessage());
            return videoFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return videoFile;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
