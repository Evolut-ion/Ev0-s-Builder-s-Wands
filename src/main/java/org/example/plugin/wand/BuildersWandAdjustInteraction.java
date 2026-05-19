package org.example.plugin.wand;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Registered as "BuildersWandAdjust". Fires on secondary use.
 *
 * <p>If the player IS sneaking:
 *   - Left/primary while sneaking → decrease wand size for this tier (handled here via secondary)
 *   - Secondary while sneaking → increase wand size for this tier
 *
 * In practice the item JSON wires this to the Secondary slot.
 * Sneaking + secondary = increase; the place interaction skips when sneaking.
 * To decrease, sneak + primary is suppressed in place interaction (no-op),
 * so a dedicated "secondary decrease" would need its own action — for now
 * secondary while sneaking cycles the size upward; players can run it down
 * by repeating until it wraps (min 1 → maxSize).
 *
 * <p>TODO: Replace with a proper HyUI panel for fine-grained control.
 */
public final class BuildersWandAdjustInteraction extends CompatSimpleBlockInteraction {

    private WandTier tier = WandTier.T1;

    public static final BuilderCodec<BuildersWandAdjustInteraction> CODEC =
            BuilderCodec.builder(
                    BuildersWandAdjustInteraction.class,
                    BuildersWandAdjustInteraction::new,
                    SimpleBlockInteraction.CODEC)
                    .documentation("Builder's Wand — adjusts build size while sneaking.")
                    .append(
                            new KeyedCodec<>("Tier", new EnumCodec<>(WandTier.class)),
                            (interaction, t) -> interaction.tier = t,
                            interaction -> interaction.tier)
                    .add()
                    .build();

    @Override
    protected void interactWithBlockCompat(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull Vector3i blockPos,
            @Nonnull CooldownHandler cooldownHandler) {

        Ref<EntityStore> playerEnt = interactionContext.getOwningEntity();
        Store<EntityStore> store = playerEnt.getStore();

        PlayerRef playerRef;
        MovementStatesComponent movStates;
        try {
            playerRef = store.getComponent(playerEnt, PlayerRef.getComponentType());
            movStates = store.getComponent(playerEnt, MovementStatesComponent.getComponentType());
        } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log("[BuildersWand] Could not resolve player components: " + t.getMessage());
            return;
        }
        if (playerRef == null) return;

        // Only act while sneaking
        if (movStates == null || !movStates.getMovementStates().crouching) return;

        UUID uuid = playerRef.getUuid();
        int before = WandPlayerRegistry.getSize(uuid, tier);
        WandPlayerRegistry.increase(uuid, tier);
        int after = WandPlayerRegistry.getSize(uuid, tier);

        // If we're already at max, wrap down to min instead
        if (after == before) {
            // Reset to minimum so cycling is possible
            while (WandPlayerRegistry.getSize(uuid, tier) > tier.minSize) {
                WandPlayerRegistry.decrease(uuid, tier);
            }
            after = WandPlayerRegistry.getSize(uuid, tier);
        }

        HytaleLogger.getLogger().atInfo().log(
                "[BuildersWand] " + playerRef.getUsername() + " set " + tier + " size to " + after);
        playerRef.sendMessage(
                Message.raw("[Builder's Wand] " + tier.name() + " size: " + after + "x" + after)
                        .color("#AADDFF"));
    }
}
