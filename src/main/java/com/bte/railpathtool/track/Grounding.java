package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;

import java.util.ArrayList;
import java.util.List;

public final class Grounding {

    public static final int MAX_UP = 15;
    public static final int MAX_DOWN = 20;

    private Grounding() {
    }

    /**
     * Case libre pour la pose de laine de trace : air ou terrain naturel
     * meuble (herbe/roche/terre/sable/neige/glace/eau…). JAMAIS du rail, de
     * la laine ou du décor manuel existant — poser de la laine dessus
     * écraserait la voie ou le repère d'une trace voisine (bug « presque
     * plus de rail » des jonctions) tandis qu'une galerie dans une colline
     * de roche doit pouvoir recevoir sa laine.
     * Miroir exact de rail_sim.NATURAL_SOFT — toute divergence = parité.
     */
    public static boolean isWoolLayable(
            net.minecraft.world.level.block.state.BlockState st) {
        if (st.isAir()) {
            return true;
        }
        if (isTraceWool(st)
                || com.bte.railpathtool.design.ClassicDesign.ColumnWriter
                        .isRailFamily(st)) {
            return false;
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath();
        return switch (id) {
            case "grass_block", "water", "stone", "dirt", "coarse_dirt",
                 "sand", "sandstone", "terracotta", "clay", "snow_block",
                 "ice", "mud", "andesite", "granite", "diorite",
                 "deepslate", "cobbled_deepslate", "oak_leaves",
                 "spruce_leaves" -> true;
            default -> false;
        };
    }

    /** Nom de bloc se terminant par _wool (toute couleur). */
    static boolean isTraceWool(
            net.minecraft.world.level.block.state.BlockState st) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath().endsWith("_wool");
    }

    /**
     * Positions des voxels de la trace EN COURS dont la case contient
     * VRAIMENT la laine posee par ce build. C'est LA reference
     * d'appartenance des passes — jamais « bloc == *_wool » : un remplissage
     * uniforme en laine orange (defaut du mod !) ou une laine decorative du
     * joueur ne doit JAMAIS etre confondu avec un voxel de voie, et une case
     * deja occupee par du rail (non posee au lay) n'est pas une laine
     * deplacable. Miroir de rail_sim._TRACE_WOOL.
     */
    /**
     * Voxels dont la case contient VRAIMENT la laine posee par cette trace
     * (famille laine). Un voxel dont la case est deja occupee (rail d'une
     * voie precedente, pierre, sol) n'APPARTIENT PAS au jeu : les passes ne
     * doivent pas le traiter comme une laine deplacable — sinon le coin-
     * laisse d'une remontee ecrase un corail voisin (jun-45). Miroir de
     * rail_sim : la condition d'entree est is_wool(case), pas spline.
     */
    static LongOpenHashSet laidWool(WorldView view, List<BlockPos> trace) {
        LongOpenHashSet wool = new LongOpenHashSet();
        for (BlockPos v : trace) {
            net.minecraft.world.level.block.state.BlockState st =
                    view.at(v.getX(), v.getY(), v.getZ());
            if (st != null && st.is(BlockTags.WOOL)) {
                wool.add(BlockPos.asLong(v.getX(), v.getY(), v.getZ()));
            }
        }
        return wool;
    }

    static void moveWool(LongOpenHashSet wool, int fx, int fy, int fz,
                         int tx, int ty, int tz) {
        if (wool.remove(BlockPos.asLong(fx, fy, fz))) {
            wool.add(BlockPos.asLong(tx, ty, tz));
        }
    }

