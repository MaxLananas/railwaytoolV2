package com.bte.railpathtool.design;

import com.bte.railpathtool.track.TrackModel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Un design de rail construit à partir d'un {@link TrackModel} analysé.
 * Remplit le plan (ordonné) sans jamais toucher au monde réel.
 */
public interface RailDesign {
    void emitCases(TrackModel model, DesignOptions options,
                   Long2ObjectOpenHashMap<BlockState> plan);
}
