package com.bte.railpathtool.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public final class LCorners {

    private static final int[][] DY_PAIRS = {
            {0, 0}, {1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {-1, 0}
    };

    private LCorners() {
    }

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

    public static List<BlockPos> purge(WorldView view, List<BlockPos> trace) {
        List<BlockPos> kept = new ArrayList<>();
        for (BlockPos v : trace) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            if (isTrace(view, x, y, z) && isLCorner(view, x, y, z)) {
                boolean isolated = view.isAir(x + 1, y, z) && view.isAir(x - 1, y, z)
                        && view.isAir(x, y, z + 1) && view.isAir(x, y, z - 1);
                view.put(x, y, z, isolated
                        ? Blocks.AIR.defaultBlockState()
                        : Blocks.GRASS_BLOCK.defaultBlockState());
                continue;
            }
            kept.add(v);
        }
        return kept;
    }
}
