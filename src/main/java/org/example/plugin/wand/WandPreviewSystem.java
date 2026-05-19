package org.example.plugin.wand;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WandPreviewSystem extends EntityTickingSystem<EntityStore> {

    private static final String PREVIEW_PARTICLE = "Block_Hit_Crystal";
    private static final double EYE_HEIGHT = 1.62;
    private static final double RAY_STEP = 0.1;
    private static final int RAY_MAX_STEPS = 200;
    private static final long THROTTLE_MS = 1000L;

    private static final ConcurrentHashMap<UUID, Long> lastUpdateMs = new ConcurrentHashMap<>();

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

            ItemStack heldItem = InventoryComponent.getItemInHand(store, entityRef);
            if (heldItem == null) {
                System.out.println("[WandPreview] heldItem null for " + playerRef.getUsername());
                return;
            }
            String itemId = heldItem.getItemId();
            System.out.println("[WandPreview] held item: " + itemId);
            if (itemId == null || !itemId.contains("BuildersWand_")) return;

            MovementStatesComponent movStates = store.getComponent(entityRef, MovementStatesComponent.getComponentType());
            if (movStates != null && movStates.getMovementStates().crouching) return;

            UUID uuid = playerRef.getUuid();
            long now = System.currentTimeMillis();
            Long last = lastUpdateMs.get(uuid);
            if (last != null && now - last < THROTTLE_MS) return;
            lastUpdateMs.put(uuid, now);

            WandTier tier = tierFromItemId(itemId);
            if (tier == null) {
                System.out.println("[WandPreview] tier null for itemId: " + itemId);
                return;
            }
            int size = WandPlayerRegistry.getSize(uuid, tier);

            World world = store.getExternalData().getWorld();
            if (world == null) {
                System.out.println("[WandPreview] world null");
                return;
            }

            Vector3f headRot = playerRef.getHeadRotation();
            if (headRot == null) {
                System.out.println("[WandPreview] headRot null");
                return;
            }

            Vector3d lookDir = Transform.getDirection(headRot.x, headRot.y);
            Vector3d eyePos = playerRef.getTransform().getPosition();
            double ex = eyePos.x, ey = eyePos.y + EYE_HEIGHT, ez = eyePos.z;

            int bx = 0, by = 0, bz = 0;
            int prevCx = (int) Math.floor(ex);
            int prevCy = (int) Math.floor(ey);
            int prevCz = (int) Math.floor(ez);
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
                System.out.println("[WandPreview] ray hit nothing");
                return;
            }

            // Face is determined by the direction the ray entered the block
            int dfx = bx - prevCx;
            int dfy = by - prevCy;
            int dfz = bz - prevCz;
            Face face = faceFromDiff(dfx, dfy, dfz);

            BlockAccessor targetChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (targetChunk == null) return;
            BlockType clickedType = targetChunk.getBlockType(bx, by, bz);
            if (clickedType == null) return;
            int clickedIdx = BlockType.getAssetMap().getIndex(clickedType.getId());
            if (clickedIdx == BlockType.EMPTY_ID) return;

            List<int[]> positions = collectViablePositions(world, bx, by, bz, face, size, clickedIdx);
            List<Ref<EntityStore>> receivers = List.of(entityRef);
            for (int[] pos : positions) {
                ParticleUtil.spawnParticleEffect(
                        PREVIEW_PARTICLE,
                        pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                        0.25f, 0.0f, 0.0f,
                        entityRef, receivers, store);
            }
        } catch (Throwable t) {
            System.out.println("[WandPreview] exception in tick: " + t);
            t.printStackTrace();
        }
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    private static WandTier tierFromItemId(String itemId) {
        if (itemId.contains("T3")) return WandTier.T3;
        if (itemId.contains("T2")) return WandTier.T2;
        if (itemId.contains("T1")) return WandTier.T1;
        return null;
    }

    private static Face faceFromDiff(int dx, int dy, int dz) {
        if (dy != 0) return dy < 0 ? Face.UP : Face.DOWN;
        if (dx != 0) return dx < 0 ? Face.EAST : Face.WEST;
        return dz < 0 ? Face.SOUTH : Face.NORTH;
    }

    private static List<int[]> collectViablePositions(
            World world, int cx, int cy, int cz, Face face, int size, int targetIdx) {
        int half = (size - 1) / 2;
        List<int[]> result = new ArrayList<>();
        for (int u = -half; u <= half; u++) {
            for (int v = -half; v <= half; v++) {
                int bx, by, bz;
                switch (face) {
                    case UP    -> { bx = cx + u; by = cy + 1; bz = cz + v; }
                    case DOWN  -> { bx = cx + u; by = cy - 1; bz = cz + v; }
                    case EAST  -> { bx = cx + 1; by = cy + v; bz = cz + u; }
                    case WEST  -> { bx = cx - 1; by = cy + v; bz = cz + u; }
                    case SOUTH -> { bx = cx + u; by = cy + v; bz = cz + 1; }
                    default    -> { bx = cx + u; by = cy + v; bz = cz - 1; }
                }
                if (!isViable(world, bx, by, bz, targetIdx)) continue;
                result.add(new int[]{bx, by, bz});
            }
        }
        return result;
    }

    private static boolean isViable(World world, int bx, int by, int bz, int targetIdx) {
        BlockAccessor chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
        if (chunk == null) return false;
        if (chunk.getBlock(bx, by, bz) != BlockType.EMPTY_ID) return false;
        int[][] neighbors = {
            {bx+1, by, bz}, {bx-1, by, bz},
            {bx, by+1, bz}, {bx, by-1, bz},
            {bx, by, bz+1}, {bx, by, bz-1}
        };
        for (int[] n : neighbors) {
            BlockAccessor nc = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(n[0], n[2]));
            if (nc == null) continue;
            if (nc.getBlock(n[0], n[1], n[2]) == targetIdx) return true;
        }
        return false;
    }
}
