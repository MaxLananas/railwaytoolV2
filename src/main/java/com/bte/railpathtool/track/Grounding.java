package com.bte.railpathtool.track;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public final class Grounding {

    public static final int MAX_UP = 15;
    public static final int MAX_DOWN = 20;

    private Grounding() {
    }

    public static boolean isUnstable(WorldView view, int x, int y, int z) {
        boolean below = view.isAir(x, y - 1, z);
        boolean bn = view.isAir(x, y - 1, z - 1);
        boolean bs = view.isAir(x, y - 1, z + 1);
        boolean be = view.isAir(x + 1, y - 1, z);
        boolean bo = view.isAir(x - 1, y - 1, z);
        if (below && bn && bs && be && bo) {
            return true;
        }
        boolean n = view.isAir(x, y, z - 1);
        boolean s = view.isAir(x, y, z + 1);
        boolean e = view.isAir(x + 1, y, z);
        boolean o = view.isAir(x - 1, y, z);
        if (count(n, s, e, o) >= 3) {
            return true;
        }
        if ((n && s) || (e && o)) {
            return true;
        }
        boolean ne = view.isAir(x + 1, y, z - 1);
        boolean so = view.isAir(x - 1, y, z + 1);
        boolean no = view.isAir(x - 1, y, z - 1);
        boolean se = view.isAir(x + 1, y, z + 1);
        if ((ne && so) || (no && se)) {
            return true;
        }
        return count(ne, so, no, se) >= 3;
    }

    private static int count(boolean... bs) {
        int c = 0;
        for (boolean b : bs) {
            if (b) {
                c++;
            }
        }
        return c;
    }

    public static List<BlockPos> apply(WorldView view, List<BlockPos> trace) {
        List<BlockPos> moved = new ArrayList<>();
        for (BlockPos v : trace) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            if (!view.at(x, y, z).is(Blocks.WHITE_WOOL)) {
                moved.add(v);
                continue;
            }
            if (!view.isAir(x, y + 1, z)) {

                boolean movedUp = false;
                for (int dy = 1; dy <= MAX_UP; dy++) {
                    if (view.isAir(x, y + dy, z)) {
                        int ny = y + dy - 1;
                        view.put(x, y, z, Blocks.AIR.defaultBlockState());
                        view.put(x, ny, z, Blocks.WHITE_WOOL.defaultBlockState());
                        moved.add(new BlockPos(x, ny, z));
                        movedUp = true;
                        break;
                    }
                }
                if (!movedUp) {
                    moved.add(v);
                }
                continue;
            }

            int target = y;
            for (int i = 0; i < MAX_DOWN; i++) {
                int nxt = target - 1;
                if (!isUnstable(view, x, target, z)) {
                    break;
                }
                if (view.at(x, nxt, z).is(Blocks.WHITE_WOOL)) {
                    break;
                }
                target = nxt;
            }
            if (target != y) {
                view.put(x, y, z, Blocks.AIR.defaultBlockState());
                view.put(x, target, z, Blocks.WHITE_WOOL.defaultBlockState());
                moved.add(new BlockPos(x, target, z));
            } else {
                moved.add(v);
            }
        }
        return moved;
    }
}