    /**
     * Composantes d'un ensemble de voxels, metrique du RUBAN DE VOIE : deux
     * points sont lies si leurs colonnes se touchent (|dx|<=1 et |dz|<=1,
     * quel que soit dy — un escalier de montagne est une voie continue).
     * Miroir de rail_sim.components_ribbon (invariant I2).
     */
    static int componentsRibbon(List<BlockPos> pts) {
        int n = pts.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                BlockPos a = pts.get(i);
                BlockPos b = pts.get(j);
                if (Math.max(Math.abs(a.getX() - b.getX()),
                        Math.abs(a.getZ() - b.getZ())) <= 1) {
                    int ra = findRoot(parent, i);
                    int rb = findRoot(parent, j);
                    if (ra != rb) {
                        parent[ra] = rb;
                    }
                }
            }
        }
        int comps = 0;
        for (int i = 0; i < n; i++) {
            if (findRoot(parent, i) == i) {
                comps++;
            }
        }
        return comps;
    }

    private static int findRoot(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
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
        LongOpenHashSet wool = laidWool(view, trace);
        for (BlockPos v : trace) {
            int x = v.getX();
            int y = v.getY();
            int z = v.getZ();
            // Seuls les voxels REELLEMENT laines participent ; les autres
            // (rail/stone preexistants sous la case) passent sans effet —
            // sinon le coin-laisse d'une remontee ecraserait un corail.
            if (!wool.contains(BlockPos.asLong(x, y, z))) {
                moved.add(v);
                continue;
            }
            // Jamais de remontée DANS/À TRAVERS une autre laine de la trace
            // (doublon vertical des dents de spline) : la pile se figerait
            // avec le corner laissé derrière. La descente, elle, reste
            // autorisée — c'est elle qui dégonfle la pile ; l'arbitrage final
            // revient à dedupeColumns.
            if (!view.isAir(x, y + 1, z)
                    && !wool.contains(BlockPos.asLong(x, y + 1, z))) {

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
                                || net.minecraft.core.registries
                                        .BuiltInRegistries.BLOCK
                                        .getKey(st.getBlock()).getPath()
                                        .endsWith("_wool")
                                || wool.contains(BlockPos.asLong(
                                        x, y + dy, z))) {
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
                        if (wool.contains(BlockPos.asLong(x, ny, z))) {
                            break;  // jamais d'atterrissage SUR une autre laine
                        }
                        if (com.bte.railpathtool.design.ClassicDesign.ColumnWriter
                                .isRailFamily(view.at(x, ny, z))) {
                            break;  // jamais d'atterrissage DANS du rail existant
                        }
                        // corner laissé derrière : seulement POSÉ et hors eau,
                        // sinon une herbe flottante verrouille la descente des
                        // voisins (monticule des captures).
                        net.minecraft.world.level.block.state.BlockState own =
                                view.at(x, y, z);
                        net.minecraft.world.level.block.state.BlockState below =
                                view.at(x, y - 1, z);
                        view.put(x, y, z,
                                below.isAir() || below.is(Blocks.WATER)
                                        ? Blocks.AIR.defaultBlockState()
                                        : Blocks.GRASS_BLOCK.defaultBlockState());
                        // la laine déplacée garde SA couleur (forcée possible)
                        view.put(x, ny, z, own);
                        moveWool(wool, x, y, z, x, ny, z);
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
            boolean wasUnstable = isUnstable(view, x, y, z);
            for (int i = 0; i < MAX_DOWN; i++) {
                int nxt = target - 1;
                if (!isUnstable(view, x, target, z)) {
                    break;
                }
                if (wool.contains(BlockPos.asLong(x, nxt, z))) {
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
                net.minecraft.world.level.block.state.BlockState own =
                        view.at(x, y, z);
                view.put(x, y, z, Blocks.AIR.defaultBlockState());
                view.put(x, target, z, own);
                moveWool(wool, x, y, z, x, target, z);
                y = target;
            }
            // JONCTION : la laine s'est arretee SUR une colonne de decor
            // d'une autre voie [sol de support + decor, AUCUN core]. Sans la
            // reprise, son rail se poserait 2 blocs au-dessus de ceux de ses
            // voisins = ruban casse a la croisee. La colonne de decor est
            // recuperee : la laine descend DANS la base de support, son core
            // remplacera le decor — croisement continu au meme niveau.
            // Miroir exact du junction-sink de rail_sim.rectify_vertical.
            // (le sink n'existe que dans la branche « initialement
            // instable » de la rectification — miroir exact du sim)
            if (wasUnstable && !isUnstable(view, x, y, z)) {
                net.minecraft.world.level.block.state.BlockState d1 =
                        view.at(x, y - 1, z);
                net.minecraft.world.level.block.state.BlockState d2 =
                        view.at(x, y - 2, z);
                net.minecraft.world.level.block.state.BlockState d3 =
                        view.at(x, y - 3, z);
                if (isDecor(d1)
                        && com.bte.railpathtool.design.ClassicDesign
                                .ColumnWriter.isSupportSoil(d2)
                        && !d3.isAir() && !d3.is(Blocks.WATER)
                        && !isTraceWool(d3)
                        && !com.bte.railpathtool.design.ClassicDesign
                                .ColumnWriter.isRailCore(d3)) {
                    net.minecraft.world.level.block.state.BlockState own =
                            view.at(x, y, z);
                    view.put(x, y, z, Blocks.AIR.defaultBlockState());
                    view.put(x, y - 1, z, Blocks.AIR.defaultBlockState());
                    view.put(x, y - 2, z, own);
                    moveWool(wool, x, y, z, x, y - 2, z);
                    y = y - 2;
                }
            }
            moved.add(new BlockPos(x, y, z));
        }
        return moved;
    }

    /** Decor de voie (jamais un core) : reprise possible par une jonction. */
    static boolean isDecor(
            net.minecraft.world.level.block.state.BlockState st) {
        return st.is(Blocks.MUD_BRICK_WALL) || st.is(Blocks.ANDESITE_WALL)
                || st.is(Blocks.IRON_DOOR) || st.is(Blocks.OAK_BUTTON)
                || st.is(Blocks.PALE_MOSS_CARPET) || st.is(Blocks.LEAF_LITTER)
                || st.is(Blocks.SPRUCE_SHELF);
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
        LongOpenHashSet wool = laidWool(view, trace);
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
                    if (wool.contains(BlockPos.asLong(
                                    out[k].getX(), ay, out[k].getZ()))
                            || isTraceWool(target)
                            || com.bte.railpathtool.design.ClassicDesign.ColumnWriter.isRailFamily(target)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    net.minecraft.world.level.block.state.BlockState[] own =
                            new net.minecraft.world.level.block.state.BlockState[j - i];
                    for (int k = i; k < j; k++) {
                        own[k - i] = view.at(out[k].getX(), by, out[k].getZ());
                        view.put(out[k].getX(), by, out[k].getZ(), Blocks.AIR.defaultBlockState());
                    }
                    for (int k = i; k < j; k++) {
                        int bx = out[k].getX();
                        int bz = out[k].getZ();
                        // la dent garde sa couleur (laine forcée possible)
                        net.minecraft.world.level.block.state.BlockState st = own[k - i];
                        view.put(bx, ay, bz, st.isAir() ? Blocks.WHITE_WOOL.defaultBlockState() : st);
                        moveWool(wool, bx, by, bz, bx, ay, bz);
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
        LongOpenHashSet woolSet = laidWool(view, trace);
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
                    if (!st.isAir() && !st.is(Blocks.WHITE_WOOL)
                            && woolSet.contains(BlockPos.asLong(
                                    v.getX(), v.getY(), v.getZ()))) {
                        touchable = false;   // rail/terrain existant : on ne touche pas
                        break;
                    }
                }
                if (!touchable) {
                    continue;
                }
                List<BlockPos> trial = new ArrayList<>(cur);
                trial.removeAll(victims);
                // Metrique ruban (comme I2) : un niveau jetable ne doit pas
                // ouvrir de trou HORIZONTAL ; escaliers/croisements empiles
                // ne bloquent plus le nettoyage des piles (tunnels).
                if (componentsRibbon(trial) <= componentsRibbon(cur)) {
                    for (BlockPos v : victims) {
                        view.put(v.getX(), v.getY(), v.getZ(), Blocks.AIR.defaultBlockState());
                        woolSet.remove(BlockPos.asLong(
                                v.getX(), v.getY(), v.getZ()));
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
