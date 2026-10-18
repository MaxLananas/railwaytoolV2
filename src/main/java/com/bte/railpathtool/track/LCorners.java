package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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

    private static boolean isLCorner(WorldView view, LongOpenHashSet wool,
                                     int x, int y, int z) {
        for (int dz : new int[]{-1, 1}) {
            for (int dx : new int[]{1, -1}) {
                for (int[] pair : DY_PAIRS) {
                    if (isTrace(wool, x, y + pair[0], z + dz)
                            && isTrace(wool, x + dx, y + pair[1], z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isTrace(LongOpenHashSet wool, int x, int y, int z) {
        return wool.contains(BlockPos.asLong(x, y, z));
    }

    /** True for a regular diagonal segment (opposite diagonal neighbours);
     * purging it would break the diagonal. */
    private static boolean isDiagSegment(LongOpenHashSet wool,
                                         int x, int y, int z) {
        int[][] diag = {{1, 1}, {1, -1}};
        for (int[] d : diag) {
            for (int dy1 : TrackModel.DY_TOLERANCE) {
                if (!isTrace(wool, x + d[0], y + dy1, z + d[1])) {
                    continue;
                }
                for (int dy2 : TrackModel.DY_TOLERANCE) {
                    if (isTrace(wool, x - d[0], y + dy2, z - d[1])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if the trace neighbours of the voxel stay 26-connected without it;
     * keeps thin diagonals from opening a hole. */
    private static boolean locallyConnectedWithout(WorldView view,
                                                   LongOpenHashSet wool,
                                                   int x, int y, int z) {
        long[] neigh = new long[26];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (isTrace(wool, x + dx, y + dy, z + dz)) {
                        neigh[n++] = BlockPos.asLong(x + dx, y + dy, z + dz);
                    }
                }
            }
        }
        if (n <= 1) {
            return true;
        }
        LongOpenHashSet allowed = new LongOpenHashSet();
        for (int i = 0; i < n; i++) {
            allowed.add(neigh[i]);
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        seen.add(neigh[0]);
        long[] stack = new long[26];
        int sp = 0;
        stack[sp++] = neigh[0];
        while (sp > 0) {
            long cur = stack[--sp];
            int cx = BlockPos.getX(cur);
            int cy = BlockPos.getY(cur);
            int cz = BlockPos.getZ(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long p = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
                        if (allowed.contains(p) && seen.add(p)) {
                            stack[sp++] = p;
                        }
                    }
                }
            }
        }
        return seen.size() == n;
    }

    /** True if removing the voxel leaves the trace 26-connected. Only its
     * neighbours matter: in a connected graph, G-v is connected iff every
     * neighbour of v stays in the same component. */
    private static boolean keepsConnected(LongOpenHashSet cells, long removed) {
        if (cells.size() <= 1) {
            return false;
        }
        long[] neigh = new long[26];
        int n = 0;
        int x = BlockPos.getX(removed);
        int y = BlockPos.getY(removed);
        int z = BlockPos.getZ(removed);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    long p = BlockPos.asLong(x + dx, y + dy, z + dz);
                    if (cells.contains(p)) {
                        neigh[n++] = p;
                    }
                }
            }
        }
        if (n == 0) {
            return false;
        }
        if (n == 1) {
            return true;
        }
        LongOpenHashSet targets = new LongOpenHashSet(n - 1);
        for (int i = 1; i < n; i++) {
            targets.add(neigh[i]);
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        seen.add(neigh[0]);
        targets.remove(neigh[0]);
        long[] stack = new long[Math.max(16, cells.size() / 4)];
        int sp = 0;
        stack[sp++] = neigh[0];
        while (sp > 0 && !targets.isEmpty()) {
            long cur = stack[--sp];
            int cx = BlockPos.getX(cur);
            int cy = BlockPos.getY(cur);
            int cz = BlockPos.getZ(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long p = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
                        if (p == removed || !cells.contains(p) || !seen.add(p)) {
                            continue;
                        }
                        targets.remove(p);
                        if (sp == stack.length) {
                            stack = java.util.Arrays.copyOf(stack, sp * 2);
                        }
                        stack[sp++] = p;
                    }
                }
            }
        }
        return targets.isEmpty();
    }

    /** True if the whole set is 26-connected. */
    private static boolean connected(LongOpenHashSet cells) {
        if (cells.isEmpty()) {
            return true;
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        long seed = cells.iterator().nextLong();
        seen.add(seed);
        long[] stack = new long[Math.max(16, cells.size() / 4)];
        int sp = 0;
        stack[sp++] = seed;
        while (sp > 0) {
            long cur = stack[--sp];
            int cx = BlockPos.getX(cur);
            int cy = BlockPos.getY(cur);
            int cz = BlockPos.getZ(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long p = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
                        if (cells.contains(p) && seen.add(p)) {
                            if (sp == stack.length) {
                                stack = java.util.Arrays.copyOf(stack, sp * 2);
                            }
                            stack[sp++] = p;
                        }
                    }
                }
            }
        }
        return seen.size() == cells.size();
    }

    public static List<BlockPos> purge(WorldView view, List<BlockPos> trace) {
        LongOpenHashSet wool = Grounding.laidWool(view, trace);
        LongOpenHashSet remaining = new LongOpenHashSet(trace.size() * 2);
        for (BlockPos v : trace) {
            remaining.add(v.asLong());
        }
        // On an already fragmented trace, the global guard blocked every purge.
        if (!connected(remaining)) {
            return new ArrayList<>(trace);
        }
        List<BlockPos> kept = new ArrayList<>(trace.size());
        for (BlockPos v : trace) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            if (remaining.contains(v.asLong()) && isTrace(wool, x, y, z)
                    && isLCorner(view, wool, x, y, z)
                    && !isDiagSegment(wool, x, y, z)
                    && locallyConnectedWithout(view, wool, x, y, z)
                    && keepsConnected(remaining, v.asLong())) {
                boolean isolated = view.isAir(x + 1, y, z) && view.isAir(x - 1, y, z)
                        && view.isAir(x, y, z + 1) && view.isAir(x, y, z - 1);
                // An L corner becomes a grass marker only when placed out of water:
                // floating grass would lock a neighbour's descent.
                boolean supported = !view.isAir(x, y - 1, z)
                        && !view.at(x, y - 1, z).is(Blocks.WATER);
                view.put(x, y, z, (isolated || !supported)
                        ? Blocks.AIR.defaultBlockState()
                        : Blocks.GRASS_BLOCK.defaultBlockState());
                remaining.remove(v.asLong());
                wool.remove(v.asLong());
                continue;
            }
            kept.add(v);
        }
        return kept;
    }
}
