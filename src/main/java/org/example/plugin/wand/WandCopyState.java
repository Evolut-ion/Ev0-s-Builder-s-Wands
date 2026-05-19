package org.example.plugin.wand;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WandCopyState {

    private static final ConcurrentHashMap<UUID, WandCopyState> states = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<String>> previewPositions = new ConcurrentHashMap<>();

    public record SavedBlock(int dx, int dy, int dz, String blockTypeId) {}

    private org.joml.Vector3i corner1;
    private org.joml.Vector3i corner2;
    private List<SavedBlock> savedBlocks;
    private boolean hasCorner1;
    private boolean hasSelection;

    public static WandCopyState get(UUID uuid) {
        return states.computeIfAbsent(uuid, k -> new WandCopyState());
    }

    public static void remove(UUID uuid) {
        states.remove(uuid);
    }

    public void setCorner1(org.joml.Vector3i pos) {
        this.corner1 = pos;
        this.corner2 = null;
        this.savedBlocks = null;
        this.hasCorner1 = true;
        this.hasSelection = false;
    }

    public void setCorner2AndScan(org.joml.Vector3i pos) {
        this.corner2 = pos;
    }

    public void setSavedBlocks(List<SavedBlock> blocks) {
        this.savedBlocks = blocks;
        this.hasSelection = true;
    }

    public void reset() {
        this.corner1 = null;
        this.corner2 = null;
        this.savedBlocks = null;
        this.hasCorner1 = false;
        this.hasSelection = false;
    }

    public org.joml.Vector3i getCorner1() { return corner1; }
    public org.joml.Vector3i getCorner2() { return corner2; }
    public List<SavedBlock> getSavedBlocks() { return savedBlocks; }
    public boolean hasCorner1() { return hasCorner1; }
    public boolean hasSelection() { return hasSelection; }

    // Preview positions tracked across preview system and interaction
    public static void setPreviewPositions(UUID uuid, Set<String> positions) {
        previewPositions.put(uuid, positions);
    }

    public static Set<String> getPreviewPositions(UUID uuid) {
        return previewPositions.get(uuid);
    }

    public static void clearPreviewPositions(UUID uuid) {
        previewPositions.remove(uuid);
    }

    public static boolean isPreviewPosition(UUID uuid, int x, int y, int z) {
        Set<String> positions = previewPositions.get(uuid);
        return positions != null && positions.contains(x + "," + y + "," + z);
    }
}
