package com.bte.railpathtool.track;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
            BlockState st) {
        if (st.isAir()) {
            return true;
        }
        if (isTraceWool(st)) {
            return false;
        }
        // Le switch terrain PRECEDE la garde rail : dans rail_sim le lay
        // teste NATURAL_SOFT directement et le gravel y figure — bien qu'il
        // soit aussi dans RAIL_FAMILY (sol de support nature, jamais un
        // rail). Ecraser un sol pose n'est jamais ecraser une voie.
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath();
        return switch (id) {
            // Les sols du mix design (et le gravel) restent re-tracables :
            // un support pose par une trace anterieure ne doit jamais
            // devier la trace suivante — sinon la GEOMETRIE dependrait du
            // tirage aleatoire du mix (non reproductible sim<->java).
            case "grass_block", "water", "stone", "dirt", "coarse_dirt",
                 "sand", "sandstone", "terracotta", "clay", "snow_block",
                 "ice", "mud", "andesite", "granite", "diorite",
                 "deepslate", "cobbled_deepslate", "pale_oak_wood",
                 "deepslate_iron_ore", "deepslate_coal_ore", "gravel",
                 "oak_leaves", "spruce_leaves" -> true;
            // Tout le reste — rail, laine forcee, blocs du joueur — est
            // protege : une trace n'ecrase jamais la voie d'une autre.
            default -> false;
        };
    }

    /** Nom de bloc se terminant par _wool (toute couleur). */
    static boolean isTraceWool(
            BlockState st) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath().endsWith("_wool");
    }

    /**
     * Voxels de la trace dont la case contient reellement la laine posee par
     * ce build. L'appartenance se lit sur la position, jamais sur le nom du
     * bloc : une laine de remplissage ou un decor du joueur ne doit pas etre
     * pris pour un voxel de voie.
     */
    static LongOpenHashSet laidWool(WorldView view, List<BlockPos> trace) {
        LongOpenHashSet wool = new LongOpenHashSet();
        for (BlockPos v : trace) {
            BlockState st =
                    view.at(v.getX(), v.getY(), v.getZ());
            // Nom de registre, pas BlockTags.WOOL : hors runtime serveur
            // (harnais Bootstrap) les tags ne sont jamais charges et
            // is(TagKey) renvoie toujours false — cf. note TrackModel.
            if (st != null && isTraceWool(st)) {
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
        Long2IntOpenHashMap dugPerColumn = null;
        if (dug != null) {
            dugPerColumn = new Long2IntOpenHashMap();
            for (long p : dug) {
                dugPerColumn.addTo(columnKey(BlockPos.getX(p), BlockPos.getZ(p)), 1);
            }
        }
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
                    // Un seul percement de crete par colonne (2 blocs max,
                    // tous passages confondus).
                    long column = columnKey(x, z);
                    int colDug = dugPerColumn.get(column);
                    for (int dy = 1; ok && dy <= 2; dy++) {
                        BlockState st = view.at(x, y + dy, z);
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
                            dugPerColumn.addTo(column, 1);
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
                        BlockState own =
                                view.at(x, y, z);
                        BlockState below =
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
                BlockState own =
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
                BlockState d1 =
                        view.at(x, y - 1, z);
                BlockState d2 =
                        view.at(x, y - 2, z);
                BlockState d3 =
                        view.at(x, y - 3, z);
                if (isDecor(d1)
                        && com.bte.railpathtool.design.ClassicDesign
                                .ColumnWriter.isSupportSoil(d2)
                        && !d3.isAir() && !d3.is(Blocks.WATER)
                        && !isTraceWool(d3)
                        && !com.bte.railpathtool.design.ClassicDesign
                                .ColumnWriter.isRailCore(d3)) {
                    BlockState own =
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
            BlockState st) {
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
                    BlockState target =
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
                    BlockState[] own =
                            new BlockState[j - i];
                    for (int k = i; k < j; k++) {
                        own[k - i] = view.at(out[k].getX(), by, out[k].getZ());
                        view.put(out[k].getX(), by, out[k].getZ(), Blocks.AIR.defaultBlockState());
                    }
                    for (int k = i; k < j; k++) {
                        int bx = out[k].getX();
                        int bz = out[k].getZ();
                        // la dent garde sa couleur (laine forcée possible)
                        BlockState st = own[k - i];
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
     * Aucune colonne (x,z) ne porte deux niveaux de voie. Les niveaux hauts
     * sont retires d'abord, le niveau du bas n'est touche qu'en dernier
     * recours : une colonne conserve toujours au moins un voxel.
     */
    public static List<BlockPos> dedupeColumns(WorldView view, List<BlockPos> trace) {
        List<BlockPos> cur = new ArrayList<>(new LinkedHashSet<>(trace));
        LongOpenHashSet woolSet = laidWool(view, cur);
        Set<BlockPos> present = new LinkedHashSet<>(cur);
        Map<Long, List<BlockPos>> byColumn = new LinkedHashMap<>();
        for (BlockPos v : cur) {
            byColumn.computeIfAbsent(columnKey(v.getX(), v.getZ()), k -> new ArrayList<>())
                    .add(v);
        }
        for (List<BlockPos> levels : byColumn.values()) {
            Set<Integer> ys = new TreeSet<>();
            for (BlockPos v : levels) {
                ys.add(v.getY());
            }
            if (ys.size() < 2) {
                continue;
            }
            List<Integer> remaining = new ArrayList<>(ys);
            List<Integer> order = new ArrayList<>(remaining.subList(1, remaining.size()));
            Collections.reverse(order);
            order.add(remaining.get(0));
            for (int y : order) {
                if (remaining.size() <= 1) {
                    break;
                }
                List<BlockPos> victims = new ArrayList<>();
                for (BlockPos v : levels) {
                    if (v.getY() == y && present.contains(v)) {
                        victims.add(v);
                    }
                }
                if (victims.isEmpty() || !isTouchable(view, woolSet, victims)) {
                    continue;
                }
                for (BlockPos v : victims) {
                    view.put(v.getX(), v.getY(), v.getZ(), Blocks.AIR.defaultBlockState());
                    woolSet.remove(v.asLong());
                    present.remove(v);
                }
                remaining.remove(Integer.valueOf(y));
            }
        }
        List<BlockPos> out = new ArrayList<>(present.size());
        for (BlockPos v : cur) {
            if (present.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isTouchable(WorldView view, LongOpenHashSet woolSet,
                                       List<BlockPos> victims) {
        for (BlockPos v : victims) {
            BlockState st = view.at(v);
            if (!st.isAir() && !st.is(Blocks.WHITE_WOOL)
                    && woolSet.contains(v.asLong())) {
                return false;
            }
        }
        return true;
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }
}
