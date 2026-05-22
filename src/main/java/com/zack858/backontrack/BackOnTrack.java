package com.zack858.backontrack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both-side) entrypoint. Back On Track is client-only, but Fabric
 * still calls this on dedicated servers if the jar is present. We keep it
 * minimal so the mod can be safely dropped on a server jar without harm.
 */
public final class BackOnTrack implements ModInitializer {
    public static final String MOD_ID = "back_on_track";
    public static final String MOD_NAME = "Back On Track";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
        LOGGER.info("[{}] Loaded (common). Client features initialize separately.", MOD_NAME);
    }
}
