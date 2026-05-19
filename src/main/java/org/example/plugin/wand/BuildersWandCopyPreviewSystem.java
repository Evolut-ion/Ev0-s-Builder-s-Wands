package org.example.plugin.wand;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class BuildersWandCopyPreviewSystem extends EntityTickingSystem<EntityStore> {

    private static final double EYE_HEIGHT = 1.62;
    private static final double RAY_STEP = 0.1;
    private static final int RAY_MAX_STEPS = 200;

    private static final long FLASH_INTERVAL_MS = 1500L;

    private static final ConcurrentHashMap<UUID, List<Ref<EntityStore>>> activePreviewRefs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> lastPreviewKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> lastFlashPhase = new ConcurrentHashMap<>();

    private enum Face { UP, DOWN, NORTH, SOUTH, EAST, WEST }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
            if (playerRef == null) return;

            Ref<EntityStore> entityRef = chunk.getReferenceTo(index);
            UUID uuid = playerRef.getUuid();

            ItemStack heldItem = InventoryComponent.getItemInHand(store, entityRef);
            if (heldItem == null || heldItem.getItemId() == null || !heldItem.getItemId().contains("BuildersWandCopy_")) {
                cleanupPreviews(uuid, commandBuffer);
                lastFlashPhase.remove(uuid);
                return;
            }

            MovementStatesComponent movStates = store.getComponent(entityRef, MovementStatesComponent.getComponentType());
            if (movStates != null && movStates.getMovementStates().crouching) {
                cleanupPreviews(uuid, commandBuffer);
                lastFlashPhase.remove(uuid);
                return;
            }

            WandCopyState copyState = WandCopyState.get(uuid);
            if (!copyState.hasSelection() || copyState.getSavedBlocks() == null || copyState.getSavedBlocks().isEmpty()) {
                cleanupPreviews(uuid, commandBuffer);
                lastFlashPhase.remove(uuid);
                return;
            }

            World world = store.getExternalData().getWorld();
            if (world == null) return;

            Vector3f headRot = playerRef.getHeadRotation();
            if (headRot == null) return;
            Face rotationFace = yawToFace(headRot.x);

            Vector3d lookDir = Transform.getDirection(headRot.x, headRot.y);
            Vector3d eyePos = playerRef.getTransform().getPosition();
            double ex = eyePos.x, ey = eyePos.y + EYE_HEIGHT, ez = eyePos.z;

            int prevCx = (int) Math.floor(ex);
            int prevCy = (int) Math.floor(ey);
            int prevCz = (int) Math.floor(ez);
            int bx = 0, by = 0, bz = 0;
            boolean found = false;
            for (int step = 1; step <= RAY_MAX_STEPS; step++) {
                double px = ex + lookDir.x * step * RAY_STEP;
                double py = ey + lookDir.y * step * RAY_STEP;
                double pz = ez + lookDir.z * step * RAY_STEP;
                int cx = (int) Math.floor(px);
                int cy = (int) Math.floor(py);
                int cz = (int) Math.floor(pz);

                if (cx == prevCx && cy == prevCy && cz == prevCz) continue;

                BlockAccessor ba = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cx, cz));
                if (ba == null) { prevCx = cx; prevCy = cy; prevCz = cz; continue; }
                if (ba.getBlock(cx, cy, cz) != BlockType.EMPTY_ID) {
                    bx = cx; by = cy; bz = cz;
                    found = true;
                    break;
                }
                prevCx = cx; prevCy = cy; prevCz = cz;
            }
            if (!found) {
                cleanupPreviews(uuid, commandBuffer);
                lastFlashPhase.remove(uuid);
                return;
            }

            int dfx = bx - prevCx;
            int dfy = by - prevCy;
            int dfz = bz - prevCz;
            Face face = faceFromDiff(dfx, dfy, dfz);

            String key = bx + "," + by + "," + bz + "," + face;

            // Flash cycle: toggle preview every 1.5s so raycasts can pass through between flashes
            long now = System.currentTimeMillis();
            int currentPhase = (int)((now / FLASH_INTERVAL_MS) % 2);
            Integer lastPhase = lastFlashPhase.get(uuid);
            boolean phaseChanged = lastPhase == null || lastPhase != currentPhase;
            lastFlashPhase.put(uuid, currentPhase);

            boolean flashOn = currentPhase == 0;
            boolean isFirstTick = lastPhase == null;

            if (isFirstTick) flashOn = true; // Show immediately on first activation

            if (!flashOn) {
                if (activePreviewRefs.containsKey(uuid)) {
                    cleanupPreviews(uuid, commandBuffer);
                }
                lastPreviewKey.put(uuid, key);
                return;
            }

            String prevKey = lastPreviewKey.get(uuid);
            boolean targetChanged = !key.equals(prevKey);
            boolean needsRecreate = targetChanged || phaseChanged || isFirstTick;

            if (!needsRecreate) return;

            if (targetChanged || isFirstTick) {
                lastPreviewKey.put(uuid, key);
            }

            org.joml.Vector3i origin = getFaceOffset(new org.joml.Vector3i(bx, by, bz), face);

            List<WandCopyState.SavedBlock> savedBlocks = copyState.getSavedBlocks();
            List<PreviewBlock> previewBlocks = new ArrayList<>();
            for (WandCopyState.SavedBlock sb : savedBlocks) {
                org.joml.Vector3i rotated = rotateOffset(sb.dx(), sb.dy(), sb.dz(), rotationFace);
                int px = origin.x + rotated.x;
                int py = origin.y + rotated.y;
                int pz = origin.z + rotated.z;
                BlockAccessor ba = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(px, pz));
                if (ba == null) continue;
                if (ba.getBlock(px, py, pz) != BlockType.EMPTY_ID) continue;
                previewBlocks.add(new PreviewBlock(px, py, pz, sb.blockTypeId()));
            }

            List<Ref<EntityStore>> oldRefs = activePreviewRefs.get(uuid);
            if (oldRefs != null) {
                commandBuffer.run(s -> {
                    for (Ref<EntityStore> ref : oldRefs) {
                        try { s.removeEntity(ref, RemoveReason.REMOVE); } catch (Throwable ignored) {}
                    }
                });
            }

            if (previewBlocks.isEmpty()) {
                activePreviewRefs.remove(uuid);
                WandCopyState.clearPreviewPositions(uuid);
                return;
            }

            java.util.Set<String> posSet = new java.util.HashSet<>();
            for (PreviewBlock pb : previewBlocks) {
                posSet.add(pb.x + "," + pb.y + "," + pb.z);
            }
            WandCopyState.setPreviewPositions(uuid, posSet);

            commandBuffer.run(s -> {
                List<Ref<EntityStore>> newRefs = new ArrayList<>();
                TimeResource time = s.getResource(TimeResource.getResourceType());
                for (PreviewBlock pb : previewBlocks) {
                    try {
                        int idx = BlockType.getAssetMap().getIndex(pb.blockTypeId);
                        if (idx == BlockType.EMPTY_ID) continue;
                        Vector3d pos = new Vector3d(pb.x + 0.5, pb.y, pb.z + 0.5);
                        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
                        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(pb.blockTypeId));
                        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(pos, Vector3f.FORWARD));
                        holder.ensureComponent(UUIDComponent.getComponentType());
                        holder.addComponent(DespawnComponent.getComponentType(), DespawnComponent.despawnInSeconds(time, 120));
                        Ref<EntityStore> ref = s.addEntity(holder, AddReason.SPAWN);
                        newRefs.add(ref);
                    } catch (Throwable t) {
                        com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning()
                                .log("[CopyPreview] Failed to create preview: " + t.getMessage());
                    }
                }
                activePreviewRefs.put(uuid, newRefs);
            });

        } catch (Throwable t) {
            com.hypixel.hytale.logger.HytaleLogger.getLogger().atWarning()
                    .log("[CopyPreview] exception in tick: " + t);
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    private void cleanupPreviews(UUID uuid, CommandBuffer<EntityStore> commandBuffer) {
        List<Ref<EntityStore>> refs = activePreviewRefs.get(uuid);
        if (refs != null) {
            commandBuffer.run(s -> {
                for (Ref<EntityStore> ref : refs) {
                    try { s.removeEntity(ref, RemoveReason.REMOVE); } catch (Throwable ignored) {}
                }
            });
        }
        activePreviewRefs.remove(uuid);
        lastPreviewKey.remove(uuid);
        WandCopyState.clearPreviewPositions(uuid);
    }

    private record PreviewBlock(int x, int y, int z, String blockTypeId) {}

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

    private static org.joml.Vector3i getFaceOffset(org.joml.Vector3i blockPos, Face face) {
        switch (face) {
            case UP:    return new org.joml.Vector3i(blockPos.x, blockPos.y + 1, blockPos.z);
            case DOWN:  return new org.joml.Vector3i(blockPos.x, blockPos.y - 1, blockPos.z);
            case EAST:  return new org.joml.Vector3i(blockPos.x + 1, blockPos.y, blockPos.z);
            case WEST:  return new org.joml.Vector3i(blockPos.x - 1, blockPos.y, blockPos.z);
            case SOUTH: return new org.joml.Vector3i(blockPos.x, blockPos.y, blockPos.z + 1);
            default:    return new org.joml.Vector3i(blockPos.x, blockPos.y, blockPos.z - 1);
        }
    }

    private static org.joml.Vector3i rotateOffset(int dx, int dy, int dz, Face face) {
        switch (face) {
            case SOUTH: return new org.joml.Vector3i(-dx, dy, -dz);
            case NORTH: return new org.joml.Vector3i(dx, dy, dz);
            case EAST:  return new org.joml.Vector3i(-dz, dy, dx);
            case WEST:  return new org.joml.Vector3i(dz, dy, -dx);
            default:    return new org.joml.Vector3i(dx, dy, dz);
        }
    }
}
