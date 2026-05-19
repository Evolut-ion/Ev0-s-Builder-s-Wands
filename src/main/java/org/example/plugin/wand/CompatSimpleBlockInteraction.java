package org.example.plugin.wand;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.InteractionType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bridges the Hytale internal Vector3i to org.joml.Vector3i for block interactions.
 * Copied from PhosphorTech (release variant).
 */
public abstract class CompatSimpleBlockInteraction extends SimpleBlockInteraction {

    @Override
    protected final void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull com.hypixel.hytale.math.vector.Vector3i blockPos,
            @Nonnull CooldownHandler cooldownHandler) {
        interactWithBlockCompat(world, commandBuffer, interactionType, interactionContext, itemStack,
                new org.joml.Vector3i(blockPos.x, blockPos.y, blockPos.z), cooldownHandler);
    }

    protected abstract void interactWithBlockCompat(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull org.joml.Vector3i blockPos,
            @Nonnull CooldownHandler cooldownHandler);

    @Override
    protected final void simulateInteractWithBlock(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull com.hypixel.hytale.math.vector.Vector3i blockPos) {
        simulateInteractWithBlockCompat(interactionType, interactionContext, itemStack, world,
                new org.joml.Vector3i(blockPos.x, blockPos.y, blockPos.z));
    }

    protected void simulateInteractWithBlockCompat(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull org.joml.Vector3i blockPos) {
        // Override to implement hover preview
    }
}
