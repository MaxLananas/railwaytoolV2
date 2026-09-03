package com.bte.railpathtool.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Épuration des coins en « L » de la trace — portage du script « track tool » n°2.
 *
 * Un voxel de trace formant un angle droit avec deux voisins de trace (4 orientations
 * × 7 paires de tolérances verticales du script) est retiré de la trace : dans le
 * script il devient un marqueur « corner » à part ; ici l'analyse est géométrique,
 * donc il est simplement épuré pour laisser des lignes nettes.
 * Les voxels retirés restent en laine blanche dans l'overlay (positions préservées).
 */
public final class LCorners {

    /** 7 paires (dy_vertical, dy_lateral) exactes du script. */
    private static final int[][] DY_PAIRS = {
            {0, 0}, {1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {-1, 0}
    };

    private LCorners() {
    }

    /** Vrai si le voxel (x,y,z) forme un coin en L avec 2 voisins de trace. */
    public static boolean isLCorner(WorldView view, int x, int y, int z) {
        for (int dz : new int[]{-1, 1}) {
            for (int dx : new int[]{1, -1}) {
                for (int[] pair : DY_PAIRS) {
                    if (isTrace(view, x, y + pair[0], z + dz)
                            && isTrace(view, x + dx, y + pair[1], z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isTrace(WorldView view, int x, int y, int z) {
        return view.at(x, y, z).is(Blocks.WHITE_WOOL);
    }

    /** Retire de la trace les voxels formant des coins en L (ordre conservé). */
    public static List<BlockPos> purge(WorldView view, List<BlockPos> trace) {
        List<BlockPos> kept = new ArrayList<>();
        for (BlockPos v : trace) {
            if (isTrace(view, v.getX(), v.getY(), v.getZ())
                    && isLCorner(view, v.getX(), v.getY(), v.getZ())) {
                continue; // épuré (reste posé en laine blanche)
            }
            kept.add(v);
        }
        return kept;
    }
}
