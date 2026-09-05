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
            // Jamais de remontée DANS/À TRAVERS une autre laine de la trace
            // (doublon vertical des dents de spline) : la pile se figerait
            // avec le corner laissé derrière. La descente, elle, reste
            // autorisée — c'est elle qui dégonfle la pile ; l'arbitrage final
            // revient à dedupeColumns.
            if (!view.isAir(x, y + 1, z) && !view.at(x, y + 1, z).is(Blocks.WHITE_WOOL)) {

                boolean dugHere = false;
                if (dug != null) {
                    int[][] toDig = new int[2][];
                    int count = 0;
                    boolean ok = true;
                    // le tunnel ne perce qu'UNE crête : 2 blocs max par
                    // colonne, tous passages confondus (esthétique).
                    var dugIt = dug.iterator();
                    int colDug = 0;
                    while (dugIt.hasNext()) {
                        long p = dugIt.nextLong();
                        if (BlockPos.getX(p) == x && BlockPos.getZ(p) == z) {
                            colDug++;
                        }
                    }
                    for (int dy = 1; ok && dy <= 2; dy++) {
                        net.minecraft.world.level.block.state.BlockState st = view.at(x, y + dy, z);
                        if (st.isAir()) {
                            break;
                        }
                        if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter.isRailFamily(st)
                                || st.getBlock() instanceof net.minecraft.world
                                        .level.block.WoolBlock) {
                            ok = false;
                            break;
                        }
                        toDig[count++] = new int[]{x, y + dy, z};
                    }
                    // 2 blocs de tunnel max par colonne, tous passages
                    // confondus — sinon on tailleade le terrain.
                    if (ok && colDug + count > 2) {
                        ok = false;
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
                        if (view.at(x, ny, z).is(Blocks.WHITE_WOOL)) {
                            break;  // jamais d'atterrissage SUR une autre laine
                        }
                        if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter
                                .isRailFamily(view.at(x, ny, z))) {
                            break;  // jamais d'atterrissage DANS du rail existant
                        }
                        // corner laissé derrière : seulement POSÉ, sinon une
                        // herbe flottante verrouille la descente des voisins
                        // (monticule des captures).
                        view.put(x, y, z, view.isAir(x, y - 1, z)
                                ? Blocks.AIR.defaultBlockState()
                                : Blocks.GRASS_BLOCK.defaultBlockState());
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
                // Jamais d'écrasement du rail existant : la 2e trace qui
                // descend sur la voie de la 1re s'arrête dessus (support) —
                // sinon la laine remplace le corail du croisement et la 1re
                // ligne est cassée (« presque plus de rail »).
                if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter
                        .isRailFamily(view.at(x, nxt, z))) {
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

    /**
     * Aucun empilement vertical : une colonne (x,z) ne doit jamais porter 2
     * voxels de laine à des hauteurs différentes (dents y±1 de la spline +
     * purge des L = piles figées, le « monticule / rail au-dessus » des
     * captures). On garde le voxel LE PLUS BAS de la colonne et on dégage les
     * autres, niveau par niveau, tant que le retrait ne sectionne pas
     * davantage la voie (le nombre de composantes 26-connexes n'augmente
     * pas — les piles parasites sont déjà des îlots). Seuls les blocs laine
     * blanche ou l'air sont touchés : du rail déjà posé n'est jamais altéré.
     */
    public static List<BlockPos> dedupeColumns(WorldView view, List<BlockPos> trace) {
        java.util.Map<Long, List<BlockPos>> byCol = new java.util.LinkedHashMap<>();
        List<BlockPos> cur = new ArrayList<>(new java.util.LinkedHashSet<>(trace));
        for (BlockPos v : cur) {
            long key = BlockPos.asLong(v.getX(), 0, v.getZ());
            byCol.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        for (List<BlockPos> vals : byCol.values()) {
            java.util.Set<Integer> ysSet = new java.util.TreeSet<>();
            for (BlockPos v : vals) {
                ysSet.add(v.getY());
            }
            if (ysSet.size() < 2) {
                continue;
            }
            List<Integer> remaining = new ArrayList<>(ysSet);
            List<Integer> ordre = new ArrayList<>(remaining.subList(1, remaining.size()));
            java.util.Collections.reverse(ordre);   // les hauts d'abord (nominal)
            ordre.add(remaining.get(0));             // le fond en dernier recours
            for (int yy : ordre) {
                if (remaining.size() <= 1) {
                    break;   // la colonne garde toujours au moins un niveau
                }
                List<BlockPos> victims = new ArrayList<>();
                for (BlockPos v : vals) {
                    if (v.getY() == yy && cur.contains(v)) {
                        victims.add(v);
                    }
                }
                if (victims.isEmpty()) {
                    continue;
                }
                boolean touchable = true;
                for (BlockPos v : victims) {
                    net.minecraft.world.level.block.state.BlockState st =
                            view.at(v.getX(), v.getY(), v.getZ());
                    if (!st.isAir() && !st.is(Blocks.WHITE_WOOL)) {
                        touchable = false;   // rail/terrain existant : on ne touche pas
                        break;
                    }
                }
                if (!touchable) {
                    continue;
                }
                List<BlockPos> trial = new ArrayList<>(cur);
                trial.removeAll(victims);
                if (countComponents(trial) <= countComponents(cur)) {
                    for (BlockPos v : victims) {
                        view.put(v.getX(), v.getY(), v.getZ(), Blocks.AIR.defaultBlockState());
                    }
                    cur = trial;
                    remaining.remove(Integer.valueOf(yy));
                }
            }
        }
        return cur;
    }

    /** Nombre de composantes 26-connexes de la trace. */
    private static int countComponents(List<BlockPos> trace) {
        LongOpenHashSet rest = new LongOpenHashSet(trace.size() * 2);
        for (BlockPos v : trace) {
            rest.add(v.asLong());
        }
        int n = 0;
        long[] stack = new long[Math.max(16, trace.size())];
        while (!rest.isEmpty()) {
            n++;
            long seed = rest.iterator().nextLong();
            rest.remove(seed);
            int sp = 0;
            stack[sp++] = seed;
            while (sp > 0) {
                long p = stack[--sp];
                int x = BlockPos.getX(p);
                int y = BlockPos.getY(p);
                int z = BlockPos.getZ(p);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long nb = BlockPos.asLong(x + dx, y + dy, z + dz);
                            if (rest.contains(nb)) {
                                rest.remove(nb);
                                if (sp == stack.length) {
                                    stack = java.util.Arrays.copyOf(stack, sp * 2);
                                }
                                stack[sp++] = nb;
                            }
                        }
                    }
                }
            }
        }
        return n;
    }
}
