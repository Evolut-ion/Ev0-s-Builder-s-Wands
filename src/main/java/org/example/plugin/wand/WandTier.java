package org.example.plugin.wand;

/**
 * Wand tiers. Each tier's maxSize is the NxN area it can fill at maximum.
 * Sizes step by 2 (always odd) so the center block is always the clicked block.
 */
public enum WandTier {
    T1(3),
    T2(5),
    T3(7);

    public final int maxSize;
    public final int minSize = 1;

    WandTier(int maxSize) {
        this.maxSize = maxSize;
    }
}
