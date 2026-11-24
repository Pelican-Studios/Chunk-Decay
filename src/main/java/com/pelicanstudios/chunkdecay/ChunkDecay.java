package com.pelicanstudios.chunkdecay;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunk Decay - a Pelican Studios singleplayer minigame.
 * Every chunk you leave crumbles into the void behind you.
 */
public class ChunkDecay implements ModInitializer {
    public static final String MOD_ID = "chunkdecay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static DecayManager manager;

    @Override
    public void onInitialize() {
        // One manager per loaded world (singleplayer starts a fresh integrated server each time).
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            manager = new DecayManager(server);
            manager.load();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (manager != null) {
                manager.save();
                manager = null;
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (manager != null) {
                manager.tick();
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (manager != null) {
                manager.onRespawn(newPlayer);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DecayCommand.register(dispatcher));

        LOGGER.info("[Chunk Decay] Loaded - by Pelican Studios");
    }

    /** The manager for the currently running world, or null if no world is loaded. */
    public static DecayManager manager() {
        return manager;
    }
}
