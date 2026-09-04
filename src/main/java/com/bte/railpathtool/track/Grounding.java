package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
        return apply(view, trace, null);
    }

    public static List<BlockPos> apply(WorldView view, List<BlockPos> trace,
                                       LongOpenHashSet dug) {
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

                boolean dugHere = false;
                if (dug != null) {
                    int[][] toDig = new int[2][];
                    int count = 0;
                    boolean ok = true;
                    for (int dy = 1; dy <= 2; dy++) {
                        net.minecraft.world.level.block.state.BlockState st = view.at(x, y + dy, z);
                        if (st.isAir()) {
                            break;
                        }
                        if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter.isRailFamily(st)
                                || st.is(net.minecraft.tags.BlockTags.WOOL)) {
                            ok = false;
                            break;
                        }
                        toDig[count++] = new int[]{x, y + dy, z};
                    }
                    if (ok && count > 0 && !view.isAir(x, y + count + 1, z)) {
                        ok = false;
                    }
                    if (ok && count > 0) {
                        for (int i = 0; i < count; i++) {
                            view.put(toDig[i][0], toDig[i][1], toDig[i][2],
                                    Blocks.AIR.defaultBlockState());
                            dug.add(BlockPos.asLong(toDig[i][0], toDig[i][1], toDig[i][2]));
                        }
                        dugHere = true;
                    }
                }
                if (dugHere) {
                    moved.add(v);
                    continue;
                }
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

    /**
     * Aplanit les dents de scie verticales : pics unitaires ET plateaux courts
     * (3 voxels max) decales d'1 bloc entre deux segments au meme niveau
     * (a.y == c.y != run.y). Realigne chaque voxel du plateau si sa case cible
     * est libre (ni laine ni rail). Leve les collisions de colonnes laterales
     * et les fragments de voie « volants » sur terrain plat.
     */
    public static List<BlockPos> flattenTeeth(WorldView view, List<BlockPos> trace) {
        if (trace.size() < 3) {
            return trace;
        }
        BlockPos[] out = trace.toArray(new BlockPos[0]);
        int n = out.length;
        int i = 1;
        while (i < n - 1) {
            int ay = out[i - 1].getY();
            int by = out[i].getY();
            if (by == ay || Math.abs(by - ay) != 1) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && out[j].getY() == by) {
                j++;
            }
            int runLen = j - i;
            boolean endsOk = j < n && out[j].getY() == ay;
            if (endsOk && runLen <= 3) {
                boolean ok = true;
                for (int k = i; k < j; k++) {
                    net.minecraft.world.level.block.state.BlockState target =
                            view.at(out[k].getX(), ay, out[k].getZ());
                    if (target.is(Blocks.WHITE_WOOL)
                            || com.bte.railpathtool.design.ClassicDesign.ColumnWriter.isRailFamily(target)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    for (int k = i; k < j; k++) {
                        view.put(out[k].getX(), by, out[k].getZ(), Blocks.AIR.defaultBlockState());
                    }
                    for (int k = i; k < j; k++) {
                        int bx = out[k].getX();
                        int bz = out[k].getZ();
                        view.put(bx, ay, bz, Blocks.WHITE_WOOL.defaultBlockState());
                        out[k] = new BlockPos(bx, ay, bz);
                    }
                }
            }
            i = j;
        }
        List<BlockPos> res = new ArrayList<>(out.length);
        java.util.Collections.addAll(res, out);
        return res;
    }
}
