package org.example.plugin.wand;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each player's current wand size per tier.
 * Sizes are always odd and within [1, tier.maxSize].
 */
public final class WandPlayerRegistry {

    private static final ConcurrentHashMap<UUID, int[]> sizes = new ConcurrentHashMap<>();

    private WandPlayerRegistry() {}

    public static int getSize(UUID uuid, WandTier tier) {
        return sizes.computeIfAbsent(uuid, k -> defaultSizes())[tier.ordinal()];
    }

    public static void increase(UUID uuid, WandTier tier) {
        int[] arr = sizes.computeIfAbsent(uuid, k -> defaultSizes());
        int cur = arr[tier.ordinal()];
        if (cur < tier.maxSize) {
            arr[tier.ordinal()] = cur + 2;
        }
    }

    public static void decrease(UUID uuid, WandTier tier) {
        int[] arr = sizes.computeIfAbsent(uuid, k -> defaultSizes());
        int cur = arr[tier.ordinal()];
        if (cur > tier.minSize) {
            arr[tier.ordinal()] = cur - 2;
        }
    }

    private static int[] defaultSizes() {
        // Each tier starts at its maximum size
        int[] arr = new int[WandTier.values().length];
        for (WandTier t : WandTier.values()) {
            arr[t.ordinal()] = t.maxSize;
        }
        return arr;
    }
}
