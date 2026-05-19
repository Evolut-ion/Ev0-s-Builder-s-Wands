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
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BuildersWandCopyInteraction extends CompatSimpleBlockInteraction {

    private WandTier tier = WandTier.T1;

    public static final BuilderCodec<BuildersWandCopyInteraction> CODEC =
            BuilderCodec.builder(
                    BuildersWandCopyInteraction.class,
                    BuildersWandCopyInteraction::new,
                    SimpleBlockInteraction.CODEC)
                    .documentation("Builder's Wand Copy — copy and paste AABB regions.")
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
            HytaleLogger.getLogger().atWarning().log("[BuildersWandCopy] Could not resolve player components: " + t.getMessage());
            return;
        }
        if (playerRef == null) return;

        UUID uuid = playerRef.getUuid();
        boolean sneaking = movStates != null && movStates.getMovementStates().crouching;

        if (sneaking) {
            handleCopy(world, store, playerRef, uuid, blockPos);
        } else {
            handlePaste(world, store, playerRef, playerEnt, uuid, blockPos, itemStack);
        }
    }

    @Override
    protected void simulateInteractWithBlockCompat(
            @Nonnull InteractionType interactionType,
            @Nonnull InteractionContext interactionContext,
            @Nullable ItemStack itemStack,
            @Nonnull World world,
            @Nonnull Vector3i blockPos) {
    }

    // -------------------------------------------------------------------
    // Copy: crouch-RMB sets corners and scans the AABB
    // -------------------------------------------------------------------
    private void handleCopy(World world, Store<EntityStore> store, PlayerRef playerRef,
                            UUID uuid, Vector3i blockPos) {
        WandCopyState state = WandCopyState.get(uuid);

        if (!state.hasSelection()) {
            if (!state.hasCorner1()) {
                state.setCorner1(new Vector3i(blockPos));
                playerRef.sendMessage(Message.raw("[Copy] Corner 1 set at " + blockPos.x + ", " + blockPos.y + ", " + blockPos.z).color("#AADDFF"));
                return;
            }

            state.setCorner2AndScan(new Vector3i(blockPos));
            List<WandCopyState.SavedBlock> scanned = scanAABB(world, state.getCorner1(), state.getCorner2());
            if (scanned == null) {
                state.reset();
                state.setCorner1(new Vector3i(blockPos));
                playerRef.sendMessage(Message.raw("[Copy] Selection too large! Maximum " + tier.maxSize + " blocks per axis").color("#FF4444"));
                return;
            }
            state.setSavedBlocks(scanned);
            playerRef.sendMessage(Message.raw("[Copy] Copied " + scanned.size() + " blocks").color("#AADDFF"));
        } else {
            state.reset();
            state.setCorner1(new Vector3i(blockPos));
            playerRef.sendMessage(Message.raw("[Copy] Selection reset, Corner 1 set").color("#AADDFF"));
        }
    }

    private List<WandCopyState.SavedBlock> scanAABB(World world, Vector3i c1, Vector3i c2) {
        int minX = Math.min(c1.x, c2.x);
        int minY = Math.min(c1.y, c2.y);
        int minZ = Math.min(c1.z, c2.z);
        int maxX = Math.max(c1.x, c2.x);
        int maxY = Math.max(c1.y, c2.y);
        int maxZ = Math.max(c1.z, c2.z);

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        if (sizeX > tier.maxSize || sizeY > tier.maxSize || sizeZ > tier.maxSize) return null;

        List<WandCopyState.SavedBlock> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockAccessor chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk == null) continue;
                    int idx = chunk.getBlock(x, y, z);
                    if (idx == BlockType.EMPTY_ID) continue;
                    BlockType type = chunk.getBlockType(x, y, z);
                    if (type == null) continue;
                    result.add(new WandCopyState.SavedBlock(
                            x - minX, y - minY, z - minZ, type.getId()));
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------
    // Paste: non-crouch RMB places saved blocks
    // -------------------------------------------------------------------
    private void handlePaste(World world, Store<EntityStore> store, PlayerRef playerRef,
                             Ref<EntityStore> playerEnt, UUID uuid, Vector3i blockPos,
                             @Nullable ItemStack itemStack) {
        WandCopyState state = WandCopyState.get(uuid);
        if (!state.hasSelection()) return;
        List<WandCopyState.SavedBlock> blocks = state.getSavedBlocks();
        if (blocks == null || blocks.isEmpty()) return;

        if (itemStack != null && itemStack.getMaxDurability() > 0 && itemStack.getDurability() <= 0) return;

        // Raycast to find targeted block and face (same as preview system — no fallback)
        Vector3f headRot = playerRef.getHeadRotation();
        if (headRot == null) return;
        Face rotationFace = yawToFace(headRot.x);
        Vector3d lookDir = Transform.getDirection(headRot.x, headRot.y);
        Vector3d pasteEyePos = playerRef.getTransform().getPosition();
        double pex = pasteEyePos.x, pey = pasteEyePos.y + 1.62, pez = pasteEyePos.z;
        int ppx = (int) Math.floor(pex), ppy = (int) Math.floor(pey), ppz = (int) Math.floor(pez);
        int bbx = 0, bby = 0, bbz = 0;
        boolean rayHit = false;
        for (int step = 1; step <= 200; step++) {
            double px = pex + lookDir.x * step * 0.1;
            double py = pey + lookDir.y * step * 0.1;
            double pz = pez + lookDir.z * step * 0.1;
            int cx = (int) Math.floor(px);
            int cy = (int) Math.floor(py);
            int cz = (int) Math.floor(pz);
            if (cx == ppx && cy == ppy && cz == ppz) continue;
            BlockAccessor ba = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cx, cz));
            if (ba == null) { ppx = cx; ppy = cy; ppz = cz; continue; }
            if (ba.getBlock(cx, cy, cz) != BlockType.EMPTY_ID && !WandCopyState.isPreviewPosition(uuid, cx, cy, cz)) {
                bbx = cx; bby = cy; bbz = cz;
                rayHit = true;
                break;
            }
            ppx = cx; ppy = cy; ppz = cz;
        }
        if (!rayHit) return;
        int faceDfx = bbx - ppx, faceDfy = bby - ppy, faceDfz = bbz - ppz;
        Face face = faceFromDiff(faceDfx, faceDfy, faceDfz);
        Vector3i origin = getFaceOffset(new Vector3i(bbx, bby, bbz), face);

        // Count required blocks by type
        HashMap<String, Integer> required = new HashMap<>();
        for (WandCopyState.SavedBlock sb : blocks) {
            required.merge(sb.blockTypeId(), 1, Integer::sum);
        }

        // Count available blocks in inventory
        @SuppressWarnings("unchecked")
        CombinedItemContainer inv = InventoryComponent.getCombined(
                store, playerEnt, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        HashMap<String, Integer> available = new HashMap<>();
        short cap = inv.getCapacity();
        for (short slot = 0; slot < cap; slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (stack == null || stack.getItemId() == null) continue;
            available.merge(stack.getItemId(), (int) stack.getQuantity(), Integer::sum);
        }

        // Validate inventory
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int avail = available.getOrDefault(entry.getKey(), 0);
            if (avail < entry.getValue()) {
                playerRef.sendMessage(Message.raw("[Copy] Missing " + entry.getKey() + " — need " + entry.getValue() + ", have " + avail).color("#FF4444"));
                return;
            }
        }

        // Place blocks (rotate offsets based on paste direction)
        int blocksPlaced = 0;
        for (WandCopyState.SavedBlock sb : blocks) {
            Vector3i rotated = rotateOffset(sb.dx(), sb.dy(), sb.dz(), rotationFace);
            int px = origin.x + rotated.x;
            int py = origin.y + rotated.y;
            int pz = origin.z + rotated.z;

            BlockAccessor targetChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(px, pz));
            if (targetChunk == null) continue;
            if (targetChunk.getBlock(px, py, pz) != BlockType.EMPTY_ID) continue;

            if (!consumeOneBlock(inv, sb.blockTypeId())) break;

            int idx = BlockType.getAssetMap().getIndex(sb.blockTypeId());
            if (idx == BlockType.EMPTY_ID) continue;
            BlockType type = BlockType.getAssetMap().getAsset(sb.blockTypeId());
            if (type == null) continue;
            targetChunk.setBlock(px, py, pz, idx, type, 0,
                    SetBlockSettings.NONE, SetBlockSettings.NONE);
            blocksPlaced++;
        }


        // Reduce wand durability
        if (blocksPlaced > 0 && itemStack != null && itemStack.getMaxDurability() > 0 && !itemStack.isUnbreakable()) {
            String heldId = itemStack.getItemId();
            for (short slot = 0; slot < cap; slot++) {
                ItemStack stack = inv.getItemStack(slot);
                if (stack == null) continue;
                if (stack.getItemId() != null && stack.getItemId().equals(heldId)) {
                    double newDura = Math.max(0, stack.getDurability() - blocksPlaced);
                    inv.setItemStackForSlot(slot, stack.withDurability(newDura));
                    break;
                }
            }
        }

        if (blocksPlaced > 0) {
            playerRef.sendMessage(Message.raw("[Copy] Pasted " + blocksPlaced + " blocks").color("#AADDFF"));
        }
        if (blocksPlaced < blocks.size()) {
            playerRef.sendMessage(Message.raw("[Copy] Paste incomplete — " + (blocks.size() - blocksPlaced) + " positions blocked or missing blocks").color("#FF4444"));
        }
    }

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

    // -------------------------------------------------------------------
    // Face detection and offset rotation
    // -------------------------------------------------------------------
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

    private static Face yawToFace(float yaw) {
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;
        if (yaw < 45 || yaw >= 315) return Face.NORTH;
        if (yaw < 135) return Face.EAST;
        if (yaw < 225) return Face.SOUTH;
        return Face.WEST;
    }

    private static Vector3i getFaceOffset(Vector3i blockPos, Face face) {
        switch (face) {
            case UP:    return new Vector3i(blockPos.x, blockPos.y + 1, blockPos.z);
            case DOWN:  return new Vector3i(blockPos.x, blockPos.y - 1, blockPos.z);
            case EAST:  return new Vector3i(blockPos.x + 1, blockPos.y, blockPos.z);
            case WEST:  return new Vector3i(blockPos.x - 1, blockPos.y, blockPos.z);
            case SOUTH: return new Vector3i(blockPos.x, blockPos.y, blockPos.z + 1);
            default:    return new Vector3i(blockPos.x, blockPos.y, blockPos.z - 1);
        }
    }

    private static Vector3i rotateOffset(int dx, int dy, int dz, Face face) {
        switch (face) {
            case SOUTH: return new Vector3i(-dx, dy, -dz);
            case NORTH: return new Vector3i(dx, dy, dz);
            case EAST:  return new Vector3i(-dz, dy, dx);
            case WEST:  return new Vector3i(dz, dy, -dx);
            default:    return new Vector3i(dx, dy, dz);
        }
    }
}
