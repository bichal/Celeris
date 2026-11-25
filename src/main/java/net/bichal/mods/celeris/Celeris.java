package net.bichal.mods.celeris;

import net.bichal.mods.celeris.config.CelerisSettings;
import net.bichal.mods.celeris.core.memory.backend.CelerisRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if neoforge {
import net.bichal.mods.celeris.config.CelerisConfig;
import net.bichal.mods.celeris.network.NetworkRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
//?}

/**
 * Mod entry point on NeoForge (on Fabric: {@code CelerisFabric}). Triggers
 * backend selection (see {@link CelerisRuntime}; the codec, {@code CelerisCodecs}, waits for its
 * first use) and network registration on startup, and registers the config file
 * ({@code CelerisConfig}; its screen is {@code CelerisClient}'s); has no state or methods another mod
 * would call directly -- for runtime feature checks, use
 * {@code net.bichal.mods.celeris.api.CelerisFeatures} instead.
 */
//? if neoforge
@Mod(Celeris.MOD_ID)
public final class Celeris {

    public static final String MOD_ID = "celeris";
    private static final Logger LOGGER = LoggerFactory.getLogger(Celeris.class);

    //? if neoforge {
    public Celeris(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(CelerisConfig.type(), CelerisConfig.SPEC);
        CelerisConfig.install();
        start();
        modEventBus.addListener(NetworkRegistry::register);
    }
    //?}

    /**
     * Selects the memory backend once, at startup, and logs it. Both loaders call it. The compression codec is not
     * chosen here: libzstd is loaded and self-tested the first time a mod asks for it, and never when none does.
     */
    @SuppressWarnings("resource")
    public static void start() {
        LOGGER.info("Celeris engine initializing");
        LOGGER.info("Celeris memory backend: {}", CelerisRuntime.backend().name());
        LOGGER.info("Celeris compression codec: chosen on first use");
        LOGGER.info("Celeris network compression level: {}", CelerisSettings.compressionLevel());
        LOGGER.info("Celeris engine working");
    }
}
