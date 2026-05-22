package com.zack858.backontrack.mixin;

import com.zack858.backontrack.gui.ConfigScreen;
import com.zack858.backontrack.recording.RecordingManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Start/Stop Recording and Recording Settings buttons to the bottom of
 * the pause menu, mirroring Flashback's quick-access pattern.
 */
@Mixin(GameMenuScreen.class)
public abstract class MixinGameMenuScreen extends Screen {

    protected MixinGameMenuScreen(Text title) {
        super(title);
    }

    @Inject(method = "initWidgets", at = @At("TAIL"))
    private void backontrack$addRecordingButtons(CallbackInfo ci) {
        addRecordingButtons();
    }

    /**
     * Fallback in case Yarn renames or moves widget initialisation to
     * {@code init} on certain MC versions; with require=0 we just no-op
     * if the target doesn't exist.
     */
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void backontrack$addRecordingButtonsLegacy(CallbackInfo ci) {
        addRecordingButtons();
    }

    private void addRecordingButtons() {
        if (this.client == null) return;
        int buttonY = this.height - 30;
        Text toggleLabel = RecordingManager.get().isRecording()
                ? Text.translatable("back_on_track.menu.stop")
                : Text.translatable("back_on_track.menu.start");

        ButtonWidget toggle = ButtonWidget.builder(toggleLabel, b -> {
                    MinecraftClient mc = this.client;
                    if (mc != null) RecordingManager.get().toggle(mc);
                    if (mc != null) mc.setScreen(null); // unpause so capture runs
                })
                .dimensions(this.width / 2 - 154, buttonY, 150, 20)
                .build();

        ButtonWidget config = ButtonWidget.builder(
                        Text.translatable("back_on_track.menu.config"),
                        b -> {
                            if (this.client != null) {
                                this.client.setScreen(new ConfigScreen((Screen) (Object) this));
                            }
                        })
                .dimensions(this.width / 2 + 4, buttonY, 150, 20)
                .build();

        this.addDrawableChild(toggle);
        this.addDrawableChild(config);
    }
}
