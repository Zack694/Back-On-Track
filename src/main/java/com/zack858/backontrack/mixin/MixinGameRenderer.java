package com.zack858.backontrack.mixin;

import com.zack858.backontrack.recording.FrameCapture;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the very end of GameRenderer.render so we can capture the final
 * composited frame, including HUD and any open screens. Matches the
 * "raw recording" behaviour Flashback ships by default.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Inject(method = "render", at = @At("TAIL"))
    private void backontrack$captureFrame(CallbackInfo ci) {
        FrameCapture.captureIfRecording();
    }
}
