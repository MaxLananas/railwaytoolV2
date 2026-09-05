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

    /**
     * Vrai si le voxel s'inscrit dans un segment diagonal regulier (trace aux
     * deux extremites diagonales opposees, tolerance y) : ce n'est PAS un coin
     * L a purger, c'est une diagonale fine que la purge script casserait.
     */
    private static boolean isDiagSegment(WorldView view, int x, int y, int z) {
        int[][] diag = {{1, 1}, {1, -1}};
        for (int[] d : diag) {
            for (int dy1 : TrackModel.DY_TOLERANCE) {
                if (!isTrace(view, x + d[0], y + dy1, z + d[1])) {
                    continue;
                }
                for (int dy2 : TrackModel.DY_TOLERANCE) {
                    if (isTrace(view, x - d[0], y + dy2, z - d[1])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Vrai si les voisins trace de (x,y,z) restent mutuellement connectes
     * (26-connexite dans le cube 3x3x3) sans ce voxel. Empeche la purge L
     * d'ouvrir un trou dans une diagonale fine (rail manquant en jeu).
     */
    private static boolean locallyConnectedWithout(WorldView view, int x, int y, int z) {
        long[] neigh = new long[26];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (isTrace(view, x + dx, y + dy, z + dz)) {
                        neigh[n++] = BlockPos.asLong(x + dx, y + dy, z + dz);
                    }
                }
            }
        }
        if (n <= 1) {
            return true;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet allowed =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < n; i++) {
            allowed.add(neigh[i]);
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
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

    /**
     * Vrai si l'ensemble restant reste 26-connexe apres le depart d'un voxel.
     * Garde GLOBALE : la garde locale ne suffit pas contre les retraits en
     * chaine (chaque retrait passe localement tout en sectionnant la voie).
     */
    private static boolean traceConnectedWithout(it.unimi.dsi.fastutil.longs.LongOpenHashSet cells,
                                                 long removed) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(cells.size());
        long seed = 0L;
        boolean found = false;
        long[] stack = new long[cells.size()];
        int sp = 0;
        for (long v : cells) {
            if (v != removed) {
                seed = v;
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }
        seen.add(seed);
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
                        if (p == removed || !cells.contains(p)) {
                            continue;
                        }
                        if (seen.add(p)) {
                            stack[sp++] = p;
                        }
                    }
                }
            }
        }
        return seen.size() == cells.size() - 1;
    }

    public static List<BlockPos> purge(WorldView view, List<BlockPos> trace) {
        List<BlockPos> kept = new ArrayList<>();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet remaining =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(trace.size() * 2);
        for (BlockPos v : trace) {
            remaining.add(v.asLong());
        }
        for (BlockPos v : trace) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            if (isTrace(view, x, y, z) && isLCorner(view, x, y, z)
                    && !isDiagSegment(view, x, y, z)
                    && locallyConnectedWithout(view, x, y, z)
                    && traceConnectedWithout(remaining, v.asLong())) {
                boolean isolated = view.isAir(x + 1, y, z) && view.isAir(x - 1, y, z)
                        && view.isAir(x, y, z + 1) && view.isAir(x, y, z - 1);
                // Un coin L ne devient un marqueur 'corner' (herbe) que POSÉ :
                // s'il flotte au-dessus du vide, il verrouille la descente d'un
                // voxel au-dessus et crée le monticule des captures.
                boolean supported = !view.isAir(x, y - 1, z);
                view.put(x, y, z, (isolated || !supported)
                        ? Blocks.AIR.defaultBlockState()
                        : Blocks.GRASS_BLOCK.defaultBlockState());
                remaining.remove(v.asLong());
                continue;
            }
            kept.add(v);
        }
        return kept;
    }
}
