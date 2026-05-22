package com.zack858.backontrack.mixin;

import com.zack858.backontrack.recording.RecordingManager;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures any active recording is gracefully closed before the client process
 * exits, to avoid corrupted MP4 files (missing moov atom).
 */
@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {
    @Inject(method = "close", at = @At("HEAD"))
    private void backontrack$flushRecording(CallbackInfo ci) {
        try {
            RecordingManager.get().shutdown();
        } catch (Throwable ignored) {
            // Never block shutdown on a recording error.
        }
    }
}
