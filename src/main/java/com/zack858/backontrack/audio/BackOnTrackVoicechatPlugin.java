package com.zack858.backontrack.audio;

import com.zack858.backontrack.BackOnTrack;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Simple Voice Chat plugin entrypoint. Registered via the {@code voicechat}
 * Fabric entrypoint declared in fabric.mod.json. Only loaded when the
 * voicechat mod is present, so this class can safely reference the
 * voicechat API types without breaking the rest of the mod.
 */
public final class BackOnTrackVoicechatPlugin implements VoicechatPlugin {
    private static final int VOICE_SAMPLE_RATE = 48_000; // SVC native rate

    @Override
    public String getPluginId() {
        return BackOnTrack.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        BackOnTrack.LOGGER.info("Voicechat plugin initialised; routing voice samples into recordings.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.class, this::onReceive);
    }

    private void onReceive(ClientReceiveSoundEvent event) {
        try {
            short[] raw = event.getRawAudio();
            if (raw != null && raw.length > 0) {
                VoiceChatBridge.pushSamples(raw, VOICE_SAMPLE_RATE);
            }
        } catch (Throwable t) {
            // Don't let a recording bug ever break voicechat playback.
            BackOnTrack.LOGGER.debug("Failed to handle voicechat sample", t);
        }
    }
}
