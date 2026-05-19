package org.example.plugin.wand;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registered as "BuildersWandPlace". Fires on primary use (right-click).
 *
 * <p>If the player is NOT sneaking: fills air blocks on the clicked face's
 * plane, consuming matching blocks from the player's inventory.
 * If sneaking: no-op (size adjustment is handled by BuildersWandAdjustInteraction).
 */
public final class BuildersWandPlaceInteraction extends CompatSimpleBlockInteraction {

    private static final ConcurrentHashMap<UUID, Long> lastPreviewMs = new ConcurrentHashMap<>();
    private static final long PREVIEW_THROTTLE_MS = 1000L;
    private static final String PREVIEW_PARTICLE = "Block_Hit_Crystal";

    private WandTier tier = WandTier.T1;

    public static final BuilderCodec<BuildersWandPlaceInteraction> CODEC =
            BuilderCodec.builder(
                    BuildersWandPlaceInteraction.class,
                    BuildersWandPlaceInteraction::new,
                    SimpleBlockInteraction.CODEC)
                    .documentation("Builder's Wand — places blocks on clicked face.")
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

        // Sneaking → skip placement (handled by adjust interaction)
        if (movStates != null && movStates.getMovementStates().crouching) return;

        // Determine the clicked block type
        BlockAccessor chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z));
        if (chunk == null) return;

        BlockType clickedType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        if (clickedType == null) return;

        int clickedIdx = BlockType.getAssetMap().getIndex(clickedType.getId());
        if (clickedIdx == BlockType.EMPTY_ID) return; // clicked air, nothing to do

        String blockItemId = clickedType.getId();

        // Determine the face the player clicked
        Face face = detectFace(playerRef, blockPos, world, store);

        // Get the current wand size for this player/tier
        UUID uuid = playerRef.getUuid();
        int size = WandPlayerRegistry.getSize(uuid, tier);

        // Collect viable candidate positions (air with a matching neighbor)
        List<int[]> candidates = collectViablePositions(world, blockPos, face, size, clickedType, clickedIdx);

        if (candidates.isEmpty()) return;
        if (itemStack != null && itemStack.getMaxDurability() > 0 && itemStack.getDurability() <= 0) return;

        // Sort candidates by Manhattan distance from center (closest first)
        candidates.sort(Comparator.comparingInt(p ->
                Math.abs(p[0] - blockPos.x) + Math.abs(p[1] - blockPos.y) + Math.abs(p[2] - blockPos.z)));

        // Get combined inventory (hotbar + storage + backpack)
        @SuppressWarnings("unchecked")
        CombinedItemContainer inv = InventoryComponent.getCombined(
                store, playerEnt, InventoryComponent.HOTBAR_STORAGE_BACKPACK);

        int blocksPlaced = 0;
        for (int[] pos : candidates) {
            if (!consumeOneBlock(inv, blockItemId)) break;
            BlockAccessor targetChunk = world.getChunkIfInMemory(
                    ChunkUtil.indexChunkFromBlock(pos[0], pos[2]));
            if (targetChunk == null) continue;
            targetChunk.setBlock(pos[0], pos[1], pos[2], clickedIdx, clickedType, 0,
                    SetBlockSettings.NONE, SetBlockSettings.NONE);
            blocksPlaced++;
        }

        // Reduce wand durability by number of blocks placed
        if (blocksPlaced > 0 && itemStack != null && itemStack.getMaxDurability() > 0 && !itemStack.isUnbreakable()) {
            short cap = inv.getCapacity();
            for (short slot = 0; slot < cap; slot++) {
                ItemStack stack = inv.getItemStack(slot);
                if (stack == null) continue;
                if (stack.getItemId() != null && stack.getItemId().contains("BuildersWand_")) {
                    double newDura = Math.max(0, stack.getDurability() - blocksPlaced);
                    inv.setItemStackForSlot(slot, stack.withDurability(newDura));
                    break;
                }
            }
        }
    }

    @Override
    protected void simulateInteractWithBlockCompat(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull Vector3i blockPos) {

        Ref<EntityStore> playerEnt = interactionContext.getOwningEntity();
        Store<EntityStore> store = playerEnt.getStore();

        PlayerRef playerRef;
        MovementStatesComponent movStates;
        try {
            playerRef = store.getComponent(playerEnt, PlayerRef.getComponentType());
            movStates = store.getComponent(playerEnt, MovementStatesComponent.getComponentType());
        } catch (Throwable t) {
            return;
        }
        if (playerRef == null) return;
        if (movStates != null && movStates.getMovementStates().crouching) return;

        UUID uuid = playerRef.getUuid();
        long now = System.currentTimeMillis();
        Long last = lastPreviewMs.get(uuid);
        if (last != null && now - last < PREVIEW_THROTTLE_MS) return;
        lastPreviewMs.put(uuid, now);

        BlockAccessor chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z));
        if (chunk == null) return;

        BlockType clickedType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        if (clickedType == null) return;
        int clickedIdx = BlockType.getAssetMap().getIndex(clickedType.getId());
        if (clickedIdx == BlockType.EMPTY_ID) return;

        Face face = detectFace(playerRef, blockPos, world, store);
        int size = WandPlayerRegistry.getSize(uuid, tier);
        List<int[]> candidates = collectViablePositions(world, blockPos, face, size, clickedType, clickedIdx);

        List<Ref<EntityStore>> receivers = List.of(playerEnt);
        for (int[] pos : candidates) {
            ParticleUtil.spawnParticleEffect(
                    PREVIEW_PARTICLE,
                    pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                    0.25f, 0.0f, 0.0f,
                    playerEnt, receivers, store);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private enum Face { UP, DOWN, NORTH, SOUTH, EAST, WEST }

    private Face detectFace(PlayerRef playerRef, Vector3i blockPos, World world, Store<EntityStore> store) {
        Vector3f headRot = playerRef.getHeadRotation();
        if (headRot != null) {
            Vector3d lookDir = Transform.getDirection(headRot.x, headRot.y);
            Vector3d eyePos = playerRef.getTransform().getPosition();
            double ex = eyePos.x, ey = eyePos.y + 1.62, ez = eyePos.z;

            int prevCx = (int) Math.floor(ex);
            int prevCy = (int) Math.floor(ey);
            int prevCz = (int) Math.floor(ez);

            for (int step = 1; step <= 200; step++) {
                double px = ex + lookDir.x * step * 0.1;
                double py = ey + lookDir.y * step * 0.1;
                double pz = ez + lookDir.z * step * 0.1;
                int cx = (int) Math.floor(px);
                int cy = (int) Math.floor(py);
                int cz = (int) Math.floor(pz);

                if (cx == prevCx && cy == prevCy && cz == prevCz) continue;

                BlockAccessor ba = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cx, cz));
                if (ba == null) { prevCx = cx; prevCy = cy; prevCz = cz; continue; }
                if (ba.getBlock(cx, cy, cz) != BlockType.EMPTY_ID) {
                    if (cx == blockPos.x && cy == blockPos.y && cz == blockPos.z) {
                        int dfx = cx - prevCx;
                        int dfy = cy - prevCy;
                        int dfz = cz - prevCz;
                        return faceFromDiff(dfx, dfy, dfz);
                    }
                    break;
                }
                prevCx = cx; prevCy = cy; prevCz = cz;
            }
        }
        // fallback: use player position relative to block center
        Vector3d pos = playerRef.getTransform().getPosition();
        double dx = pos.x - (blockPos.x + 0.5);
        double dy = pos.y - (blockPos.y + 0.5);
        double dz = pos.z - (blockPos.z + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Face.UP : Face.DOWN;
        if (ax >= az) return dx > 0 ? Face.EAST : Face.WEST;
        return dz > 0 ? Face.SOUTH : Face.NORTH;
    }

    private static Face faceFromDiff(int dx, int dy, int dz) {
        if (dy != 0) return dy < 0 ? Face.UP : Face.DOWN;
        if (dx != 0) return dx < 0 ? Face.EAST : Face.WEST;
        return dz < 0 ? Face.SOUTH : Face.NORTH;
    }

    private List<int[]> collectViablePositions(
            World world, Vector3i center, Face face, int size,
            BlockType targetType, int targetIdx) {

        int half = (size - 1) / 2;
        List<int[]> result = new ArrayList<>();

        for (int u = -half; u <= half; u++) {
            for (int v = -half; v <= half; v++) {
                int bx, by, bz;
                switch (face) {
                    case UP    -> { bx = center.x + u; by = center.y + 1; bz = center.z + v; }
                    case DOWN  -> { bx = center.x + u; by = center.y - 1; bz = center.z + v; }
                    case EAST  -> { bx = center.x + 1; by = center.y + v; bz = center.z + u; }
                    case WEST  -> { bx = center.x - 1; by = center.y + v; bz = center.z + u; }
                    case SOUTH -> { bx = center.x + u; by = center.y + v; bz = center.z + 1; }
                    default    -> { bx = center.x + u; by = center.y + v; bz = center.z - 1; }
                }

                if (!isViable(world, bx, by, bz, targetType, targetIdx)) continue;
                result.add(new int[]{bx, by, bz});
            }
        }
        return result;
    }

    /**
     * A position is viable if it is currently air and has at least one
     * orthogonal neighbor that is the same block type as the target.
     */
    private boolean isViable(World world, int bx, int by, int bz,
                              BlockType targetType, int targetIdx) {
        BlockAccessor chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
        if (chunk == null) return false;
        if (chunk.getBlock(bx, by, bz) != BlockType.EMPTY_ID) return false;

        int[][] neighbors = {
                {bx + 1, by, bz}, {bx - 1, by, bz},
                {bx, by + 1, bz}, {bx, by - 1, bz},
                {bx, by, bz + 1}, {bx, by, bz - 1}
        };
        for (int[] n : neighbors) {
            BlockAccessor nc = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(n[0], n[2]));
            if (nc == null) continue;
            if (nc.getBlock(n[0], n[1], n[2]) == targetIdx) return true;
        }
        return false;
    }

    /**
     * Removes one item matching {@code itemId} from the combined inventory.
     * Returns true if a block was successfully consumed.
     */
    private boolean consumeOneBlock(CombinedItemContainer inv, String itemId) {
        short cap = inv.getCapacity();
        for (short slot = 0; slot < cap; slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (stack == null) continue;
            if (!itemId.equals(stack.getItemId())) continue;
            if (stack.getQuantity() <= 0) continue;
            inv.removeItemStackFromSlot(slot, 1);
            return true;
        }
        return false;
    }
}
