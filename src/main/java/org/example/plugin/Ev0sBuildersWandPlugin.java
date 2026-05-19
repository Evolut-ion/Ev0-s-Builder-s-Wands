package org.example.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.example.plugin.wand.BuildersWandAdjustInteraction;
import org.example.plugin.wand.BuildersWandPlaceInteraction;
import org.example.plugin.wand.WandPreviewSystem;

import javax.annotation.Nonnull;

public class Ev0sBuildersWandPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public Ev0sBuildersWandPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from " + this.getName() + " v" + this.getManifest().getVersion());
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up " + this.getName());

        var interactionRegistry = this.getCodecRegistry(Interaction.CODEC);
        interactionRegistry.register(
                "BuildersWandPlace",
                BuildersWandPlaceInteraction.class,
                BuildersWandPlaceInteraction.CODEC);
        interactionRegistry.register(
                "BuildersWandAdjust",
                BuildersWandAdjustInteraction.class,
                BuildersWandAdjustInteraction.CODEC);

        this.getEntityStoreRegistry().registerSystem(new WandPreviewSystem());
    }
}
