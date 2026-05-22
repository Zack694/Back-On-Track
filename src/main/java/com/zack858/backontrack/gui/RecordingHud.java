package com.zack858.backontrack.gui;

import com.zack858.backontrack.config.ModConfig;
import com.zack858.backontrack.recording.RecordingManager;
import com.zack858.backontrack.recording.RecordingState;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.concurrent.TimeUnit;

/**
 * Tiny on-screen overlay drawn while a recording is active. Shows a red
 * blinking dot, an "REC" label, the elapsed time, and the encoded frame count.
 *
 * Disabled if {@link ModConfig#showHud} is false. The HUD itself is rendered
 * before {@code FrameCapture} runs, so it's also captured into the output --
 * matching Flashback's "you can see what you recorded" behaviour.
 */
public final class RecordingHud implements HudRenderCallback {
    private static final long BLINK_INTERVAL_MS = 700L;

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.showHud) return;

        RecordingManager mgr = RecordingManager.get();
        RecordingState state = mgr.getState();
        if (state == RecordingState.IDLE) return;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;

        int x = 8;
        int y = 8;

        // Background pill so the text remains readable on bright sky.
        String time = formatTime(mgr.getRecordingNanos());
        String frames = mgr.getFrameCount() + " " + Text.translatable("back_on_track.hud.frames").getString();
        String label = Text.translatable("back_on_track.hud.recording").getString();

        int textWidth = font.getWidth(label + "  " + time + "  " + frames) + 22;
        ctx.fill(x, y, x + textWidth, y + 14, 0x80000000);

        // Blinking red dot.
        boolean visible = state == RecordingState.STOPPING
                || (System.currentTimeMillis() / BLINK_INTERVAL_MS) % 2 == 0;
        if (visible) {
            ctx.fill(x + 4, y + 4, x + 10, y + 10, 0xFFFF3333);
        }

        int textX = x + 14;
        ctx.drawText(font, label, textX, y + 3, 0xFFFFFFFF, false);
        textX += font.getWidth(label) + 6;
        ctx.drawText(font, time, textX, y + 3, 0xFFCCCCCC, false);
        textX += font.getWidth(time) + 6;
        ctx.drawText(font, frames, textX, y + 3, 0xFFAAAAAA, false);
    }

    private static String formatTime(long nanos) {
        long totalSec = TimeUnit.NANOSECONDS.toSeconds(nanos);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return String.format("%02d:%02d", min, sec);
    }
}
