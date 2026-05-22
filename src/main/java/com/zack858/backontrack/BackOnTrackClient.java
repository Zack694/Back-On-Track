package com.zack858.backontrack;

import com.zack858.backontrack.audio.VoiceChatBridge;
import com.zack858.backontrack.config.ModConfig;
import com.zack858.backontrack.gui.RecordingHud;
import com.zack858.backontrack.recording.RecordingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. Wires up config, keybindings, HUD, and shutdown hooks.
 */
public final class BackOnTrackClient implements ClientModInitializer {
    /** Custom keybind category. The translation key is derived as
     *  {@code key.categories.<namespace>.<path>}. */
    public static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of(BackOnTrack.MOD_ID, "main"));

    public static KeyBinding toggleRecordingKey;

    @Override
    public void onInitializeClient() {
        // 1. Load config from disk (creates default file on first run).
        ModConfig.load();

        // 2. Register the recording-toggle keybinding (default: F8).
        toggleRecordingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.back_on_track.toggle_recording",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                KEY_CATEGORY
        ));

        // 3. Per-tick: handle hotkey presses.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleRecordingKey.wasPressed()) {
                RecordingManager.get().toggle(client);
            }
        });

        // 4. HUD overlay (REC indicator + timer).
        HudRenderCallback.EVENT.register(new RecordingHud());

        // 5. Probe Simple Voice Chat once it's safe to ask the loader.
        if (FabricLoader.getInstance().isModLoaded("voicechat")) {
            VoiceChatBridge.tryInitialize();
        }

        // 6. Make sure FFmpeg is shut down cleanly on game exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RecordingManager.get().shutdown());

        BackOnTrack.LOGGER.info("[{}] Client initialized. Press F8 to toggle recording.", BackOnTrack.MOD_NAME);
    }

    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }
}
